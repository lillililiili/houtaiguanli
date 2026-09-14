package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.integration.mock.RuleReplayRunner;
import com.uav.lowaltitude.integration.mock.RuleReplayRunner.ReplayReport;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/**
 * 回放回归命令的 H2 层：契约十个场景的决策表。空间事实（空域覆盖/接触、到航线距离、版本歧义）只可能来自 PostGIS，
 * H2 上由本类的桩 {@link SpatialFactPort} 按目标给出与种子几何等价的固定事实；PostGIS 端到端断言见 Stage7PostgresTest。
 *
 * 决策表按契约 C03 短路顺序推导，不复述实现：
 * legal → LEGAL；deviation → ABNORMAL/ROUTE_DEVIATION；airspace-limit → ILLEGAL/AIRSPACE_ALTITUDE_EXCEEDED；
 * plan-altitude → ABNORMAL/PLAN_ALTITUDE_EXCEEDED；boundary → UNDETERMINED/BOUNDARY_POLICY_UNKNOWN；
 * no-plan → ILLEGAL/NO_AUTHORIZATION 并带评分；degraded → UNDETERMINED/LOW_CONFIDENCE+TRACK_BRIDGED；
 * datum-mismatch → UNDETERMINED/ALTITUDE_DATUM_OR_RANGE_UNKNOWN；merge → ILLEGAL/TEMPORARY_RESTRICTION_ACTIVE 且同目标两次只一条告警；
 * cross-scope → ILLEGAL/NO_AUTHORIZATION 落在另一元组。
 *
 * 种子由 LocalStage7RuleEngineSeeder 在上下文启动时写入（app.dev-seed.enabled=true）；本类只驱动 RuleReplayRunner 并断言结果。
 * 测试事务包住整次回放：每主体的 NESTED 事务退化为保存点，结束后整体回滚，不给共享 H2 留下 REPLAY 运行记录。
 */
@SpringBootTest(properties = { "app.dev-seed.enabled=true", "app.rule-engine.enabled=false", "app.rule-engine.replay.run-on-start=false" })
@ActiveProfiles("test")
@Transactional
class RuleReplayRegressionTest {
    private static final OffsetDateTime T0 = LocalStage7RuleEngineSeeder.T0.atOffset(ZoneOffset.UTC);
    private static final BigDecimal HALF_WIDTH = new BigDecimal("50");
    private static final BigDecimal ON_CENTERLINE = BigDecimal.ZERO;
    /** 偏航场景：目标北移约 133 m，> 半宽 50 + C02-3 容差 20，且 ≤ 半宽 50 + C01 走廊容差 100（仍匹配计划）。 */
    private static final BigDecimal DEVIATION_M = new BigDecimal("133.2");
    private static final String AIRSPACE_P1 = "seed-stage7-airspace-p1", AIRSPACE_H1 = "seed-stage7-airspace-h1", AIRSPACE_T1 = "seed-stage7-airspace-t1";
    private static final int SUBJECTS = LocalStage7RuleEngineSeeder.SCENARIOS.size() + 1;

    @Autowired JdbcTemplate jdbc;
    @Autowired StubSpatialFacts facts;
    @Autowired RuleReplayRunner runner;
    @Autowired ObjectMapper json;

    /**
     * 场景决策表：场景码、期望四态、期望计划匹配等级、期望违规原因码（精确）、必须出现的未知原因码（包含）、是否评分、
     * 期望告警合并结果（null 表示不告警）。C02-6 恒未知，所以 unknown_reasons 常带 PILOT_POSITION_UNAVAILABLE，只断言包含。
     */
    record Expectation(String scenario, String legalStatus, String planMatch, List<String> violationReasons, List<String> unknownReasons,
            boolean scored, String alarmKind) { }

