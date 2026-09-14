package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvidenceRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ResultCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/**
 * C01 计划匹配。分两步：{@link #match} 在引擎收集输入阶段由 {@code LegalityEvaluationService} 调用，
 * 把候选计划、目标状态与航线距离归结为 {@link PlanMatch}；{@link #evaluate} 只把上下文里已算好的 PlanMatch
 * 转成 hit_details 明细，规则本身不再访问数据库或空间后端。
 */
@Component
public class PlanMatchCheck implements RuleCheck {
    public static final String RULE_CODE = "C01";
    public static final String PARAM_TIME_WINDOW_MIN = "time_window_min";
    public static final String PARAM_CORRIDOR_TOLERANCE_M = "corridor_tolerance_m";
    private static final String MATCH = "MATCH", MISMATCH = "MISMATCH", UNDETERMINED = "UNDETERMINED";
    private static final String DIM_TIME = "time_window", DIM_CORRIDOR = "corridor", DIM_IDENTITY = "identity";
    private static final String DIM_TAKEOFF = "takeoff_point", DIM_PILOT = "pilot_unit";

    /** 航线距离来源：引擎用 {@code rv -> spatialFactPort.distanceToRoute(state, rv)} 适配，测试用固定桩。 */
    @FunctionalInterface
    public interface RouteDistanceSource {
        RouteDistance distanceToRoute(String routeVersionId);
    }

    @Override
    public String ruleCode() { return RULE_CODE; }

    @Override
    public int defaultPriority() { return RuleCodes.PRIORITY_C01; }

