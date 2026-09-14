package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.Application;
import com.uav.lowaltitude.integration.mock.RuleReplayRunner.ReplayReport;
import com.uav.lowaltitude.modules.airspace.domain.AirspaceKind;

/**
 * 阶段 7 种子：幂等、重启不重置人工复核与已激活版本、双门禁（production 与 production,local 都不注册）、
 * 回放 Runner 已有同数据集运行则跳过且不新增运行记录。回放的十个场景结论由 RuleReplayRegressionTest（H2 桩事实）与 Stage7PostgresTest 覆盖。
 */
@SpringBootTest(properties = { "app.dev-seed.enabled=true", "app.rule-engine.enabled=false", "app.rule-engine.replay.run-on-start=false" })
@ActiveProfiles("test")
@Transactional
class LocalStage7RuleEngineSeederTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage7RuleEngineSeeder seeder;
    @Autowired RuleReplayRunner runner;
    @Autowired ApplicationArguments arguments;

    @Test
    void seedsContractCatalogScenariosAndAirspacesIdempotently() {
        Map<String, Long> before = counts();
        seeder.run(arguments);
        assertThat(counts()).isEqualTo(before);

        assertThat(jdbc.queryForObject("select active_version_id from rule_set where rule_set_code=?", String.class, LocalStage7RuleEngineSeeder.RULE_SET_CODE)).isEqualTo(LocalStage7RuleEngineSeeder.VERSION_1);
        assertThat(jdbc.queryForObject("select shadow_version_id from rule_set where rule_set_code=?", String.class, LocalStage7RuleEngineSeeder.RULE_SET_CODE)).isNull();
        assertThat(jdbc.queryForList("select status_code||'/'||param_status from rule_set_version where rule_set_id=? order by version_no", String.class, LocalStage7RuleEngineSeeder.RULE_SET_ID))
                .containsExactly("PUBLISHED/DEMO", "PUBLISHED/DEMO");
        List<String> members = jdbc.queryForList("select rv.rule_code from rule_set_member m join rule_version rv on rv.rule_version_id=m.rule_version_id where m.rule_set_version_id=? order by m.priority", String.class, LocalStage7RuleEngineSeeder.VERSION_1);
        assertThat(members).containsExactly("C01", "C02-1", "C02-2", "C02-3", "C02-4", "C02-5", "C02-6", "C02-7", "C02-8", "C03", "C06");
        // 契约 DEMO 参数目录全量（37 项），v2 只改 C02-3.tolerance_m。
        assertThat(jdbc.queryForObject("select count(*) from rule_param where rule_set_version_id=? and param_status='DEMO'", Long.class, LocalStage7RuleEngineSeeder.VERSION_1)).isEqualTo(37L);
        assertThat(jdbc.queryForObject("select count(*) from rule_param where rule_set_version_id=?", Long.class, LocalStage7RuleEngineSeeder.VERSION_2)).isEqualTo(37L);
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C02-3' and param_key='tolerance_m'", String.class, LocalStage7RuleEngineSeeder.VERSION_1)).isEqualTo("20");
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C02-3' and param_key='tolerance_m'", String.class, LocalStage7RuleEngineSeeder.VERSION_2)).isEqualTo("50");
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C03' and param_key='no_plan_status'", String.class, LocalStage7RuleEngineSeeder.VERSION_1)).isEqualTo("ILLEGAL");
        assertThat(jdbc.queryForObject("select count(*) from rule_param p join rule_set_version v on v.rule_set_version_id=p.rule_set_version_id where v.rule_set_id=? and p.param_status<>'DEMO'", Long.class, LocalStage7RuleEngineSeeder.RULE_SET_ID)).isZero();

        for (String scenario : LocalStage7RuleEngineSeeder.SCENARIOS) {
            assertThat(jdbc.queryForObject("select count(*) from target_latest_state where target_id=?", Long.class, LocalStage7RuleEngineSeeder.targetId(scenario))).as(scenario).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from track_point tp join track t on t.track_id=tp.track_id where t.target_id=?", Long.class, LocalStage7RuleEngineSeeder.targetId(scenario))).as(scenario).isGreaterThanOrEqualTo(3L);
        }
        assertThat(jdbc.queryForList("select kind_code from airspace_version where airspace_id like 'seed-stage7-airspace-%' order by kind_code", String.class))
                .as("阶段 10 起种子只写 AirspaceKind 五值字典，历史同义写法不再出现（决策 10-2）")
                .containsExactly(AirspaceKind.ALTITUDE_LIMIT, AirspaceKind.PROHIBITED, AirspaceKind.TEMPORARY_CONTROL)
                .allSatisfy(kind -> assertThat(AirspaceKind.supported(kind)).isTrue());
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C02-2' and param_key='kinds'", String.class, LocalStage7RuleEngineSeeder.VERSION_1))
                .isEqualTo(AirspaceKind.ALTITUDE_LIMIT);
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C02-8' and param_key='kinds'", String.class, LocalStage7RuleEngineSeeder.VERSION_1))
                .isEqualTo(AirspaceKind.TEMPORARY_CONTROL);
        assertThat(jdbc.queryForObject("select altitude_datum||'/'||min_altitude_m||'/'||max_altitude_m from airspace_version where airspace_version_id='seed-stage7-av-h1'", String.class)).startsWith("AMSL/0");
        // 无计划场景没有计划；跨范围场景落在另一元组；缺高度基准场景只有 AGL。
        assertThat(jdbc.queryForObject("select count(*) from flight_plan where plan_id in (?,?)", Long.class, LocalStage7RuleEngineSeeder.planId("no-plan"), LocalStage7RuleEngineSeeder.planId("cross-scope"))).isZero();
        assertThat(jdbc.queryForObject("select owner_org_id||'/'||district_id from target where target_id=?", String.class, LocalStage7RuleEngineSeeder.targetId("cross-scope")))
                .isEqualTo(LocalStage7RuleEngineSeeder.OTHER_ORG + "/" + LocalStage7RuleEngineSeeder.OTHER_DISTRICT);
        assertThat(jdbc.queryForObject("select count(*) from target_latest_state where target_id=? and altitude_amsl_m is null and height_agl_m is not null", Long.class, LocalStage7RuleEngineSeeder.targetId("datum-mismatch"))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select fusion_confidence from target_latest_state where target_id=?", java.math.BigDecimal.class, LocalStage7RuleEngineSeeder.targetId("degraded")).doubleValue()).isLessThan(0.75);
        // 种子只写事实：不产生研判、复核或告警。
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation e join rule_set_version v on v.rule_set_version_id=e.rule_set_version_id where v.rule_set_id=? and e.run_id like 'seed-%'", Long.class, LocalStage7RuleEngineSeeder.RULE_SET_ID)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alarm where target_id like 'seed-stage7-target-%' and source_id='seed-stage7-source'", Long.class)).isZero();
    }

    /**
     * 迁移 074（决策 10-2/10-5）在 H2 上的证据：ck_stage9_airspace_kind_code 只留五值，历史写法 HEIGHT_LIMIT / TEMPORARY 被 CHECK 拒，
     * 规范值照常写入。PostgreSQL 侧同一断言由助手的 Stage9PostgresTest 覆盖。按约束名断言，换成别的错误也算失败。
     */
    @Test
    void migration074RejectsLegacyKindCodesOnH2() {
        Timestamp at = Timestamp.from(LocalStage7RuleEngineSeeder.T0);
        String boundary = jdbc.queryForObject("select cast(boundary as varchar) from airspace_version where airspace_version_id='seed-stage7-av-h1'", String.class);
        final int legacyVersionNo = 100;
        int versionNo = legacyVersionNo;
        for (String legacy : List.of("HEIGHT_LIMIT", "TEMPORARY")) {
            String id = "s10-legacy-" + UUID.randomUUID().toString().substring(0, 8);
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> insertVersion(id, legacyVersionNo, legacy, boundary, at));
            assertThat(failure).as("历史写法 " + legacy + " 必须被 CHECK 拒").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(failure.getMessage()).containsIgnoringCase("ck_stage9_airspace_kind_code");
            assertThat(jdbc.queryForObject("select count(*) from airspace_version where airspace_version_id=?", Long.class, id)).isZero();
        }
        for (String kind : AirspaceKind.CODES) {
            String id = "s10-canonical-" + UUID.randomUUID().toString().substring(0, 8);
            insertVersion(id, ++versionNo, kind, boundary, at);
            assertThat(jdbc.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, id)).isEqualTo(kind);
        }
    }

    private void insertVersion(String id, int versionNo, String kind, String boundary, Timestamp at) {
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at)"
                + " values (?,'seed-stage7-airspace-h1',?,?,CAST(? AS GEOMETRY),?,?)", id, versionNo, kind, boundary, at, at);
    }

    @Test
    void restartNeverResetsManualReviewOrOperatorActivation() {
        String run = "seed-test-run-" + UUID.randomUUID().toString().substring(0, 8), evaluation = "seed-test-eval-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(LocalStage7RuleEngineSeeder.T0);
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,finished_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) values (?,?,?,'ACTIVE','MANUAL',?,?,?,'DONE',1,1,0,0,'mock',?)",
                run, LocalStage7RuleEngineSeeder.RULE_SET_ID, LocalStage7RuleEngineSeeder.VERSION_1, at, at, at, at);
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,"
                + "violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " values (?,?,?,'ACTIVE','TARGET',?,?,?,'REPLAY','NONE','ILLEGAL',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,'mock',?)",
                evaluation, run, LocalStage7RuleEngineSeeder.VERSION_1, LocalStage7RuleEngineSeeder.targetId("no-plan"), at, at, LocalStage7RuleEngineSeeder.ORG, LocalStage7RuleEngineSeeder.DISTRICT, at);
        jdbc.update("insert into legality_review (evaluation_id,review_state,manual_status,version,owner_org_id,district_id,created_at,updated_at) values (?,'REJECTED',null,1,?,?,?,?)",
                evaluation, LocalStage7RuleEngineSeeder.ORG, LocalStage7RuleEngineSeeder.DISTRICT, at, at);
        // 运维已把 v2 激活：重启后的种子必须尊重这个选择。
        jdbc.update("update rule_set set active_version_id=?,previous_active_version_id=?,version=version+1 where rule_set_id=?",
                LocalStage7RuleEngineSeeder.VERSION_2, LocalStage7RuleEngineSeeder.VERSION_1, LocalStage7RuleEngineSeeder.RULE_SET_ID);
        seeder.run(arguments);
        assertThat(jdbc.queryForObject("select review_state||'/'||version from legality_review where evaluation_id=?", String.class, evaluation)).isEqualTo("REJECTED/1");
        assertThat(jdbc.queryForObject("select active_version_id from rule_set where rule_set_id=?", String.class, LocalStage7RuleEngineSeeder.RULE_SET_ID)).isEqualTo(LocalStage7RuleEngineSeeder.VERSION_2);
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation where run_id=?", Long.class, run)).isEqualTo(1L);
    }

    @Test
    void replayRunnerSkipsWhenDatasetAlreadyReplayedOnActiveVersion() {
        String existing = "seed-test-replay-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(LocalStage7RuleEngineSeeder.T0);
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,replay_dataset_code,as_of,started_at,finished_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) values (?,?,?,'ACTIVE','REPLAY',?,?,?,?,'DONE',11,11,1,1,'mock',?)",
                existing, LocalStage7RuleEngineSeeder.RULE_SET_ID, LocalStage7RuleEngineSeeder.VERSION_1, RuleReplayRunner.DATASET, at, at, at, at);
        long runsBefore = jdbc.queryForObject("select count(*) from rule_run", Long.class);
        ReplayReport report = runner.replay();
        assertThat(report.skipped()).isTrue();
        assertThat(report.runId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from rule_run", Long.class)).isEqualTo(runsBefore);
        // 没有 ACTIVE 版本也只跳过，不伪造运行记录。
        jdbc.update("update rule_set set active_version_id=null where rule_set_id=?", LocalStage7RuleEngineSeeder.RULE_SET_ID);
        assertThat(runner.replay().skipped()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from rule_run", Long.class)).isEqualTo(runsBefore);
    }

    @Test
    void profileAndPropertyGatesExcludeProductionEvenWhenLocalIsAlsoActive() {
        Profile profile = LocalStage7RuleEngineSeeder.class.getAnnotation(Profile.class);
        ConditionalOnProperty property = LocalStage7RuleEngineSeeder.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(profile).isNotNull();
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches(name -> Set.of("production", "local").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("production").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("test").contains(name))).isTrue();
        assertThat(expression.matches(name -> Set.of("local").contains(name))).isTrue();
        assertThat(property.havingValue()).isEqualTo("true");
        Profiles runner = Profiles.of(RuleReplayRunner.class.getAnnotation(Profile.class).value());
        assertThat(runner.matches(name -> Set.of("production", "local").contains(name))).isFalse();
        assertThat(runner.matches(name -> Set.of("local").contains(name))).isTrue();
    }

    @Test
    void isolatedProductionContextsRegisterNeitherSeederNorReplayRunner() {
        assertIsolated("production");
        assertIsolated("production", "local");
    }

    private static void assertIsolated(String... profiles) {
        String[] args = {
                "--spring.datasource.url=jdbc:h2:mem:stage7-gate-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=true", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.rule-engine.replay.run-on-start=true", "--spring.main.banner-mode=off"};
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).profiles(profiles).run(args)) {
            assertThat(context.getBeansOfType(LocalStage7RuleEngineSeeder.class)).isEmpty();
            assertThat(context.getBeansOfType(RuleReplayRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(ApplicationRunner.class).keySet()).noneMatch(name -> name.toLowerCase().contains("stage7"));
            JdbcTemplate isolated = context.getBean(JdbcTemplate.class);
            // 阶段 9 起迁移 062 会登记 SPACE-RISK-DEMO 规则集（结构性目录，未激活）；生产的不变量是"没有生效/影子版本、没有阶段 7 种子规则集"。
            assertThat(isolated.queryForObject("select count(*) from rule_set where active_version_id is not null or shadow_version_id is not null", Long.class)).isZero();
            assertThat(isolated.queryForObject("select count(*) from rule_set where rule_set_code='LEGALITY-DEMO'", Long.class)).isZero();
            assertThat(isolated.queryForObject("select count(*) from rule_run", Long.class)).isZero();
            assertThat(isolated.queryForObject("select count(*) from target where target_id like 'seed-stage7-%'", Long.class)).isZero();
        }
    }

    private Map<String, Long> counts() {
        return Map.of(
                "rule_version", count("rule_version where rule_version_id like 'seed-stage7-%'"),
                "rule_set_version", count("rule_set_version where rule_set_id='" + LocalStage7RuleEngineSeeder.RULE_SET_ID + "'"),
                "rule_set_member", count("rule_set_member where rule_set_version_id like 'seed-stage7-%'"),
                "rule_param", count("rule_param where rule_set_version_id like 'seed-stage7-%'"),
                "airspace_version", count("airspace_version where airspace_id like 'seed-stage7-%'"),
                "target", count("target where target_id like 'seed-stage7-%'"),
                "track_point", count("track_point where track_id like 'seed-stage7-%'"),
                "flight_plan", count("flight_plan where plan_id like 'seed-stage7-%'"),
                "route_version", count("route_version where route_version_id like 'seed-stage7-%'"));
    }

    private long count(String fromWhere) { return jdbc.queryForObject("select count(*) from " + fromWhere, Long.class); }
}