    static final List<Expectation> DECISION_TABLE = List.of(
            new Expectation("legal", "LEGAL", "FULL", List.of(), List.of(), false, null),
            new Expectation("deviation", "ABNORMAL", "FULL", List.of("ROUTE_DEVIATION"), List.of(), true, "CREATED"),
            new Expectation("airspace-limit", "ILLEGAL", "FULL", List.of("AIRSPACE_ALTITUDE_EXCEEDED"), List.of(), true, "CREATED"),
            new Expectation("plan-altitude", "ABNORMAL", "FULL", List.of("PLAN_ALTITUDE_EXCEEDED"), List.of(), true, "CREATED"),
            new Expectation("boundary", "UNDETERMINED", "FULL", List.of(), List.of("BOUNDARY_POLICY_UNKNOWN"), false, null),
            new Expectation("no-plan", "ILLEGAL", "NONE", List.of("NO_AUTHORIZATION"), List.of(), true, "CREATED"),
            new Expectation("degraded", "UNDETERMINED", "FULL", List.of(), List.of("LOW_CONFIDENCE", "TRACK_BRIDGED"), false, null),
            new Expectation("datum-mismatch", "UNDETERMINED", "FULL", List.of(), List.of("ALTITUDE_DATUM_OR_RANGE_UNKNOWN"), false, null),
            new Expectation("merge", "ILLEGAL", "FULL", List.of("TEMPORARY_RESTRICTION_ACTIVE"), List.of(), true, "CREATED"),
            new Expectation("cross-scope", "ILLEGAL", "NONE", List.of("NO_AUTHORIZATION"), List.of(), true, "CREATED"));

    @BeforeEach
    void registerSeedEquivalentFacts() {
        facts.reset();
        OffsetDateTime dayBefore = T0.minusDays(1);
        // 有计划的场景都在各自航线中心线上，只有 deviation 偏离；无计划场景不需要航线距离（C02-3 不适用）。
        for (String scenario : List.of("legal", "airspace-limit", "plan-altitude", "boundary", "degraded", "datum-mismatch", "merge")) {
            facts.route(LocalStage7RuleEngineSeeder.targetId(scenario), ON_CENTERLINE, HALF_WIDTH);
        }
        facts.route(LocalStage7RuleEngineSeeder.targetId("deviation"), DEVIATION_M, HALF_WIDTH);
        // 空域事实与种子几何等价：H1 限高 AMSL 0–60（airspace-limit、datum-mismatch 在其内）、P1 禁止（boundary 恰在顶点）、T1 临时管制 T0±1h（merge 在其内）。
        // kind 用种子自阶段 10 起写出的规范值（决策 10-2）；决策表的十个结论不变——桩事实只是换了同义写法，规则参数也只认规范值。
        facts.airspace(LocalStage7RuleEngineSeeder.targetId("airspace-limit"), covers(AIRSPACE_H1, "ALTITUDE_LIMIT", BigDecimal.ZERO, new BigDecimal("60"), "AMSL", dayBefore, null));
        facts.airspace(LocalStage7RuleEngineSeeder.targetId("datum-mismatch"), covers(AIRSPACE_H1, "ALTITUDE_LIMIT", BigDecimal.ZERO, new BigDecimal("60"), "AMSL", dayBefore, null));
        facts.airspace(LocalStage7RuleEngineSeeder.targetId("boundary"), touches(AIRSPACE_P1, "PROHIBITED", dayBefore));
        facts.airspace(LocalStage7RuleEngineSeeder.targetId("merge"), covers(AIRSPACE_T1, "TEMPORARY_CONTROL", null, null, null, T0.minusHours(1), T0.plusHours(1)));
    }

    @Test
    void decisionTableIsComplete() {
        assertThat(DECISION_TABLE).extracting(Expectation::scenario).containsExactlyElementsOf(LocalStage7RuleEngineSeeder.SCENARIOS);
        assertThat(jdbc.queryForObject("select active_version_id from rule_set where rule_set_code=?", String.class, LocalStage7RuleEngineSeeder.RULE_SET_CODE))
                .as("种子已在上下文启动时激活 v1").isEqualTo(LocalStage7RuleEngineSeeder.VERSION_1);
    }

