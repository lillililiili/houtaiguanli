package com.uav.lowaltitude.modules.dashboard.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceDetailDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceSummaryDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceVersionDto;
import com.uav.lowaltitude.modules.airspace.application.AirspaceReadService;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto;
import com.uav.lowaltitude.modules.alarm.application.AlarmReadService;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvaluationDto;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.AlarmItemDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.AlarmsDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.ClosureDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.DevicesDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.EvidenceDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.FlightsDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.KpisDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.MapAirspaceDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.MapAlarmDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.MapDeviceDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.MapDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.MapTargetDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.SimulatedIncludedDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.SnapshotDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.TargetRiskDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.TrendDayDto;
import com.uav.lowaltitude.modules.dashboard.api.DashboardDtos.TrendDto;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceMapMarker;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceOverview;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.modules.handoff.application.HandoffReadService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.reporting.application.ReportingService;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.DayPoint;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.DayTargets;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.OperationsReport;
import com.uav.lowaltitude.modules.reporting.application.ReportingService.RiskTiers;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LocationDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetStateDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetSummaryDto;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.query.StatisticsScope;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 数据大屏只读聚合：先过 dashboard.read，再按源权限填充各块。
 * 缺权限的计数必须是 null；证据库本切片未建设，恒为 NOT_BUILT。
 *
 * <p>统计口径（ZT-17；2026-10-07 用户决定设备模拟器的数据也算，见 {@link StatisticsScope}）：大屏上的计数——
 * 今日感知目标、今日告警、待研判、交接待办、待核实/已确认、今日计划/执行中、设备总数/在线、风险分档——
 * 与"运行统计"同一口径，算真实设备和设备模拟器的数据，不算建库时系统自带的演示样例。
 * 其中来自设备模拟器的条数放在 {@code simulated_included} 里，页面据此写明，免得被当成现场真实数据。
 * 地图和最新告警列表不是计数，仍按全部来源给。</p>
 *
 * <p>今日感知目标与风险分档直接用运行统计的取数（{@link ReportingService#dayTargets}，ZT-17 复测 2）：
 * 原先各算各的——大屏按今天出现过、不含被合并的目标数，运行统计按首次发现、含被合并的目标数，差了 21 个；
 * 风险分档大屏按研判等级抽样 100 条，运行统计按风险记录的等级，对不上。现在两处同一批目标、同一套分档。</p>
 */
@Service
public class DashboardSnapshotService {
    static final String AVAILABLE = "AVAILABLE", FORBIDDEN = "FORBIDDEN", UNCONFIGURED = "UNCONFIGURED";
    private static final ZoneId ZONE = ReportingService.ZONE;
    private static final EvidenceDto EVIDENCE_NOT_BUILT = new EvidenceDto("NOT_BUILT");

    private final AccessService menuAccess;
    private final AccessControlService access;
    private final TargetReadService targets;
    private final AlarmReadService alarms;
    private final LegalityEvaluationReadService evaluations;
    private final HandoffReadService handoffs;
    private final DeviceService devices;
    private final FlightReadService flights;
    private final AirspaceReadService airspaces;
    private final ReportingService reporting;
    private final StatisticsScope statistics;
    private final AppClock clock;

    public DashboardSnapshotService(AccessService menuAccess, AccessControlService access, TargetReadService targets,
            AlarmReadService alarms, LegalityEvaluationReadService evaluations, HandoffReadService handoffs,
            DeviceService devices, FlightReadService flights, AirspaceReadService airspaces,
            ReportingService reporting, StatisticsScope statistics, AppClock clock) {
        this.menuAccess = menuAccess;
        this.access = access;
        this.targets = targets;
        this.alarms = alarms;
        this.evaluations = evaluations;
        this.handoffs = handoffs;
        this.devices = devices;
        this.flights = flights;
        this.airspaces = airspaces;
        this.reporting = reporting;
        this.statistics = statistics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SnapshotDto snapshot(MultiValueMap<String, String> parameters) {
        menuAccess.requireBusinessData("dashboard.read");
        if (parameters != null && !parameters.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "参数无效");
        }
        long asOf = clock.nowMillis();
        LocalDate today = clock.now().atZone(ZONE).toLocalDate();
        long dayStart = today.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long dayEnd = today.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        String from = String.valueOf(dayStart);
        String to = String.valueOf(dayEnd);

        boolean canTarget = probe(PermissionCode.TARGET_READ);
        boolean canAlarm = probe(PermissionCode.ALARM_READ);
        boolean canAssessment = probe(PermissionCode.ASSESSMENT_READ);
        boolean canHandoff = probe(PermissionCode.HANDOFF_READ);
        boolean canDevice = probe(PermissionCode.DEVICE_READ) && menuReadable("monitoring.read");
        boolean canFlight = probe(PermissionCode.FLIGHT_READ);
        boolean canAirspace = probe(PermissionCode.AIRSPACE_READ);
        boolean canRisk = probe(PermissionCode.RISK_READ);
        boolean canStats = menuReadable("statistics.read");

        /* 统计口径与运行统计一致（StatisticsScope）：真实设备和设备模拟器的数据都算，演示样例不算；
           同时记下其中来自设备模拟器的条数，供页面写明。今日目标与风险分档就是运行统计选今天时的那一份。 */
        DayTargets todayTargets = canTarget ? reporting.dayTargets(today) : null;
        Scoped alarmCount = canAlarm ? scoped(mode -> countAlarms(from, to, null, mode)) : null;
        Long sensedToday = todayTargets == null ? null : (long) todayTargets.total();
        Long alarmsToday = alarmCount == null ? null : alarmCount.total();
        Long allSourceAlarmsToday = canAlarm ? countAlarms(from, to, null, null) : null;
        Long pendingAssessment = canAssessment ? scoped(mode -> countEvaluations("PENDING_REVIEW", mode)).total() : null;
        Long pendingHandoffs = canHandoff ? scoped(this::countHandoffs).total() : null;
        DeviceCounts deviceCounts = canDevice ? deviceCounts() : null;
        FlightCounts flightCounts = canFlight ? flightCounts(from, to) : null;
        SimulatedIncludedDto simulated = new SimulatedIncludedDto(
                todayTargets == null ? null : (long) todayTargets.simulated(),
                alarmCount == null ? null : alarmCount.simulated(),
                flightCounts == null ? null : flightCounts.simulatedToday(),
                deviceCounts == null ? null : deviceCounts.simulated());

        /* 地图要的是当前有位置的目标，不是今日 KPI 窗口。演示库 last_seen 往往不在当天，
           用 seen_from/seen_to 会让大屏只剩设备点；同理地图也不按来源过滤。今日计数按统计口径走 countTargets。 */
        List<TargetSummaryDto> mapTargetRows = canTarget ? listMapTargets() : List.of();
        Map<String, EvaluationDto> latestByTarget = canAssessment ? latestEvaluationsByTarget() : Map.of();
        List<AlarmDto> todayAlarmRows = canAlarm ? listAlarms(from, to, 8) : List.of();

        Map<String, String> availability = new LinkedHashMap<>();
        availability.put("targets", flag(canTarget));
        availability.put("alarms", flag(canAlarm));
        availability.put("assessments", flag(canAssessment));
        availability.put("handoffs", flag(canHandoff));
        availability.put("devices", flag(canDevice));
        availability.put("flights", flag(canFlight));
        availability.put("airspaces", flag(canAirspace));
        availability.put("risks", flag(canRisk));
        availability.put("stats", flag(canStats));

        return new SnapshotDto(asOf, availability,
                new KpisDto(sensedToday, alarmsToday, pendingAssessment, pendingHandoffs),
                simulated, statistics.sourceModes(),
                canStats ? trend(today) : null,
                targetRisk(todayTargets),
                /* 办理队列也按统计口径：设备模拟器批次里待核实的告警照样计入，系统自带的演示样例不计。 */
                new ClosureDto(
                        canAlarm ? scoped(mode -> countAlarms(null, null, "PENDING_VERIFICATION", mode)).total() : null,
                        canAlarm ? scoped(mode -> countAlarms(null, null, "CONFIRMED", mode)).total() : null,
                        pendingHandoffs, EVIDENCE_NOT_BUILT),
                deviceCounts == null ? null : deviceCounts.devices(),
                flightCounts == null ? null : flightCounts.flights(),
                // total 与 items 同口径（全部来源的今日告警），不是统计口径的 KPI 计数。
                new AlarmsDto(todayAlarmRows.stream().map(DashboardSnapshotService::alarmItem).toList(),
                        allSourceAlarmsToday),
                new MapDto(
                        canTarget ? mapTargets(mapTargetRows, latestByTarget) : List.of(),
                        canDevice ? mapDevices() : List.of(),
                        canAirspace ? mapAirspaces() : List.of(),
                        canAlarm && canTarget ? mapAlarms(todayAlarmRows, mapTargetRows) : List.of()));
    }

    private TrendDto trend(LocalDate today) {
        LocalDate from = today.minusDays(6);
        OperationsReport report = reporting.operations(from.toString(), today.toString());
        List<TrendDayDto> days = new ArrayList<>();
        for (DayPoint day : report.days()) {
            days.add(new TrendDayDto(day.date(), day.md(), day.total(), day.illegal()));
        }
        return new TrendDto(report.from(), report.to(), report.sourceMode(), report.simulated(), List.copyOf(days));
    }

    /**
     * 风险分档：今日感知目标按各自最新的风险等级分档，与运行统计选今天时的"各异物风险等级分布"一致（ZT-17 复测 2）。
     * 今日目标全量统计，不再抽样，truncated 恒为 false；不能读目标或风险时整块为 null。地图上的研判标签不变。
     */
    private static TargetRiskDto targetRisk(DayTargets today) {
        if (today == null || today.risks() == null) return null;
        RiskTiers tiers = today.risks();
        return new TargetRiskDto(tiers.critical(), tiers.high(), tiers.medium(), tiers.low(), tiers.unknown(), false);
    }

    /** 设备健康按统计口径（与运行统计的设备口径一致）；统计里的模拟设备台数 = 统计口径台数 - 正式接入台数。 */
    private DeviceCounts deviceCounts() {
        DeviceOverview counted = devices.statisticsOverview();
        Double rate = counted.total() == 0 ? null : Math.round(counted.online() * 1000.0 / counted.total()) / 10.0;
        long simulated = Math.max(counted.total() - devices.formalOverview().total(), 0);
        return new DeviceCounts(new DevicesDto(counted.total(), counted.online(), counted.offline(), counted.abnormal(),
                counted.alarm(), rate, counted.sourceMode(), counted.simulated()), simulated);
    }

    private record DeviceCounts(DevicesDto devices, long simulated) { }

    private FlightCounts flightCounts(String from, String to) {
        Scoped today = scoped(mode -> countPlans(from, to, null, mode));
        long executing = scoped(mode -> countPlans(from, to, "EXECUTING", mode)).total();
        // 已完成也在这里按统计口径算好，页面不再自己按来源拼查询，免得口径两处各写一份。
        long completed = scoped(mode -> countPlans(from, to, "COMPLETED", mode)).total();
        return new FlightCounts(new FlightsDto(today.total(), executing, completed), today.simulated());
    }

    private record FlightCounts(FlightsDto flights, long simulatedToday) { }

    /** 按统计口径逐个来源计数后相加，同时记下其中来自设备模拟器的条数。 */
    private Scoped scoped(ToLongFunction<String> countByMode) {
        long total = 0, simulated = 0;
        for (String mode : statistics.sourceModes()) {
            long count = countByMode.applyAsLong(mode);
            total += count;
            if (StatisticsScope.SIMULATOR.equals(mode)) simulated = count;
        }
        return new Scoped(total, simulated);
    }

    private record Scoped(long total, long simulated) { }

    private long countPlans(String from, String to, String statusCode, String sourceMode) {
        MultiValueMap<String, String> query = q("page", "1", "size", "1", "window_from", from, "window_to", to);
        if (statusCode != null) query.add("status_code", statusCode);
        if (sourceMode != null) query.add("source_mode", sourceMode);
        return flights.flightPlans(query).total();
    }

    private List<TargetSummaryDto> listMapTargets() {
        return targets.targets(q("page", "1", "size", "100")).items();
    }

    private long countAlarms(String from, String to, String state, String sourceMode) {
        MultiValueMap<String, String> query = q("page", "1", "size", "1");
        if (from != null) {
            query.add("occurred_from", from);
            query.add("occurred_to", to);
        }
        if (state != null) query.add("state", state);
        if (sourceMode != null) query.add("source_mode", sourceMode);
        return alarms.list(query).total();
    }

    private List<AlarmDto> listAlarms(String from, String to, int size) {
        return alarms.list(q("page", "1", "size", String.valueOf(size), "occurred_from", from, "occurred_to", to)).items();
    }

    private long countEvaluations(String reviewState, String sourceMode) {
        return evaluations.list(q("page", "1", "size", "1", "latest_only", "true", "review_state", reviewState,
                "source_mode", sourceMode)).total();
    }

    private Map<String, EvaluationDto> latestEvaluationsByTarget() {
        Map<String, EvaluationDto> byTarget = new LinkedHashMap<>();
        for (EvaluationDto row : evaluations.list(q("page", "1", "size", "100", "latest_only", "true",
                "subject_kind", "TARGET")).items()) {
            if (row.targetId() != null) byTarget.putIfAbsent(row.targetId(), row);
        }
        return byTarget;
    }

    /**
     * 交接待办就是“移送与处罚”页的“待发送”：无人机事件的处罚移送里还没发出去的（2026-10-08 新-2 第 6 点）。
     * 风险的“通知上级”没发出去的不算在这里，在风险详情里看，接收端恢复后会自动补发。
     */
    private long countHandoffs(String sourceMode) {
        return handoffs.list(q("page", "1", "size", "1", "source_kind", "UAV_EVENT", "delivery_status", "PENDING_DELIVERY",
                "source_mode", sourceMode)).total();
    }

    private List<MapTargetDto> mapTargets(List<TargetSummaryDto> rows, Map<String, EvaluationDto> latest) {
        List<MapTargetDto> items = new ArrayList<>();
        for (TargetSummaryDto row : rows) {
            TargetStateDto state = row.latestState();
            LocationDto location = state == null ? null : state.location();
            if (location == null || location.longitude() == null || location.latitude() == null) continue;
            if (location.coordinateSystem() != null && !"WGS84".equalsIgnoreCase(location.coordinateSystem())) continue;
            EvaluationDto evaluation = latest.get(row.targetId());
            items.add(new MapTargetDto(row.targetId(), row.targetNo(), row.objectTypeCode(), row.subtype(),
                    location.longitude(), location.latitude(), "WGS84",
                    state.altitudeAmslM(), state.speedMps(), state.headingDeg(), state.fusionConfidence(),
                    evaluation == null ? null : evaluation.legalStatus(),
                    evaluation == null ? null : evaluation.grade(), state.observedAt(), row.mapExpiresAt()));
        }
        return List.copyOf(items);
    }

    private List<MapDeviceDto> mapDevices() {
        List<MapDeviceDto> items = new ArrayList<>();
        for (DeviceMapMarker marker : devices.mapMarkers(46)) {
            if (marker.coordinateSystem() != null && !"WGS84".equalsIgnoreCase(marker.coordinateSystem())
                    && !"WGS-84".equalsIgnoreCase(marker.coordinateSystem())) continue;
            items.add(new MapDeviceDto(marker.deviceId(), marker.deviceNo(), marker.name(), marker.deviceTypeName(),
                    marker.channel(), marker.connectivity(), marker.hasAlarm(), marker.longitude(), marker.latitude(),
                    marker.coordinateSystem()));
        }
        return List.copyOf(items);
    }

    private List<MapAirspaceDto> mapAirspaces() {
        List<MapAirspaceDto> items = new ArrayList<>();
        for (AirspaceSummaryDto summary : airspaces.airspaces(q("page", "1", "size", "40")).items()) {
            AirspaceDetailDto detail = airspaces.airspace(summary.airspaceId());
            AirspaceVersionDto version = detail.currentVersion();
            if (version == null || version.boundary() == null || version.boundary().coordinates() == null) continue;
            items.add(new MapAirspaceDto(detail.airspaceId(), detail.airspaceNo(), detail.name(), version.kindCode(),
                    version.boundary(), version.minAltitudeM(), version.maxAltitudeM(), version.altitudeDatum()));
            if (items.size() >= 40) break;
        }
        return List.copyOf(items);
    }

    private static List<MapAlarmDto> mapAlarms(List<AlarmDto> rows, List<TargetSummaryDto> targets) {
        Map<String, LocationDto> locations = new LinkedHashMap<>();
        for (TargetSummaryDto row : targets) {
            TargetStateDto state = row.latestState();
            if (state != null && state.location() != null && state.location().longitude() != null) {
                locations.put(row.targetId(), state.location());
            }
        }
        List<MapAlarmDto> items = new ArrayList<>();
        for (AlarmDto row : rows) {
            if (row.getTargetId() == null) continue;
            LocationDto location = locations.get(row.getTargetId());
            if (location == null) continue;
            items.add(new MapAlarmDto(row.getAlarmId(), row.getTargetId(), row.getAlarmType(), row.getSeverity(),
                    row.getState(), row.getReceivedAt(), location.longitude(), location.latitude()));
        }
        return List.copyOf(items);
    }

    private static AlarmItemDto alarmItem(AlarmDto row) {
        return new AlarmItemDto(row.getAlarmId(), row.getAlarmNo(), row.getAlarmType(), row.getSeverity(),
                row.getState(), row.getReceivedAt(), row.getOccurredAt(), row.getTargetId());
    }

    private boolean probe(PermissionCode permission) {
        try {
            access.require(permission);
            return true;
        } catch (ApiException ex) {
            if (denied(ex)) return false;
            throw ex;
        }
    }

    private boolean menuReadable(String code) {
        try {
            menuAccess.requireBusinessData(code);
            return true;
        } catch (ApiException ex) {
            if (denied(ex)) return false;
            throw ex;
        }
    }

    private static boolean denied(ApiException ex) {
        return HttpStatus.FORBIDDEN.equals(ex.getStatus());
    }

    private static String flag(boolean allowed) {
        return allowed ? AVAILABLE : FORBIDDEN;
    }

    private static MultiValueMap<String, String> q(String... pairs) {
        LinkedMultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.add(pairs[i], pairs[i + 1]);
        return map;
    }
}
