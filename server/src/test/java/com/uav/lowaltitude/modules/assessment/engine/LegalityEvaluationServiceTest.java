package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import com.uav.lowaltitude.modules.assessment.engine.LegalityEvaluationService.EvaluationResult;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.EvaluationOutcome;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.HookResult;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunHandle;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunSummary;

/** 引擎核心：H2 + 桩空间事实/计划匹配/钩子；夹具直接插目标、状态、轨迹、计划与规则集。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LegalityEvaluationServiceTest {
    @Autowired LegalityEvaluationService service;
    @Autowired RuleRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StubSpatialFacts spatial;
    @Autowired RecordingHooks hooks;

    private String code, targetId, planId, routeVersionId, c03RuleVersion;
    private OffsetDateTime observedAt;

    @TestConfiguration
    static class Stubs {
        @Bean @Primary StubSpatialFacts stubSpatialFacts() { return new StubSpatialFacts(); }
        @Bean @Primary RecordingHooks recordingHooks() { return new RecordingHooks(); }
        @Bean @Primary PlanMatcher stubPlanMatcher() {
            return (EvaluationContext context, List<PlanFact> candidates, RuleParams params) -> candidates.isEmpty()
                    ? new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"))
                    : new PlanMatch(PlanMatchCode.FULL, candidates.get(0), Map.of("time", "MATCH", "corridor", "MATCH", "identity", "MATCH"), List.of());
        }
    }

    static class StubSpatialFacts implements SpatialFactPort {
        List<AirspaceHit> hits = List.of();
        boolean ambiguous;
        @Override public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) { return hits; }
        @Override public RouteDistance distanceToRoute(TargetState state, String routeVersionId) { return new RouteDistance(routeVersionId, new BigDecimal("5"), new BigDecimal("50"), null); }
        @Override public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf) { return ambiguous; }
    }

    static class RecordingHooks implements RuleEngineHooks {
        final List<EvaluationOutcome> outcomes = new ArrayList<>();
        @Override public HookResult afterEvaluation(EvaluationOutcome outcome) {
            outcomes.add(outcome);
            // 桩只在引擎声明可告警时回填结果；SHADOW 或 LEGAL 时必须收到 alarmEligible=false。
            return outcome.alarmEligible() ? new HookResult(null, Map.of("kind", "CREATED"), true, false) : HookResult.none();
        }
    }

    @BeforeEach
    void fixture() {
        spatial.hits = List.of(); spatial.ambiguous = false; hooks.outcomes.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        code = "E1-TEST-" + suffix;
        observedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10).withNano(0);
        targetId = target(suffix, "E1-SN-" + suffix, observedAt);
        String routeId = "e1-route-" + suffix; routeVersionId = "e1-rv-" + suffix; planId = "e1-plan-" + suffix;
        Timestamp at = ts(observedAt.minusHours(1));
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'seed-stage3-source','mock','seed-stage3-org','seed-stage3-district',?,?,0)", routeId, "E1-R-" + suffix, "引擎测试航线", at, at);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) values (?,?,1,CAST('SRID=4326;LINESTRING (118.02 37.02,118.03 37.03)' AS GEOMETRY),100,10,100,'AMSL',?,?)", routeVersionId, routeId, at, at);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'APPROVED','seed-stage3-source','mock',?,?,?,?,'seed-stage3-org','seed-stage3-district',?,?,0)",
                planId, "E1-P-" + suffix, "E1-SN-" + suffix, ts(observedAt.minusMinutes(10)), ts(observedAt.plusMinutes(50)), routeVersionId, at, at);
        c03RuleVersion = ruleSet(suffix, at);
    }

    @Test
    void birdInProhibitedAirspaceCannotBecomeIllegalUavAlarm() {
        jdbc.update("update target set object_type_code='BIRD' where target_id=?", targetId);
        spatial.hits = List.of(covers("PROHIBITED"));
        RunHandle run = runs.start(code, RunMode.ACTIVE, "MANUAL", null, null, now());
        EvaluationResult result = service.evaluate(subject(targetId), RunMode.ACTIVE, now(), run.runId());
        assertThat(result.legalStatus()).isEqualTo(RuleContracts.LegalStatus.NOT_APPLICABLE);
        assertThat(result.assessmentId()).isNull();
        assertThat(hooks.outcomes).hasSize(1);
        assertThat(hooks.outcomes.get(0).alarmEligible()).isFalse();
    }

    @Test
    void unidentifiedObjectIsUndeterminedEvenInsideProhibitedAirspace() {
        jdbc.update("update target set object_type_code='UNKNOWN' where target_id=?", targetId);
        spatial.hits = List.of(covers("PROHIBITED"));
        RunHandle run = runs.start(code, RunMode.ACTIVE, "MANUAL", null, null, now());
        EvaluationResult result = service.evaluate(subject(targetId), RunMode.ACTIVE, now(), run.runId());
        assertThat(result.legalStatus()).isEqualTo(RuleContracts.LegalStatus.UNDETERMINED);
        assertThat(result.assessmentId()).isNull();
        assertThat(hooks.outcomes).hasSize(1);
        assertThat(hooks.outcomes.get(0).alarmEligible()).isFalse();
    }

    @Test
    void shadowModeRecordsEvaluationWithoutProjectionOrAlarm() {
        spatial.hits = List.of(covers("PROHIBITED"));
        // 影子运行只取 shadow_version_id：没有影子版本必须 409，不能悄悄退回生效版本。
        org.junit.jupiter.api.Assertions.assertThrows(com.uav.lowaltitude.platform.api.ApiException.class,
                () -> runs.start(code, RunMode.SHADOW, "MANUAL", null, null, now()));
        jdbc.update("update rule_set set shadow_version_id=active_version_id where rule_set_code=?", code);
        RunHandle run = runs.start(code, RunMode.SHADOW, "MANUAL", null, null, now());
        EvaluationResult result = service.evaluate(subject(targetId), RunMode.SHADOW, now(), run.runId());
        assertThat(result.legalStatus()).isEqualTo(RuleContracts.LegalStatus.ILLEGAL);
        assertThat(result.assessmentId()).isNull();
        Map<String, Object> row = jdbc.queryForMap("select mode,legal_status,alarm_outcome,assessment_id,alarm_id from rule_evaluation where evaluation_id=?", result.evaluationId());
        assertThat(row.get("mode")).isEqualTo("SHADOW");
        assertThat(text(row.get("alarm_outcome"))).contains("SUPPRESSED_SHADOW");
        assertThat(row.get("assessment_id")).isNull();
        assertThat(row.get("alarm_id")).isNull();
        // SHADOW 不投影：计划维度的 assessment_result 不能出现影子结论。
        assertThat(jdbc.queryForObject("select count(*) from assessment_result where plan_id=?", Long.class, planId)).isZero();
        assertThat(hooks.outcomes).hasSize(1);
        assertThat(hooks.outcomes.get(0).mode()).isEqualTo(RunMode.SHADOW);
        assertThat(hooks.outcomes.get(0).alarmEligible()).isFalse();
    }

    @Test
    void activeFullMatchProjectsAssessmentConsistentWithHitDetails() throws Exception {
        spatial.hits = List.of(covers("PROHIBITED"));
        RunHandle run = runs.start(code, RunMode.ACTIVE, "MANUAL", null, null, now());
        EvaluationResult result = service.evaluate(subject(targetId), RunMode.ACTIVE, now(), run.runId());
        assertThat(result.legalStatus()).isEqualTo(RuleContracts.LegalStatus.ILLEGAL);
        assertThat(result.planMatchCode()).isEqualTo(PlanMatchCode.FULL);
        assertThat(result.assessmentId()).isNotNull();
        Map<String, Object> projection = jdbc.queryForMap("select plan_id,target_id,route_version_id,rule_version_id,conclusion_code,checks,evaluation_id,rule_set_version_id,supersedes_assessment_id from assessment_result where assessment_id=?", result.assessmentId());
        assertThat(projection.get("plan_id")).isEqualTo(planId);
        assertThat(projection.get("target_id")).isEqualTo(targetId);
        assertThat(projection.get("route_version_id")).isEqualTo(routeVersionId);
        assertThat(projection.get("rule_version_id")).isEqualTo(c03RuleVersion);
        assertThat(projection.get("conclusion_code")).isEqualTo("ILLEGAL");
        assertThat(projection.get("evaluation_id")).isEqualTo(result.evaluationId());
        assertThat(projection.get("rule_set_version_id")).isEqualTo(run.ruleSetVersionId());
        assertThat(projection.get("supersedes_assessment_id")).isNull();
        Map<String, Object> evaluation = jdbc.queryForMap("select hit_details,assessment_id,alarm_outcome,violation_reasons,score,grade from rule_evaluation where evaluation_id=?", result.evaluationId());
        assertThat(evaluation.get("assessment_id")).isEqualTo(result.assessmentId());
        JsonNode hits = array(evaluation.get("hit_details")); JsonNode checks = array(projection.get("checks"));
        assertThat(checks.size()).isEqualTo(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            assertThat(checks.get(i).get("rule_code").asText()).isEqualTo(hits.get(i).get("rule_code").asText());
            assertThat(checks.get(i).get("result_code").asText()).isEqualTo(hits.get(i).get("result_code").asText());
            assertThat(checks.get(i).path("reason_code").asText(null)).isEqualTo(hits.get(i).path("reason_code").asText(null));
            assertThat(hits.get(i).get("rule_version_id").asText()).isNotBlank();
        }
        assertThat(text(evaluation.get("violation_reasons"))).contains("INSIDE_RESTRICTED_AIRSPACE");
        assertThat(evaluation.get("score")).isNotNull();
        assertThat(evaluation.get("grade")).isNotNull();
        // 钩子在同事务收到可告警结论，引擎把告警结果一次性回填到研判行。
        assertThat(hooks.outcomes).hasSize(1);
        assertThat(hooks.outcomes.get(0).alarmEligible()).isTrue();
        assertThat(hooks.outcomes.get(0).assessmentId()).isEqualTo(result.assessmentId());
        assertThat(text(evaluation.get("alarm_outcome"))).contains("CREATED");
        assertThat(result.alarmCreated()).isTrue();
    }

    /**
     * 阶段 8.5：飞手位置写进 target_latest_state 之后，C02-6 从"恒未知"变成可判定。
     * 目标在 POINT(118.025 37.025)：飞手北移 0.01° 约 1111 m（超 500 m 阈值），北移 0.0005° 约 55 m（阈内）。
     */
    @Test
    void visualLineOfSightBecomesDecidableOncePilotPositionIsStored() throws Exception {
        assertThat(check(evaluateActive(), "C02-6").path("reason_code").asText(null))
                .as("没有飞手位置时仍是未知").isEqualTo("PILOT_POSITION_UNAVAILABLE");

        setPilot("118.025 37.035");
        JsonNode far = check(evaluateActive(), "C02-6");
        assertThat(far.get("result_code").asText()).isEqualTo("FAIL");
        assertThat(far.get("reason_code").asText()).isEqualTo("BVLOS_EXCEEDED");
        assertThat(far.path("facts").path("distance_m").asDouble()).isBetween(1000.0, 1200.0);

        setPilot("118.025 37.0255");
        JsonNode near = check(evaluateActive(), "C02-6");
        assertThat(near.get("result_code").asText()).isEqualTo("PASS");
        assertThat(near.path("facts").path("distance_m").asDouble()).isLessThan(500.0);
    }

    private void setPilot(String point) {
        jdbc.update("update target_latest_state set pilot_location=CAST(? AS GEOMETRY) where target_id=?", "SRID=4326;POINT(" + point + ")", targetId);
    }

    private Object evaluateActive() {
        RunHandle run = runs.start(code, RunMode.ACTIVE, "MANUAL", null, null, now());
        EvaluationResult result = service.evaluate(subject(targetId), RunMode.ACTIVE, now(), run.runId());
        return jdbc.queryForMap("select hit_details from rule_evaluation where evaluation_id=?", result.evaluationId()).get("hit_details");
    }

    private JsonNode check(Object hitDetails, String ruleCode) throws Exception {
        JsonNode hits = array(hitDetails);
        for (JsonNode hit : hits) {
            if (ruleCode.equals(hit.path("rule_code").asText())) return hit;
        }
        throw new AssertionError("研判明细里没有 " + ruleCode + "：" + hits);
    }

    @Test
    void staleStateIsNotApplicableAndNeverProjected() {
        jdbc.update("update target_latest_state set observed_at=?,updated_at=? where target_id=?", ts(now().minusHours(2)), ts(now().minusHours(2)), targetId);
        spatial.hits = List.of(covers("PROHIBITED"));
        RunHandle run = runs.start(code, RunMode.ACTIVE, "SCHEDULED", null, null, now());
        EvaluationResult result = service.evaluate(subject(targetId), RunMode.ACTIVE, now(), run.runId());
        assertThat(result.legalStatus()).isEqualTo(RuleContracts.LegalStatus.NOT_APPLICABLE);
        assertThat(result.freshness()).isEqualTo(RuleContracts.Freshness.STALE);
        assertThat(result.planMatchCode()).isEqualTo(PlanMatchCode.NOT_APPLICABLE);
        Map<String, Object> row = jdbc.queryForMap("select freshness_code,plan_match_code,legal_status,unknown_reasons,assessment_id from rule_evaluation where evaluation_id=?", result.evaluationId());
        assertThat(row.get("freshness_code")).isEqualTo("STALE");
        assertThat(row.get("plan_match_code")).isEqualTo("NOT_APPLICABLE");
        assertThat(text(row.get("unknown_reasons"))).contains("STATE_STALE");
        assertThat(row.get("assessment_id")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from assessment_result where plan_id=?", Long.class, planId)).isZero();
        assertThat(hooks.outcomes.get(0).alarmEligible()).isFalse();
    }

    @Test
    void recomputeChainsSupersedesOnBothEvaluationAndProjection() {
        spatial.hits = List.of(covers("PROHIBITED"));
        RunHandle first = runs.start(code, RunMode.ACTIVE, "MANUAL", null, null, now());
        EvaluationResult old = service.evaluate(subject(targetId), RunMode.ACTIVE, now(), first.runId());
        spatial.hits = List.of();
        RunHandle second = runs.start(code, RunMode.ACTIVE, "RECOMPUTE", null, null, now());
        EvaluationResult fresh = service.evaluate(subject(targetId), RunMode.ACTIVE, now(), second.runId(), old.evaluationId());
        assertThat(fresh.legalStatus()).isEqualTo(RuleContracts.LegalStatus.LEGAL);
        assertThat(jdbc.queryForObject("select supersedes_evaluation_id from rule_evaluation where evaluation_id=?", String.class, fresh.evaluationId())).isEqualTo(old.evaluationId());
        assertThat(jdbc.queryForObject("select supersedes_assessment_id from assessment_result where assessment_id=?", String.class, fresh.assessmentId())).isEqualTo(old.assessmentId());
        // 旧研判与旧投影原样保留：重算只追加。
        assertThat(jdbc.queryForObject("select legal_status from rule_evaluation where evaluation_id=?", String.class, old.evaluationId())).isEqualTo("ILLEGAL");
        assertThat(jdbc.queryForObject("select conclusion_code from assessment_result where assessment_id=?", String.class, old.assessmentId())).isEqualTo("ILLEGAL");
    }

    @Test
    void runBatchCountsSubjectsEvaluationsAlarmsAndErrors() {
        spatial.hits = List.of(covers("PROHIBITED"));
        RunHandle run = runs.start(code, RunMode.ACTIVE, "SCHEDULED", null, null, now());
        RunSummary summary = runs.runBatch(run, List.of(subject(targetId), subject("e1-missing-target")), now());
        assertThat(summary.subjectCount()).isEqualTo(2);
        assertThat(summary.evaluatedCount()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,error_summary,finished_at from rule_run where run_id=?", run.runId());
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(((Number) row.get("subject_count")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("evaluated_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("alarm_created_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("alarm_merged_count")).intValue()).isZero();
        assertThat(text(row.get("error_summary"))).contains("e1-missing-target");
        assertThat(row.get("finished_at")).isNotNull();
    }

    @Test
    void missingParameterIsADeploymentErrorAndWritesNothing() {
        jdbc.update("delete from rule_param where rule_code='C03' and param_key='fresh_seconds' and rule_set_version_id=(select active_version_id from rule_set where rule_set_code=?)", code);
        RunHandle run = runs.start(code, RunMode.ACTIVE, "SCHEDULED", null, null, now());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.evaluate(subject(targetId), RunMode.ACTIVE, now(), run.runId()));
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation where run_id=?", Long.class, run.runId())).isZero();
    }

    @Test
    void noActiveVersionMeansNoRunAtAll() {
        jdbc.update("update rule_set set active_version_id=null where rule_set_code=?", code);
        org.junit.jupiter.api.Assertions.assertThrows(com.uav.lowaltitude.platform.api.ApiException.class,
                () -> runs.start(code, RunMode.ACTIVE, "MANUAL", null, null, now()));
        assertThat(jdbc.queryForObject("select count(*) from rule_run where rule_set_id=(select rule_set_id from rule_set where rule_set_code=?)", Long.class, code)).isZero();
    }

    /** H2 的 JSON 列经 getObject 返回 UTF-8 字节数组，PostgreSQL 返回字符串；两者都归一为文本再解析。 */
    private static String text(Object stored) {
        return stored instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(stored);
    }

    private JsonNode array(Object stored) throws Exception {
        JsonNode node = json.readTree(text(stored));
        if (node.isTextual()) node = json.readTree(node.textValue());
        assertThat(node.isArray()).isTrue();
        return node;
    }

    private String target(String suffix, String sn, OffsetDateTime observed) {
        String id = "e1-target-" + suffix, link = "e1-link-" + suffix, track = "e1-track-" + suffix;
        Timestamp at = ts(observed.minusMinutes(5));
        jdbc.update("insert into target (target_id,target_no,object_type_code,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'UAV',?,?,?,'mock','seed-stage3-org','seed-stage3-district',?,?,0)", id, "E1-T-" + suffix, sn, at, ts(observed), at, ts(observed));
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,'seed-stage3-source',?,?,?)", link, id, "s-" + suffix, "x-" + suffix, at);
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,observed_at,received_at,created_at,updated_at,version) values (?,CAST('SRID=4326;POINT(118.025 37.025)' AS GEOMETRY),80,60,10,90,0.9,0.95,?,?,?,?,0)", id, ts(observed), ts(observed), at, ts(observed));
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) values (?,?,?,?,?,?)", track, id, link, "tr-" + suffix, at, at);
        for (int i = 0; i < 5; i++) {
            Timestamp seen = ts(observed.minusSeconds((4 - i) * 5L));
            jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at) values (?,?,?,?,?,CAST('SRID=4326;POINT(118.025 37.025)' AS GEOMETRY),80,60,?)", "e1-pt-" + suffix + "-" + i, track, i, seen, seen, seen);
        }
        return id;
    }

    /** 建 LEGALITY 规则集：v1 PUBLISHED+ACTIVE，成员 C01/C02-1…8/C03/C06，参数为契约 DEMO 目录（夜航窗口关闭以免测试时刻影响结论）。 */
    private String ruleSet(String suffix, Timestamp at) {
        String setId = "e1-rs-" + suffix, versionId = "e1-rsv-" + suffix, c03 = null;
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,version,created_at,updated_at) values (?,?,?,0,?,?)", setId, code, "引擎测试规则集", at, at);
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at) values (?,?,1,'PUBLISHED','DEMO',?,?,'mock',?,?)", versionId, setId, at, "v1", at, at);
        jdbc.update("update rule_set set active_version_id=? where rule_set_id=?", versionId, setId);
        String[] codes = {"C01", "C02-1", "C02-2", "C02-3", "C02-4", "C02-5", "C02-6", "C02-7", "C02-8", "C03", "C06"};
        int[] priorities = {100, 210, 220, 230, 240, 250, 260, 270, 280, 300, 400};
        for (int i = 0; i < codes.length; i++) {
            String ruleVersion = "e1-rv-" + codes[i] + "-" + suffix;
            jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at) values (?,?,?,?,?,?,?)",
                    ruleVersion, codes[i], ThreadLocalRandom.current().nextInt(100_000, 999_999), "ACTIVE", at, "mock", at);
            jdbc.update("insert into rule_set_member (rule_set_version_id,rule_version_id,priority,enabled) values (?,?,?,true)", versionId, ruleVersion, priorities[i]);
            if ("C03".equals(codes[i])) c03 = ruleVersion;
        }
        String[][] params = {
                {"C01", "time_window_min", "10", "INTEGER"}, {"C01", "corridor_tolerance_m", "20", "NUMBER"},
                {"C02-1", "kinds", "PROHIBITED,RESTRICTED", "LIST"}, {"C02-2", "kinds", "ALTITUDE_LIMIT", "LIST"},
                {"C02-3", "tolerance_m", "20", "NUMBER"}, {"C02-4", "grace_min", "10", "INTEGER"},
                {"C02-5", "timezone", "Asia/Shanghai", "STRING"}, {"C02-5", "night_from", "24", "INTEGER"}, {"C02-5", "night_to", "0", "INTEGER"},
                {"C02-6", "vlos_m", "500", "NUMBER"}, {"C02-8", "kinds", "TEMPORARY_CONTROL", "LIST"},
                {"C03", "fresh_seconds", "120", "INTEGER"}, {"C03", "track_points", "10", "INTEGER"}, {"C03", "conf_min", "0.75", "NUMBER"},
                {"C03", "min_points", "3", "INTEGER"}, {"C03", "gap_seconds", "30", "INTEGER"}, {"C03", "no_plan_status", "ILLEGAL", "STRING"},
                {"C03", "ignore_undetermined_rules", "C02-6", "LIST"},
                {"C03", "w.violation", "0.40", "NUMBER"}, {"C03", "w.plan_match", "0.25", "NUMBER"}, {"C03", "w.airspace", "0.15", "NUMBER"},
                {"C03", "w.track", "0.10", "NUMBER"}, {"C03", "w.confidence", "0.10", "NUMBER"},
                {"C03", "severity.INSIDE_RESTRICTED_AIRSPACE", "1.0", "NUMBER"}, {"C03", "severity.AIRSPACE_ALTITUDE_EXCEEDED", "0.9", "NUMBER"},
                {"C03", "severity.TEMPORARY_RESTRICTION_ACTIVE", "0.9", "NUMBER"}, {"C03", "severity.NO_AUTHORIZATION", "0.8", "NUMBER"},
                {"C03", "severity.ROUTE_DEVIATION", "0.6", "NUMBER"}, {"C03", "severity.PLAN_ALTITUDE_EXCEEDED", "0.5", "NUMBER"},
                {"C03", "severity.TIME_WINDOW_OVERRUN", "0.4", "NUMBER"}, {"C03", "severity.NIGHT_FLIGHT", "0.3", "NUMBER"},
                {"C03", "grade.high", "67", "NUMBER"}, {"C03", "grade.medium", "34", "NUMBER"},
                {"C06", "dedup_window_min", "5", "INTEGER"}, {"C06", "upgrade_window_min", "10", "INTEGER"}, {"C06", "auto_close_min", "15", "INTEGER"},
                {"C06", "severity_by_grade", "HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW", "LIST"}};
        for (String[] param : params) {
            jdbc.update("insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,param_status) values (?,?,?,?,?,?,'DEMO')",
                    UUID.randomUUID().toString(), versionId, param[0], param[1], param[2], param[3]);
        }
        return c03;
    }

    private static AirspaceHit covers(String kind) {
        return new AirspaceHit("e1-airspace", "e1-airspace-version", kind, "COVERS", null, null, null, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null, null);
    }
    private static Subject subject(String targetId) { return new Subject(SubjectKind.TARGET, targetId, null, null, null); }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private static Timestamp ts(OffsetDateTime value) { return Timestamp.from(Instant.from(value)); }
}
