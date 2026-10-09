package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.NoPlanExemption;
import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/**
 * C02-5 夜航：按 C02-5.timezone 换算本地时，本地小时 ∈ [night_from, 24) ∪ [0, night_to) 即夜航时段。
 * 夜航时段内飞行是否违规看计划：C01 已匹配上计划（FULL/PARTIAL，时间窗按 C01 的计划时段与容差已对上），
 * 说明这段夜间飞行已经报备，判通过；没有计划、计划不明或已超出计划时段（C01 对不上）的夜间飞行仍记 NIGHT_FLIGHT。
 * 计划时段的容差与白天一致，超出宽限的超时仍由 C02-4 单独判。时区来自参数而不是服务器默认时区，回放与生产才能得到同一结论。
 * 夜间只在原有违规上加注（确认书 2-9）：完全没有报备任务、离地 120 米及以下、不在管控空域里的飞行按规定无需申请
 * （{@link NoPlanExemption}，新-28），没有原有违规，夜航判通过并写明原因。
 */
@Component
public class NightFlightCheck implements RuleCheck {
    static final String PARAM_TIMEZONE = "timezone";
    static final String PARAM_NIGHT_FROM = "night_from";
    static final String PARAM_NIGHT_TO = "night_to";

    @Override public String ruleCode() { return RuleCodes.C02_5; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_5; }

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        String timezone = params.string(ruleCode(), PARAM_TIMEZONE);
        int from = params.integer(ruleCode(), PARAM_NIGHT_FROM);
        int to = params.integer(ruleCode(), PARAM_NIGHT_TO);
        List<ParamRef> refs = List.of(CheckSupport.string(params, ruleCode(), PARAM_TIMEZONE),
                CheckSupport.integer(params, ruleCode(), PARAM_NIGHT_FROM), CheckSupport.integer(params, ruleCode(), PARAM_NIGHT_TO));
        ZoneId zone;
        try { zone = ZoneId.of(timezone); }
        catch (DateTimeException ex) { throw new IllegalStateException("规则参数格式无效: " + ruleCode() + "." + PARAM_TIMEZONE + " 不是有效时区", ex); }
        if (context.asOf() == null) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.STATE_STALE, CheckSupport.facts(), refs, List.of(), "评估时刻缺失，无法判断夜航");
        }
        ZonedDateTime local = context.asOf().atZoneSameInstant(zone);
        int hour = local.getHour();
        Map<String, Object> facts = CheckSupport.facts();
        facts.put("timezone", timezone);
        facts.put("local_hour", hour);
        String time = local.toLocalTime().withNano(0).toString();
        facts.put("local_time", time);
        boolean night = hour >= from || hour < to;
        if (!night) return CheckSupport.pass(ruleCode(), facts, refs, List.of(), "本地时间 " + time + " 不在夜航时段");
        PlanFact plan = matchedPlan(context.planMatch());
        if (plan == null && NoPlanExemption.applies(context, params)) {
            return CheckSupport.pass(ruleCode(), facts, refs, List.of(), "本地时间 " + time
                    + " 处于夜航时段；没有报备任务、离地 120 米以下的普通区域飞行按规定无需申请，夜间不单独算违规");
        }
        if (plan == null) {
            String why = context.planMatch() != null && context.planMatch().plan() != null ? "已超出本机飞行任务的时段或航线" : "没有匹配上的飞行任务";
            return CheckSupport.fail(ruleCode(), RuleCodes.NIGHT_FLIGHT, facts, refs, List.of(), "本地时间 " + time + " 处于夜航时段，" + why);
        }
        facts.put("plan_id", plan.planId());
        return CheckSupport.pass(ruleCode(), facts, refs, CheckSupport.evidence(CheckSupport.EVIDENCE_FLIGHT_PLAN, plan.planId()),
                "本地时间 " + time + " 处于夜航时段，已匹配上飞行任务，在任务时段内飞行");
    }

    /** 只认 C01 已匹配上的计划；NONE 时挂着的本机计划、计划不明（UNDETERMINED）都不能为夜间飞行作保。 */
    private static PlanFact matchedPlan(PlanMatch match) {
        if (match == null || match.plan() == null) return null;
        return match.code() == PlanMatchCode.FULL || match.code() == PlanMatchCode.PARTIAL ? match.plan() : null;
    }
}
