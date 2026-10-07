package com.uav.lowaltitude.modules.dashboard.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.GeoJsonMultiPolygonDto;

/** 数据大屏快照：无权限块的计数必须显式 null，不能省略成“看起来像没有数据”。 */
public final class DashboardDtos {
    private DashboardDtos() { }

    public record SnapshotDto(
            long asOf,
            Map<String, String> availability,
            KpisDto kpis,
            SimulatedIncludedDto simulatedIncluded,
            /* 计数计入哪些来源（StatisticsScope）：允许模拟的环境为 [live, replay]，正式环境为 [live]。页面据此写口径说明。 */
            List<String> statisticsSourceModes,
            @JsonInclude(JsonInclude.Include.ALWAYS) TrendDto trend,
            @JsonInclude(JsonInclude.Include.ALWAYS) TargetRiskDto targetRisk,
            ClosureDto closure,
            @JsonInclude(JsonInclude.Include.ALWAYS) DevicesDto devices,
            @JsonInclude(JsonInclude.Include.ALWAYS) FlightsDto flights,
            AlarmsDto alarms,
            MapDto map) { }

    public record KpisDto(
            @JsonInclude(JsonInclude.Include.ALWAYS) Long sensedToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long alarmsToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long pendingAssessment,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long pendingHandoffs) { }

    /**
     * 统计卡里来自设备模拟器的条数（2026-10-07 起模拟器的数据也计入统计，见 StatisticsScope）。
     * 页面用它写明"其中来自设备模拟器 N 条"，免得把模拟数据当成现场真实数据。无对应源权限时为 null。
     */
    public record SimulatedIncludedDto(
            @JsonInclude(JsonInclude.Include.ALWAYS) Long sensedToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long alarmsToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long flightsToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long devices) { }

    public record TrendDto(String from, String to, String sourceMode, boolean simulated, List<TrendDayDto> days) { }

    /** 运行统计不给的数（不能读目标或研判）为 null，页面显示"—"；原先是 int，拆箱空值让整个大屏 500。 */
    public record TrendDayDto(String date, String md,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer total,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer illegal) { }

    /**
     * 今日感知目标按最新风险等级分档，与运行统计"各风险等级分布"同一套：超高风险、高风险、中风险、低风险、未识别（ungraded）。
     * 五档相加等于 kpis.sensed_today；全量统计，truncated 恒为 false（保留字段兼容旧页面）。
     */
    public record TargetRiskDto(int critical, int high, int medium, int low, int ungraded, boolean truncated) { }

    public record ClosureDto(
            @JsonInclude(JsonInclude.Include.ALWAYS) Long pendingVerification,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long confirmedBlocked,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long pendingHandoffs,
            EvidenceDto evidence) { }

    public record EvidenceDto(String status) { }

    public record DevicesDto(int total, int online, int offline, int abnormal, int alarm, Double onlineRate,
            String sourceMode, boolean simulated) { }

    /** 今日窗口内的计划数、其中执行中和已完成的数，都按统计口径（StatisticsScope）。 */
    public record FlightsDto(long today, long executing, long completed) { }

    public record AlarmsDto(List<AlarmItemDto> items, @JsonInclude(JsonInclude.Include.ALWAYS) Long total) { }

    public record AlarmItemDto(String alarmId, String alarmNo, String alarmType, String severity, String state,
            long receivedAt, Long occurredAt, String targetId) { }

    public record MapDto(List<MapTargetDto> targets, List<MapDeviceDto> devices, List<MapAirspaceDto> airspaces,
            List<MapAlarmDto> alarms) { }

    public record MapTargetDto(String targetId, String targetNo, String objectTypeCode, String subtype,
            BigDecimal longitude, BigDecimal latitude, String coordinateSystem, BigDecimal altitudeAmslM,
            BigDecimal speedMps, BigDecimal headingDeg, BigDecimal fusionConfidence, String legalStatus, String grade,
            long observedAt, Long mapExpiresAt) { }

    public record MapDeviceDto(String deviceId, String deviceNo, String name, String deviceTypeName, String channel,
            String connectivity, boolean hasAlarm, BigDecimal longitude, BigDecimal latitude, String coordinateSystem) { }

    public record MapAirspaceDto(String airspaceId, String airspaceNo, String name, String kindCode,
            GeoJsonMultiPolygonDto boundary, BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum) { }

    public record MapAlarmDto(String alarmId, String targetId, String alarmType, String severity, String state,
            long receivedAt, BigDecimal longitude, BigDecimal latitude) { }
}