    @Test
    void replayRunnerReproducesContractDecisionTableOnStubbedSpatialFacts() {
        ReplayReport report = runner.replay();
        assertThat(report.skipped()).isFalse();
        assertThat(report.runId()).isNotNull();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.evaluatedCount()).isEqualTo(SUBJECTS);

        Map<String, Object> run = jdbc.queryForMap("select status,mode,trigger_kind,replay_dataset_code,rule_set_version_id,subject_count,evaluated_count,"
                + "alarm_created_count,alarm_merged_count,error_summary from rule_run where run_id=?", report.runId());
        assertThat(run.get("status")).isEqualTo("DONE");
        assertThat(run.get("mode")).isEqualTo("ACTIVE");
        assertThat(run.get("trigger_kind")).isEqualTo("REPLAY");
        assertThat(run.get("replay_dataset_code")).isEqualTo(RuleReplayRunner.DATASET);
        assertThat(run.get("rule_set_version_id")).isEqualTo(LocalStage7RuleEngineSeeder.VERSION_1);
        assertThat(((Number) run.get("subject_count")).intValue()).isEqualTo(SUBJECTS);
        assertThat(((Number) run.get("evaluated_count")).intValue()).isEqualTo(SUBJECTS);
        assertThat(run.get("error_summary")).as("没有任何主体评估失败").isNull();
        long created = DECISION_TABLE.stream().filter(e -> "CREATED".equals(e.alarmKind())).count();
        assertThat(((Number) run.get("alarm_created_count")).longValue()).isEqualTo(created);
        assertThat(((Number) run.get("alarm_merged_count")).longValue()).as("merge 场景的第二次评估只合并").isEqualTo(1L);

