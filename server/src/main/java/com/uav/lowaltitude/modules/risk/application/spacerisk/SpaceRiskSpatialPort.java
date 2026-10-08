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
     * 窗口内命中细类字典的异物目标与待执行/执行中计划的航线。
     * 目标经 target_current_alias 解析到存活目标：被合并的历史目标不应各自再生成一条风险。
     */
    List<SpaceObservation> observations(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes);

    /**
     * 定时评估用：最新状态在 [refreshedFrom, refreshedTo) 内被写入过（服务器处理时间）、且观测时刻不早于 observedSince 的异物目标。
     * 按观测时刻切定时窗口时，融合积压或设备时钟偏差会让最新状态落进已经算过的窗口，目标被静默跳过（BUG-17）；
     * 按写入时刻切，每次写入都至少被一轮评估看到。其余口径与 {@link #observations} 相同。
     */
    List<SpaceObservation> refreshedObservations(OffsetDateTime refreshedFrom, OffsetDateTime refreshedTo, OffsetDateTime observedSince);

    /** C05：目标到机场进离场程序中心线与保护目标的最近距离（米），用于缓冲判定。 */
    List<AirportProximity> airportProximity(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes);

    /**
     * 定时 C05（2026-10-08 确认书 4-3，新-27）：窗口口径与 {@link #refreshedObservations} 相同，按最新状态的写入时刻切。
     * 只关联待执行/执行中、且时段（前后放宽 planWindowPadMinutes）盖住这次观测时刻的计划：任务时段外的异物只计数，不生成任务风险。
     */
    List<AirportProximity> refreshedAirportProximity(OffsetDateTime refreshedFrom, OffsetDateTime refreshedTo, OffsetDateTime observedSince,
            int planWindowPadMinutes);

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
