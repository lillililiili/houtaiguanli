package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.uav.lowaltitude.Application;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort;
import com.uav.lowaltitude.testsupport.SourceTypeCatalogFixture;

/**
 * production 必须压过 local：阶段 8.5 的直连切片（凌云 v2 回放数据集、实测雷达提升端口）不允许因部署 profile 组合泄入生产。
 *
 * 这里守的是两件不同的事，不要混为一谈：
 *   ① **演示数据**（v2 数据集、回放种子写的 inbox 与观测）在生产必须一行都没有；
 *   ② **结构性目录**（`source_type_catalog` 八行、`fusion_config demo-v1`）在生产**必须存在**——
 *      它们是外键与融合参数的前提，缺了引擎读不到精度缺省只能退回裸阈值。所以目录断言的是"在且标 DEMO"，不是"为空"。
 *
 * 实测雷达提升另有两道闸，本类验证第一道：`app.fusion.live-promotion.enabled` 默认关时
 * {@link com.uav.lowaltitude.modules.fusion.application.LiveRadarSourceObservationPort} 不注册，
 * 由 `NoopSourceObservationPort` 兜底丢弃。第二道（`FusionInboxRepository.claim` 的前缀白名单不含 `live-radar:`）
 * 归 E1，落地后在 `Stage85PostgresTest` 上钉。
 *
 * 按 Bean 名断言，不引用 E1/E2 的类型：类被重命名时这里也不会因编译依赖而"默认通过"。
 */
class ProductionStage85SeedIsolationTest {
    @Test void productionNeverRegistersStage85ReplaySeedersOrLivePromotion() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    /**
     * 开关的另一侧：`app.fusion.live-promotion.enabled=true` 时必须**恰好一个** `SourceObservationPort` Bean，
     * 且是实测那个。两个实现的 @ConditionalOnProperty 若不互斥，上下文会因 Bean 冲突启动失败——
     * 这类失败只在有人真的打开开关时才出现，不钉住就会留到联调现场才炸。
     */
    @Test
    void livePromotionSwitchSelectsExactlyOnePortImplementation() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=local",
                "--spring.datasource.url=jdbc:h2:mem:stage85_live_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=false", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--app.fusion.enabled=false", "--app.fusion.replay.run-on-start=false",
                "--app.fusion.live-promotion.enabled=true",
                "--spring.main.banner-mode=off")) {
            assertThat(context.getBeanNamesForType(SourceObservationPort.class))
                    .as("开关打开时只能有实测端口一个实现").containsExactly("liveRadarSourceObservationPort");
        }
    }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage85_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration",
                // 故意打开：要证明的是"即使演示种子被打开，production 仍然赢"，而不是"因为没开所以没数据"。
                "--app.dev-seed.enabled=true", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--app.fusion.enabled=true", "--app.fusion.replay.run-on-start=true",
                "--spring.main.banner-mode=off")) {

            // 闸一：实测提升端口默认关，不注册；关闭态由 Noop 兜底（丢弃并记日志，不写任何表）。
            assertThat(context.containsBean("liveRadarSourceObservationPort"))
                    .as("app.fusion.live-promotion.enabled 默认关，实测雷达端口不得在生产注册").isFalse();
            assertThat(context.containsBean("noopSourceObservationPort"))
                    .as("关闭态必须有兜底实现，否则注入点会因缺 Bean 启动失败").isTrue();
            // 回放种子与摄取 Worker 都不该在生产出现（Worker 归 app.fusion.enabled，这里连它一起钉住）。
            assertThat(context.containsBean("localStage8FusionReplaySeeder")).as("凌云 v2 回放种子不得在 production 注册").isFalse();
            List<String> fusionRunners = context.getBeansOfType(ApplicationRunner.class).keySet().stream()
                    .filter(name -> name.toLowerCase().contains("replay") || name.toLowerCase().contains("stage85")).toList();
            assertThat(fusionRunners).as("生产不得注册任何回放/8.5 启动任务").isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            // 演示数据：逐表断言为空，不抽查——漏掉哪张表，那张表就是演示数据进生产的通道。
            for (String table : List.of("source_observation", "target", "target_latest_state", "track", "track_point",
                    "target_lineage", "fusion_event", "inbox_message")) {
                assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                        .as(table + " 属于业务数据，生产必须为空").isZero();
            }
            // 四个直连前缀一个都不能出现在 inbox 里（上面的整表断言已覆盖，这里点名是为了让失败信息直接指出是哪条通道）。
            for (String prefix : List.of("replay:", "lingyun:", "eo-edge:", "live-radar:")) {
                assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like ?", Integer.class, prefix + "%"))
                        .as(prefix + " 通道在生产不得有任何报文").isZero();
            }
            // 回放数据集本身也不得建：v2 数据集的传感器来源行是演示资产，不是目录。
            // 注意范围：迁移 040 建的 `rule-engine-legality-replay` 也是 replay 模式，但它是规则引擎的结构性来源
            // （source_type 为空，不属于任何传感器类型），生产必须有。所以这里只排查挂了传感器 source_type 的回放来源。
            assertThat(jdbc.queryForList("select source_code from integration_source where source_mode='replay' and source_type is not null",
                    String.class)).as("凌云 v2 数据集的传感器来源行属于演示资产，生产不得有").isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from integration_source where source_id='rule-engine-legality-replay'", Integer.class))
                    .as("规则引擎的回放来源行来自迁移 040，是结构性目录，不在清理范围内").isEqualTo(1);

            // 结构性目录：生产也必须有，且必须自述为 DEMO（未联调）。这一段与上面的"为空"断言方向相反，是有意的。
            // 契约 §2 的八种来源类型是外键与融合参数的前提，生产也要有；口径统一在夹具里（阶段 10.3）。
            SourceTypeCatalogFixture.assertCatalog(jdbc);
            assertThat(jdbc.queryForList(
                    "select source_type from source_type_catalog where schema_status='DEMO' and source_type in ('AOA','DCD','RID') order by source_type",
                    String.class)).as("凌云三路未联调，生产也必须标 DEMO").containsExactlyElementsOf(SourceTypeCatalogFixture.STAGE85_TYPES);
            assertThat(jdbc.queryForObject("select count(*) from source_type_catalog where spec_ref is null", Integer.class))
                    .as("每种来源都要能追到协议出处").isZero();
            assertThat(jdbc.queryForObject("select status from fusion_config where config_version='demo-v1'", String.class))
                    .as("融合参数来自迁移，生产也要有一份 ACTIVE").isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select schema_status from fusion_config where config_version='demo-v1'", String.class))
                    .as("阈值与权重未经算法方确认").isEqualTo("DEMO");
        }
    }
}
