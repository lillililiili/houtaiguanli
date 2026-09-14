package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/** C02-4 时间窗：as_of ≥ end_at + grace 或 as_of < start_at − grace 即超出计划时窗；计划无时间记 PLAN_TIME_UNKNOWN。 */
@Component
public class TimeWindowCheck implements RuleCheck {
    static final String PARAM_GRACE_MIN = "grace_min";

    @Override public String ruleCode() { return RuleCodes.C02_4; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_4; }

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        int grace = params.integer(ruleCode(), PARAM_GRACE_MIN);
        List<ParamRef> refs = List.of(CheckSupport.integer(params, ruleCode(), PARAM_GRACE_MIN));
        PlanFact plan = context.planMatch() == null ? null : context.planMatch().plan();
        if (plan == null) return CheckSupport.notApplicable(ruleCode(), RuleCodes.NO_PLAN, refs, "没有匹配到飞行计划，时间窗不适用");
        Map<String, Object> facts = CheckSupport.facts();
        facts.put("plan_id", plan.planId());
        var evidence = CheckSupport.evidence(CheckSupport.EVIDENCE_FLIGHT_PLAN, plan.planId());
        OffsetDateTime asOf = context.asOf();
        if (plan.startAt() == null || plan.endAt() == null || asOf == null) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.PLAN_TIME_UNKNOWN, facts, refs, evidence, "计划起止时间缺失，无法判断是否超出时窗");
        }
        facts.put("plan_start_at", plan.startAt().toInstant().toEpochMilli());
        facts.put("plan_end_at", plan.endAt().toInstant().toEpochMilli());
        facts.put("as_of", asOf.toInstant().toEpochMilli());
        boolean late = !asOf.isBefore(plan.endAt().plusMinutes(grace));
        boolean early = asOf.isBefore(plan.startAt().minusMinutes(grace));
        if (late || early) {
            facts.put("overrun_side", late ? "AFTER_END" : "BEFORE_START");
            return CheckSupport.fail(ruleCode(), RuleCodes.TIME_WINDOW_OVERRUN, facts, refs, evidence,
                    late ? "飞行时刻已超过计划结束时间加宽限 " + grace + " 分钟" : "飞行时刻早于计划开始时间减宽限 " + grace + " 分钟");
        }
        return CheckSupport.pass(ruleCode(), facts, refs, evidence, "飞行时刻在计划时窗（含宽限）内");
    }
}
