package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/**
 * C02-5 夜航：按 C02-5.timezone 换算本地时，本地小时 ∈ [night_from, 24) ∪ [0, night_to) 即夜航。
 * 夜航是行为事实，不依赖计划；时区来自参数而不是服务器默认时区，回放与生产才能得到同一结论。
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
        facts.put("local_time", local.toLocalTime().withNano(0).toString());
        boolean night = hour >= from || hour < to;
        if (night) {
            return CheckSupport.fail(ruleCode(), RuleCodes.NIGHT_FLIGHT, facts, refs, List.of(), "本地时间 " + local.toLocalTime().withNano(0) + " 处于夜航时段");
        }
        return CheckSupport.pass(ruleCode(), facts, refs, List.of(), "本地时间 " + local.toLocalTime().withNano(0) + " 不在夜航时段");
    }
}
