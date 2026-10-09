package com.uav.lowaltitude.modules.assessment.engine;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/** Versioned interpretations of the confirmed rules; absent keys retain published legacy behavior. */
public final class LegalityRulePolicy {
    private LegalityRulePolicy() { }

    public static boolean centerline(RuleParams params, String rule) {
        return params.has(rule, "distance_basis") && "ROUTE_CENTERLINE".equals(params.string(rule, "distance_basis"));
    }

    public static boolean inclusiveEnd(RuleParams params, String rule, String key) {
        return params.has(rule, key) && params.bool(rule, key);
    }

    public static boolean qualityWindow(RuleParams params) {
        return params.has("C03", "quality_window_basis") && "AS_OF".equals(params.string("C03", "quality_window_basis"));
    }

    /** Missing registration is a non-blocking fact only when this rule version explicitly says so. */
    public static boolean noPlanIsPermitted(PlanMatch match, RuleParams params) {
        return match != null && match.code() == PlanMatchCode.NONE
                && "LEGAL".equals(params.string("C03", "no_plan_status"));
    }

    /** A reliably identified plan with only a time mismatch is overrun, not an absent authorization. */
    public static boolean ownPlanTimeMismatch(PlanMatch match, RuleParams params) {
        return params.has("C03", "own_plan_time_mismatch_policy")
                && "OVERRUN".equals(params.string("C03", "own_plan_time_mismatch_policy"))
                && match != null && match.code() == PlanMatchCode.NONE && match.plan() != null
                && "MATCH".equals(match.dimensions().get("identity"))
                && "MATCH".equals(match.dimensions().get("corridor"))
                && "MISMATCH".equals(match.dimensions().get("time_window"));
    }
}
