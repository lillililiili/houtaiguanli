package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/**
 * C02-6 超视距：目标与飞手（遥控器）位置的大圆距离超过阈值即判超视距。
 * 距离在 Java 侧用 Haversine 算，不走 SpatialFactPort——点到点距离不需要 PostGIS，H2 环境也必须能判；
 * 走廊那类涉及几何图形的判定才必须交给数据库。
 * 飞手位置缺失仍是 PILOT_POSITION_UNAVAILABLE（阶段 8.5 之前恒定如此，C03 默认经 ignore_undetermined_rules 忽略）；
 * 只有飞手位置而目标位置缺失时是 POSITION_UNKNOWN——单边坐标算不出距离，也不能当成"没接入"。
 */
@Component
public class VisualLineOfSightCheck implements RuleCheck {
    static final String PARAM_VLOS_M = "vlos_m";
    /** WGS84 平均地球半径（IUGG）：米制距离用球面近似即可，视距阈值是百米量级，椭球修正意义不大。 */
    private static final double EARTH_RADIUS_M = 6371008.8;
    private static final int METRIC_SCALE = 2;

    @Override public String ruleCode() { return RuleCodes.C02_6; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_6; }

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        BigDecimal threshold = params.number(ruleCode(), PARAM_VLOS_M);
        List<ParamRef> refs = List.of(CheckSupport.number(params, ruleCode(), PARAM_VLOS_M));
        TargetState state = context.state();
        if (state == null || state.pilotLongitude() == null || state.pilotLatitude() == null) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.PILOT_POSITION_UNAVAILABLE, CheckSupport.facts(), refs, List.of(),
                    "飞手位置缺失，无法判断是否超视距");
        }
        Map<String, Object> facts = CheckSupport.facts();
        // 飞手位置作为一块坐标进 facts（与契约 §6 的 pilot_location 同名），而不是拆成两个平行字段。
        facts.put("pilot_location", Map.of("longitude", state.pilotLongitude(), "latitude", state.pilotLatitude()));
        // 决策 8.5-27 之后飞手位置可以保留自若干帧之前，因此判定依据必须带上它的观测时刻；
        // 本期不设独立过期阈值，由目标整体新鲜度兜底（8.5-28），但"有多旧"要让读的人看得见。
        facts.put("pilot_observed_at", state.pilotObservedAt());
        var evidence = CheckSupport.evidence(CheckSupport.EVIDENCE_TARGET, state.targetId());
        if (!CheckSupport.positionKnown(state)) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.POSITION_UNKNOWN, facts, refs, evidence,
                    "目标位置缺失，无法计算与飞手的距离");
        }
        BigDecimal distance = greatCircleMetres(state.longitude(), state.latitude(), state.pilotLongitude(), state.pilotLatitude());
        facts.put("distance_m", distance);
        if (distance.compareTo(threshold) > 0) {
            return CheckSupport.fail(ruleCode(), RuleCodes.BVLOS_EXCEEDED, facts, refs, evidence,
                    "目标距飞手 " + distance.toPlainString() + " m，超过视距阈值 " + threshold.toPlainString() + " m");
        }
        return CheckSupport.pass(ruleCode(), facts, refs, evidence,
                "目标距飞手 " + distance.toPlainString() + " m，在视距阈值内");
    }

    /** Haversine 大圆距离（米）。经纬度已由调用方保证非空。 */
    private static BigDecimal greatCircleMetres(BigDecimal lon1, BigDecimal lat1, BigDecimal lon2, BigDecimal lat2) {
        double phi1 = Math.toRadians(lat1.doubleValue());
        double phi2 = Math.toRadians(lat2.doubleValue());
        double deltaPhi = Math.toRadians(lat2.subtract(lat1).doubleValue());
        double deltaLambda = Math.toRadians(lon2.subtract(lon1).doubleValue());
        double a = Math.pow(Math.sin(deltaPhi / 2), 2) + Math.cos(phi1) * Math.cos(phi2) * Math.pow(Math.sin(deltaLambda / 2), 2);
        double metres = 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
        return BigDecimal.valueOf(metres).setScale(METRIC_SCALE, RoundingMode.HALF_UP);
    }
}
