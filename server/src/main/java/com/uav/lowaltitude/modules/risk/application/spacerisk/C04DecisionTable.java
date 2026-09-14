package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.math.BigDecimal;
import java.util.List;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/**
 * C04 空中异物规则的决策表：纯 Java、无 Spring、不依赖数据库或几何后端。
 * 输入是已经算好的事实（走廊关系、高度带、有无活动计划、数量、趋势），输出是否生成风险、等级与原因码。
 * 所有阈值只经阶段 7 的 {@link RuleParams} 从 rule_param 读取；本类不得出现任何阈值字面量——
 * 参数缺失是部署错误，直接抛 IllegalStateException，不用代码默认值把"未配置"伪装成一次正常判定。
 */
public final class C04DecisionTable {
    public static final String RULE_CODE = "C04";
    public static final String REASON_CORRIDOR_INTRUSION = "SPACE_OBJECT_IN_CORRIDOR";
    public static final String REASON_ALTITUDE_UNKNOWN = "SPACE_OBJECT_ALTITUDE_UNKNOWN";
    public static final String REASON_NEAR_ROUTE = "SPACE_OBJECT_NEAR_ROUTE";
    /** 决策 9-18：数量与趋势当前没有数据源；缺失时不上调等级，并如实记录"这项事实没有"。 */
    public static final String UNKNOWN_OBJECT_COUNT = "OBJECT_COUNT_UNAVAILABLE";
    public static final String UNKNOWN_TREND = "TREND_UNAVAILABLE";
    /** 等级阶梯：上调一级即在本表内右移一位，最高到 CRITICAL。 */
    private static final List<String> LADDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    public enum CorridorRelation { INSIDE, NEAR, OUTSIDE, UNKNOWN }
    public enum AltitudeBand { CLIMB, APPROACH, CRUISE, UNKNOWN }
    public enum Trend { RISING, FLAT, FALLING, UNKNOWN }

    /** 一次判定的输入事实；`objectCount` 为 null 表示数量未知（不参与上调）。 */
    public record Observation(CorridorRelation corridorRelation, AltitudeBand altitudeBand, boolean activePlan,
            Integer objectCount, Trend trend) { }

    /**
     * 判定结果；`generate=false` 时等级与原因码为 null——不生成风险就没有等级可言。
     * `unknownReasons` 无论是否生成都要给：它说明本次判定缺了哪些事实。
     */
    public record Decision(boolean generate, String severity, String reasonCode, boolean escalated, List<String> unknownReasons) {
        static Decision none(List<String> unknownReasons) { return new Decision(false, null, null, false, unknownReasons); }
    }

    public Decision decide(Observation observation, RuleParams params) {
        // 先读一次参数：即使本次判定用不到阈值（例如无计划直接返回），参数缺失也必须立刻暴露，
        // 否则"没生成风险"会被误读成业务结论，而不是配置缺失。
        int flockThreshold = params.integer(RULE_CODE, "flock_count_threshold");
        List<String> unknown = unknownReasons(observation);
        // 无活动计划就没有被威胁的飞行活动：只计入 targets_seen，不生成风险。
        // flight_risk.plan_id 非空，没有计划的"异物"没有可挂靠的业务对象，硬造一条会污染风险队列。
        if (!observation.activePlan()) return Decision.none(unknown);
        String base = baseSeverity(observation);
        if (base == null) return Decision.none(unknown);
        boolean escalate = escalates(observation, flockThreshold);
        return new Decision(true, escalate ? escalate(base) : base, reasonCode(observation), escalate, unknown);
    }

    private static String baseSeverity(Observation observation) {
        return switch (observation.corridorRelation()) {
            // 走廊内 + 同基准高度落在起降/进近带 → 高：这是"异物与飞行活动在同一空间"的完整证据。
            case INSIDE -> inBand(observation.altitudeBand()) ? "HIGH" : "MEDIUM";
            // 邻近航线：还没进走廊，但已在参数给定的距离内。
            case NEAR -> "MEDIUM";
            case OUTSIDE, UNKNOWN -> null;
        };
    }

    private static boolean inBand(AltitudeBand band) {
        return band == AltitudeBand.CLIMB || band == AltitudeBand.APPROACH;
    }

    private static String reasonCode(Observation observation) {
        if (observation.corridorRelation() == CorridorRelation.NEAR) return REASON_NEAR_ROUTE;
        // 走廊内但高度带未知：原因码要说清"为什么只到中风险"，否则页面无法解释这条与高风险的差别。
        return observation.altitudeBand() == AltitudeBand.UNKNOWN ? REASON_ALTITUDE_UNKNOWN : REASON_CORRIDOR_INTRUSION;
    }

    /**
     * 决策 9-18：数量与趋势缺失时如实记录，不猜测也不上调。
     * "不知道有多少只"与"确认少于阈值"是两回事，后者才是不上调的业务结论。
     */
    private static List<String> unknownReasons(Observation observation) {
        List<String> unknown = new java.util.ArrayList<>();
        if (observation.objectCount() == null) unknown.add(UNKNOWN_OBJECT_COUNT);
        if (observation.trend() == null || observation.trend() == Trend.UNKNOWN) unknown.add(UNKNOWN_TREND);
        return List.copyOf(unknown);
    }

    /** 数量达到阈值或趋势上升 → 上调一级；两者同时命中也只上调一级（阶梯是等级，不是分数）。 */
    private static boolean escalates(Observation observation, int flockThreshold) {
        boolean byCount = observation.objectCount() != null && observation.objectCount() >= flockThreshold;
        return byCount || observation.trend() == Trend.RISING;
    }

    private static String escalate(String severity) {
        int index = LADDER.indexOf(severity);
        if (index < 0) return severity;
        return LADDER.get(Math.min(index + 1, LADDER.size() - 1));
    }

    /**
     * 高度带：只有目标高度基准与航线/计划高度基准相同才分带。
     * AGL 与 AMSL 之间没有可信换算（需要地形高程），互比会把"离地 100 m"当成"海拔 100 m"，
     * 因此基准不同或任一缺失一律 UNKNOWN，由 {@link #decide} 降到中风险。
     */
    public AltitudeBand band(BigDecimal altitude, String altitudeDatum, String referenceDatum, RuleParams params) {
        if (altitude == null || altitudeDatum == null || referenceDatum == null || !altitudeDatum.equals(referenceDatum)) {
            return AltitudeBand.UNKNOWN;
        }
        BigDecimal climb = params.number(RULE_CODE, "climb_band_agl_m");
        BigDecimal approach = params.number(RULE_CODE, "approach_band_agl_m");
        if (altitude.compareTo(climb) < 0) return AltitudeBand.CLIMB;
        if (altitude.compareTo(approach) < 0) return AltitudeBand.APPROACH;
        return AltitudeBand.CRUISE;
    }

    /** 走廊关系：距离 ≤ 走廊半宽即 INSIDE，≤ corridor_near_m 即 NEAR，其余 OUTSIDE；距离未知则 UNKNOWN。 */
    public CorridorRelation relation(BigDecimal distanceM, BigDecimal corridorHalfWidthM, RuleParams params) {
        if (distanceM == null) return CorridorRelation.UNKNOWN;
        if (corridorHalfWidthM != null && distanceM.compareTo(corridorHalfWidthM) <= 0) return CorridorRelation.INSIDE;
        return distanceM.compareTo(params.number(RULE_CODE, "corridor_near_m")) <= 0 ? CorridorRelation.NEAR : CorridorRelation.OUTSIDE;
    }
}