    /**
     * @param tuplePlans 与目标同 (owner_org_id, district_id) 的计划；候选谓词（sn 相等 或 时间窗覆盖 as_of）在这里按参数判定，
     *                   调用方不必预先套用 time_window_min。
     */
    public PlanMatch match(TargetState state, List<PlanFact> tuplePlans, RouteDistanceSource distances, OffsetDateTime asOf, RuleParams params) {
        int windowMinutes = params.integer(RULE_CODE, PARAM_TIME_WINDOW_MIN);
        BigDecimal tolerance = params.number(RULE_CODE, PARAM_CORRIDOR_TOLERANCE_M);
        String targetSn = blankToNull(state == null ? null : state.uavSn());
        List<Candidate> candidates = new ArrayList<>();
        for (PlanFact plan : tuplePlans == null ? List.<PlanFact>of() : tuplePlans) {
            if (plan == null || !isCandidate(plan, targetSn, asOf, windowMinutes)) continue;
            candidates.add(grade(plan, state, targetSn, distances, asOf, windowMinutes, tolerance));
        }
        if (candidates.isEmpty()) {
            return new PlanMatch(PlanMatchCode.NONE, null, unavailableDimensions(), List.of("NO_PLAN_CANDIDATE"));
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> rank(c.code)).reversed().thenComparing(c -> c.plan.planId()));
        Candidate best = candidates.get(0);
        // 多候选同优（且不是全部 NONE）时不能随意挑一个计划做后续偏航/高度判定，只能报歧义。
        boolean ambiguous = best.code != PlanMatchCode.NONE && candidates.size() > 1 && candidates.get(1).code == best.code;
        if (ambiguous) {
            List<String> reasons = new ArrayList<>(List.of("PLAN_AMBIGUOUS"));
            best.reasons.stream().filter(r -> !reasons.contains(r)).forEach(reasons::add);
            return new PlanMatch(PlanMatchCode.UNDETERMINED, null, best.dimensions, List.copyOf(reasons));
        }
        return new PlanMatch(best.code, best.plan, best.dimensions, List.copyOf(best.reasons));
    }

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        PlanMatch match = context == null || context.planMatch() == null ? PlanMatch.notApplicable() : context.planMatch();
        List<ParamRef> refs = List.of(
                new ParamRef(PARAM_TIME_WINDOW_MIN, Integer.toString(params.integer(RULE_CODE, PARAM_TIME_WINDOW_MIN)), params.paramStatus(RULE_CODE, PARAM_TIME_WINDOW_MIN)),
                new ParamRef(PARAM_CORRIDOR_TOLERANCE_M, params.number(RULE_CODE, PARAM_CORRIDOR_TOLERANCE_M).toPlainString(), params.paramStatus(RULE_CODE, PARAM_CORRIDOR_TOLERANCE_M)));
        String reason = match.reasonCodes().isEmpty() ? null : match.reasonCodes().get(0);
        ResultCode result = switch (match.code()) {
            case FULL, PARTIAL -> ResultCode.PASS;
            case NONE -> ResultCode.FAIL;
            case UNDETERMINED -> ResultCode.UNDETERMINED;
            case NOT_APPLICABLE -> ResultCode.NOT_APPLICABLE;
        };
        // facts 只放安全字段：计划/航线 ID 与维度结果；目标 sn、坐标等原始输入留在 input_snapshot，不进 API。
        // NONE 的匹配原因（NO_PLAN_CANDIDATE/CORRIDOR_MISMATCH…）不是可评分的违规原因码：C03 只对 FAIL 的 reason_code 查 severity 参数，
        // 并自行给出 NO_AUTHORIZATION；因此 FAIL 行的 reason_code 留空，匹配原因放在 facts.match_reason 供页面解释。
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("plan_match_code", match.code().name());
        facts.put("match_reason", reason);
        facts.put("plan_id", match.plan() == null ? null : match.plan().planId());
        facts.put("route_version_id", match.plan() == null ? null : match.plan().routeVersionId());
        facts.put("candidate_count", candidateCount(match, reason));
        facts.put("dimensions", match.dimensions());
        List<EvidenceRef> evidence = new ArrayList<>();
        if (match.plan() != null) {
            evidence.add(new EvidenceRef("flight_plan", match.plan().planId()));
            if (match.plan().routeVersionId() != null) evidence.add(new EvidenceRef("route_version", match.plan().routeVersionId()));
        }
        boolean demo = refs.stream().anyMatch(ref -> "DEMO".equals(ref.status()));
        return new HitDetail(RULE_CODE, null, result, result == ResultCode.UNDETERMINED ? reason : null, null, facts, refs, List.copyOf(evidence), message(match, reason, demo));
    }

    private static boolean isCandidate(PlanFact plan, String targetSn, OffsetDateTime asOf, int windowMinutes) {
        String planSn = blankToNull(plan.uavSn());
        if (targetSn != null && targetSn.equals(planSn)) return true;
        if (plan.startAt() == null || plan.endAt() == null || asOf == null) return false;
        return !asOf.isBefore(plan.startAt().minusMinutes(windowMinutes)) && asOf.isBefore(plan.endAt().plusMinutes(windowMinutes));
    }

    private static Candidate grade(PlanFact plan, TargetState state, String targetSn, RouteDistanceSource distances,
            OffsetDateTime asOf, int windowMinutes, BigDecimal tolerance) {
        Map<String, String> dims = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        // 时间窗
        if (plan.startAt() == null || plan.endAt() == null) { dims.put(DIM_TIME, UNDETERMINED); reasons.add("PLAN_TIME_UNKNOWN"); }
        else if (!asOf.isBefore(plan.startAt().minusMinutes(windowMinutes)) && asOf.isBefore(plan.endAt().plusMinutes(windowMinutes))) dims.put(DIM_TIME, MATCH);
        else { dims.put(DIM_TIME, MISMATCH); reasons.add("TIME_WINDOW_MISMATCH"); }
        // 走廊：与 C02-3 同一距离来源；宽度或位置未知时只能不可判定，不能按 0 米推断在走廊内。
        if (state == null || state.longitude() == null || state.latitude() == null) { dims.put(DIM_CORRIDOR, UNDETERMINED); reasons.add("POSITION_UNKNOWN"); }
        else {
            RouteDistance distance = distances == null ? null : distances.distanceToRoute(plan.routeVersionId());
            if (distance == null || distance.distanceM() == null || distance.halfWidthM() == null) {
                dims.put(DIM_CORRIDOR, UNDETERMINED);
                reasons.add(distance != null && distance.unknownReason() != null && !distance.unknownReason().isBlank() ? distance.unknownReason() : "CORRIDOR_WIDTH_UNKNOWN");
            } else if (distance.distanceM().compareTo(distance.halfWidthM().add(tolerance)) <= 0) dims.put(DIM_CORRIDOR, MATCH);
            else { dims.put(DIM_CORRIDOR, MISMATCH); reasons.add("CORRIDOR_MISMATCH"); }
        }
        // 身份：目标没有 sn 是“线索缺失”而非不匹配（TDOA/5G-A 身份线索尚未接入）。
        String planSn = blankToNull(plan.uavSn());
        if (targetSn == null) { dims.put(DIM_IDENTITY, UNDETERMINED); reasons.add("IDENTITY_CLUE_MISSING"); }
        else if (planSn == null) { dims.put(DIM_IDENTITY, UNDETERMINED); reasons.add("PLAN_IDENTITY_UNKNOWN"); }
        else if (targetSn.equals(planSn)) dims.put(DIM_IDENTITY, MATCH);
        else { dims.put(DIM_IDENTITY, MISMATCH); reasons.add("IDENTITY_MISMATCH"); }
        // 起降点与飞手/单位：数据源尚未接入，恒为未知并带原因码。
        dims.put(DIM_TAKEOFF, UNDETERMINED); reasons.add("TAKEOFF_POINT_UNAVAILABLE");
        dims.put(DIM_PILOT, UNDETERMINED); reasons.add("PILOT_UNIT_UNAVAILABLE");

        PlanMatchCode code;
        boolean mismatch = MISMATCH.equals(dims.get(DIM_TIME)) || MISMATCH.equals(dims.get(DIM_CORRIDOR)) || MISMATCH.equals(dims.get(DIM_IDENTITY));
        boolean core = MATCH.equals(dims.get(DIM_TIME)) && MATCH.equals(dims.get(DIM_CORRIDOR));
        if (mismatch) code = PlanMatchCode.NONE;
        else if (core && MATCH.equals(dims.get(DIM_IDENTITY))) code = PlanMatchCode.FULL;
        else if (core) code = PlanMatchCode.PARTIAL;
        else code = PlanMatchCode.UNDETERMINED;
        return new Candidate(plan, code, Map.copyOf(dims), reasons);
    }

    private static Map<String, String> unavailableDimensions() {
        return Map.of(DIM_TAKEOFF, UNDETERMINED, DIM_PILOT, UNDETERMINED);
    }

    /** 选中计划时为 1；确定无候选时为 0；歧义/不适用时无法从 PlanMatch 还原候选数，保持 null 而不是编造。 */
    private static Integer candidateCount(PlanMatch match, String reason) {
        if (match.plan() != null) return 1;
        if (match.code() == PlanMatchCode.NONE && "NO_PLAN_CANDIDATE".equals(reason)) return 0;
        return null;
    }

    private static int rank(PlanMatchCode code) {
        return switch (code) { case FULL -> 3; case PARTIAL -> 2; case UNDETERMINED -> 1; default -> 0; };
    }

    private static String message(PlanMatch match, String reason, boolean demo) {
        String base = switch (match.code()) {
            case FULL -> "时间窗、走廊与身份均匹配计划 " + match.plan().planId();
            case PARTIAL -> "时间窗与走廊匹配计划 " + match.plan().planId() + "，身份线索缺失";
            case NONE -> "NO_PLAN_CANDIDATE".equals(reason) ? "没有可匹配的飞行计划" : "候选计划维度不匹配（" + reason + "）";
            case UNDETERMINED -> "计划匹配不可判定（" + reason + "）";
            case NOT_APPLICABLE -> "计划匹配不适用";
        };
        return demo ? base + "；参数为 DEMO 演示值，尚未确认" : base;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record Candidate(PlanFact plan, PlanMatchCode code, Map<String, String> dimensions, List<String> reasons) { }
}