        for (Expectation expected : DECISION_TABLE) {
            String target = LocalStage7RuleEngineSeeder.targetId(expected.scenario());
            List<Map<String, Object>> rows = jdbc.queryForList("select evaluation_id,mode,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,unknown_reasons,"
                    + "assessment_id,alarm_id,alarm_outcome,owner_org_id,district_id from rule_evaluation where run_id=? and target_id=? order by evaluated_at desc,evaluation_id desc",
                    report.runId(), target);
            assertThat(rows).as(expected.scenario() + " 研判条数").hasSize("merge".equals(expected.scenario()) ? 2 : 1);
            for (Map<String, Object> row : rows) assertScenario(expected, row);
        }
        assertMergeScenarioCreatesExactlyOneAlarm(report.runId());
        assertCrossScopeStaysInOtherTuple(report.runId());
    }

    @Test
    void replayIsSkippedOnceDatasetExistsAndNeverOverwritesManualReview() {
        ReplayReport first = runner.replay();
        assertThat(first.skipped()).isFalse();
        String legal = jdbc.queryForObject("select evaluation_id from rule_evaluation where run_id=? and target_id=?", String.class,
                first.runId(), LocalStage7RuleEngineSeeder.targetId("legal"));
        // 人工把 legal 场景的复核改为已确认：第二次回放既不能新增运行，也不能把复核重置回 PENDING_REVIEW。
        assertThat(jdbc.update("update legality_review set review_state='CONFIRMED',manual_status='LEGAL',version=1 where evaluation_id=?", legal)).isEqualTo(1);
        long runs = jdbc.queryForObject("select count(*) from rule_run", Long.class);
        long evaluations = jdbc.queryForObject("select count(*) from rule_evaluation where run_id=?", Long.class, first.runId());

        ReplayReport second = runner.replay();
        assertThat(second.skipped()).isTrue();
        assertThat(second.runId()).isNull();
        assertThat(second.evaluatedCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from rule_run", Long.class)).isEqualTo(runs);
        assertThat(jdbc.queryForObject("select count(*) from rule_run where trigger_kind='REPLAY' and replay_dataset_code=? and rule_set_version_id=?", Long.class,
                RuleReplayRunner.DATASET, LocalStage7RuleEngineSeeder.VERSION_1)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation where run_id=?", Long.class, first.runId())).isEqualTo(evaluations);
        assertThat(jdbc.queryForObject("select review_state||'/'||manual_status||'/'||version from legality_review where evaluation_id=?", String.class, legal))
                .isEqualTo("CONFIRMED/LEGAL/1");
    }

    private void assertScenario(Expectation expected, Map<String, Object> row) {
        String scenario = expected.scenario();
        String evaluationId = (String) row.get("evaluation_id");
        assertThat(row.get("mode")).as(scenario).isEqualTo("ACTIVE");
        assertThat(row.get("freshness_code")).as(scenario + " 回放以观测时刻为评估时点").isEqualTo("REPLAY");
        assertThat(row.get("legal_status")).as(scenario + " legal_status").isEqualTo(expected.legalStatus());
        assertThat(row.get("plan_match_code")).as(scenario + " plan_match_code").isEqualTo(expected.planMatch());
        assertThat(strings(row.get("violation_reasons"))).as(scenario + " violation_reasons").containsExactlyInAnyOrderElementsOf(expected.violationReasons());
        assertThat(strings(row.get("unknown_reasons"))).as(scenario + " unknown_reasons").containsAll(expected.unknownReasons());
        if (expected.scored()) {
            assertThat(row.get("score")).as(scenario + " 评分").isNotNull();
            assertThat(row.get("grade")).as(scenario + " 等级").isIn("HIGH", "MEDIUM", "LOW");
        } else {
            assertThat(row.get("score")).as(scenario + " 非 ILLEGAL/ABNORMAL 不评分").isNull();
            assertThat(row.get("grade")).as(scenario).isNull();
        }
        // 投影：只有匹配到计划（FULL/PARTIAL）的 ACTIVE 研判才落 assessment_result，且结论一致。
        boolean projected = "FULL".equals(expected.planMatch()) || "PARTIAL".equals(expected.planMatch());
        if (projected) {
            assertThat(row.get("assessment_id")).as(scenario + " 投影").isNotNull();
            assertThat(jdbc.queryForObject("select conclusion_code from assessment_result where assessment_id=? and evaluation_id=?", String.class, row.get("assessment_id"), evaluationId))
                    .isEqualTo(expected.legalStatus());
        } else {
            assertThat(row.get("assessment_id")).as(scenario + " 无计划不投影").isNull();
        }
        // ACTIVE 研判都有待复核行，且归属元组复制自研判行。
        assertThat(jdbc.queryForObject("select review_state||'/'||version||'/'||owner_org_id||'/'||district_id from legality_review where evaluation_id=?", String.class, evaluationId))
                .as(scenario + " 复核行").isEqualTo("PENDING_REVIEW/0/" + row.get("owner_org_id") + "/" + row.get("district_id"));
        String kind = kind(row.get("alarm_outcome"));
        if (expected.alarmKind() == null) {
            assertThat(kind).as(scenario + " 不告警").isNull();
            assertThat(row.get("alarm_id")).as(scenario).isNull();
        } else if (!"merge".equals(scenario)) {
            assertThat(kind).as(scenario + " 告警结果").isEqualTo(expected.alarmKind());
            assertThat(row.get("alarm_id")).as(scenario + " 告警关联").isNotNull();
        }
    }

    /** C06：merge 目标两次 ILLEGAL 只建一条告警与一个 OPEN 合并组，第二次以 MERGED 成员挂入。 */
    private void assertMergeScenarioCreatesExactlyOneAlarm(String runId) {
        String target = LocalStage7RuleEngineSeeder.targetId("merge");
        List<Map<String, Object>> rows = jdbc.queryForList("select evaluation_id,alarm_id,alarm_outcome from rule_evaluation where run_id=? and target_id=?", runId, target);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> kind(r.get("alarm_outcome"))).toList()).containsExactlyInAnyOrder("CREATED", "MERGED");
        List<String> alarms = jdbc.queryForList("select alarm_id from alarm where target_id=? and alarm_type='RULE_LEGALITY'", String.class, target);
        assertThat(alarms).hasSize(1);
        Map<String, Object> alarm = jdbc.queryForMap("select source_id,source_alarm_id,severity,source_mode from alarm where alarm_id=?", alarms.get(0));
        assertThat(alarm.get("source_id")).isEqualTo("rule-engine-legality-mock");
        assertThat(alarm.get("source_mode")).isEqualTo("mock");
        String created = rows.stream().filter(r -> "CREATED".equals(kind(r.get("alarm_outcome")))).map(r -> (String) r.get("evaluation_id")).findFirst().orElseThrow();
        String merged = rows.stream().filter(r -> "MERGED".equals(kind(r.get("alarm_outcome")))).map(r -> (String) r.get("evaluation_id")).findFirst().orElseThrow();
        assertThat(alarm.get("source_alarm_id")).isEqualTo("eval:" + created);
        assertThat(rows.stream().filter(r -> created.equals(r.get("evaluation_id"))).findFirst().orElseThrow().get("alarm_id")).isEqualTo(alarms.get(0));
        assertThat(rows.stream().filter(r -> merged.equals(r.get("evaluation_id"))).findFirst().orElseThrow().get("alarm_id")).as("合并不建第二条告警").isNull();
        assertThat(jdbc.queryForObject("select count(*) from uav_event where alarm_id=? and state_code='PENDING_VERIFICATION'", Long.class, alarms.get(0))).isEqualTo(1L);
        List<Map<String, Object>> groups = jdbc.queryForList("select group_id,state,hit_count,first_alarm_id,latest_alarm_id,current_severity from alarm_merge_group where target_id=? and alarm_type='RULE_LEGALITY'", target);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("state")).isEqualTo("OPEN");
        assertThat(((Number) groups.get(0).get("hit_count")).intValue()).isEqualTo(2);
        assertThat(groups.get(0).get("first_alarm_id")).isEqualTo(alarms.get(0));
        assertThat(groups.get(0).get("latest_alarm_id")).isEqualTo(alarms.get(0));
        assertThat(groups.get(0).get("current_severity")).isEqualTo(alarm.get("severity"));
        List<Map<String, Object>> members = jdbc.queryForList("select evaluation_id,alarm_id,member_kind from alarm_merge_member where group_id=? order by created_at,member_id", groups.get(0).get("group_id"));
        assertThat(members).hasSize(2);
        assertThat(members.stream().map(m -> (String) m.get("member_kind")).toList()).containsExactlyInAnyOrder("CREATED", "MERGED");
        assertThat(members.stream().filter(m -> "CREATED".equals(m.get("member_kind"))).findFirst().orElseThrow()).containsEntry("evaluation_id", created).containsEntry("alarm_id", alarms.get(0));
        assertThat(members.stream().filter(m -> "MERGED".equals(m.get("member_kind"))).findFirst().orElseThrow().get("alarm_id")).isNull();
    }

    /** cross-scope：研判与告警都复制目标的另一元组，默认范围（seed-stage7-org/district）读不到。 */
    private void assertCrossScopeStaysInOtherTuple(String runId) {
        String target = LocalStage7RuleEngineSeeder.targetId("cross-scope");
        Map<String, Object> row = jdbc.queryForMap("select owner_org_id,district_id,alarm_id from rule_evaluation where run_id=? and target_id=?", runId, target);
        assertThat(row.get("owner_org_id")).isEqualTo(LocalStage7RuleEngineSeeder.OTHER_ORG);
        assertThat(row.get("district_id")).isEqualTo(LocalStage7RuleEngineSeeder.OTHER_DISTRICT);
        assertThat(jdbc.queryForObject("select owner_org_id||'/'||district_id from alarm where alarm_id=?", String.class, row.get("alarm_id")))
                .isEqualTo(LocalStage7RuleEngineSeeder.OTHER_ORG + "/" + LocalStage7RuleEngineSeeder.OTHER_DISTRICT);
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation where run_id=? and owner_org_id=? and district_id=?", Long.class, runId,
                LocalStage7RuleEngineSeeder.ORG, LocalStage7RuleEngineSeeder.DISTRICT)).isEqualTo(SUBJECTS - 1L);
    }

    /** H2 的 JSON 列经 JDBC 取回是 byte[]，PostgreSQL 是 PGobject/String；统一成文本再解析。 */
    private static String text(Object stored) {
        return stored instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(stored);
    }

    private List<String> strings(Object stored) {
        List<String> output = new ArrayList<>();
        if (stored == null) return output;
        try {
            JsonNode node = json.readTree(text(stored));
            if (node.isTextual()) node = json.readTree(node.textValue());
            for (JsonNode item : node) output.add(item.asText());
        } catch (Exception ex) {
            throw new AssertionError("JSON 列不可解析: " + text(stored), ex);
        }
        return output;
    }

    private String kind(Object alarmOutcome) {
        if (alarmOutcome == null) return null;
        try {
            JsonNode node = json.readTree(text(alarmOutcome));
            if (node.isTextual()) node = json.readTree(node.textValue());
            return node.path("kind").isMissingNode() ? null : node.path("kind").asText();
        } catch (Exception ex) {
            throw new AssertionError("alarm_outcome 不可解析: " + text(alarmOutcome), ex);
        }
    }

    /** 桩事实：按目标 ID 登记，未登记的目标一律给出“未知”而不是默认安全。 */
    static final class StubSpatialFacts implements SpatialFactPort {
        private final Map<String, List<AirspaceHit>> airspaceHits = new ConcurrentHashMap<>();
        private final Map<String, BigDecimal[]> routeDistances = new ConcurrentHashMap<>();
        volatile boolean ambiguous;

        void airspace(String targetId, AirspaceHit... hits) { airspaceHits.put(targetId, List.of(hits)); }
        void route(String targetId, BigDecimal distanceM, BigDecimal halfWidthM) { routeDistances.put(targetId, new BigDecimal[] { distanceM, halfWidthM }); }
        void reset() { airspaceHits.clear(); routeDistances.clear(); ambiguous = false; }

        @Override public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) {
            return new ArrayList<>(airspaceHits.getOrDefault(state.targetId(), List.of()));
        }

        @Override public RouteDistance distanceToRoute(TargetState state, String routeVersionId) {
            BigDecimal[] known = routeDistances.get(state.targetId());
            if (known != null) return new RouteDistance(routeVersionId, known[0], known[1], null);
            return new RouteDistance(routeVersionId, null, null, RuleCodes.POSITION_UNKNOWN);
        }

        @Override public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf) { return ambiguous; }
    }

    static AirspaceHit covers(String airspaceId, String kind, BigDecimal min, BigDecimal max, String datum, OffsetDateTime from, OffsetDateTime to) {
        return new AirspaceHit(airspaceId, airspaceId.replace("-airspace-", "-av-"), kind, RuleCodes.RELATION_COVERS, min, max, datum, from, to, null);
    }

    static AirspaceHit touches(String airspaceId, String kind, OffsetDateTime from) {
        return new AirspaceHit(airspaceId, airspaceId.replace("-airspace-", "-av-"), kind, RuleCodes.RELATION_TOUCHES, null, null, null, from, null, RuleCodes.BOUNDARY_POLICY_UNKNOWN);
    }

    @TestConfiguration
    static class StubSpatialConfiguration {
        @Bean @Primary StubSpatialFacts stubSpatialFacts() { return new StubSpatialFacts(); }
    }
}
