package com.uav.lowaltitude.modules.assessment.engine.checks;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Freshness;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ResultCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TrackQuality;

/** C01 纯 Java 决策表：五维结果与等级只由事实与参数决定，不访问数据库。 */
class PlanMatchCheckTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.of(2026, 9, 5, 2, 0, 0, 0, ZoneOffset.UTC);
    private final PlanMatchCheck check = new PlanMatchCheck();
    private final RuleParams params = new StubParams();

    @Test
    void identityTimeAndCorridorAllMatchingGiveFull() {
        PlanFact plan = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanMatch match = check.match(state("SN-1"), List.of(plan), distance("rv-a", 40, 50), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.FULL);
        assertThat(match.plan().planId()).isEqualTo("plan-a");
        assertThat(match.dimensions()).containsEntry("time_window", "MATCH").containsEntry("corridor", "MATCH")
                .containsEntry("identity", "MATCH").containsEntry("takeoff_point", "UNDETERMINED").containsEntry("pilot_unit", "UNDETERMINED");
        // 起降点与飞手/单位尚未接入：恒未知但必须带原因码，不能伪装成 MATCH。
        assertThat(match.reasonCodes()).containsExactlyInAnyOrder("TAKEOFF_POINT_UNAVAILABLE", "PILOT_UNIT_UNAVAILABLE");
    }

    @Test
    void missingTargetSerialGivesPartialWithIdentityClueMissing() {
        PlanFact plan = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanMatch match = check.match(state(null), List.of(plan), distance("rv-a", 60, 50), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.PARTIAL);
        assertThat(match.plan().planId()).isEqualTo("plan-a");
        assertThat(match.dimensions()).containsEntry("identity", "UNDETERMINED");
        assertThat(match.reasonCodes()).contains("IDENTITY_CLUE_MISSING");
    }

    @Test
    void anyDecidableMismatchGivesNoneAndNoCandidateGivesNoneWithReason() {
        PlanFact plan = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        // 半宽 50 + 容差 20 = 70；距离 71 即走廊不匹配。
        PlanMatch corridor = check.match(state("SN-1"), List.of(plan), distance("rv-a", 71, 50), AS_OF, params);
        assertThat(corridor.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(corridor.dimensions()).containsEntry("corridor", "MISMATCH");
        assertThat(corridor.reasonCodes()).contains("CORRIDOR_MISMATCH");
        PlanMatch identity = check.match(state("SN-2"), List.of(plan), distance("rv-a", 10, 50), AS_OF, params);
        assertThat(identity.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(identity.reasonCodes()).contains("IDENTITY_MISMATCH");
        PlanMatch none = check.match(state("SN-1"), List.of(), distance("rv-a", 10, 50), AS_OF, params);
        assertThat(none.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(none.plan()).isNull();
        assertThat(none.reasonCodes()).containsExactly("NO_PLAN_CANDIDATE");
    }

    @Test
    void plansOutsideTimeWindowWithDifferentSerialAreNotCandidates() {
        // time_window_min=10：结束 11 分钟前且 sn 不同的计划不进入候选，因此没有候选而不是身份不匹配。
        PlanFact stale = plan("plan-old", "rv-old", "SN-9", AS_OF.minusMinutes(90), AS_OF.minusMinutes(11));
        PlanMatch match = check.match(state("SN-1"), List.of(stale), distance("rv-old", 10, 50), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(match.reasonCodes()).containsExactly("NO_PLAN_CANDIDATE");
        // 同 sn 即使时间窗不覆盖也是候选，此时时间维度 MISMATCH → NONE。
        PlanFact sameSn = plan("plan-sn", "rv-sn", "SN-1", AS_OF.minusMinutes(90), AS_OF.minusMinutes(11));
        PlanMatch bySn = check.match(state("SN-1"), List.of(sameSn), distance("rv-sn", 10, 50), AS_OF, params);
        assertThat(bySn.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(bySn.dimensions()).containsEntry("time_window", "MISMATCH");
        assertThat(bySn.reasonCodes()).contains("TIME_WINDOW_MISMATCH");
    }

    @Test
    void twoEquallyRankedCandidatesAreAmbiguous() {
        PlanFact a = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanFact b = plan("plan-b", "rv-b", "SN-1", AS_OF.minusMinutes(20), AS_OF.plusMinutes(40));
        PlanMatch match = check.match(state("SN-1"), List.of(a, b), routeVersionId -> new RouteDistance(routeVersionId, new BigDecimal("10"), new BigDecimal("50"), null), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.UNDETERMINED);
        assertThat(match.plan()).isNull();
        assertThat(match.reasonCodes()).contains("PLAN_AMBIGUOUS");
    }

    @Test
    void unknownCorridorWidthOrPositionGivesUndeterminedNotMatch() {
        PlanFact plan = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanMatch width = check.match(state("SN-1"), List.of(plan),
                routeVersionId -> new RouteDistance(routeVersionId, new BigDecimal("10"), null, "CORRIDOR_WIDTH_UNKNOWN"), AS_OF, params);
        assertThat(width.code()).isEqualTo(PlanMatchCode.UNDETERMINED);
        assertThat(width.dimensions()).containsEntry("corridor", "UNDETERMINED");
        assertThat(width.reasonCodes()).contains("CORRIDOR_WIDTH_UNKNOWN");
        TargetState noPosition = new TargetState("t1", "tr1", "SN-1", null, null, new BigDecimal("60"), null, null, null, new BigDecimal("0.9"), AS_OF, AS_OF);
        PlanMatch position = check.match(noPosition, List.of(plan), distance("rv-a", 10, 50), AS_OF, params);
        assertThat(position.code()).isEqualTo(PlanMatchCode.UNDETERMINED);
        assertThat(position.reasonCodes()).contains("POSITION_UNKNOWN");
        PlanFact noTime = plan("plan-t", "rv-t", "SN-1", null, null);
        PlanMatch time = check.match(state("SN-1"), List.of(noTime), distance("rv-t", 10, 50), AS_OF, params);
        assertThat(time.code()).isEqualTo(PlanMatchCode.UNDETERMINED);
        assertThat(time.reasonCodes()).contains("PLAN_TIME_UNKNOWN");
    }

    @Test
    void hitDetailOnlyCarriesSafeFactsAndDemoLabelledParams() {
        PlanFact plan = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanMatch match = check.match(state("SN-1"), List.of(plan), distance("rv-a", 40, 50), AS_OF, params);
        HitDetail detail = check.evaluate(context(match), params);
        assertThat(check.ruleCode()).isEqualTo("C01");
        assertThat(detail.ruleCode()).isEqualTo("C01");
        assertThat(detail.resultCode()).isEqualTo(ResultCode.PASS);
        assertThat(detail.facts().keySet()).containsExactlyInAnyOrder("plan_match_code", "match_reason", "plan_id", "route_version_id", "candidate_count", "dimensions");
        assertThat(detail.facts()).doesNotContainKeys("uav_sn", "longitude", "latitude");
        assertThat(detail.params()).extracting("key").containsExactlyInAnyOrder("time_window_min", "corridor_tolerance_m");
        assertThat(detail.params()).allMatch(param -> "DEMO".equals(param.status()));
        assertThat(detail.evidence()).extracting("kind").contains("flight_plan", "route_version");
        assertThat(detail.message()).contains("DEMO");
        PlanMatch none = check.match(state("SN-1"), List.of(), distance("rv-a", 10, 50), AS_OF, params);
        HitDetail failed = check.evaluate(context(none), params);
        assertThat(failed.resultCode()).isEqualTo(ResultCode.FAIL);
        // FAIL 行不带可评分的违规原因码（C03 自行给出 NO_AUTHORIZATION）；匹配原因放在 facts.match_reason。
        assertThat(failed.reasonCode()).isNull();
        assertThat(failed.facts()).containsEntry("match_reason", "NO_PLAN_CANDIDATE");
        HitDetail notApplicable = check.evaluate(context(PlanMatch.notApplicable()), params);
        assertThat(notApplicable.resultCode()).isEqualTo(ResultCode.NOT_APPLICABLE);
    }

    private static EvaluationContext context(PlanMatch match) {
        return new EvaluationContext(new Subject(SubjectKind.TARGET, "t1", "org", "district", "mock"), state("SN-1"),
                new TrackQuality(5, 5L, false), match, List.of(), AS_OF, Freshness.REPLAY, RunMode.ACTIVE, "mock");
    }

    private static TargetState state(String sn) {
        return new TargetState("t1", "tr1", sn, new BigDecimal("118.5"), new BigDecimal("37.4"), new BigDecimal("60"), null,
                new BigDecimal("8"), new BigDecimal("90"), new BigDecimal("0.9"), AS_OF, AS_OF);
    }

    private static PlanFact plan(String planId, String routeVersionId, String sn, OffsetDateTime start, OffsetDateTime end) {
        return new PlanFact(planId, routeVersionId, sn, start, end, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"), "AMSL", "org", "district");
    }

    private static PlanMatchCheck.RouteDistanceSource distance(String routeVersionId, double distanceM, double halfWidthM) {
        return id -> id.equals(routeVersionId)
                ? new RouteDistance(id, BigDecimal.valueOf(distanceM), BigDecimal.valueOf(halfWidthM), null)
                : new RouteDistance(id, null, null, "ROUTE_UNKNOWN");
    }

    private static final class StubParams implements RuleParams {
        private final Map<String, String> values = Map.of("C01.time_window_min", "10", "C01.corridor_tolerance_m", "20");
        @Override public String ruleSetVersionId() { return "rsv-test"; }
        @Override public String paramStatus(String ruleCode, String key) { return "DEMO"; }
        @Override public BigDecimal number(String ruleCode, String key) { return new BigDecimal(required(ruleCode, key)); }
        @Override public int integer(String ruleCode, String key) { return Integer.parseInt(required(ruleCode, key)); }
        @Override public boolean bool(String ruleCode, String key) { return Boolean.parseBoolean(required(ruleCode, key)); }
        @Override public String string(String ruleCode, String key) { return required(ruleCode, key); }
        @Override public List<String> list(String ruleCode, String key) { return List.of(required(ruleCode, key).split(",")); }
        private String required(String ruleCode, String key) {
            String value = values.get(ruleCode + "." + key);
            if (value == null) throw new IllegalStateException("missing " + ruleCode + "." + key);
            return value;
        }
    }
}
