package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.assessment.engine.C03Decision.Decision;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Freshness;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ResultCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TrackQuality;
import com.uav.lowaltitude.modules.assessment.engine.checks.RestrictedAirspaceCheck;

/** C03 四态决策纯 Java 测试：契约六步短路顺序每分支各一例，阈值全部来自 RuleParams。 */
class C03DecisionTest {
    private static final OffsetDateTime AS_OF = OffsetDateTime.of(2026, 9, 5, 4, 0, 0, 0, ZoneOffset.UTC);
    private final C03Decision decision = new C03Decision();
    private final TestRuleParams params = TestRuleParams.demoCatalog();

    @Test
    void staleOrMissingStateIsNotApplicableBeforeAnyRuleIsConsulted() {
        Decision stale = decision.decide(context(Freshness.STALE, state("0.99"), goodTrack(), full()), List.of(fail("C02-1", "INSIDE_RESTRICTED_AIRSPACE")), params);
        assertThat(stale.status()).isEqualTo(LegalStatus.NOT_APPLICABLE);
        assertThat(stale.reasonCode()).isEqualTo("STATE_STALE");
        assertThat(stale.violationReasons()).isEmpty();
        assertThat(stale.score()).isNull();
        Decision none = decision.decide(context(Freshness.NO_STATE, null, new TrackQuality(0, null, false), full()), List.of(), params);
        assertThat(none.status()).isEqualTo(LegalStatus.NOT_APPLICABLE);
        assertThat(none.reasonCode()).isEqualTo("NO_STATE");
    }

