package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.uav.lowaltitude.modules.assessment.engine.C03Decision.Decision;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;

/**
 * 对 C01–C03 的本次结论计算证据充分性，和风险严重度、统计准确率分开。
 * 缺计划不是无授权的证明；明确空域违规可独立于计划身份成立。所有质量阈值沿用本次规则参数。
 * 只使用本次快照，不读库、不产生通知、不代替动作授权。
 */
public final class DecisionAssuranceAlgorithm {
    public static final String VERSION = "EVIDENCE_SUFFICIENCY_V1";
    public static final String SUFFICIENT = "SUFFICIENT", INSUFFICIENT = "INSUFFICIENT", NOT_APPLICABLE = "NOT_APPLICABLE";
    private static final List<String> REQUIRED_CHECKS = List.of("C01", "C02-1", "C02-2", "C02-3", "C02-4", "C02-5", "C02-6", "C02-7", "C02-8");
    private static final Set<String> AIRSPACE_REASONS = Set.of(RuleCodes.INSIDE_RESTRICTED_AIRSPACE,
            RuleCodes.AIRSPACE_ALTITUDE_EXCEEDED, RuleCodes.TEMPORARY_RESTRICTION_ACTIVE);

    public Assurance assess(EvaluationContext context, List<HitDetail> hits, Decision verdict, RuleParams params) {
        List<HitDetail> details = hits == null ? List.of() : hits;
        Set<String> reasons = new LinkedHashSet<>();
        if (verdict.status() == LegalStatus.NOT_APPLICABLE) {
            return new Assurance(VERSION, NOT_APPLICABLE, verdict.unknownReasons());
        }
        if (context == null || context.state() == null) return insufficient(Set.of(RuleCodes.NO_STATE));
        TargetState state = context.state();
        if (context.freshness() != Freshness.FRESH && context.freshness() != Freshness.REPLAY) reasons.add(RuleCodes.STATE_STALE);
        if (state.observedAt() == null) reasons.add(RuleCodes.NO_STATE);
        if (state.longitude() == null || state.latitude() == null) reasons.add(RuleCodes.POSITION_UNKNOWN);
        BigDecimal confidence = state.confidence();
        if (confidence == null) reasons.add(RuleCodes.CONFIDENCE_UNKNOWN);
        else if (confidence.compareTo(params.number("C03", "conf_min")) < 0) reasons.add(RuleCodes.LOW_CONFIDENCE);
        var track = context.track();
        if (track == null || track.pointCount() < params.integer("C03", "min_points")) reasons.add(RuleCodes.TRACK_DEGRADED);
        if (track != null && (track.bridged() || (track.maxGapSeconds() != null && track.maxGapSeconds() > params.integer("C03", "gap_seconds")))) {
            reasons.add(RuleCodes.TRACK_BRIDGED);
        }
        // DEMO 可以验证明确标记的模拟场景，不能自动认定真实观测；不把 DEMO 改成 CONFIRMED。
        if ("live".equals(context.sourceMode())) {
            for (String key : List.of("fresh_seconds", "track_points", "conf_min", "min_points", "gap_seconds")) {
                parameterReason(params.paramStatus("C03", key), reasons);
            }
        }
        if (!reasons.isEmpty()) return insufficient(reasons);

        // 数据质量合格且有已判明的禁飞、限高或临管事实，才可独立确认 ILLEGAL。
        // 其他检查里的非决定性未知仍保留在原 unknown_reasons，不强迫人重复确认该明确违规。
        List<HitDetail> decisiveHits = details.stream().filter(hit -> RuleCodes.AIRSPACE_CHECKS.contains(hit.ruleCode())
                && hit.resultCode() == ResultCode.FAIL && hit.reasonCode() != null && AIRSPACE_REASONS.contains(hit.reasonCode())).toList();
        if (verdict.status() == LegalStatus.ILLEGAL && !decisiveHits.isEmpty()) {
            // 任何一项参数已确认的明确违规即可成立；与该事实无关的计划/其他空域检查不扩大复核范围。
            for (HitDetail hit : decisiveHits) {
                Set<String> parameterIssues = new LinkedHashSet<>();
                if ("live".equals(context.sourceMode())) checkParameters(hit, parameterIssues);
                if (parameterIssues.isEmpty()) return sufficient();
                reasons.addAll(parameterIssues);
            }
            return insufficient(reasons);
        }
        if ("live".equals(context.sourceMode())) {
            parameterReason(params.paramStatus("C03", "ignore_undetermined_rules"), reasons);
            for (HitDetail hit : details) checkParameters(hit, reasons);
        }

        PlanMatch match = context.planMatch();
        if (match == null || match.code() == PlanMatchCode.NONE || match.code() == PlanMatchCode.NOT_APPLICABLE) {
            reasons.add("PLAN_AUTHORIZATION_UNVERIFIED");
        } else if (match.code() != PlanMatchCode.FULL) {
            reasons.addAll(match.reasonCodes().isEmpty() ? List.of(RuleCodes.PLAN_MATCH_UNDETERMINED) : match.reasonCodes());
        } else if (match.plan() == null || match.plan().uavSn() == null || match.plan().uavSn().isBlank()) {
            reasons.add("PLAN_IDENTITY_UNKNOWN");
        } else if (state.uavSn() == null || state.uavSn().isBlank()) {
            reasons.add("IDENTITY_CLUE_MISSING");
        } else if (!state.uavSn().equals(match.plan().uavSn())) {
            reasons.add("IDENTITY_MISMATCH");
        }
        Set<String> present = new LinkedHashSet<>();
        List<String> ignored = params.list("C03", "ignore_undetermined_rules");
        for (HitDetail hit : details) {
            present.add(hit.ruleCode());
            if (hit.resultCode() == ResultCode.UNDETERMINED && !ignored.contains(hit.ruleCode())) {
                reasons.add(hit.reasonCode() == null ? "DECISIVE_EVIDENCE_MISSING" : hit.reasonCode());
            }
        }
        if (!present.containsAll(REQUIRED_CHECKS)) reasons.add("RULE_CHECKS_INCOMPLETE");
        if (verdict.status() == LegalStatus.ABNORMAL) reasons.add("BINARY_CONCLUSION_UNRESOLVED");
        if (verdict.status() == LegalStatus.UNDETERMINED) reasons.addAll(verdict.unknownReasons());
        if (verdict.status() != LegalStatus.LEGAL && reasons.isEmpty()) reasons.add("DECISIVE_EVIDENCE_MISSING");
        // 即使调用方给出了 LEGAL，也不能接受与单项 FAIL 冲突的结论。
        if (verdict.status() == LegalStatus.LEGAL && details.stream().anyMatch(hit -> hit.resultCode() == ResultCode.FAIL)) reasons.add("DECISIVE_EVIDENCE_MISSING");
        return reasons.isEmpty() ? sufficient() : insufficient(reasons);
    }

    private static void checkParameters(HitDetail hit, Set<String> reasons) {
        for (ParamRef ref : hit.params() == null ? List.<ParamRef>of() : hit.params()) parameterReason(ref.status(), reasons);
    }

    private static void parameterReason(String status, Set<String> reasons) {
        if ("DEMO".equals(status)) reasons.add("DEMO_RULE_PARAMETERS");
        else if (!"CONFIRMED".equals(status)) reasons.add("PARAMETER_STATUS_UNKNOWN");
    }
    private static Assurance sufficient() { return new Assurance(VERSION, SUFFICIENT, List.of()); }
    private static Assurance insufficient(Set<String> reasons) { return new Assurance(VERSION, INSUFFICIENT, List.copyOf(reasons)); }
    public record Assurance(String algorithmVersion, String status, List<String> reasons) {
        public Assurance { reasons = List.copyOf(reasons); }
    }
}
