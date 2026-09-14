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
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;

/**
 * C02-3 航线偏离：目标到中心线距离 − 走廊半宽 > C02-3.tolerance_m 即偏离。距离与 C01 走廊维度同源（同一个 SpatialFactPort）。
 * 没有匹配到计划就没有航线可比，记 NOT_APPLICABLE 而不是 PASS。
 */
@Component
public class RouteDeviationCheck implements RuleCheck {
    static final String PARAM_TOLERANCE_M = "tolerance_m";
    private final SpatialFactPort spatial;

    public RouteDeviationCheck(SpatialFactPort spatial) { this.spatial = spatial; }

    @Override public String ruleCode() { return RuleCodes.C02_3; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_3; }

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        BigDecimal tolerance = params.number(ruleCode(), PARAM_TOLERANCE_M);
        List<ParamRef> refs = List.of(CheckSupport.number(params, ruleCode(), PARAM_TOLERANCE_M));
        PlanFact plan = context.planMatch() == null ? null : context.planMatch().plan();
        if (plan == null) return CheckSupport.notApplicable(ruleCode(), RuleCodes.NO_PLAN, refs, "没有匹配到飞行计划，航线偏离不适用");
        Map<String, Object> facts = CheckSupport.facts();
        facts.put("plan_id", plan.planId());
        facts.put("route_version_id", plan.routeVersionId());
        var evidence = CheckSupport.evidence(CheckSupport.EVIDENCE_ROUTE_VERSION, plan.routeVersionId());
        if (!CheckSupport.positionKnown(context.state())) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.POSITION_UNKNOWN, facts, refs, evidence, "目标位置缺失，无法计算到航线的距离");
        }
        RouteDistance distance = spatial.distanceToRoute(context.state(), plan.routeVersionId());
        if (distance == null || distance.distanceM() == null || distance.halfWidthM() == null) {
            String reason = distance == null || distance.unknownReason() == null || distance.unknownReason().isBlank()
                    ? RuleCodes.CORRIDOR_WIDTH_UNKNOWN : distance.unknownReason();
            return CheckSupport.undetermined(ruleCode(), reason, facts, refs, evidence, "航线走廊宽度或几何缺失，无法判断偏离");
        }
        BigDecimal deviation = distance.distanceM().subtract(distance.halfWidthM());
        facts.put("distance_m", distance.distanceM());
        facts.put("half_width_m", distance.halfWidthM());
        facts.put("deviation_m", deviation);
        if (deviation.compareTo(tolerance) > 0) {
            return CheckSupport.fail(ruleCode(), RuleCodes.ROUTE_DEVIATION, facts, refs, evidence,
                    "目标偏离航线走廊 " + deviation.stripTrailingZeros().toPlainString() + " m，超过容差 " + tolerance.toPlainString() + " m");
        }
        return CheckSupport.pass(ruleCode(), facts, refs, evidence, "目标在航线走廊容差范围内");
    }
}
