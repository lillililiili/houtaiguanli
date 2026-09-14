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
 * production 必须压过 local：阶段 9 的空域演示种子与空间风险种子不允许因部署 profile 组合泄入生产。
 *
 * C04/C05 定时评估（SpaceRiskEvaluationJob）按决策 9-16 是**正式能力**，不用 profile 排除生产，
 * 因此这里不断言它是否注册。生产安全改由两道闸保证，本类验证的正是这两道闸：
 *   ① 迁移不把 DEMO 规则集置为 ACTIVE（激活只发生在 local/test 的种子里）——没有 ACTIVE 版本，Job 空转；
 *   ② 即使 Job 注册并被属性打开，也不会产生任何 C04 行。
 * 故意打开 app.dev-seed.enabled 与 app.rule-engine.c04.enabled，就是为了证明这两道闸单独成立。
 *
 * 迁移登记的结构性目录（权限码、异物细类字典、规则引擎来源行、SPACE-RISK-DEMO 规则集与 DEMO 参数）必须存在——
 * 它们是引擎与外键的前提，不是演示数据。按 Bean 名断言，不引用 E1/E2 的类型：类被重命名时这里也不会因编译依赖而“默认通过”。
 */
class ProductionStage9SeedIsolationTest {
    @Test void productionNeverRegistersStage9SeedersAndLeavesDemoRuleSetInactive() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage9_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=true", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=false",
                "--app.fusion.enabled=false", "--app.fusion.replay.run-on-start=false",
                "--app.rule-engine.c04.enabled=true",
                "--spring.main.banner-mode=off")) {
            assertThat(context.containsBean("localStage9AirspaceSeeder")).as("阶段 9 空域演示种子不得在 production 注册").isFalse();
            assertThat(context.containsBean("localStage9SpaceRiskSeeder")).as("阶段 9 空间风险种子不得在 production 注册").isFalse();
            List<String> stage9Runners = context.getBeansOfType(ApplicationRunner.class).keySet().stream()
                    .filter(name -> name.toLowerCase().contains("stage9")).toList();
            assertThat(stage9Runners).isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            // 迁移 060 的结构性目录：四个动作权限码只登记不授权（flight:authorize 已按 F8 裁定由 V202609090106 撤除），两个既有 MODULE 行拿到 route_key 成为真实菜单。
            List<String> actions = jdbc.queryForList(
                    "select permission_code from app_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') order by permission_code",
                    String.class);
            assertThat(actions).containsExactly("airport:manage", "airport:read", "airspace:manage", "risk:evaluate");
            assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') and route_key is not null", Integer.class))
                    .as("动作权限不带菜单键").isZero();
            // V202609070010 撤回菜单提升：两行仍在但 route_key 为空（用户 2026-09-07 裁定）。
            assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code in ('airspace','risk')", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code in ('airspace','risk') and route_key is not null", Integer.class)).isZero();
            // 只登记不授权：除内置超级管理员角色外，没有任何角色被默认授予阶段 9 动作。
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_role_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') and role_code <> 'ROLE-ADMIN'",
                    Integer.class)).isZero();
            // 生产没有空域演示数据：阶段 3 的种子空域与阶段 9 的演示空域都不存在。
            assertThat(jdbc.queryForObject("select count(*) from airspace where airspace_id like 'seed-stage9-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from airspace_version where airspace_version_id like 'seed-stage9-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_org where org_id like 'seed-stage9-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_district where district_id like 'seed-stage9-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_type='SPACE_OBJECT'", Integer.class)).isZero();

            // 闸一：迁移只建规则集与参数，绝不激活。没有 ACTIVE 版本时 C04/C05 无参数可用，Job 只能空转。
            assertThat(jdbc.queryForObject("select count(*) from rule_set where rule_set_code='SPACE-RISK-DEMO'", Integer.class))
                    .as("规则集来自迁移，生产也要有").isEqualTo(1);
            assertThat(jdbc.queryForObject("select active_version_id from rule_set where rule_set_code='SPACE-RISK-DEMO'", String.class))
                    .as("迁移不得把 DEMO 规则集置为 ACTIVE：激活只发生在 local/test 的 LocalStage9SpaceRiskSeeder（决策 9-16）").isNull();
            assertThat(jdbc.queryForObject("select shadow_version_id from rule_set where rule_set_code='SPACE-RISK-DEMO'", String.class)).isNull();
            // 参数本身必须在（引擎读参数缺失即抛，不能用代码默认值掩盖），且全部标 DEMO。
            List<String> params = jdbc.queryForList(
                    "select rule_code||'.'||param_key from rule_param p join rule_set_version v on v.rule_set_version_id=p.rule_set_version_id"
                            + " join rule_set s on s.rule_set_id=v.rule_set_id where s.rule_set_code='SPACE-RISK-DEMO' order by 1", String.class);
            assertThat(params).containsExactly("C04.approach_band_agl_m", "C04.climb_band_agl_m", "C04.corridor_near_m",
                    "C04.flock_count_threshold", "C04.plan_window_pad_min", "C04.trend_window_min",
                    "C05.procedure_buffer_m", "C05.protected_target_pad_m");
            assertThat(jdbc.queryForObject(
                    "select count(*) from rule_param p join rule_set_version v on v.rule_set_version_id=p.rule_set_version_id"
                            + " join rule_set s on s.rule_set_id=v.rule_set_id where s.rule_set_code='SPACE-RISK-DEMO' and p.param_status<>'DEMO'",
                    Integer.class)).as("阶段 9 阈值全部未经业务方确认").isZero();
            // 异物细类字典与两条规则引擎来源行来自迁移，生产也需要（外键前提）。
            assertThat(jdbc.queryForList("select subtype_code from space_object_subtype order by subtype_code", String.class))
                    .containsExactly("BALLOON", "BIRD_FLOCK", "KITE", "OTHER_OBJECT", "SKY_LANTERN");
            assertThat(jdbc.queryForList(
                    "select source_id from integration_source where source_id like 'rule-engine-space-risk-%' and enabled=true and credential_ref is null order by source_id",
                    String.class)).containsExactly("rule-engine-space-risk-live", "rule-engine-space-risk-mock", "rule-engine-space-risk-replay");

            // 闸二：Job 即使注册并被属性打开，也不产生任何 C04/C05 行。
            assertThat(jdbc.queryForObject("select count(*) from space_risk_fact", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from rule_evaluation_run", Integer.class))
                    .as("无 ACTIVE 版本时 Job 空转，连一条评估运行记录都不该有").isZero();

            // 迁移 061/063 建的都是**业务数据表**，不是目录：生产里它们必须一行都没有。
            // 逐表断言而不是抽查——漏掉哪张表，那张表就成了演示数据进生产的通道。
            for (String table : List.of("airspace_version_origin", "airspace_import_batch", "airspace_import_item",
                    "airport", "airport_runway", "airport_procedure_route", "airport_protected_target",
                    "airport_notification_target")) {
                assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                        .as(table + " 是业务数据表，生产必须为空").isZero();
            }
            // 两个种子各自的行也点名核一次：即使将来有人换了 Bean 名绕过上面的 containsBean 断言，数据这一层仍然拦得住。
            assertThat(jdbc.queryForObject("select count(*) from target where target_id like 'seed-stage9-%'", Integer.class))
                    .as("阶段 9 空间风险种子的异物目标不得进生产").isZero();
            assertThat(jdbc.queryForObject("select count(*) from route_version where route_version_id like 'seed-stage3-%'", Integer.class))
                    .as("阶段 9 种子依赖的阶段 3 计划航线同样不得进生产").isZero();
            assertThat(jdbc.queryForObject("select count(*) from flight_plan where plan_id like 'seed-stage3-%'", Integer.class)).isZero();
        }
    }
}
