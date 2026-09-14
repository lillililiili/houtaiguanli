package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
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

/**
 * C02-7 计划高度：目标同基准高度 > route_version.max_altitude_m 或 < min_altitude_m 即越界。
 * 高度带与目标高度基准必须一致（route_version.altitude_datum），缺一即未知；无计划则不适用。本规则没有阈值参数。
 */
@Component
public class PlanAltitudeCheck implements RuleCheck {
    @Override public String ruleCode() { return RuleCodes.C02_7; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_7; }

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        List<ParamRef> refs = List.of();
        PlanFact plan = context.planMatch() == null ? null : context.planMatch().plan();
        if (plan == null) return CheckSupport.notApplicable(ruleCode(), RuleCodes.NO_PLAN, refs, "没有匹配到飞行计划，计划高度不适用");
        Map<String, Object> facts = CheckSupport.facts();
        facts.put("plan_id", plan.planId());
        facts.put("route_version_id", plan.routeVersionId());
        facts.put("altitude_datum", plan.altitudeDatum());
        facts.put("min_altitude_m", plan.minAltitudeM());
        facts.put("max_altitude_m", plan.maxAltitudeM());
        var evidence = CheckSupport.evidence(CheckSupport.EVIDENCE_ROUTE_VERSION, plan.routeVersionId());
        if (plan.altitudeDatum() == null || plan.minAltitudeM() == null || plan.maxAltitudeM() == null) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.ALTITUDE_DATUM_OR_RANGE_UNKNOWN, facts, refs, evidence, "计划航线没有高度带或高度基准，无法比较");
        }
        BigDecimal altitude = CheckSupport.altitudeOn(context.state(), plan.altitudeDatum());
        if (altitude == null) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.ALTITUDE_DATUM_OR_RANGE_UNKNOWN, facts, refs, evidence,
                    "目标缺少 " + plan.altitudeDatum() + " 基准高度，AGL 与 AMSL 不互比");
        }
        facts.put("target_altitude_m", altitude);
        if (altitude.compareTo(plan.maxAltitudeM()) > 0 || altitude.compareTo(plan.minAltitudeM()) < 0) {
            return CheckSupport.fail(ruleCode(), RuleCodes.PLAN_ALTITUDE_EXCEEDED, facts, refs, evidence,
                    "目标高度 " + altitude.stripTrailingZeros().toPlainString() + " m（" + plan.altitudeDatum() + "）超出计划高度带 "
                            + plan.minAltitudeM().stripTrailingZeros().toPlainString() + "–" + plan.maxAltitudeM().stripTrailingZeros().toPlainString() + " m");
        }
        return CheckSupport.pass(ruleCode(), facts, refs, evidence, "目标高度在计划高度带内");
    }
}