    @Test
    void qualityGateReturnsUndeterminedWithSpecificReason() {
        Decision low = decision.decide(context(Freshness.FRESH, state("0.50"), goodTrack(), full()), List.of(), params);
        assertThat(low.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(low.unknownReasons()).contains("LOW_CONFIDENCE");
        Decision degraded = decision.decide(context(Freshness.FRESH, state("0.99"), new TrackQuality(2, 5L, false), full()), List.of(), params);
        assertThat(degraded.unknownReasons()).contains("TRACK_DEGRADED");
        Decision bridged = decision.decide(context(Freshness.FRESH, state("0.99"), new TrackQuality(10, 45L, true), full()), List.of(), params);
        assertThat(bridged.unknownReasons()).contains("TRACK_BRIDGED");
        // 置信度两种来源都缺失时不能默认合格：缺失事实即未知。
        Decision missing = decision.decide(context(Freshness.FRESH, state(null), goodTrack(), full()), List.of(), params);
        assertThat(missing.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(missing.unknownReasons()).contains("CONFIDENCE_UNKNOWN");
    }

    @Test
    void noPlanStatusIsAParameterAndUndeterminedMatchShortCircuits() {
        PlanMatch none = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        Decision illegal = decision.decide(context(Freshness.FRESH, state("0.99"), goodTrack(), none), List.of(pass("C02-1")), params);
        assertThat(illegal.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(illegal.violationReasons()).containsExactly("NO_AUTHORIZATION");
        assertThat(illegal.grade()).isNotNull();
        Decision abnormal = decision.decide(context(Freshness.FRESH, state("0.99"), goodTrack(), none), List.of(pass("C02-1")),
                TestRuleParams.demoCatalog().put("C03", "no_plan_status", "ABNORMAL"));
        assertThat(abnormal.status()).isEqualTo(LegalStatus.ABNORMAL);
        PlanMatch ambiguous = new PlanMatch(PlanMatchCode.UNDETERMINED, null, Map.of(), List.of("PLAN_AMBIGUOUS"));
        Decision undetermined = decision.decide(context(Freshness.FRESH, state("0.99"), goodTrack(), ambiguous), List.of(fail("C02-3", "ROUTE_DEVIATION")), params);
        assertThat(undetermined.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(undetermined.unknownReasons()).contains("PLAN_AMBIGUOUS");
    }

    @Test
    void airspaceFailuresAreIllegalAndAirspaceUnknownsWinOverOtherFailures() {
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), full());
        Decision illegal = decision.decide(ctx, List.of(fail("C02-1", "INSIDE_RESTRICTED_AIRSPACE"), fail("C02-3", "ROUTE_DEVIATION")), params);
        assertThat(illegal.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(illegal.violationReasons()).containsExactlyInAnyOrder("INSIDE_RESTRICTED_AIRSPACE", "ROUTE_DEVIATION");
        Decision unknown = decision.decide(ctx, List.of(undetermined("C02-8", "BOUNDARY_POLICY_UNKNOWN"), fail("C02-4", "TIME_WINDOW_OVERRUN")), params);
        assertThat(unknown.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(unknown.unknownReasons()).contains("BOUNDARY_POLICY_UNKNOWN");
        assertThat(unknown.score()).isNull();
    }

    @Test
    void behaviourFailuresAreAbnormalAndScoredByParameters() {
        // 违规 ROUTE_DEVIATION 严重度 0.6、计划 FULL、无限制空域命中、无桥接、置信度 0.9：100*(0.4*0.6+0.1*0.1)=25 → LOW。
        Decision abnormal = decision.decide(context(Freshness.FRESH, state("0.90"), goodTrack(), full()), List.of(pass("C02-1"), fail("C02-3", "ROUTE_DEVIATION")), params);
        assertThat(abnormal.status()).isEqualTo(LegalStatus.ABNORMAL);
        assertThat(abnormal.score()).isEqualByComparingTo("25.00");
        assertThat(abnormal.grade()).isEqualTo("LOW");
        // 违规 INSIDE_RESTRICTED_AIRSPACE 1.0、计划 NONE、限制空域命中、置信度 0.9：100*(0.4+0.25+0.15+0.01)=81 → HIGH。
        PlanMatch none = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        Decision high = decision.decide(context(Freshness.FRESH, state("0.90"), goodTrack(), none), List.of(fail("C02-1", "INSIDE_RESTRICTED_AIRSPACE")), params);
        assertThat(high.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(high.violationReasons()).containsExactlyInAnyOrder("NO_AUTHORIZATION", "INSIDE_RESTRICTED_AIRSPACE");
        assertThat(high.score()).isEqualByComparingTo("81.00");
        assertThat(high.grade()).isEqualTo("HIGH");
    }

    @Test
    void ignoredUndeterminedRulesStillAllowLegalButOthersDoNot() {
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), full());
        Decision legal = decision.decide(ctx, List.of(pass("C02-1"), pass("C02-3"), undetermined("C02-6", "PILOT_POSITION_UNAVAILABLE")), params);
        assertThat(legal.status()).isEqualTo(LegalStatus.LEGAL);
        assertThat(legal.score()).isNull();
        assertThat(legal.unknownReasons()).containsExactly("PILOT_POSITION_UNAVAILABLE");
        Decision unknown = decision.decide(ctx, List.of(pass("C02-1"), undetermined("C02-4", "PLAN_TIME_UNKNOWN"), undetermined("C02-6", "PILOT_POSITION_UNAVAILABLE")), params);
        assertThat(unknown.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(unknown.unknownReasons()).contains("PLAN_TIME_UNKNOWN");
    }

    @Test
    void aglOnlyTargetAgainstAmslAirspaceBandIsUndeterminedNeverIllegal() {
        TargetState aglOnly = new TargetState("t-1", "tr-1", "SN-1", new BigDecimal("118.005"), new BigDecimal("37.000"), null,
                new BigDecimal("50.00"), null, null, new BigDecimal("0.95"), AS_OF, AS_OF);
        AirspaceHit covers = new AirspaceHit("a-1", "av-1", "PROHIBITED", "COVERS", new BigDecimal("10"), new BigDecimal("100"), "AMSL", AS_OF.minusDays(1), null, null);
        EvaluationContext ctx = new EvaluationContext(subject(), aglOnly, goodTrack(), full(), List.of(covers), AS_OF, Freshness.FRESH, RunMode.ACTIVE, "mock");
        HitDetail hit = new RestrictedAirspaceCheck().evaluate(ctx, params);
        assertThat(hit.resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(hit.reasonCode()).isEqualTo("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        Decision result = decision.decide(ctx, List.of(hit), params);
        assertThat(result.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(result.violationReasons()).isEmpty();
    }

    @Test
    void missingParameterIsADeploymentErrorNotABusinessUnknown() {
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), full());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> decision.decide(ctx, List.of(), TestRuleParams.empty()));
    }

    private static EvaluationContext context(Freshness freshness, TargetState state, TrackQuality track, PlanMatch planMatch) {
        return new EvaluationContext(subject(), state, track, planMatch, List.of(), AS_OF, freshness, RunMode.ACTIVE, "mock");
    }
    private static Subject subject() { return new Subject(SubjectKind.TARGET, "t-1", "org-1", "district-1", "mock"); }
    private static TargetState state(String confidence) {
        return new TargetState("t-1", "tr-1", "SN-1", new BigDecimal("118.02"), new BigDecimal("37.02"), new BigDecimal("80.00"), new BigDecimal("60.00"),
                null, null, confidence == null ? null : new BigDecimal(confidence), AS_OF, AS_OF);
    }
    private static TrackQuality goodTrack() { return new TrackQuality(10, 5L, false); }
    private static PlanMatch full() {
        PlanFact plan = new PlanFact("p-1", "rv-1", "SN-1", AS_OF.minusMinutes(10), AS_OF.plusMinutes(50), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"), "AMSL", "org-1", "district-1");
        return new PlanMatch(PlanMatchCode.FULL, plan, Map.of("time", "MATCH", "corridor", "MATCH", "identity", "MATCH"), List.of());
    }
    private static HitDetail pass(String code) { return new HitDetail(code, "rv-" + code, ResultCode.PASS, null, null, Map.of(), List.of(), List.of(), "通过"); }
    private static HitDetail fail(String code, String reason) { return new HitDetail(code, "rv-" + code, ResultCode.FAIL, reason, null, Map.of(), List.of(), List.of(), "未通过"); }
    private static HitDetail undetermined(String code, String reason) { return new HitDetail(code, "rv-" + code, ResultCode.UNDETERMINED, reason, null, Map.of(), List.of(), List.of(), "未知"); }
}
