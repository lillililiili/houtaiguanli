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
import com.uav.lowaltitude.testsupport.SourceTypeCatalogFixture;

/**
 * 有效生产配置不注册演示种子、不写样本，迁移目录和显式启用的正式能力继续验证。
 * 非法模拟开关必须拒绝启动，由 ProductionDevSeedIsolationTest 与 SimulationPolicyTest 单独覆盖。
 */
class ProductionStage8SeedIsolationTest {
    @Test void productionHasNoReplaySeedsAndHonorsDisabledFusionWorker() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage8_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=false", "--app.bootstrap-admin.enabled=false", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--app.fusion.enabled=false", "--app.fusion.replay.run-on-start=false",
                "--spring.main.banner-mode=off")) {
            assertThat(context.containsBean("localStage8FusionReplaySeeder")).isFalse();
            assertThat(context.containsBean("fusionReplayRunner")).isFalse();
            // app.fusion.enabled 默认关闭：生产未显式打开时没有摄取 Worker。
            assertThat(context.containsBean("fusionIngestWorker")).isFalse();
            List<String> stage8Runners = context.getBeansOfType(ApplicationRunner.class).keySet().stream()
                    .filter(name -> name.toLowerCase().contains("stage8") || name.toLowerCase().contains("fusionreplay")).toList();
            assertThat(stage8Runners).isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from source_observation", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from target where source_mode='replay'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from fusion_event", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from target_lineage", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from replay_ground_truth", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from integration_source where source_mode='replay' and source_type is not null", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like 'replay:%'", Integer.class)).isZero();

            // 迁移登记的结构性目录：恰一个 ACTIVE 的 demo-v1（DEMO 状态）。
            assertThat(jdbc.queryForObject("select count(*) from fusion_config where config_version='demo-v1' and status='ACTIVE' and schema_status='DEMO'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from fusion_config where status='ACTIVE'", Integer.class)).isEqualTo(1);
            // 设备协议与模拟类型目录均保留，只有雷达 CONFIRMED；目录不代表存在模拟业务数据。
            SourceTypeCatalogFixture.assertCatalog(jdbc);
            assertThat(jdbc.queryForObject("select count(*) from source_type_catalog where schema_status='CONFIRMED'", Integer.class))
                    .isEqualTo(SourceTypeCatalogFixture.CONFIRMED_TYPES.size());
        }
    }
}
