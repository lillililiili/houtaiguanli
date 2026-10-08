package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;

/** 规则结论与能否自动采纳分别验证；不以风险分数冒充判定正确概率。 */
class DecisionAssuranceAlgorithmTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T04:00:00Z");
    private final TestRuleParams params = TestRuleParams.demoCatalog();
    private final DecisionAssuranceAlgorithm algorithm = new DecisionAssuranceAlgorithm();

    @Test void completePlanAndChecksAllowAutomaticLegalDecision() {
        var result = assess(context("mock", Freshness.FRESH, full(), "0.95", goodTrack()), checks());
        assertThat(result.status()).isEqualTo("SUFFICIENT");
        assertThat(result.reasons()).isEmpty();
        assertThat(result.algorithmVersion()).isEqualTo("EVIDENCE_SUFFICIENCY_V2");
    }

    @Test void explicitAirspaceViolationAllowsAutomaticIllegalDecisionWithUnrelatedUnknown() {
        var hits = replace(checks(), hit("C02-1", ResultCode.FAIL, "INSIDE_RESTRICTED_AIRSPACE"));
        hits = replace(hits, hit("C02-6", ResultCode.UNDETERMINED, "PILOT_POSITION_UNAVAILABLE"));
        var result = assess(context("mock", Freshness.FRESH, full(), "0.95", goodTrack()), hits);
        assertThat(result.status()).isEqualTo("SUFFICIENT");
        assertThat(result.reasons()).isEmpty();
    }

    @Test void noMatchedPlanAloneDoesNotProveFlightUnauthorized() {
        var match = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        var result = assess(context("mock", Freshness.FRESH, match, "0.95", goodTrack()), checks());
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.reasons()).contains("PLAN_AUTHORIZATION_UNVERIFIED");
    }

    /**
     * 新-28：完全没有报备任务、离地 120 米以下、不在管控空域里，按规定无需申请而判 LEGAL：不需要核对任务授权，C01 照实记的
     * “对不上任务”和夜航都不算与合法结论冲突，可直接采纳（不出告警）。超过 120 米的照旧要人核对授权。
     */
    @Test void noTaskLowFlightThatNeedsNoApplicationIsReliablyLegal() {
        var none = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        var hits = replace(checks(), hit("C01", ResultCode.FAIL, null));
        var low = context("mock", Freshness.FRESH, none, "0.95", goodTrack(), "60");
        var verdict = new C03Decision().decide(low, hits, params);
        assertThat(verdict.status()).isEqualTo(LegalStatus.LEGAL);
        assertThat(algorithm.assess(low, hits, verdict, params).status()).isEqualTo("SUFFICIENT");
        var night = replace(hits, hit("C02-5", ResultCode.FAIL, "NIGHT_FLIGHT"));
        var nightVerdict = new C03Decision().decide(low, night, params);
        assertThat(nightVerdict.status()).isEqualTo(LegalStatus.LEGAL);
        assertThat(algorithm.assess(low, night, nightVerdict, params).reasons()).isEmpty();
        // 调用方给出 LEGAL、但目标其实不在无需申请之列（离地 150 米）：C01 FAIL 仍与合法结论冲突。
        var high = context("mock", Freshness.FRESH, none, "0.95", goodTrack());
        var forced = new C03Decision.Decision(LegalStatus.LEGAL, null, List.of(), List.of(), null, null);
        assertThat(algorithm.assess(high, hits, forced, params).reasons()).contains("PLAN_AUTHORIZATION_UNVERIFIED", "DECISIVE_EVIDENCE_MISSING");
        var illegal = assess(high, hits);
        assertThat(illegal.status()).isEqualTo("INSUFFICIENT");
        assertThat(illegal.reasons()).contains("PLAN_AUTHORIZATION_UNVERIFIED");
    }

    @Test void missingIdentityCannotBecomeReliableLegalFromTimeAndCorridorAlone() {
        var match = new PlanMatch(PlanMatchCode.PARTIAL, full().plan(), Map.of("identity", "UNDETERMINED"), List.of("IDENTITY_CLUE_MISSING"));
        var result = assess(context("mock", Freshness.FRESH, match, "0.95", goodTrack()), checks());
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.reasons()).contains("IDENTITY_CLUE_MISSING");
    }

    @Test void independentlyProvenAirspaceViolationDoesNotRequireMatchedPlan() {
        var match = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        var result = assess(context("mock", Freshness.FRESH, match, "0.95", goodTrack()),
                replace(checks(), hit("C02-8", ResultCode.FAIL, "TEMPORARY_RESTRICTION_ACTIVE")));
        assertThat(result.status()).isEqualTo("SUFFICIENT");
    }

    @Test void lowInputConfidenceAndBrokenTrackCannotBeTrustedEvenWhenAHitExists() {
        var result = assess(context("mock", Freshness.FRESH, full(), "0.40", new TrackQuality(2, 60L, true)),
                replace(checks(), hit("C02-1", ResultCode.FAIL, "INSIDE_RESTRICTED_AIRSPACE")));
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.reasons()).contains("LOW_CONFIDENCE", "TRACK_DEGRADED", "TRACK_BRIDGED");
    }

    @Test void omittedCheckDoesNotBecomePassedByAbsence() {
        var hits = new ArrayList<>(checks());
        hits.removeIf(h -> h.ruleCode().equals("C02-2"));
        var result = assess(context("mock", Freshness.FRESH, full(), "0.95", goodTrack()), hits);
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.reasons()).contains("RULE_CHECKS_INCOMPLETE");
    }

    @Test void explicitlyIgnoredNonDecisiveUnknownDoesNotForceReview() {
        var result = assess(context("mock", Freshness.FRESH, full(), "0.95", goodTrack()),
                replace(checks(), hit("C02-6", ResultCode.UNDETERMINED, "PILOT_POSITION_UNAVAILABLE")));
        assertThat(result.status()).isEqualTo("SUFFICIENT");
    }

    @Test void unknownAirspaceEvidenceRequiresReview() {
        var result = assess(context("mock", Freshness.FRESH, full(), "0.95", goodTrack()),
                replace(checks(), hit("C02-2", ResultCode.UNDETERMINED, "ALTITUDE_DATUM_OR_RANGE_UNKNOWN")));
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.reasons()).contains("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
    }

    @Test void actualDataWithDemoParametersIsNotReliableProductionDecision() {
        var result = assess(context("live", Freshness.FRESH, full(), "0.95", goodTrack()), checks());
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.reasons()).contains("DEMO_RULE_PARAMETERS");
    }

    @Test void explicitBehaviourViolationWithCompleteEvidenceIsReliable() {
        var result = assess(context("mock", Freshness.FRESH, full(), "0.95", goodTrack()),
                replace(checks(), hit("C02-3", ResultCode.FAIL, "ROUTE_DEVIATION")));
        assertThat(result.status()).isEqualTo("SUFFICIENT");
        assertThat(result.reasons()).isEmpty();
    }

    @Test void behaviourViolationStillRequiresIdentityAndConfirmedParameters() {
        var hits = replace(checks(), hit("C02-3", ResultCode.FAIL, "ROUTE_DEVIATION"));
        var partial = new PlanMatch(PlanMatchCode.PARTIAL, full().plan(), Map.of(), List.of("IDENTITY_CLUE_MISSING"));
        assertThat(assess(context("mock", Freshness.FRESH, partial, "0.95", goodTrack()), hits).status()).isEqualTo("INSUFFICIENT");
        assertThat(assess(context("live", Freshness.FRESH, full(), "0.95", goodTrack()), hits).reasons()).contains("DEMO_RULE_PARAMETERS");
        assertThat(assess(context("mock", Freshness.FRESH, full(), "0.10", goodTrack()), hits).status()).isEqualTo("INSUFFICIENT");
    }

    /** 2026-10-07 起单独超视距判 ILLEGAL：飞手与目标的两点距离本身就是明确依据，数据与计划都齐全时可直接采纳，不再要求"关键依据缺失"复核。 */
    @Test void explicitBvlosViolationWithCompleteEvidenceIsReliable() {
        var context = context("mock", Freshness.FRESH, full(), "0.95", goodTrack());
        var hits = replace(checks(), hit("C02-6", ResultCode.FAIL, "BVLOS_EXCEEDED"));
        var verdict = new C03Decision().decide(context, hits, params);
        assertThat(verdict.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(verdict.grade()).isEqualTo("LOW");
        var result = algorithm.assess(context, hits, verdict, params);
        assertThat(result.status()).isEqualTo("SUFFICIENT");
        assertThat(result.reasons()).isEmpty();
    }

    /** 超视距不放宽其余复核要求：身份未核实、实测数据配演示参数、置信度不足时仍不充分，且原因写清楚，不笼统写"关键依据缺失"。 */
    @Test void bvlosViolationKeepsTheUsualReviewRequirements() {
        var hits = replace(checks(), hit("C02-6", ResultCode.FAIL, "BVLOS_EXCEEDED"));
        var partial = new PlanMatch(PlanMatchCode.PARTIAL, full().plan(), Map.of(), List.of("IDENTITY_CLUE_MISSING"));
        var unverified = assess(context("mock", Freshness.FRESH, partial, "0.95", goodTrack()), hits);
        assertThat(unverified.status()).isEqualTo("INSUFFICIENT");
        assertThat(unverified.reasons()).contains("IDENTITY_CLUE_MISSING").doesNotContain("DECISIVE_EVIDENCE_MISSING");
        assertThat(assess(context("live", Freshness.FRESH, full(), "0.95", goodTrack()), hits).reasons()).contains("DEMO_RULE_PARAMETERS");
        assertThat(assess(context("mock", Freshness.FRESH, full(), "0.10", goodTrack()), hits).status()).isEqualTo("INSUFFICIENT");
    }

    @Test void staleAndMissingObservationsRemainNotApplicable() {
        var stale = assess(context("mock", Freshness.STALE, full(), "0.95", goodTrack()), checks());
        assertThat(stale.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(stale.reasons()).contains("STATE_STALE");
        var missing = new EvaluationContext(new Subject(SubjectKind.TARGET, "t", "o", "d", "mock"), null, goodTrack(), full(), List.of(), NOW, Freshness.NO_STATE, RunMode.ACTIVE, "mock");
        assertThat(assess(missing, checks()).status()).isEqualTo("NOT_APPLICABLE");
    }

    @Test void liveFreshnessAndTrackWindowAlsoRequireConfirmedParameters() {
        var context = context("live", Freshness.FRESH, full(), "0.95", goodTrack());
        for (String key : List.of("fresh_seconds", "track_points")) {
            RuleParams mixed = confirmedExcept(key);
            var result = algorithm.assess(context, checks(), new C03Decision().decide(context, checks(), mixed), mixed);
            assertThat(result.status()).as(key).isEqualTo("INSUFFICIENT");
            assertThat(result.reasons()).contains("DEMO_RULE_PARAMETERS");
        }
    }

    @Test void unrelatedPlanParameterDoesNotBlockConfirmedAirspaceViolation() {
        var context = context("live", Freshness.FRESH, full(), "0.95", goodTrack());
        var hits = replace(checks(), hit("C02-1", ResultCode.FAIL, "INSIDE_RESTRICTED_AIRSPACE"));
        hits = replace(hits, new HitDetail("C01", "rv-C01", ResultCode.PASS, null, null, Map.of(),
                List.of(new ParamRef("corridor_tolerance_m", "100", "DEMO")), List.of(), "任务匹配使用演示参数"));
        RuleParams confirmed = confirmedExcept("");
        var result = algorithm.assess(context, hits, new C03Decision().decide(context, hits, confirmed), confirmed);
        assertThat(result.status()).isEqualTo("SUFFICIENT");
    }

    @Test void decisiveAirspaceParameterMustBeConfirmedForLiveObservation() {
        var context = context("live", Freshness.FRESH, full(), "0.95", goodTrack());
        var hits = replace(checks(), new HitDetail("C02-1", "rv-C02-1", ResultCode.FAIL, "INSIDE_RESTRICTED_AIRSPACE", null,
                Map.of(), List.of(new ParamRef("kinds", "PROHIBITED", "DEMO")), List.of(), "禁飞检查使用演示参数"));
        RuleParams confirmed = confirmedExcept("");
        var result = algorithm.assess(context, hits, new C03Decision().decide(context, hits, confirmed), confirmed);
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.reasons()).contains("DEMO_RULE_PARAMETERS");
    }

    private RuleParams confirmedExcept(String demoKey) {
        return new RuleParams() {
            public String ruleSetVersionId() { return params.ruleSetVersionId(); }
            public String paramStatus(String rule, String key) { return key.equals(demoKey) ? "DEMO" : "CONFIRMED"; }
            public BigDecimal number(String rule, String key) { return params.number(rule, key); }
            public int integer(String rule, String key) { return params.integer(rule, key); }
            public boolean bool(String rule, String key) { return params.bool(rule, key); }
            public String string(String rule, String key) { return params.string(rule, key); }
            public List<String> list(String rule, String key) { return params.list(rule, key); }
        };
    }

    private DecisionAssuranceAlgorithm.Assurance assess(EvaluationContext context, List<HitDetail> hits) {
        return algorithm.assess(context, hits, new C03Decision().decide(context, hits, params), params);
    }

    /** 离地 150 米：高于 120 米，没有报备任务时不属于按规定无需申请的飞行（新-28）。 */
    private static EvaluationContext context(String source, Freshness freshness, PlanMatch match, String confidence, TrackQuality track) {
        return context(source, freshness, match, confidence, track, "150");
    }

    private static EvaluationContext context(String source, Freshness freshness, PlanMatch match, String confidence, TrackQuality track, String heightAgl) {
        var state = new TargetState("t", "tr", "SN1", new BigDecimal("118.5"), new BigDecimal("37.5"), new BigDecimal("170"), new BigDecimal(heightAgl), BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal(confidence), NOW, NOW);
        return new EvaluationContext(new Subject(SubjectKind.TARGET, "t", "o", "d", source), state, track, match, List.of(), NOW, freshness, RunMode.ACTIVE, source);
    }

    private static PlanMatch full() {
        var plan = new PlanFact("p", "r", "SN1", NOW.minusHours(1), NOW.plusHours(1), new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("200"), "AMSL", "o", "d");
        return new PlanMatch(PlanMatchCode.FULL, plan, Map.of("identity", "MATCH", "time_window", "MATCH", "corridor", "MATCH"), List.of());
    }

    private static TrackQuality goodTrack() { return new TrackQuality(10, 5L, false); }
    private static List<HitDetail> checks() {
        return List.of("C01", "C02-1", "C02-2", "C02-3", "C02-4", "C02-5", "C02-6", "C02-7", "C02-8").stream()
                .map(code -> hit(code, ResultCode.PASS, null)).toList();
    }
    private static HitDetail hit(String code, ResultCode result, String reason) {
        return new HitDetail(code, "rv-" + code, result, reason, null, Map.of(), List.of(), List.of(), "测试规则事实");
    }
    private static List<HitDetail> replace(List<HitDetail> hits, HitDetail value) {
        return hits.stream().map(h -> h.ruleCode().equals(value.ruleCode()) ? value : h).toList();
    }
}
