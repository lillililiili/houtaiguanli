package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.LegalityRulePolicy;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;

/**
 * C02-3 航线偏离：确认版直接比较中心线距离，历史版先减走廊半宽，再与 tolerance_m 比较。距离与 C01 同源。
 * 没有匹配到计划就没有航线可比，记 NOT_APPLICABLE 而不是 PASS。
 * 对不上任务（C01 NONE）时 PlanMatch 里也许挂着本机自己的计划（只给时间窗、高度参考），同样不再拿它比偏航：
 * 没有任务就谈不上偏离任务航线，原因只写无飞行授权（2026-10-08 验收预跑 2-1，新-15）。
 * 走廊外 C02-3 容差到 C01 走廊容差之间仍对得上任务，照常判偏航；再往外才是对不上任务。
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
        List<ParamRef> refs = new ArrayList<>(List.of(CheckSupport.number(params, ruleCode(), PARAM_TOLERANCE_M)));
        boolean centerline = LegalityRulePolicy.centerline(params, ruleCode());
        if (params.has(ruleCode(), "distance_basis")) refs.add(CheckSupport.string(params, ruleCode(), "distance_basis"));
        PlanMatch match = context.planMatch();
        PlanFact plan = match == null || match.code() == PlanMatchCode.NONE ? null : match.plan();
        if (plan == null) return CheckSupport.notApplicable(ruleCode(), RuleCodes.NO_PLAN, refs, "没有匹配到飞行任务，航线偏离不适用");
        Map<String, Object> facts = CheckSupport.facts();
        facts.put("plan_id", plan.planId());
        facts.put("route_version_id", plan.routeVersionId());
        var evidence = CheckSupport.evidence(CheckSupport.EVIDENCE_ROUTE_VERSION, plan.routeVersionId());
        if (!CheckSupport.positionKnown(context.state())) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.POSITION_UNKNOWN, facts, refs, evidence, "目标位置缺失，无法计算到航线的距离");
        }
        RouteDistance distance = spatial.distanceToRoute(context.state(), plan.routeVersionId());
        if (distance == null || distance.distanceM() == null || (!centerline && distance.halfWidthM() == null)) {
            String reason = distance == null || distance.unknownReason() == null || distance.unknownReason().isBlank()
                    ? RuleCodes.CORRIDOR_WIDTH_UNKNOWN : distance.unknownReason();
            return CheckSupport.undetermined(ruleCode(), reason, facts, refs, evidence, "航线走廊宽度或几何缺失，无法判断偏离");
        }
        BigDecimal deviation = centerline ? distance.distanceM() : distance.distanceM().subtract(distance.halfWidthM());
        facts.put("distance_m", distance.distanceM());
        facts.put("half_width_m", distance.halfWidthM());
        facts.put("deviation_m", deviation);
        facts.put("distance_basis", centerline ? "ROUTE_CENTERLINE" : "CORRIDOR_EDGE");
        if (deviation.compareTo(tolerance) > 0) {
            return CheckSupport.fail(ruleCode(), RuleCodes.ROUTE_DEVIATION, facts, refs, evidence,
                    "目标偏离" + (centerline ? "航线中心线 " : "航线走廊 ") + deviation.stripTrailingZeros().toPlainString() + " m，超过容差 " + tolerance.toPlainString() + " m");
        }
        return CheckSupport.pass(ruleCode(), facts, refs, evidence, centerline ? "目标在航线中心线容差范围内" : "目标在航线走廊容差范围内");
    }
}
