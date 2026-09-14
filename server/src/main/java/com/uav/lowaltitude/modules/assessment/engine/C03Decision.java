package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Freshness;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ResultCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TrackQuality;

/**
 * C03 四态决策（纯 Java，无 Spring）。严格按契约短路顺序：
 * 1. NO_STATE / STALE → NOT_APPLICABLE：过期或缺失的位置不能用来判合法性——目标可能早已离开该点，
 *    据此产生 ILLEGAL 会制造假告警，产生 LEGAL 又会掩盖真实违规，所以只能"不适用"。
 * 2. 质量门（置信度、轨迹点数、相邻间隔）任一不达标 → UNDETERMINED。
 * 3. C01 NONE → C03.no_plan_status；C01 UNDETERMINED → UNDETERMINED。
 * 4. 空域类（C02-1/2/8）任一 FAIL → ILLEGAL；任一 UNDETERMINED（无 FAIL）→ UNDETERMINED。
 * 5. 行为类（C02-3/4/5/7）任一 FAIL → ABNORMAL。
 * 6. 其余检查有 UNDETERMINED（排除 ignore_undetermined_rules）→ UNDETERMINED；否则 LEGAL。
 * 评分只在 ILLEGAL/ABNORMAL 给出，权重、严重度、等级阈值全部来自参数。
 */
public final class C03Decision {
    public static final String RULE_CODE = RuleCodes.C03;
    static final String PARAM_CONF_MIN = "conf_min";
    static final String PARAM_MIN_POINTS = "min_points";
    static final String PARAM_GAP_SECONDS = "gap_seconds";
    static final String PARAM_NO_PLAN_STATUS = "no_plan_status";
    static final String PARAM_IGNORE_UNDETERMINED = "ignore_undetermined_rules";
    static final String PARAM_W_VIOLATION = "w.violation";
    static final String PARAM_W_PLAN_MATCH = "w.plan_match";
    static final String PARAM_W_AIRSPACE = "w.airspace";
    static final String PARAM_W_TRACK = "w.track";
    static final String PARAM_W_CONFIDENCE = "w.confidence";
    static final String PARAM_SEVERITY_PREFIX = "severity.";
    static final String PARAM_GRADE_HIGH = "grade.high";
    static final String PARAM_GRADE_MEDIUM = "grade.medium";
    static final String GRADE_HIGH = "HIGH", GRADE_MEDIUM = "MEDIUM", GRADE_LOW = "LOW";
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final int SCORE_SCALE = 2;
    /** 因子取值是契约定义的定性映射（NONE 1 / PARTIAL 0.5 / FULL 0，桥接 0.6），不是可调阈值。 */
    private static final BigDecimal FACTOR_FULL = BigDecimal.ZERO;
    private static final BigDecimal FACTOR_PARTIAL = new BigDecimal("0.5");
    private static final BigDecimal FACTOR_NONE = BigDecimal.ONE;
    private static final BigDecimal FACTOR_BRIDGED = new BigDecimal("0.6");

