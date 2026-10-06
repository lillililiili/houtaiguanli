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
            SimulatedExcludedDto simulatedExcluded,
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
     * ZT-17：统计类 KPI 只计正式接入（live）后，被排除在外的模拟/回放条数。
     * 页面用它说明"另有模拟/回放 N 条，不计入统计"，免得演示库里看着像功能坏了。
     * 无对应源权限时为 null；办理队列、地图和最新告警列表不受这一口径影响，所以这里没有它们。
     */
    public record SimulatedExcludedDto(
            @JsonInclude(JsonInclude.Include.ALWAYS) Long sensedToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long alarmsToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long flightsToday,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long devices) { }

    public record TrendDto(String from, String to, String sourceMode, boolean simulated, List<TrendDayDto> days) { }

    public record TrendDayDto(String date, String md, int total, int illegal) { }

    public record TargetRiskDto(int high, int medium, int low, int ungraded, boolean truncated) { }

    public record ClosureDto(
            @JsonInclude(JsonInclude.Include.ALWAYS) Long pendingVerification,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long confirmedBlocked,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long pendingHandoffs,
            EvidenceDto evidence) { }

    public record EvidenceDto(String status) { }

    public record DevicesDto(int total, int online, int offline, int abnormal, int alarm, Double onlineRate,
            String sourceMode, boolean simulated) { }

    public record FlightsDto(long today, long executing) { }

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
