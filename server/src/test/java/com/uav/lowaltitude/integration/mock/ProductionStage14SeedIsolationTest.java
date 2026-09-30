package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.uav.lowaltitude.Application;

/**
 * 有效生产配置不注册演示种子、不写样本，迁移目录和显式启用的正式能力继续验证。
 * 非法模拟开关必须拒绝启动，由 ProductionDevSeedIsolationTest 与 SimulationPolicyTest 单独覆盖。
 */
class ProductionStage14SeedIsolationTest {

    @Test void productionNeverRegistersStage14Seeder() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    /**
     * 反面对照：`local,qa` 下种子**必须真的注册**。
     * 没有这一条，"production 下不注册"可能只是因为 Bean 条件写错了、任何 profile 都不注册——
     * 那样隔离测试全绿，而演示环境根本没有数据，问题要到演示当天才发现。
     */
    @Test
    void explicitLocalQaRegistersTheSeeder() {
        try (ConfigurableApplicationContext context = context("local,qa", "--app.dev-seed.enabled=true", "--app.dev-seed.password=changeme")) {
            assertThat(context.containsBean("localStage14PunishmentSeeder"))
                    .as("local,qa 下演示种子必须注册，否则隔离测试的绿是假的").isTrue();
        }
    }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = context(profiles, "--app.dev-seed.enabled=false")) {
            assertThat(context.containsBean("localStage14PunishmentSeeder"))
                    .as("阶段 14 演示种子不得在 production 注册").isFalse();
            List<String> punishmentSeeders = java.util.Arrays.stream(context.getBeanDefinitionNames())
                    .filter(name -> name.toLowerCase().contains("punishment") && name.toLowerCase().contains("seeder"))
                    .toList();
            assertThat(punishmentSeeders).as("生产不得注册任何处罚演示种子").isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            // ① 业务数据逐表为空。逐表列出而不是抽查——漏掉哪张表，那张表就是演示数据进生产的通道。
            for (String table : List.of("punishment_case", "punishment_case_event", "punishment_case_lead",
                    "penalty_discretion", "penalty_decision_document", "punishment_review", "punishment_no_counter")) {
                assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                        .as(table + " 是案件业务数据，生产必须为空").isZero();
            }
            // 阶段 14 的交接与接收方同样是演示资产（种子建的），生产不得有。
            assertThat(jdbc.queryForObject("select count(*) from handoff_recipient where recipient_id<>'fixed-superior-recipient'", Integer.class))
                    .as("接收方目录由运维在生产自行登记，除迁移登记的上级逻辑接收方外不得预置").isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff", Integer.class)).isZero();

            // ② 档位表必须在，且必须自述为 DEMO。这一段断言方向与上面相反，是有意的。
            assertThat(jdbc.queryForObject("select count(*) from penalty_rule", Integer.class))
                    .as("档位表来自迁移，生产也要有——缺了连裁量草稿都拟不出来").isPositive();
            assertThat(jdbc.queryForObject("select count(*) from penalty_rule where schema_status<>'DEMO'", Integer.class))
                    .as("条款号未经法制岗核定前不得标 CONFIRMED").isZero();
            assertThat(jdbc.queryForObject("select count(*) from penalty_rule where enabled=true", Integer.class))
                    .as("至少要有一条可用档位，否则页面上是一张空表").isPositive();

            // 权限目录行（迁移 0101）：只登记不授权。授权矩阵是部署时的决定，迁移不该替部署做主。
            //
            // **枚举与目录行必须逐一对上**（阶段 9 出过 P0：代码里的枚举多一个、目录里没有，
            // 于是 access.require 永远拿不到那条权限，接口在生产上静默 403，而所有 H2 用例都绿）。
            // 这里直接引用 PermissionCode 而不是抄一份字符串常量：谁改了枚举，这条会在编译期或断言处立刻炸，
            // 而不是等到某个接口在生产上莫名 403。
            List<String> expected = java.util.Arrays.stream(
                    com.uav.lowaltitude.modules.identity.domain.PermissionCode.values())
                    .map(com.uav.lowaltitude.modules.identity.domain.PermissionCode::value)
                    .filter(code -> code.startsWith("punishment:"))
                    .sorted().toList();
            assertThat(expected).as("PermissionCode 里应有五个处罚权限").hasSize(5);
            assertThat(jdbc.queryForList(
                    "select permission_code from app_permission where permission_code like 'punishment:%' order by permission_code",
                    String.class)).as("目录行必须与枚举逐一对上，多一个少一个都不行").containsExactlyElementsOf(expected);
            // 动作码不是菜单项：带 route_key 会让它出现在导航里。
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_permission where permission_code like 'punishment:%' and route_key is not null",
                    Integer.class)).as("动作权限不得带菜单键").isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_permission where permission_code like 'punishment:%' and permission_kind<>'ACTION'",
                    Integer.class)).as("五条都必须是 ACTION 类").isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_role_permission where permission_code like 'punishment:%' and role_code <> 'ROLE-ADMIN'",
                    Integer.class)).as("除内置超级管理员外不得预先授予处罚权限").isZero();
        }
    }

    private static ConfigurableApplicationContext context(String profiles, String... extra) {
        String[] base = {
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage14_seed_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration",
                "--app.bootstrap-admin.enabled=false", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--app.fusion.enabled=false", "--app.fusion.replay.run-on-start=false",
                "--app.disposal.expiry.enabled=false",
                "--spring.main.banner-mode=off" };
        String[] args = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(args);
    }
}