    public Decision decide(EvaluationContext context, List<HitDetail> hits, RuleParams params) {
        List<HitDetail> details = hits == null ? List.of() : hits;
        // 步骤 1：新鲜度。
        if (context.freshness() == Freshness.NO_STATE || context.state() == null) {
            return Decision.notApplicable(RuleCodes.NO_STATE);
        }
        if (context.freshness() == Freshness.STALE) {
            return Decision.notApplicable(RuleCodes.STATE_STALE);
        }
        List<String> violations = new ArrayList<>(reasons(details, ResultCode.FAIL));
        Set<String> unknowns = new LinkedHashSet<>();
        // 步骤 2：质量门。三项都检查完再返回，便于页面一次看到全部质量问题。
        BigDecimal confidence = context.state().confidence();
        BigDecimal confMin = params.number(RULE_CODE, PARAM_CONF_MIN);
        int minPoints = params.integer(RULE_CODE, PARAM_MIN_POINTS);
        long gapSeconds = params.integer(RULE_CODE, PARAM_GAP_SECONDS);
        if (confidence == null) unknowns.add(RuleCodes.CONFIDENCE_UNKNOWN);
        else if (confidence.compareTo(confMin) < 0) unknowns.add(RuleCodes.LOW_CONFIDENCE);
        TrackQuality track = context.track();
        if (track == null || track.pointCount() < minPoints) unknowns.add(RuleCodes.TRACK_DEGRADED);
        boolean bridged = track != null && (track.bridged() || (track.maxGapSeconds() != null && track.maxGapSeconds() > gapSeconds));
        if (bridged) unknowns.add(RuleCodes.TRACK_BRIDGED);
        if (!unknowns.isEmpty()) {
            unknowns.addAll(reasons(details, ResultCode.UNDETERMINED));
            return Decision.undetermined(unknowns, violations);
        }
        // 步骤 3：计划匹配。
        PlanMatch match = context.planMatch() == null ? PlanMatch.notApplicable() : context.planMatch();
        boolean airspaceFail = anyResult(details, RuleCodes.AIRSPACE_CHECKS, ResultCode.FAIL);
        boolean airspaceUnknown = anyResult(details, RuleCodes.AIRSPACE_CHECKS, ResultCode.UNDETERMINED);
        boolean behaviourFail = anyResult(details, RuleCodes.BEHAVIOUR_CHECKS, ResultCode.FAIL);
        List<String> allUnknowns = reasons(details, ResultCode.UNDETERMINED);
        if (match.code() == PlanMatchCode.UNDETERMINED) {
            unknowns.addAll(match.reasonCodes().isEmpty() ? List.of(RuleCodes.PLAN_MATCH_UNDETERMINED) : match.reasonCodes());
            unknowns.addAll(allUnknowns);
            return Decision.undetermined(unknowns, violations);
        }
        if (match.code() == PlanMatchCode.NONE) {
            LegalStatus noPlan = noPlanStatus(params);
            if (noPlan == LegalStatus.ILLEGAL || noPlan == LegalStatus.ABNORMAL) violations.add(0, RuleCodes.NO_AUTHORIZATION);
            // 无计划的状态是参数给的下限；进入禁飞空域这种更重的事实不能被参数压低成 ABNORMAL/LEGAL。
            LegalStatus status = airspaceFail ? LegalStatus.ILLEGAL : noPlan;
            if (status != LegalStatus.LEGAL) {
                unknowns.addAll(allUnknowns);
                if (status == LegalStatus.UNDETERMINED) return Decision.undetermined(unknowns, violations);
                return scored(status, context, match, violations, unknowns, airspaceFail, bridged, params);
            }
            // no_plan_status=LEGAL 只把"无授权"本身视为合法；空域未知、行为违规仍要走步骤 4–6，不能直接判 LEGAL。
        }
        // 步骤 4：空域类。
        if (airspaceFail) {
            unknowns.addAll(allUnknowns);
            return scored(LegalStatus.ILLEGAL, context, match, violations, unknowns, true, bridged, params);
        }
        if (airspaceUnknown) {
            unknowns.addAll(reasons(details, RuleCodes.AIRSPACE_CHECKS, ResultCode.UNDETERMINED));
            unknowns.addAll(allUnknowns);
            return Decision.undetermined(unknowns, violations);
        }
        // 步骤 5：行为类。
        if (behaviourFail) {
            unknowns.addAll(allUnknowns);
            return scored(LegalStatus.ABNORMAL, context, match, violations, unknowns, false, bridged, params);
        }
        // 步骤 6：其余未知（忽略列表内的规则不阻断 LEGAL，但原因码仍保留给页面）。
        List<String> ignored = params.list(RULE_CODE, PARAM_IGNORE_UNDETERMINED);
        unknowns.addAll(allUnknowns);
        boolean blocking = details.stream().anyMatch(hit -> hit.resultCode() == ResultCode.UNDETERMINED && !ignored.contains(hit.ruleCode()));
        if (blocking) return Decision.undetermined(unknowns, violations);
        return Decision.legal(unknowns);
    }

    private static LegalStatus noPlanStatus(RuleParams params) {
        String value = params.string(RULE_CODE, PARAM_NO_PLAN_STATUS);
        try {
            LegalStatus status = LegalStatus.valueOf(value);
            if (status == LegalStatus.NOT_APPLICABLE) throw new IllegalArgumentException(value);
            return status;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("规则参数格式无效: " + RULE_CODE + "." + PARAM_NO_PLAN_STATUS + " 必须是 LEGAL/ABNORMAL/ILLEGAL/UNDETERMINED", ex);
        }
    }

