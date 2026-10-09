package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.LegalityRulePolicy;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/**
 * C02-6 飞手距离（原“超视距”）：算目标与飞手（遥控器）位置的大圆距离，只给值班员参考，不再判违规。
 * 2026-10-08 业务决定（法规核对，确认书 2-10，新-29）：国家规定只有微型无人机必须在视距内飞，其他经批准可以超视距；
 * 系统分不出是不是微型，任务里也没写批没批超视距，所以超过 vlos_m 时照样 PASS，事实里带 beyond_vlos 和
 * “飞手离无人机约 N 米（超过 500 米），是否经批准请核实”这句（pilot_distance_note，目标详情、告警详情也用它），
 * 不出 BVLOS_EXCEEDED、不告警；10-07 那条“只有超视距时判非法、低风险告警”取消。
 * 距离在 Java 侧用 Haversine 算，不走 SpatialFactPort——点到点距离不需要 PostGIS，H2 环境也必须能判；
 * 走廊那类涉及几何图形的判定才必须交给数据库。
 * 没有飞手位置：这一项不判（NOT_APPLICABLE，原因码仍记 PILOT_POSITION_UNAVAILABLE），不进未知原因、不影响结论；
 * 只有飞手位置而目标位置缺失时是 POSITION_UNKNOWN——单边坐标算不出距离，C03 同样不因它挡结论。
 */
@Component
public class VisualLineOfSightCheck implements RuleCheck {
    static final String PARAM_VLOS_M = "vlos_m";
    /** 超过阈值时 facts 里的标记和那句提示：研判页、目标详情、告警详情都据此显示，不在各处各拼一遍。 */
    public static final String FACT_BEYOND_VLOS = "beyond_vlos";
    public static final String FACT_PILOT_DISTANCE_NOTE = "pilot_distance_note";
    /** WGS84 平均地球半径（IUGG）：米制距离用球面近似即可，视距阈值是百米量级，椭球修正意义不大。 */
    private static final double EARTH_RADIUS_M = 6371008.8;
    private static final int METRIC_SCALE = 2;

    @Override public String ruleCode() { return RuleCodes.C02_6; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_6; }

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        BigDecimal threshold = params.number(ruleCode(), PARAM_VLOS_M);
        List<ParamRef> refs = new ArrayList<>(List.of(CheckSupport.number(params, ruleCode(), PARAM_VLOS_M)));
        boolean qualityWindow = LegalityRulePolicy.qualityWindow(params);
        if (qualityWindow) {
            refs.add(new ParamRef("C03.fresh_seconds", Integer.toString(params.integer("C03", "fresh_seconds")), params.paramStatus("C03", "fresh_seconds")));
            refs.add(new ParamRef("C03.quality_window_basis", params.string("C03", "quality_window_basis"), params.paramStatus("C03", "quality_window_basis")));
        }
        TargetState state = context.state();
        if (state == null || state.pilotLongitude() == null || state.pilotLatitude() == null) {
            return CheckSupport.notApplicable(ruleCode(), RuleCodes.PILOT_POSITION_UNAVAILABLE, refs, "没有遥控器位置，飞手距离这一项不判，不影响结论");
        }
        Map<String, Object> facts = CheckSupport.facts();
        // 飞手位置作为一块坐标进 facts（与契约 §6 的 pilot_location 同名），而不是拆成两个平行字段。
        facts.put("pilot_location", Map.of("longitude", state.pilotLongitude(), "latitude", state.pilotLatitude()));
        // 决策 8.5-27 之后飞手位置可以保留自若干帧之前，因此判定依据必须带上它的观测时刻；
        // 历史方法由目标整体新鲜度兜底；确认书版本对本条位置本身也应用 C03 的有效时段。
        facts.put("pilot_observed_at", state.pilotObservedAt());
        var evidence = CheckSupport.evidence(CheckSupport.EVIDENCE_TARGET, state.targetId());
        if (qualityWindow && (context.asOf() == null || state.pilotObservedAt() == null
                || state.pilotObservedAt().isBefore(context.asOf().minusSeconds(params.integer("C03", "fresh_seconds")))
                || state.pilotObservedAt().isAfter(context.asOf()))) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.PILOT_POSITION_UNAVAILABLE, facts, refs, evidence,
                    "遥控器位置缺少有效观测时间或不在本次数据有效时段内，本项不判");
        }
        if (!CheckSupport.positionKnown(state)) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.POSITION_UNKNOWN, facts, refs, evidence,
                    "目标位置缺失，无法计算与飞手的距离");
        }
        BigDecimal distance = greatCircleMetres(state.longitude(), state.latitude(), state.pilotLongitude(), state.pilotLatitude());
        facts.put("distance_m", distance);
        if (distance.compareTo(threshold) > 0) {
            // 距离取整到米、阈值取本版本参数，精确距离仍在 facts.distance_m。结果仍是 PASS：超视距不再算违规（新-29）。
            String note = "飞手离无人机约 " + distance.setScale(0, RoundingMode.HALF_UP).toPlainString()
                    + " 米（超过 " + threshold.stripTrailingZeros().toPlainString() + " 米），是否经批准请核实";
            facts.put("vlos_m", threshold);
            facts.put(FACT_BEYOND_VLOS, true);
            facts.put(FACT_PILOT_DISTANCE_NOTE, note);
            return CheckSupport.pass(ruleCode(), facts, refs, evidence, note);
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
