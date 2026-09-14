package com.uav.lowaltitude.modules.dashboard.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.uav.lowaltitude.modules.reporting.application.ReportingService.OperationsReport;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LocationDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetStateDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetSummaryDto;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 数据大屏只读聚合：先过 dashboard.read，再按源权限填充各块。
 * 缺权限的计数必须是 null；证据库本切片未建设，恒为 NOT_BUILT。
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
    private final AppClock clock;

    public DashboardSnapshotService(AccessService menuAccess, AccessControlService access, TargetReadService targets,
            AlarmReadService alarms, LegalityEvaluationReadService evaluations, HandoffReadService handoffs,
            DeviceService devices, FlightReadService flights, AirspaceReadService airspaces,
            ReportingService reporting, AppClock clock) {
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
        boolean canStats = menuReadable("statistics.read");

        Long sensedToday = canTarget ? countTargets(from, to) : null;
        Long alarmsToday = canAlarm ? countAlarms(from, to, null) : null;
        Long pendingAssessment = canAssessment ? countEvaluations("PENDING_REVIEW") : null;
        Long pendingHandoffs = canHandoff ? countHandoffs() : null;

        /* 地图要的是当前有位置的目标，不是今日 KPI 窗口。演示库 last_seen 往往不在当天，
           用 seen_from/seen_to 会让大屏只剩设备点。今日计数仍走 countTargets(from, to)。 */
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
        availability.put("stats", flag(canStats));

        return new SnapshotDto(asOf, availability,
                new KpisDto(sensedToday, alarmsToday, pendingAssessment, pendingHandoffs),
                canStats ? trend(today) : null,
                canAssessment ? targetRisk(latestByTarget) : null,
                new ClosureDto(
                        canAlarm ? countAlarms(null, null, "PENDING_VERIFICATION") : null,
                        canAlarm ? countAlarms(null, null, "CONFIRMED") : null,
                        pendingHandoffs, EVIDENCE_NOT_BUILT),
                canDevice ? devices() : null,
                canFlight ? flights(from, to) : null,
                new AlarmsDto(todayAlarmRows.stream().map(DashboardSnapshotService::alarmItem).toList(), alarmsToday),
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

    private TargetRiskDto targetRisk(Map<String, EvaluationDto> latest) {
        int high = 0, medium = 0, low = 0, ungraded = 0;
        for (EvaluationDto row : latest.values()) {
            String grade = row.grade();
            if ("HIGH".equals(grade)) high++;
            else if ("MEDIUM".equals(grade)) medium++;
            else if ("LOW".equals(grade)) low++;
            else ungraded++;
        }
        return new TargetRiskDto(high, medium, low, ungraded, latest.size() >= 100);
    }

    private DevicesDto devices() {
        DeviceOverview overview = devices.overview();
        Double rate = overview.total() == 0 ? null : Math.round(overview.online() * 1000.0 / overview.total()) / 10.0;
        return new DevicesDto(overview.total(), overview.online(), overview.offline(), overview.abnormal(),
                overview.alarm(), rate, overview.sourceMode(), overview.simulated());
    }

    private FlightsDto flights(String from, String to) {
        long today = flights.flightPlans(q("page", "1", "size", "1", "window_from", from, "window_to", to)).total();
        long executing = flights.flightPlans(q("page", "1", "size", "1", "status_code", "EXECUTING",
                "window_from", from, "window_to", to)).total();
        return new FlightsDto(today, executing);
    }

    private long countTargets(String from, String to) {
        return targets.targets(q("page", "1", "size", "1", "seen_from", from, "seen_to", to)).total();
    }

    private List<TargetSummaryDto> listMapTargets() {
        return targets.targets(q("page", "1", "size", "100")).items();
    }

    private long countAlarms(String from, String to, String state) {
        MultiValueMap<String, String> query = q("page", "1", "size", "1");
        if (from != null) {
            query.add("occurred_from", from);
            query.add("occurred_to", to);
        }
        if (state != null) query.add("state", state);
        return alarms.list(query).total();
    }

    private List<AlarmDto> listAlarms(String from, String to, int size) {
        return alarms.list(q("page", "1", "size", String.valueOf(size), "occurred_from", from, "occurred_to", to)).items();
    }

    private long countEvaluations(String reviewState) {
        return evaluations.list(q("page", "1", "size", "1", "latest_only", "true", "review_state", reviewState)).total();
    }

    private Map<String, EvaluationDto> latestEvaluationsByTarget() {
        Map<String, EvaluationDto> byTarget = new LinkedHashMap<>();
        for (EvaluationDto row : evaluations.list(q("page", "1", "size", "100", "latest_only", "true",
                "subject_kind", "TARGET")).items()) {
            if (row.targetId() != null) byTarget.putIfAbsent(row.targetId(), row);
        }
        return byTarget;
    }

    private long countHandoffs() {
        return handoffs.list(q("page", "1", "size", "1", "delivery_status", "PENDING_DELIVERY")).total();
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
                    evaluation == null ? null : evaluation.grade()));
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
