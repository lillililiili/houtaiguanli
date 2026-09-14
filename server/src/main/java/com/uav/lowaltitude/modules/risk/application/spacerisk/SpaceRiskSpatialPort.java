package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * C04/C05 需要的空间事实：目标点到活动计划航线走廊的距离、到机场进离场程序与保护目标的距离。
 * 这些都只能由 PostGIS 计算；H2 上没有可信实现，实现类会报告"后端不可用"而不是返回一个编造的距离。
 */
public interface SpaceRiskSpatialPort {

    /** 空间后端是否可用（PostGIS）。false 时评估记 UNAVAILABLE，不产生任何风险。 */
    boolean available();

    /**
     * 窗口内命中细类字典的异物目标与其最近的活动计划航线。
     * 目标经 target_current_alias 解析到存活目标：被合并的历史目标不应各自再生成一条风险。
     */
    List<SpaceObservation> observations(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes);

    /** C05：目标到机场进离场程序中心线与保护目标的最近距离（米），用于缓冲判定。 */
    List<AirportProximity> airportProximity(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes);

    /**
     * 一个异物目标在窗口内的观测事实。
     * `corridorHalfWidthM` 为 null 表示航线走廊宽度未知——此时不能当作"在走廊外"，由决策表按未知处理。
     */
    record SpaceObservation(String targetId, String targetNo, String subtypeCode, String planId, String routeVersionId,
            BigDecimal distanceToRouteM, BigDecimal corridorHalfWidthM, BigDecimal altitudeM, String altitudeDatum,
            String routeAltitudeDatum, Integer objectCount, String trend, String ownerOrgId, String districtId,
            /* 评估时刻的目标坐标：写入 space_risk_fact 的位置快照（决策 9-19）；缺坐标时为 null，不补零。 */
            BigDecimal longitude, BigDecimal latitude,
            OffsetDateTime observedAt) { }

    /** C05：命中机场进离场缓冲或保护目标半径的目标。 */
    record AirportProximity(String targetId, String subtypeCode, String airportId, String airportName, String planId,
            String routeVersionId, BigDecimal distanceToProcedureM, BigDecimal distanceToProtectedM, BigDecimal altitudeM,
            String altitudeDatum, Integer objectCount, OffsetDateTime observedAt) { }
}
