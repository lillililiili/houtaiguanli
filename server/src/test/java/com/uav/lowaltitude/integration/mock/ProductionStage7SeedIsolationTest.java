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

/**
 * 有效生产配置不注册演示种子、不写样本，迁移目录和显式启用的正式能力继续验证。
 * 非法模拟开关必须拒绝启动，由 ProductionDevSeedIsolationTest 与 SimulationPolicyTest 单独覆盖。
 */
class ProductionStage7SeedIsolationTest {
    @Test void productionNeverRegistersStage7SeederOrReplayRunner() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage7_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=false", "--app.bootstrap-admin.enabled=false", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--spring.main.banner-mode=off")) {
            // 按 Bean 名称断言，不引用种子类型：即使类被重命名，这里也不会因为编译依赖而“默认通过”。
            assertThat(context.containsBean("localStage7RuleEngineSeeder")).isFalse();
            assertThat(context.containsBean("ruleReplayRunner")).isFalse();
            List<String> stage7Runners = context.getBeansOfType(ApplicationRunner.class).keySet().stream()
                    .filter(name -> name.toLowerCase().contains("stage7") || name.toLowerCase().contains("replayrunner")).toList();
            assertThat(stage7Runners).isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            // 生产没有 ACTIVE/SHADOW 规则集版本：引擎空转，不产生研判或告警。
            // 阶段 9 迁移 062 登记 SPACE-RISK-DEMO（PUBLISHED+DEMO，未激活）及其参数属结构性目录；生产不变量改为"无生效/影子版本、无阶段 7 种子规则集"。
            assertThat(jdbc.queryForObject("select count(*) from rule_set where rule_set_code='LEGALITY-DEMO'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from rule_set where active_version_id is not null or shadow_version_id is not null", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from rule_set_version where rule_set_id not in (select rule_set_id from rule_set where rule_set_code='SPACE-RISK-DEMO')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from rule_param where rule_set_version_id not in (select rule_set_version_id from rule_set_version v join rule_set s on s.rule_set_id=v.rule_set_id where s.rule_set_code='SPACE-RISK-DEMO')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from rule_run", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from rule_evaluation", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from legality_review", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from alarm_merge_group", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_org where org_id like 'seed-stage7-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_district where district_id like 'seed-stage7-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from target where target_id like 'seed-stage7-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from airspace where airspace_id like 'seed-stage7-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from alarm where source_id like 'rule-engine-legality-%'", Integer.class)).isZero();

            // 迁移 040 的三条引擎来源行必须存在且无凭据：它们来自迁移而不是种子，生产也需要。
            List<String> sources = jdbc.queryForList(
                    "select source_id from integration_source where source_id like 'rule-engine-legality-%' and enabled=true and credential_ref is null order by source_id",
                    String.class);
            assertThat(sources).containsExactly("rule-engine-legality-live", "rule-engine-legality-mock", "rule-engine-legality-replay");
            assertThat(jdbc.queryForObject("select source_mode from integration_source where source_id='rule-engine-legality-live'", String.class)).isEqualTo("live");
            assertThat(jdbc.queryForObject("select source_mode from integration_source where source_id='rule-engine-legality-mock'", String.class)).isEqualTo("mock");
            assertThat(jdbc.queryForObject("select source_mode from integration_source where source_id='rule-engine-legality-replay'", String.class)).isEqualTo("replay");
        }
    }
}
