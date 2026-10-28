package com.uav.lowaltitude.modules.flightavoidance.domain;

import java.math.BigDecimal;

/**
 * 反制前检查附近航班用的距离和高度（规则管理「航班避让」）。
 * 迫降会打开卫星导航频段，设禁止反制范围和提醒范围；驱离不碰卫星导航频段，只设提醒范围。
 */
public record FlightAvoidancePolicy(long version, BigDecimal forceLandBlockKm, BigDecimal forceLandWarnKm,
        int forceLandMaxAltitudeM, BigDecimal driveAwayWarnKm, int driveAwayMaxAltitudeM, long updatedAt) {

    /** 按飞机现在的速度和方向，这么多秒内会飞进范围的也算。 */
    public static final int APPROACH_SECONDS = 300;
    /**
     * 接收机超过这么久没送来任何报文（航班或状态），就算收不到航班信息。
     * 附近没有航班时接收机只有每 30 秒一条状态报文，写库最多再晚 1 秒，所以留两个周期：
     * 原来按 30 秒算，天上没飞机时每隔半分钟就会闪一次「收不到航班信息」（2026-10-28 用户「改后台」）。
     */
    public static final long DATA_STALE_MILLIS = 60_000L;
    /** 一架航班超过这么久没再报位置（正常每秒一条）就不算在附近，地图上也不再画。 */
    public static final long AIRCRAFT_STALE_MILLIS = 30_000L;
    /** 设备模拟器的接收机 10 分钟没送数据就当模拟已结束，不再参与检查。 */
    public static final long SIMULATED_QUIET_MILLIS = 10 * 60_000L;

    public Zones zones(CounterMode mode) {
        return mode == CounterMode.FORCE_LAND
                ? new Zones(forceLandBlockKm.doubleValue(), forceLandWarnKm.doubleValue(), forceLandMaxAltitudeM)
                : new Zones(0, driveAwayWarnKm.doubleValue(), driveAwayMaxAltitudeM);
    }

    /** blockKm 为 0 表示不设禁止反制范围。 */
    public record Zones(double blockKm, double warnKm, int maxAltitudeM) { }

    public enum CounterMode { FORCE_LAND, DRIVE_AWAY }
}