    /** score = 100·Σ w_k·F_k；因子：最大违规严重度、计划匹配、限制空域命中、轨迹桥接、1 − 置信度。 */
    private static Decision scored(LegalStatus status, EvaluationContext context, PlanMatch match, List<String> violations,
            Set<String> unknowns, boolean airspaceHit, boolean bridged, RuleParams params) {
        BigDecimal severity = BigDecimal.ZERO;
        String primary = null;
        for (String reason : violations) {
            BigDecimal value = params.number(RULE_CODE, PARAM_SEVERITY_PREFIX + reason);
            if (primary == null || value.compareTo(severity) > 0) { severity = value; primary = reason; }
        }
        BigDecimal planFactor = switch (match.code()) {
            case NONE -> FACTOR_NONE;
            case PARTIAL -> FACTOR_PARTIAL;
            default -> FACTOR_FULL;
        };
        TargetState state = context.state();
        BigDecimal confidenceFactor = state.confidence() == null ? BigDecimal.ONE : BigDecimal.ONE.subtract(state.confidence());
        BigDecimal weighted = params.number(RULE_CODE, PARAM_W_VIOLATION).multiply(severity)
                .add(params.number(RULE_CODE, PARAM_W_PLAN_MATCH).multiply(planFactor))
                .add(params.number(RULE_CODE, PARAM_W_AIRSPACE).multiply(airspaceHit ? BigDecimal.ONE : BigDecimal.ZERO))
                .add(params.number(RULE_CODE, PARAM_W_TRACK).multiply(bridged ? FACTOR_BRIDGED : BigDecimal.ZERO))
                .add(params.number(RULE_CODE, PARAM_W_CONFIDENCE).multiply(confidenceFactor));
        BigDecimal score = weighted.multiply(PERCENT).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        String grade = score.compareTo(params.number(RULE_CODE, PARAM_GRADE_HIGH)) >= 0 ? GRADE_HIGH
                : score.compareTo(params.number(RULE_CODE, PARAM_GRADE_MEDIUM)) >= 0 ? GRADE_MEDIUM : GRADE_LOW;
        return new Decision(status, primary, List.copyOf(violations), List.copyOf(unknowns), score, grade);
    }

    private static boolean anyResult(List<HitDetail> hits, List<String> codes, ResultCode result) {
        return hits.stream().anyMatch(hit -> codes.contains(hit.ruleCode()) && hit.resultCode() == result);
    }

    private static List<String> reasons(List<HitDetail> hits, ResultCode result) {
        return hits.stream().filter(hit -> hit.resultCode() == result && hit.reasonCode() != null && !hit.reasonCode().isBlank())
                .map(HitDetail::reasonCode).distinct().toList();
    }

    private static List<String> reasons(List<HitDetail> hits, List<String> codes, ResultCode result) {
        return hits.stream().filter(hit -> codes.contains(hit.ruleCode()) && hit.resultCode() == result && hit.reasonCode() != null)
                .map(HitDetail::reasonCode).distinct().toList();
    }

    /**
     * 决策输出：reasonCode 是主原因（NOT_APPLICABLE 的新鲜度原因、UNDETERMINED 的首个未知、ILLEGAL/ABNORMAL 的最重违规），
     * violationReasons 是全部 FAIL 原因码，unknownReasons 是全部未知原因码；score/grade 只在 ILLEGAL/ABNORMAL 非空。
     */
    public record Decision(LegalStatus status, String reasonCode, List<String> violationReasons, List<String> unknownReasons,
            BigDecimal score, String grade) {
        static Decision notApplicable(String reason) {
            return new Decision(LegalStatus.NOT_APPLICABLE, reason, List.of(), List.of(reason), null, null);
        }
        static Decision undetermined(Set<String> unknowns, List<String> violations) {
            List<String> list = List.copyOf(unknowns);
            return new Decision(LegalStatus.UNDETERMINED, list.isEmpty() ? null : list.get(0), List.copyOf(violations), list, null, null);
        }
        static Decision legal(Set<String> unknowns) {
            return new Decision(LegalStatus.LEGAL, null, List.of(), List.copyOf(unknowns), null, null);
        }
    }
}
