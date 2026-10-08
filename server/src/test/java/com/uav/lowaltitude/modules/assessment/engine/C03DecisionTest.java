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
        assertThat(abnormal.status()).isEqualTo(LegalStatus.UNDETERMINED);
        PlanMatch ambiguous = new PlanMatch(PlanMatchCode.UNDETERMINED, null, Map.of(), List.of("PLAN_AMBIGUOUS"));
        Decision undetermined = decision.decide(context(Freshness.FRESH, state("0.99"), goodTrack(), ambiguous), List.of(fail("C02-3", "ROUTE_DEVIATION")), params);
        assertThat(undetermined.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(undetermined.unknownReasons()).contains("PLAN_AMBIGUOUS");
    }

    @Test
    void ambiguousPlanDoesNotHideAnAirspaceViolation() {
        // 附近多个执行中计划分不清（PLAN_AMBIGUOUS）时，进禁飞区本身仍是违规：判 ILLEGAL，计划不明的原因保留给复核。
        PlanMatch ambiguous = new PlanMatch(PlanMatchCode.UNDETERMINED, null, Map.of(), List.of("PLAN_AMBIGUOUS", "IDENTITY_CLUE_MISSING"));
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), ambiguous);
        Decision illegal = decision.decide(ctx, List.of(undetermined("C01", "PLAN_AMBIGUOUS"), fail("C02-1", "INSIDE_RESTRICTED_AIRSPACE")), params);
        assertThat(illegal.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(illegal.reasonCode()).isEqualTo("INSIDE_RESTRICTED_AIRSPACE");
        assertThat(illegal.violationReasons()).containsExactly("INSIDE_RESTRICTED_AIRSPACE");
        assertThat(illegal.unknownReasons()).contains("PLAN_AMBIGUOUS");
        // 计划不明的计划因子取 0.5：100*(0.4*1.0+0.25*0.5+0.15*1+0.1*0.1)=68.5 → HIGH。
        assertThat(illegal.score()).isEqualByComparingTo("68.50");
        assertThat(illegal.grade()).isEqualTo("HIGH");
        // 临管、限高同属空域类，同样不被计划不明挡住。
        assertThat(decision.decide(ctx, List.of(fail("C02-8", "TEMPORARY_RESTRICTION_ACTIVE")), params).status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(decision.decide(ctx, List.of(fail("C02-2", "AIRSPACE_ALTITUDE_EXCEEDED")), params).status()).isEqualTo(LegalStatus.ILLEGAL);
        // 空域只是边界未知、或只有依赖计划的行为项时，仍因计划不明而不可判定。
        Decision boundary = decision.decide(ctx, List.of(undetermined("C02-1", "BOUNDARY_POLICY_UNKNOWN")), params);
        assertThat(boundary.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(boundary.unknownReasons()).contains("PLAN_AMBIGUOUS", "BOUNDARY_POLICY_UNKNOWN");
        assertThat(decision.decide(ctx, List.of(fail("C02-5", "NIGHT_FLIGHT")), params).status()).isEqualTo(LegalStatus.UNDETERMINED);
    }

    @Test
    void missingSeverityParameterOnlyLowersTheScoreAndNeverAbortsTheDecision() {
        // 已发布的旧版本没有 C03.severity.BVLOS_EXCEEDED（从未猜填）：无计划且超视距仍要给出结论。
        // 2026-10-07 起超视距根本不经严重度评分（见下面的超视距用例），缺项与否都不影响无授权单独时的结论。
        PlanMatch none = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), none);
        List<HitDetail> hits = List.of(pass("C02-1"), fail("C02-6", "BVLOS_EXCEEDED"));
        Decision legacy = decision.decide(ctx, hits, TestRuleParams.demoCatalog().without("C03", "severity.BVLOS_EXCEEDED"));
        assertThat(legacy.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(legacy.violationReasons()).containsExactly("NO_AUTHORIZATION", "BVLOS_EXCEEDED");
        assertThat(legacy.reasonCode()).isEqualTo("NO_AUTHORIZATION");
        // 100*(0.4*0.8+0.25*1+0.1*0.1)=58 → MEDIUM；即使某个版本给超视距配了严重度，结论与评分也不变。
        assertThat(legacy.score()).isEqualByComparingTo("58.00");
        assertThat(legacy.grade()).isEqualTo("MEDIUM");
        Decision confirmed = decision.decide(ctx, hits, TestRuleParams.demoCatalog().put("C03", "severity.BVLOS_EXCEEDED", "0.3"));
        assertThat(confirmed.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(confirmed.score()).isEqualByComparingTo("58.00");
        // 其他原因码缺严重度时按 0 计分、照常给出结论：夜航缺项 → 100*(0.1*0.1)=1 → LOW，仍是 ILLEGAL。
        Decision night = decision.decide(context(Freshness.FRESH, state("0.90"), goodTrack(), full()), List.of(fail("C02-5", "NIGHT_FLIGHT")),
                TestRuleParams.demoCatalog().without("C03", "severity.NIGHT_FLIGHT"));
        assertThat(night.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(night.reasonCode()).isEqualTo("NIGHT_FLIGHT");
        assertThat(night.score()).isEqualByComparingTo("1.00");
        assertThat(night.grade()).isEqualTo("LOW");
        // 只有严重度可以缺项；权重、等级阈值等其他参数缺失仍是部署错误。
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> decision.decide(ctx, hits, TestRuleParams.demoCatalog().without("C03", "w.violation")));
    }

    /**
     * 2026-10-07 业务决定：只有超视距（C02-6 FAIL）一项违规 → ILLEGAL，原因 BVLOS_EXCEEDED，等级固定 LOW，不看加权分数。
     * 逻辑写在 C03 里而不是参数里，所以已发布、没有 severity.BVLOS_EXCEEDED 的旧版本（本用例的 DEMO 目录）不重新发布也照此执行。
     */
    @Test
    void bvlosAloneIsIllegalWithAFixedLowGrade() {
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), full());
        Decision bvlos = decision.decide(ctx, allPassExcept(fail("C02-6", "BVLOS_EXCEEDED")), params);
        assertThat(bvlos.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(bvlos.reasonCode()).isEqualTo("BVLOS_EXCEEDED");
        assertThat(bvlos.violationReasons()).containsExactly("BVLOS_EXCEEDED");
        assertThat(bvlos.unknownReasons()).isEmpty();
        // 超视距不计严重度：100*(0.1*0.1)=1。
        assertThat(bvlos.score()).isEqualByComparingTo("1.00");
        assertThat(bvlos.grade()).isEqualTo("LOW");

        // 加权分数够得上 HIGH 也仍是 LOW：无计划按参数视为合法（C01 FAIL 不带违规原因码，不算第二项违规）、
        // 计划权重调到 0.9、并给超视距配上最高严重度——100*(0.9*1+0.1*0.1)=91，等级照样固定 LOW。
        PlanMatch none = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        TestRuleParams heavy = TestRuleParams.demoCatalog().put("C03", "no_plan_status", "LEGAL").put("C03", "w.plan_match", "0.90")
                .put("C03", "severity.BVLOS_EXCEEDED", "1.0");
        List<HitDetail> noPlanHits = new java.util.ArrayList<>(allPassExcept(fail("C02-6", "BVLOS_EXCEEDED")));
        noPlanHits.set(0, fail("C01", null));
        Decision fixed = decision.decide(context(Freshness.FRESH, state("0.90"), goodTrack(), none), noPlanHits, heavy);
        assertThat(fixed.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(fixed.violationReasons()).containsExactly("BVLOS_EXCEEDED");
        assertThat(fixed.score()).isEqualByComparingTo("91.00");
        assertThat(fixed.grade()).isEqualTo("LOW");

        // 在阈值内（PASS）或没有飞手位置（未知，C03.ignore_undetermined_rules=C02-6 忽略）：LEGAL，不因 C02-6 变成不可判定。
        assertThat(decision.decide(ctx, allPassExcept(pass("C02-6")), params).status()).isEqualTo(LegalStatus.LEGAL);
        Decision noPilot = decision.decide(ctx, allPassExcept(undetermined("C02-6", "PILOT_POSITION_UNAVAILABLE")), params);
        assertThat(noPilot.status()).isEqualTo(LegalStatus.LEGAL);
        assertThat(noPilot.violationReasons()).isEmpty();
        assertThat(noPilot.unknownReasons()).containsExactly("PILOT_POSITION_UNAVAILABLE");
    }

    /**
     * 超视距与其他违规同时出现：状态、分数、等级、主原因与去掉超视距时逐项相同，只是 violation_reasons 多一个 BVLOS_EXCEEDED。
     * 超视距不参与严重度取最大，所以旧版本缺 severity.BVLOS_EXCEEDED、或者某个版本把它配成最高的 1.0，都抬不高也压不低组合结论；
     * 超视距排在列表最前、其他原因又缺严重度时，主原因也不会落到超视距上。
     */
    @Test
    void bvlosNeverRaisesOrLowersTheResultOfOtherViolations() {
        PlanMatch none = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        PlanMatch ambiguous = new PlanMatch(PlanMatchCode.UNDETERMINED, null, Map.of(), List.of("PLAN_AMBIGUOUS"));
        // {计划匹配, 其他检查结果, 不带超视距时的等级}：禁飞+无计划 81、无计划 58、禁飞+计划不明 68.5、限高 52、偏航 25、夜航+计划高度 21。
        List<Object[]> cases = List.of(
                new Object[]{none, List.of(fail("C02-1", "INSIDE_RESTRICTED_AIRSPACE")), "HIGH"},
                new Object[]{none, List.of(pass("C02-1")), "MEDIUM"},
                new Object[]{ambiguous, List.of(undetermined("C01", "PLAN_AMBIGUOUS"), fail("C02-1", "INSIDE_RESTRICTED_AIRSPACE")), "HIGH"},
                new Object[]{full(), List.of(fail("C02-2", "AIRSPACE_ALTITUDE_EXCEEDED")), "MEDIUM"},
                new Object[]{full(), List.of(fail("C02-3", "ROUTE_DEVIATION")), "LOW"},
                new Object[]{full(), List.of(fail("C02-5", "NIGHT_FLIGHT"), fail("C02-7", "PLAN_ALTITUDE_EXCEEDED")), "LOW"});
        List<TestRuleParams> catalogs = List.of(TestRuleParams.demoCatalog(),
                TestRuleParams.demoCatalog().put("C03", "severity.BVLOS_EXCEEDED", "1.0"),
                TestRuleParams.demoCatalog().put("C03", "severity.BVLOS_EXCEEDED", "0"));
        HitDetail bvlos = fail("C02-6", "BVLOS_EXCEEDED");
        for (TestRuleParams catalog : catalogs) {
            for (Object[] row : cases) {
                PlanMatch match = (PlanMatch) row[0];
                @SuppressWarnings("unchecked") List<HitDetail> others = (List<HitDetail>) row[1];
                EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), match);
                Decision without = decision.decide(ctx, others, catalog);
                List<HitDetail> after = new java.util.ArrayList<>(others); after.add(bvlos);
                List<HitDetail> before = new java.util.ArrayList<>(); before.add(bvlos); before.addAll(others);
                assertThat(without.status()).isEqualTo(LegalStatus.ILLEGAL);
                assertThat(without.grade()).as("基线 %s", others).isEqualTo(row[2]);
                for (List<HitDetail> hits : List.of(after, before)) {
                    Decision with = decision.decide(ctx, hits, catalog);
                    assertThat(with.status()).isEqualTo(without.status());
                    assertThat(with.score()).as("分数 %s", hits).isEqualByComparingTo(without.score());
                    assertThat(with.grade()).as("等级 %s", hits).isEqualTo(without.grade());
                    assertThat(with.reasonCode()).as("主原因 %s", hits).isEqualTo(without.reasonCode());
                    assertThat(with.unknownReasons()).isEqualTo(without.unknownReasons());
                    List<String> expected = new java.util.ArrayList<>(without.violationReasons()); expected.add("BVLOS_EXCEEDED");
                    assertThat(with.violationReasons()).containsExactlyInAnyOrderElementsOf(expected);
                }
            }
        }
        // 另一项违规缺严重度（按 0 计）且超视距排在前面：主原因仍是那项违规，分数等级同样不变。
        TestRuleParams legacy = TestRuleParams.demoCatalog().without("C03", "severity.PLAN_ALTITUDE_EXCEEDED");
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), full());
        Decision without = decision.decide(ctx, List.of(fail("C02-7", "PLAN_ALTITUDE_EXCEEDED")), legacy);
        Decision with = decision.decide(ctx, List.of(bvlos, fail("C02-7", "PLAN_ALTITUDE_EXCEEDED")), legacy);
        assertThat(with.reasonCode()).isEqualTo(without.reasonCode()).isEqualTo("PLAN_ALTITUDE_EXCEEDED");
        assertThat(with.score()).isEqualByComparingTo(without.score());
        assertThat(with.grade()).isEqualTo(without.grade());
    }

    /**
     * 超视距排在行为类之后（第 6 步）：过期、质量门、计划不明、空域未知、无计划按参数不可判定时，照旧先给出这些结论；
     * 只有不阻断第 5 步的其余未知（如计划时段未知）同样不阻断超视距，结论 ILLEGAL/LOW，未知原因留给复核。
     */
    @Test
    void bvlosDoesNotOverrideEarlierShortCircuits() {
        HitDetail bvlos = fail("C02-6", "BVLOS_EXCEEDED");
        assertThat(decision.decide(context(Freshness.STALE, state("0.90"), goodTrack(), full()), List.of(bvlos), params).status())
                .isEqualTo(LegalStatus.NOT_APPLICABLE);
        Decision lowConfidence = decision.decide(context(Freshness.FRESH, state("0.50"), goodTrack(), full()), List.of(bvlos), params);
        assertThat(lowConfidence.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(lowConfidence.violationReasons()).containsExactly("BVLOS_EXCEEDED");
        PlanMatch ambiguous = new PlanMatch(PlanMatchCode.UNDETERMINED, null, Map.of(), List.of("PLAN_AMBIGUOUS"));
        Decision unclearPlan = decision.decide(context(Freshness.FRESH, state("0.90"), goodTrack(), ambiguous),
                List.of(undetermined("C01", "PLAN_AMBIGUOUS"), bvlos), params);
        assertThat(unclearPlan.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(unclearPlan.unknownReasons()).contains("PLAN_AMBIGUOUS");
        EvaluationContext ctx = context(Freshness.FRESH, state("0.90"), goodTrack(), full());
        Decision boundary = decision.decide(ctx, List.of(undetermined("C02-1", "BOUNDARY_POLICY_UNKNOWN"), bvlos), params);
        assertThat(boundary.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(boundary.unknownReasons()).contains("BOUNDARY_POLICY_UNKNOWN");
        PlanMatch none = new PlanMatch(PlanMatchCode.NONE, null, Map.of(), List.of("NO_PLAN_CANDIDATE"));
        assertThat(decision.decide(context(Freshness.FRESH, state("0.90"), goodTrack(), none), List.of(fail("C01", null), bvlos),
                TestRuleParams.demoCatalog().put("C03", "no_plan_status", "UNDETERMINED")).status()).isEqualTo(LegalStatus.UNDETERMINED);
        Decision planTime = decision.decide(ctx, List.of(undetermined("C02-4", "PLAN_TIME_UNKNOWN"), bvlos), params);
        assertThat(planTime.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(planTime.grade()).isEqualTo("LOW");
        assertThat(planTime.reasonCode()).isEqualTo("BVLOS_EXCEEDED");
        assertThat(planTime.unknownReasons()).containsExactly("PLAN_TIME_UNKNOWN");
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
    void behaviourFailuresAreIllegalAndScoredByParameters() {
        // 违规 ROUTE_DEVIATION 严重度 0.6、计划 FULL、无限制空域命中、无桥接、置信度 0.9：100*(0.4*0.6+0.1*0.1)=25 → LOW。
        Decision abnormal = decision.decide(context(Freshness.FRESH, state("0.90"), goodTrack(), full()), List.of(pass("C02-1"), fail("C02-3", "ROUTE_DEVIATION")), params);
        assertThat(abnormal.status()).isEqualTo(LegalStatus.ILLEGAL);
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
    /** C01、C02-1…C02-8 全部 PASS，只把 replacement 那一条换掉。 */
    private static List<HitDetail> allPassExcept(HitDetail replacement) {
        return List.of("C01", "C02-1", "C02-2", "C02-3", "C02-4", "C02-5", "C02-6", "C02-7", "C02-8").stream()
                .map(code -> code.equals(replacement.ruleCode()) ? replacement : pass(code)).toList();
    }
}
