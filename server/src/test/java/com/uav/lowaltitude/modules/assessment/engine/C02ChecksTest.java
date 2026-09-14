package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Freshness;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ResultCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TrackQuality;
import com.uav.lowaltitude.modules.assessment.engine.checks.AirspaceAltitudeCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.NightFlightCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.PlanAltitudeCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.RestrictedAirspaceCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.RouteDeviationCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.TemporaryRestrictionCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.TimeWindowCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.VisualLineOfSightCheck;

/** C02-1…C02-8 单条检查：桩 SpatialFactPort 与固定 RuleParams，边界接触与基准缺失一律未知，绝不推断为命中或合法。 */
class C02ChecksTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.of(2026, 9, 5, 4, 0, 0, 0, ZoneOffset.UTC);
    private final TestRuleParams params = TestRuleParams.demoCatalog();

    @Test
    void restrictedAirspaceTreatsTouchesAsUnknownAndCoversAsFail() {
        AirspaceHit touches = hit("PROHIBITED", "TOUCHES", null, null, null, null);
        HitDetail unknown = new RestrictedAirspaceCheck().evaluate(context(state(), full(), List.of(touches), null), params);
        assertThat(unknown.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(unknown.reasonCode()).isEqualTo("BOUNDARY_POLICY_UNKNOWN");
        assertThat(unknown.ruleCode()).isEqualTo("C02-1");
        AirspaceHit covers = hit("RESTRICTED", "COVERS", null, null, null, null);
        HitDetail fail = new RestrictedAirspaceCheck().evaluate(context(state(), full(), List.of(covers), null), params);
        assertThat(fail.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(fail.reasonCode()).isEqualTo("INSIDE_RESTRICTED_AIRSPACE");
        assertThat(fail.evidence()).anyMatch(ref -> "airspace_version".equals(ref.kind()) && "av-RESTRICTED".equals(ref.id()));
        assertThat(fail.params()).anyMatch(ref -> "kinds".equals(ref.key()) && "DEMO".equals(ref.status()));
        assertThat(fail.message()).contains("演示");
        // 同基准高度带外：水平覆盖但高度不在带内，不算进入。
        AirspaceHit above = hit("PROHIBITED", "COVERS", "10", "60", "AMSL", null);
        assertThat(new RestrictedAirspaceCheck().evaluate(context(state(), full(), List.of(above), null), params).resultCode()).isEqualTo(ResultCode.PASS);
        // 类型不在参数列表内即与本规则无关。
        AirspaceHit other = hit("ALTITUDE_LIMIT", "COVERS", null, null, null, null);
        assertThat(new RestrictedAirspaceCheck().evaluate(context(state(), full(), List.of(other), null), params).resultCode()).isEqualTo(ResultCode.PASS);
    }

    @Test
    void airspaceChecksReportMissingPositionDatumAndAmbiguousVersion() {
        TargetState noPosition = new TargetState("t-1", "tr-1", "SN-1", null, null, new BigDecimal("80"), null, null, null, new BigDecimal("0.9"), AS_OF, AS_OF);
        HitDetail position = new RestrictedAirspaceCheck().evaluate(context(noPosition, full(), List.of(), null), params);
        assertThat(position.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(position.reasonCode()).isEqualTo("POSITION_UNKNOWN");
        // 空域带 AMSL、目标只有 AGL：两种基准不互比，缺换算依据即未知。
        AirspaceHit amsl = hit("PROHIBITED", "COVERS", "10", "100", "AMSL", null);
        TargetState aglOnly = new TargetState("t-1", "tr-1", "SN-1", new BigDecimal("118"), new BigDecimal("37"), null, new BigDecimal("50"), null, null, new BigDecimal("0.9"), AS_OF, AS_OF);
        HitDetail datum = new RestrictedAirspaceCheck().evaluate(context(aglOnly, full(), List.of(amsl), null), params);
        assertThat(datum.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(datum.reasonCode()).isEqualTo("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        AirspaceHit ambiguous = new AirspaceHit(null, null, null, "UNKNOWN", null, null, null, null, null, "VERSION_AMBIGUOUS");
        for (RuleContracts.RuleCheck check : List.of(new RestrictedAirspaceCheck(), new AirspaceAltitudeCheck(), new TemporaryRestrictionCheck())) {
            HitDetail detail = check.evaluate(context(state(), full(), List.of(ambiguous), null), params);
            assertThat(detail.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
            assertThat(detail.reasonCode()).isEqualTo("VERSION_AMBIGUOUS");
        }
    }

    @Test
    void airspaceAltitudeComparesOnlySameDatum() {
        AirspaceHit limit = hit("ALTITUDE_LIMIT", "COVERS", "0", "60", "AMSL", null);
        HitDetail fail = new AirspaceAltitudeCheck().evaluate(context(state(), full(), List.of(limit), null), params);
        assertThat(fail.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(fail.reasonCode()).isEqualTo("AIRSPACE_ALTITUDE_EXCEEDED");
        assertThat(fail.ruleCode()).isEqualTo("C02-2");
        AirspaceHit agl = hit("ALTITUDE_LIMIT", "COVERS", "0", "30", "AGL", null);
        TargetState amslOnly = new TargetState("t-1", "tr-1", "SN-1", new BigDecimal("118"), new BigDecimal("37"), new BigDecimal("80"), null, null, null, new BigDecimal("0.9"), AS_OF, AS_OF);
        HitDetail unknown = new AirspaceAltitudeCheck().evaluate(context(amslOnly, full(), List.of(agl), null), params);
        assertThat(unknown.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(unknown.reasonCode()).isEqualTo("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        AirspaceHit noBand = hit("ALTITUDE_LIMIT", "COVERS", null, null, null, null);
        assertThat(new AirspaceAltitudeCheck().evaluate(context(state(), full(), List.of(noBand), null), params).reasonCode()).isEqualTo("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        AirspaceHit touches = hit("ALTITUDE_LIMIT", "TOUCHES", "0", "60", "AMSL", null);
        assertThat(new AirspaceAltitudeCheck().evaluate(context(state(), full(), List.of(touches), null), params).reasonCode()).isEqualTo("BOUNDARY_POLICY_UNKNOWN");
    }

    @Test
    void routeDeviationUsesSpatialPortAndIsNotApplicableWithoutPlan() {
        RouteDeviationCheck check = new RouteDeviationCheck(port(new RouteDistance("rv-1", new BigDecimal("120"), new BigDecimal("50"), null)));
        HitDetail fail = check.evaluate(context(state(), full(), List.of(), null), params);
        assertThat(fail.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(fail.reasonCode()).isEqualTo("ROUTE_DEVIATION");
        assertThat(fail.ruleCode()).isEqualTo("C02-3");
        RouteDeviationCheck within = new RouteDeviationCheck(port(new RouteDistance("rv-1", new BigDecimal("65"), new BigDecimal("50"), null)));
        assertThat(within.evaluate(context(state(), full(), List.of(), null), params).resultCode()).isEqualTo(ResultCode.PASS);
        RouteDeviationCheck unknown = new RouteDeviationCheck(port(new RouteDistance("rv-1", null, null, "CORRIDOR_WIDTH_UNKNOWN")));
        HitDetail detail = unknown.evaluate(context(state(), full(), List.of(), null), params);
        assertThat(detail.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(detail.reasonCode()).isEqualTo("CORRIDOR_WIDTH_UNKNOWN");
        HitDetail none = check.evaluate(context(state(), noPlan(), List.of(), null), params);
        assertThat(none.resultCode()).isEqualTo(ResultCode.NOT_APPLICABLE);
    }

    @Test
    void timeWindowAppliesGraceAndUnknownPlanTimes() {
        PlanFact plan = plan(AS_OF.minusHours(2), AS_OF.minusMinutes(20));
        HitDetail overrun = new TimeWindowCheck().evaluate(context(state(), match(plan), List.of(), null), params);
        assertThat(overrun.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(overrun.reasonCode()).isEqualTo("TIME_WINDOW_OVERRUN");
        assertThat(overrun.ruleCode()).isEqualTo("C02-4");
        PlanFact grace = plan(AS_OF.minusHours(2), AS_OF.minusMinutes(5));
        assertThat(new TimeWindowCheck().evaluate(context(state(), match(grace), List.of(), null), params).resultCode()).isEqualTo(ResultCode.PASS);
        PlanFact early = plan(AS_OF.plusMinutes(30), AS_OF.plusHours(2));
        assertThat(new TimeWindowCheck().evaluate(context(state(), match(early), List.of(), null), params).resultCode()).isEqualTo(ResultCode.FAIL);
        HitDetail unknown = new TimeWindowCheck().evaluate(context(state(), match(plan(null, null)), List.of(), null), params);
        assertThat(unknown.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(unknown.reasonCode()).isEqualTo("PLAN_TIME_UNKNOWN");
        assertThat(new TimeWindowCheck().evaluate(context(state(), noPlan(), List.of(), null), params).resultCode()).isEqualTo(ResultCode.NOT_APPLICABLE);
    }

    @Test
    void nightFlightUsesConfiguredTimezoneAndHalfOpenHours() {
        // 04:00Z = 12:00 上海，白天；13:00Z = 21:00 上海，夜航；21:30Z = 05:30 上海仍在夜航；22:30Z = 06:30 上海不算。
        NightFlightCheck check = new NightFlightCheck();
        assertThat(check.evaluate(context(state(), full(), List.of(), AS_OF), params).resultCode()).isEqualTo(ResultCode.PASS);
        HitDetail night = check.evaluate(context(state(), full(), List.of(), AS_OF.withHour(13)), params);
        assertThat(night.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(night.reasonCode()).isEqualTo("NIGHT_FLIGHT");
        assertThat(night.ruleCode()).isEqualTo("C02-5");
        assertThat(night.facts()).containsEntry("local_hour", 21);
        assertThat(check.evaluate(context(state(), full(), List.of(), AS_OF.withHour(21).withMinute(30)), params).resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(check.evaluate(context(state(), full(), List.of(), AS_OF.withHour(22).withMinute(30)), params).resultCode()).isEqualTo(ResultCode.PASS);
        // 无计划也照样按时刻判定：夜航是行为事实，不依赖计划。
        assertThat(check.evaluate(context(state(), noPlan(), List.of(), AS_OF.withHour(13)), params).resultCode()).isEqualTo(ResultCode.FAIL);
    }

    /**
     * C02-6 三态：飞手位置接入后按目标与飞手的大圆距离判定，缺飞手位置仍是未知。
     * 阈值 500 m：飞手在 (118.02, 37.02)，目标北移 0.01° 约 1111 m（超），北移 0.001° 约 111 m（不超）。
     */
    @Test
    void visualLineOfSightJudgesDistanceOnceThePilotPositionIsKnown() {
        HitDetail unknown = new VisualLineOfSightCheck().evaluate(context(state(), full(), List.of(), null), params);
        assertThat(unknown.ruleCode()).isEqualTo("C02-6");
        assertThat(unknown.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(unknown.reasonCode()).isEqualTo("PILOT_POSITION_UNAVAILABLE");
        assertThat(unknown.params()).anyMatch(ref -> "vlos_m".equals(ref.key()));
        assertThat(unknown.facts()).doesNotContainKey("distance_m");

        HitDetail far = new VisualLineOfSightCheck().evaluate(context(withPilot("118.02", "37.03"), full(), List.of(), null), params);
        assertThat(far.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(far.reasonCode()).isEqualTo("BVLOS_EXCEEDED");
        assertThat(((BigDecimal) far.facts().get("distance_m")).doubleValue()).isBetween(1000.0, 1200.0);
        assertThat(far.facts()).containsEntry("pilot_location", Map.of("longitude", new BigDecimal("118.02"), "latitude", new BigDecimal("37.03")));
        // 决策 8.5-28：飞手位置可能保留自若干帧之前，判定依据里必须带上它的观测时刻，读的人才知道有多旧。
        assertThat(far.facts()).containsEntry("pilot_observed_at", AS_OF.minusMinutes(3));
        assertThat(far.message()).contains("演示");

        HitDetail near = new VisualLineOfSightCheck().evaluate(context(withPilot("118.02", "37.021"), full(), List.of(), null), params);
        assertThat(near.resultCode()).isEqualTo(ResultCode.PASS);
        assertThat(near.reasonCode()).isNull();
        assertThat(((BigDecimal) near.facts().get("distance_m")).doubleValue()).isLessThan(500.0);

        // 有飞手位置但目标位置缺失：不拿单边坐标硬算，也不当成"没接入"。
        TargetState noTarget = new TargetState("t-1", "tr-1", "SN-1", null, null, null, null, null, null, new BigDecimal("0.9"), AS_OF, AS_OF,
                new BigDecimal("118.02"), new BigDecimal("37.02"));
        HitDetail missing = new VisualLineOfSightCheck().evaluate(context(noTarget, full(), List.of(), null), params);
        assertThat(missing.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(missing.reasonCode()).isEqualTo("POSITION_UNKNOWN");
    }

    @Test
    void planAltitudeComparesSameDatumBandOnly() {
        // 计划带 AMSL 10–60，目标 AMSL 80：越界。
        PlanFact plan = new PlanFact("p-1", "rv-1", "SN-1", AS_OF.minusMinutes(10), AS_OF.plusMinutes(50), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("60"), "AMSL", "org-1", "district-1");
        HitDetail fail = new PlanAltitudeCheck().evaluate(context(state(), match(plan), List.of(), null), params);
        assertThat(fail.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(fail.reasonCode()).isEqualTo("PLAN_ALTITUDE_EXCEEDED");
        assertThat(fail.ruleCode()).isEqualTo("C02-7");
        PlanFact below = new PlanFact("p-1", "rv-1", "SN-1", AS_OF.minusMinutes(10), AS_OF.plusMinutes(50), new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("120"), "AMSL", "org-1", "district-1");
        assertThat(new PlanAltitudeCheck().evaluate(context(state(), match(below), List.of(), null), params).resultCode()).isEqualTo(ResultCode.FAIL);
        PlanFact within = new PlanFact("p-1", "rv-1", "SN-1", AS_OF.minusMinutes(10), AS_OF.plusMinutes(50), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"), "AMSL", "org-1", "district-1");
        assertThat(new PlanAltitudeCheck().evaluate(context(state(), match(within), List.of(), null), params).resultCode()).isEqualTo(ResultCode.PASS);
        // 计划带 AGL，目标只有 AMSL：不互比。
        PlanFact agl = new PlanFact("p-1", "rv-1", "SN-1", AS_OF.minusMinutes(10), AS_OF.plusMinutes(50), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("30"), "AGL", "org-1", "district-1");
        TargetState amslOnly = new TargetState("t-1", "tr-1", "SN-1", new BigDecimal("118"), new BigDecimal("37"), new BigDecimal("80"), null, null, null, new BigDecimal("0.9"), AS_OF, AS_OF);
        HitDetail datum = new PlanAltitudeCheck().evaluate(context(amslOnly, match(agl), List.of(), null), params);
        assertThat(datum.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(datum.reasonCode()).isEqualTo("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        PlanFact noBand = new PlanFact("p-1", "rv-1", "SN-1", AS_OF.minusMinutes(10), AS_OF.plusMinutes(50), new BigDecimal("100"), null, null, null, "org-1", "district-1");
        assertThat(new PlanAltitudeCheck().evaluate(context(state(), match(noBand), List.of(), null), params).reasonCode()).isEqualTo("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        assertThat(new PlanAltitudeCheck().evaluate(context(state(), noPlan(), List.of(), null), params).resultCode()).isEqualTo(ResultCode.NOT_APPLICABLE);
    }

    @Test
    void temporaryRestrictionRequiresEffectiveWindowAndCoverage() {
        AirspaceHit active = new AirspaceHit("a-T", "av-T", "TEMPORARY_CONTROL", "COVERS", null, null, null, AS_OF.minusHours(1), AS_OF.plusHours(1), null);
        HitDetail fail = new TemporaryRestrictionCheck().evaluate(context(state(), full(), List.of(active), null), params);
        assertThat(fail.resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(fail.reasonCode()).isEqualTo("TEMPORARY_RESTRICTION_ACTIVE");
        assertThat(fail.ruleCode()).isEqualTo("C02-8");
        AirspaceHit expired = new AirspaceHit("a-T", "av-T", "TEMPORARY_CONTROL", "COVERS", null, null, null, AS_OF.minusHours(3), AS_OF.minusHours(1), null);
        assertThat(new TemporaryRestrictionCheck().evaluate(context(state(), full(), List.of(expired), null), params).resultCode()).isEqualTo(ResultCode.PASS);
        AirspaceHit touches = new AirspaceHit("a-T", "av-T", "TEMPORARY_CONTROL", "TOUCHES", null, null, null, AS_OF.minusHours(1), null, null);
        assertThat(new TemporaryRestrictionCheck().evaluate(context(state(), full(), List.of(touches), null), params).reasonCode()).isEqualTo("BOUNDARY_POLICY_UNKNOWN");
    }

    @Test
    void missingParameterFailsLoudly() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new RestrictedAirspaceCheck().evaluate(context(state(), full(), List.of(), null), TestRuleParams.empty()));
    }

    private static SpatialFactPort port(RouteDistance distance) {
        return new SpatialFactPort() {
            @Override public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) { return List.of(); }
            @Override public RouteDistance distanceToRoute(TargetState state, String routeVersionId) { return distance; }
            @Override public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf) { return false; }
        };
    }

    private static EvaluationContext context(TargetState state, PlanMatch planMatch, List<AirspaceHit> hits, OffsetDateTime asOf) {
        return new EvaluationContext(new Subject(SubjectKind.TARGET, "t-1", "org-1", "district-1", "mock"), state, new TrackQuality(10, 5L, false), planMatch, hits,
                asOf == null ? AS_OF : asOf, Freshness.FRESH, RunMode.ACTIVE, "mock");
    }
    private static TargetState state() {
        return new TargetState("t-1", "tr-1", "SN-1", new BigDecimal("118.02"), new BigDecimal("37.02"), new BigDecimal("80.00"), new BigDecimal("60.00"),
                null, null, new BigDecimal("0.9"), AS_OF, AS_OF);
    }
    /** 目标位置同 state()，另带飞手位置：C02-6 的唯一新增输入。 */
    private static TargetState withPilot(String pilotLon, String pilotLat) {
        return new TargetState("t-1", "tr-1", "SN-1", new BigDecimal("118.02"), new BigDecimal("37.02"), new BigDecimal("80.00"), new BigDecimal("60.00"),
                null, null, new BigDecimal("0.9"), AS_OF, AS_OF, new BigDecimal(pilotLon), new BigDecimal(pilotLat), AS_OF.minusMinutes(3));
    }
    private static AirspaceHit hit(String kind, String relation, String min, String max, String datum, String unknown) {
        return new AirspaceHit("a-" + kind, "av-" + kind, kind, relation, min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max), datum,
                AS_OF.minusDays(1), null, unknown);
    }
    private static PlanFact plan(OffsetDateTime start, OffsetDateTime end) {
        return new PlanFact("p-1", "rv-1", "SN-1", start, end, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"), "AMSL", "org-1", "district-1");
    }
    private static PlanMatch match(PlanFact plan) { return new PlanMatch(PlanMatchCode.FULL, plan, Map.of(), List.of()); }
    private static PlanMatch full() { return match(plan(AS_OF.minusMinutes(10), AS_OF.plusMinutes(50))); }
    private static PlanMatch noPlan() { return new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE")); }
}
