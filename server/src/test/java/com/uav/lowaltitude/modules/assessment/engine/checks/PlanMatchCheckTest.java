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
    void flyingOutsideTheOwnPlanPeriodNamesTheOwnPlanNotAnUnrelatedOne() {
        // 评估时刻 02:00Z，本机计划 00:30–01:40Z 已结束 20 分钟；同区域还有一条别的编号、别的航线的计划在执行，按 ID 排序还排在前面。
        PlanFact own = plan("plan-own", "rv-own", "SN-1", AS_OF.minusMinutes(90), AS_OF.minusMinutes(20));
        PlanFact other = plan("plan-0other", "rv-other", "SN-9", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanMatch match = check.match(state("SN-1"), List.of(other, own), distances(Map.of("rv-own", 10.0, "rv-other", 900.0)), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(match.plan().planId()).isEqualTo("plan-own");
        assertThat(match.dimensions()).containsEntry("time_window", "MISMATCH").containsEntry("identity", "MATCH");
        assertThat(match.reasonCodes().get(0)).isEqualTo("TIME_WINDOW_MISMATCH");
        HitDetail detail = check.evaluate(context(match), params);
        assertThat(detail.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(detail.facts()).containsEntry("plan_id", "plan-own").containsEntry("match_reason", "TIME_WINDOW_MISMATCH");
        assertThat(detail.message()).startsWith("不在任务时段").doesNotContain("plan-0other").doesNotContain("TIME_WINDOW_MISMATCH");
        // 时段对上、但离开了本机计划航线：同样挂本机计划，说明不在航线走廊内。
        PlanFact current = plan("plan-own", "rv-own", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanMatch away = check.match(state("SN-1"), List.of(other, current), distances(Map.of("rv-own", 300.0, "rv-other", 10.0)), AS_OF, params);
        assertThat(away.plan().planId()).isEqualTo("plan-own");
        assertThat(away.reasonCodes().get(0)).isEqualTo("CORRIDOR_MISMATCH");
        assertThat(check.evaluate(context(away), params).message()).startsWith("不在任务航线走廊内");
    }

    @Test
    void ownPlanClosestToTheMomentIsReportedAmongSeveral() {
        PlanFact morning = plan("plan-a-morning", "rv-m", "SN-1", AS_OF.minusHours(5), AS_OF.minusHours(3));
        PlanFact evening = plan("plan-b-evening", "rv-e", "SN-1", AS_OF.plusHours(2), AS_OF.plusHours(4));
        PlanMatch between = check.match(state("SN-1"), List.of(morning, evening), distances(Map.of("rv-m", 10.0, "rv-e", 10.0)), AS_OF, params);
        assertThat(between.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(between.plan().planId()).isEqualTo("plan-b-evening");
        // 有一条本机计划时段对得上（只是偏离航线）时优先报它，而不是时段对不上的那条。
        PlanFact now = plan("plan-c-now", "rv-n", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanMatch off = check.match(state("SN-1"), List.of(morning, now), distances(Map.of("rv-m", 10.0, "rv-n", 500.0)), AS_OF, params);
        assertThat(off.plan().planId()).isEqualTo("plan-c-now");
        assertThat(off.dimensions()).containsEntry("time_window", "MATCH").containsEntry("corridor", "MISMATCH");
    }

    @Test
    void serialMismatchIsReportedWithoutAttachingSomeoneElsesPlan() {
        // SN-2 沿计划 P3（登记 SN-1）的航线飞；另有一条别的航线上的旧计划，按 ID 排序排在前面。都不是本机计划。
        PlanFact p3 = plan("plan-p3", "rv-p3", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanFact unrelated = plan("plan-0old", "rv-old", "SN-9", AS_OF.minusMinutes(40), AS_OF.plusMinutes(5));
        PlanMatch match = check.match(state("SN-2"), List.of(unrelated, p3), distances(Map.of("rv-p3", 10.0, "rv-old", 900.0)), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.NONE);
        // 别的编号的计划不挂到这次研判上：既不拿它比偏航/高度，也不让它的实际轨迹里出现这架无人机。
        assertThat(match.plan()).isNull();
        assertThat(match.reasonCodes().get(0)).isEqualTo("IDENTITY_MISMATCH");
        assertThat(match.dimensions()).containsEntry("identity", "MISMATCH").containsEntry("time_window", "MATCH").containsEntry("corridor", "MATCH");
        HitDetail detail = check.evaluate(context(match), params);
        assertThat(detail.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(detail.reasonCode()).isNull();
        assertThat(detail.facts()).containsEntry("match_reason", "IDENTITY_MISMATCH").containsEntry("plan_id", null);
        assertThat(detail.evidence()).isEmpty();
        assertThat(detail.message()).startsWith("编号不匹配").doesNotContain("plan-0old").doesNotContain("plan-p3");
        // 走廊也对不上时，首要原因仍是编号不匹配：这架无人机根本没有本机计划。
        PlanMatch far = check.match(state("SN-2"), List.of(unrelated), distances(Map.of("rv-old", 900.0)), AS_OF, params);
        assertThat(far.plan()).isNull();
        assertThat(far.reasonCodes()).startsWith("IDENTITY_MISMATCH").contains("CORRIDOR_MISMATCH");
    }

    @Test
    void targetWithoutSerialAwayFromEveryRouteIsNotTiedToAnyPlan() {
        PlanFact a = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanFact b = plan("plan-b", "rv-b", "SN-2", AS_OF.minusMinutes(10), AS_OF.plusMinutes(60));
        PlanMatch match = check.match(state(null), List.of(a, b), distances(Map.of("rv-a", 400.0, "rv-b", 800.0)), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(match.plan()).isNull();
        assertThat(match.reasonCodes().get(0)).isEqualTo("CORRIDOR_MISMATCH");
        assertThat(check.evaluate(context(match), params).message()).startsWith("不在任何候选飞行任务的航线走廊内");
    }

    @Test
    void twoEquallyRankedCandidatesAreAmbiguous() {
        PlanFact a = plan("plan-a", "rv-a", "SN-1", AS_OF.minusMinutes(30), AS_OF.plusMinutes(30));
        PlanFact b = plan("plan-b", "rv-b", "SN-1", AS_OF.minusMinutes(20), AS_OF.plusMinutes(40));
        PlanMatch match = check.match(state("SN-1"), List.of(a, b), routeVersionId -> new RouteDistance(routeVersionId, new BigDecimal("10"), new BigDecimal("50"), null), AS_OF, params);
        assertThat(match.code()).isEqualTo(PlanMatchCode.UNDETERMINED);
        assertThat(match.plan()).isNull();
        assertThat(match.reasonCodes()).contains("PLAN_AMBIGUOUS");
        // 页面上的说明用业务话，不出现原因码。
        assertThat(check.evaluate(context(match), params).message())
                .startsWith("任务匹配不可判定：附近有多个飞行任务都可能对应这架无人机").doesNotContain("PLAN_AMBIGUOUS");
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
        assertThat(check.evaluate(context(width), params).message()).startsWith("任务匹配不可判定：候选飞行任务的航线走廊无法确认");
        assertThat(check.evaluate(context(position), params).message()).startsWith("任务匹配不可判定：目标位置未知");
        assertThat(check.evaluate(context(time), params).message()).startsWith("任务匹配不可判定：候选飞行任务缺少起止时间");
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

    /** 新-28：完全没有报备任务、离地 120 米以下的普通区域飞行，C01 照实记“对不上任务”，说明里写清按规定无需申请，页面据此解释合法结论。 */
    @Test
    void noTaskLowFlightIsExplainedAsNotNeedingAnApplication() {
        PlanMatch none = check.match(state("SN-1"), List.of(), distance("rv-a", 10, 50), AS_OF, params);
        HitDetail low = check.evaluate(context(none, height("60.4")), params);
        assertThat(low.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(low.reasonCode()).isNull();
        assertThat(low.facts()).containsEntry("match_reason", "NO_PLAN_CANDIDATE").containsEntry("no_plan_exempt", true)
                .containsEntry("height_agl_m", new BigDecimal("60.4"));
        assertThat(low.message()).isEqualTo("没有报备任务；离地约 60 米，不超过 120 米，不在禁飞区、管制区、限高区、临时管控区内，按规定无需申请；"
                + "参数为 DEMO 演示值，尚未确认");
        // 超过 120 米、设备没报离地高度：照旧，不带标记。
        for (TargetState state : java.util.Arrays.asList(height("120.5"), height(null))) {
            HitDetail detail = check.evaluate(context(none, state), params);
            assertThat(detail.facts()).doesNotContainKeys("no_plan_exempt", "height_agl_m");
            assertThat(detail.message()).doesNotContain("无需申请");
        }
        // 有本机任务、只是飞出了时段：照旧按任务说明，不当作没有报备任务。
        PlanFact own = plan("plan-own", "rv-own", "SN-1", AS_OF.minusMinutes(90), AS_OF.minusMinutes(20));
        PlanMatch outside = check.match(state("SN-1"), List.of(own), distance("rv-own", 10, 50), AS_OF, params);
        HitDetail late = check.evaluate(context(outside, height("60")), params);
        assertThat(late.facts()).doesNotContainKey("no_plan_exempt");
        assertThat(late.message()).startsWith("不在任务时段");
    }

    private static EvaluationContext context(PlanMatch match) {
        return context(match, state("SN-1"));
    }

    private static EvaluationContext context(PlanMatch match, TargetState state) {
        return new EvaluationContext(new Subject(SubjectKind.TARGET, "t1", "org", "district", "mock"), state,
                new TrackQuality(5, 5L, false), match, List.of(), AS_OF, Freshness.REPLAY, RunMode.ACTIVE, "mock");
    }

    /** 同 state("SN-1")，另带设备报的离地高度（null 表示没报）。 */
    private static TargetState height(String agl) {
        return new TargetState("t1", "tr1", "SN-1", new BigDecimal("118.5"), new BigDecimal("37.4"), new BigDecimal("60"),
                agl == null ? null : new BigDecimal(agl), new BigDecimal("8"), new BigDecimal("90"), new BigDecimal("0.9"), AS_OF, AS_OF);
    }

    private static TargetState state(String sn) {
        return new TargetState("t1", "tr1", sn, new BigDecimal("118.5"), new BigDecimal("37.4"), new BigDecimal("60"), null,
                new BigDecimal("8"), new BigDecimal("90"), new BigDecimal("0.9"), AS_OF, AS_OF);
    }

    private static PlanFact plan(String planId, String routeVersionId, String sn, OffsetDateTime start, OffsetDateTime end) {
        return new PlanFact(planId, routeVersionId, sn, start, end, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"), "AMSL", "org", "district");
    }

    /** 各航线到目标的距离（米），走廊半宽统一 50 m；未列出的航线距离未知。 */
    private static PlanMatchCheck.RouteDistanceSource distances(Map<String, Double> byRoute) {
        return id -> byRoute.containsKey(id)
                ? new RouteDistance(id, BigDecimal.valueOf(byRoute.get(id)), BigDecimal.valueOf(50), null)
                : new RouteDistance(id, null, null, "ROUTE_UNKNOWN");
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
