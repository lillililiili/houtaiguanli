package com.uav.lowaltitude.modules.flightavoidance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.*;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightAvoidancePolicy;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightAvoidancePolicy.CounterMode;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck.Level;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck.Nearby;
import com.uav.lowaltitude.modules.flightavoidance.infrastructure.FlightAvoidanceRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 航班避让（2026-10-26 用户确认）：反制前以选中的反制设备为中心检查附近的民航航班。
 *
 * 禁止反制范围有航班：人工、直接反制、自动反制一律不能启动，不提供强行继续。
 * 提醒范围有航班或收不到航班信息：人工确认后可以反制，自动反制不启动。
 * 反制中有航班飞进禁止反制范围：系统自动停止（见 disposal 模块的 DisposalFlightGuard）。
 * 没有在用的航班接收机时不做这项检查，反制流程不变。
 */
@Service
public class FlightAvoidanceService {
    public static final String STAGE_REQUEST = "REQUEST", STAGE_DIRECT = "DIRECT", STAGE_EXECUTE = "EXECUTE",
            STAGE_AUTO = "AUTO", STAGE_RUNNING = "RUNNING";
    private static final List<PermissionCode> CHECK_READERS = List.of(PermissionCode.DISPOSAL_REQUEST,
            PermissionCode.DISPOSAL_DIRECT, PermissionCode.DISPOSAL_EXECUTE, PermissionCode.DISPOSAL_APPROVE,
            PermissionCode.DISPOSAL_READ);

    private final FlightAvoidanceRepository repository;
    private final AccessControlService access;
    private final AccessService permissions;
    private final UavEventRepository events;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public FlightAvoidanceService(FlightAvoidanceRepository repository, AccessControlService access, AccessService permissions,
            UavEventRepository events, IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json) {
        this.repository = repository; this.access = access; this.permissions = permissions; this.events = events;
        this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.json = json;
    }

    /* ---- 规则管理「航班避让」 ---- */

    @Transactional(readOnly = true)
    public Policy policy() {
        permissions.requireBusinessData("responsePlans.read");
        return view(repository.policy(false));
    }

    @Transactional
    public Policy savePolicy(PolicyInput input, String key) {
        permissions.requireBusinessData("responsePlans.read");
        permissions.require("responsePlans.auth");
        if (input.forceLandWarnKm().compareTo(input.forceLandBlockKm()) < 0)
            throw bad("迫降的提醒范围不能小于禁止反制范围");
        FlightAvoidancePolicy before = repository.policy(true);
        idempotency.claim(key, "flight-avoidance:" + encode(input));
        if (before.version() != input.expectedVersion())
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "航班避让设置已被修改，请重新读取后保存");
        long now = clock.nowMillis();
        if (repository.updatePolicy(before.version(), scale(input.forceLandBlockKm()), scale(input.forceLandWarnKm()),
                input.forceLandMaxAltitudeM(), scale(input.driveAwayWarnKm()), input.driveAwayMaxAltitudeM(), now) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "航班避让设置已被修改，请重新读取后保存");
        FlightAvoidancePolicy after = repository.policy(false);
        var actor = AuthContext.require();
        repository.change(UUID.randomUUID().toString(), after.version(), actor.userId(), actor.account(), now,
                encode(plain(before)), encode(plain(after)));
        audit.record(actor.userId(), actor.account(), "USER", "responsePlans", "flight_avoidance_changed",
                "flight_avoidance_policy", "counter", "version=" + after.version() + "; before=" + encode(plain(before))
                        + "; after=" + encode(plain(after)), "SUCCESS", "", "");
        return view(after);
    }

    @Transactional(readOnly = true)
    public ChangePage history(int page, int size) {
        permissions.requireBusinessData("responsePlans.read");
        if (page < 1 || size < 1 || size > 100) throw bad("分页参数无效");
        List<Change> items = repository.changes(page, size).stream().map(row -> new Change(text(row, "change_id"),
                number(row, "version"), text(row, "actor"), number(row, "created_at"),
                decodePolicy(text(row, "before_json")), decodePolicy(text(row, "after_json")))).toList();
        return new ChangePage(items, page, size, repository.changeCount());
    }

    /* ---- 每台反制设备单独设置的距离（2026-10-27 用户确认） ---- */

    @Transactional(readOnly = true)
    public DeviceDistance deviceDistance(String deviceId) {
        permissions.requireBusinessData("responsePlans.read");
        if (repository.counterDeviceName(deviceId) == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "反制设备不存在");
        return distanceView(deviceId, canManage());
    }

    @Transactional
    public DeviceDistance saveDeviceDistance(String deviceId, DeviceDistanceInput input, String key) {
        permissions.requireBusinessData("responsePlans.read");
        permissions.require("responsePlans.auth");
        String name = repository.counterDeviceName(deviceId);
        if (name == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "反制设备不存在");
        if (input.custom()) {
            if (input.forceLandBlockKm() == null || input.forceLandWarnKm() == null || input.forceLandMaxAltitudeM() == null
                    || input.driveAwayWarnKm() == null || input.driveAwayMaxAltitudeM() == null)
                throw bad("请填写全部距离和高度");
            if (input.forceLandWarnKm().compareTo(input.forceLandBlockKm()) < 0) throw bad("迫降的提醒范围不能小于禁止反制范围");
        }
        idempotency.claim(key, "flight-distance:" + deviceId + ":" + encode(input));
        Map<String, Object> before = repository.deviceDistance(deviceId);
        Long current = before == null ? null : number(before, "version");
        if (!java.util.Objects.equals(current, input.expectedVersion()))
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "这台设备的航班安全距离已被修改，请重新打开后保存");
        long now = clock.nowMillis();
        if (input.custom()) repository.saveDeviceDistance(deviceId, scale(input.forceLandBlockKm()), scale(input.forceLandWarnKm()),
                input.forceLandMaxAltitudeM(), scale(input.driveAwayWarnKm()), input.driveAwayMaxAltitudeM(), now);
        else repository.clearDeviceDistance(deviceId);
        DeviceDistance after = distanceView(deviceId, true);
        var actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), "USER", "responsePlans", "counter_flight_distance_changed", "device", deviceId,
                "device=" + name + "; before=" + (before == null ? "default" : encode(distanceView(deviceId, before)))
                        + "; after=" + (after.custom() ? encode(after) : "default"), "SUCCESS", "", "");
        return after;
    }

    /** 这台设备实际用的距离：单独设置过就用自己的，否则用规则管理里的默认值。 */
    private FlightAvoidancePolicy policyFor(String deviceId) {
        FlightAvoidancePolicy global = repository.policy(false);
        if (deviceId == null) return global;
        FlightAvoidancePolicy own = repository.devicePolicy(deviceId, global.version());
        return own == null ? global : own;
    }

    private DeviceDistance distanceView(String deviceId, boolean manage) {
        Map<String, Object> row = repository.deviceDistance(deviceId);
        if (row != null) return distanceView(deviceId, row, manage);
        FlightAvoidancePolicy p = repository.policy(false);
        return new DeviceDistance(deviceId, false, p.forceLandBlockKm(), p.forceLandWarnKm(), p.forceLandMaxAltitudeM(),
                p.driveAwayWarnKm(), p.driveAwayMaxAltitudeM(), null, manage);
    }

    private DeviceDistance distanceView(String deviceId, Map<String, Object> row) { return distanceView(deviceId, row, false); }

    private static DeviceDistance distanceView(String deviceId, Map<String, Object> row, boolean manage) {
        return new DeviceDistance(deviceId, true, km(row, "force_land_block_km"), km(row, "force_land_warn_km"),
                (int) number(row, "force_land_max_altitude_m"), km(row, "drive_away_warn_km"),
                (int) number(row, "drive_away_max_altitude_m"), number(row, "version"), manage);
    }

    private static BigDecimal km(Map<String, Object> row, String key) {
        return new BigDecimal(String.valueOf(row.get(key))).setScale(1, RoundingMode.HALF_UP);
    }

    private boolean canManage() {
        return permissions.permissionCodesForUser(AuthContext.require().userId()).contains("responsePlans.auth");
    }

    /* ---- 检查 ---- */

    /** 内部检查：不读当前账号，反制各条路径和反制中的巡检共用。 */
    public FlightCheck.Result evaluate(String deviceId, CounterMode mode) {
        FlightAvoidancePolicy policy = policyFor(deviceId);
        List<Map<String, Object>> receivers = repository.activeReceivers(simulatedActiveAfter());
        if (receivers.isEmpty()) return FlightCheck.evaluate(policy, mode, 0, 0, 0, List.of());
        long now = clock.nowMillis(), freshAfter = now - FlightAvoidancePolicy.DATA_STALE_MILLIS;
        Map<String, Object> device = deviceId == null ? null : repository.devicePosition(deviceId);
        boolean fresh = receivers.stream().anyMatch(r -> r.get("last_data_at") != null && number(r, "last_data_at") >= freshAfter);
        // 反制设备没有位置时无法判断离航班多远，按收不到航班信息处理，不当作附近没有飞机。
        if (device == null || device.get("longitude") == null || device.get("latitude") == null || !fresh)
            return FlightCheck.evaluate(policy, mode, 0, 0, receivers.size(), null);
        return FlightCheck.evaluate(policy, mode, decimal(device, "longitude"), decimal(device, "latitude"),
                receivers.size(), repository.aircraftSince(freshAfter));
    }

    /** 反制弹窗：选好执行设备和反制方式后显示附近航班情况。 */
    @Transactional(readOnly = true)
    public Check preview(String deviceId, String countermeasureMode) {
        requireAny();
        if (deviceId == null || deviceId.isBlank()) throw bad("请选择反制设备");
        return check(evaluate(deviceId.trim(), modeOf("COUNTERMEASURE", countermeasureMode)));
    }

    /**
     * 人工申请或直接反制提交时：禁止反制范围有航班就拒绝；提醒范围有航班或收不到航班信息时必须已确认。
     * 通过时记一条检查记录（没有在用的接收机时不记）。
     */
    public FlightCheck.Result requireForRequest(String stage, String eventId, String authorizationId, String deviceId,
            CounterMode mode, boolean acknowledged, String actorId) {
        FlightCheck.Result result = evaluate(deviceId, mode);
        String refusal = manualRefusal(result, acknowledged);
        if (refusal != null)
            throw new ApiException(HttpStatus.CONFLICT, result.blocked() ? "AIRCRAFT_NEARBY" : "AIRCRAFT_CONFIRM_REQUIRED", refusal);
        record(stage, eventId, authorizationId, deviceId, result, acknowledged && result.needsConfirmation(), actorId);
        return result;
    }

    /** 下发反制命令前一刻再查一次。返回不能下发的原因，能下发时返回 null。 */
    public Refusal beforeDispatch(String stage, String eventId, String authorizationId, String deviceId, CounterMode mode,
            boolean acknowledgedNow, String actorId, boolean recordPass) {
        FlightCheck.Result result = evaluate(deviceId, mode);
        if (result.level() == Level.OFF) return null;
        boolean acknowledged = acknowledgedNow || authorizationId != null && repository.acknowledged(authorizationId);
        String refusal = manualRefusal(result, acknowledged);
        if (refusal != null || recordPass)
            record(stage, eventId, authorizationId, deviceId, result, acknowledgedNow && result.needsConfirmation(), actorId);
        return refusal == null ? null : new Refusal(result.blocked() ? "AIRCRAFT_NEARBY" : "AIRCRAFT_CONFIRM_REQUIRED", refusal);
    }

    /**
     * 自动反制：附近没有航班才启动。不启动的原因记在告警上，同一个结果只记一次。
     */
    public boolean allowsAutomatic(String eventId, String deviceId) {
        FlightCheck.Result result = evaluate(deviceId, CounterMode.FORCE_LAND);
        if (result.clearForAutomatic()) return true;
        if (!result.level().name().equals(repository.latestAutomaticResult(eventId)))
            record(STAGE_AUTO, eventId, null, deviceId, result, false, null);
        return false;
    }

    /** 反制中巡检：有航班飞进禁止反制范围时记下，由调用方停止反制。 */
    public FlightCheck.Result whileRunning(String eventId, String authorizationId, String deviceId, CounterMode mode) {
        FlightCheck.Result result = evaluate(deviceId, mode);
        if (result.blocked()) record(STAGE_RUNNING, eventId, authorizationId, deviceId, result, false, null);
        return result;
    }

    public static String stopNote(FlightCheck.Result result) {
        Nearby first = result.aircraft().isEmpty() ? null : result.aircraft().get(0);
        if (first == null) return "有航班飞近反制设备，反制已自动停止。";
        // 按航向几分钟内会飞进禁止反制范围的也停：这时飞机可能还在范围外，写明“正在飞近”和现在的距离。
        return "航班 " + name(first) + (first.approaching() ? " 正在飞近反制设备（现在 " : " 在禁止反制范围内（")
                + km(first.distanceKm()) + " 公里），反制已自动停止。";
    }

    public record Refusal(String code, String detail) { }

    /** 反制方式：申请时选的为准；旧授权按动作推断（JAMMING 为驱离，其余反制为迫降）。 */
    public static CounterMode modeOf(String actionType, String countermeasureMode) {
        if (countermeasureMode != null && !countermeasureMode.isBlank()) {
            try { return CounterMode.valueOf(countermeasureMode.trim()); }
            catch (IllegalArgumentException ex) { throw bad("反制方式必须为 FORCE_LAND（迫降）或 DRIVE_AWAY（驱离）"); }
        }
        return "JAMMING".equals(actionType) ? CounterMode.DRIVE_AWAY : CounterMode.FORCE_LAND;
    }

    private static String manualRefusal(FlightCheck.Result result, boolean acknowledged) {
        if (result.blocked()) {
            Nearby first = result.aircraft().get(0);
            String action = result.mode() == CounterMode.FORCE_LAND ? "迫降" : "反制";
            return "附近有航班，不能" + action + "：航班 " + name(first) + " 距反制设备 " + km(first.distanceKm()) + " 公里"
                    + (first.altitudeM() == null ? "" : "，高度 " + first.altitudeM() + " 米") + (first.approaching() ? "，正在靠近" : "")
                    + "。" + (result.mode() == CounterMode.FORCE_LAND ? "可以改用驱离，或等航班飞远后再反制。" : "请等航班飞远后再反制。");
        }
        if (acknowledged || !result.needsConfirmation()) return null;
        return result.level() == Level.NO_DATA ? "暂时收不到航班信息，无法确认附近有没有航班。请在反制弹窗里勾选「仍要反制」后再提交。"
                : "附近有航班，请在反制弹窗里勾选「已确认附近航班，继续反制」后再提交。";
    }

    private void record(String stage, String eventId, String authorizationId, String deviceId, FlightCheck.Result result,
            boolean acknowledged, String actorId) {
        if (result.level() == Level.OFF) return;
        repository.insertCheck(UUID.randomUUID().toString(), eventId, authorizationId, deviceId, result.mode().name(), stage,
                result.level().name(), acknowledged, actorId, encode(nearby(result.aircraft())), result.policyVersion(),
                clock.nowMillis());
    }

    /** 有没有在用的航班接收机；反制中巡检据此决定要不要查。 */
    public boolean hasActiveReceivers() {
        return !repository.activeReceivers(simulatedActiveAfter()).isEmpty();
    }

    private long simulatedActiveAfter() {
        return clock.nowMillis() - FlightAvoidancePolicy.SIMULATED_QUIET_MILLIS;
    }

    /* ---- 地图与告警详情 ---- */

    @Transactional(readOnly = true)
    public Traffic traffic() {
        access.require(PermissionCode.TARGET_READ);
        long now = clock.nowMillis(), freshAfter = now - FlightAvoidancePolicy.DATA_STALE_MILLIS;
        List<Receiver> receivers = repository.activeReceivers(simulatedActiveAfter()).stream().map(r -> new Receiver(text(r, "device_id"),
                text(r, "device_no"), text(r, "name"), r.get("longitude") == null ? null : decimal(r, "longitude"),
                r.get("latitude") == null ? null : decimal(r, "latitude"), (int) number(r, "receive_range_km"),
                r.get("last_data_at") == null ? null : number(r, "last_data_at"),
                r.get("last_data_at") != null && number(r, "last_data_at") >= freshAfter, Boolean.TRUE.equals(r.get("simulated"))))
                .toList();
        Map<String, Aircraft> latest = new LinkedHashMap<>();
        for (FlightCheck.Aircraft a : receivers.isEmpty() ? List.<FlightCheck.Aircraft>of() : repository.aircraftSince(freshAfter))
            latest.put(a.icao24(), new Aircraft(a.icao24(), a.callsign(), a.airline(), a.longitude(), a.latitude(), a.altitudeM(),
                    a.groundSpeedMps(), a.trackDeg(), a.observedAt()));
        List<DeviceDistance> distances = repository.deviceDistances().stream()
                .map(row -> distanceView(text(row, "device_id"), row)).toList();
        return new Traffic(receivers, new ArrayList<>(latest.values()), view(repository.policy(false)), distances, now);
    }

    @Transactional(readOnly = true)
    public List<CheckRecord> checks(String eventId) {
        AccessDecision decision = access.require(PermissionCode.ALARM_READ);
        if (eventId == null || events.find(eventId, decision) == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "告警不存在或不在授权范围内");
        return repository.checksForEvent(eventId, repository.authorizationIdsForEvent(eventId)).stream()
                .map(row -> new CheckRecord(text(row, "check_id"), text(row, "event_id"), text(row, "authorization_id"),
                        text(row, "device_id"), text(row, "device_name"), text(row, "countermeasure_mode"), text(row, "stage"),
                        text(row, "result"), Boolean.TRUE.equals(row.get("acknowledged")), text(row, "actor_name"),
                        decodeNearby(text(row, "aircraft_json")), number(row, "checked_at")))
                .toList();
    }

    /* ---- 内部 ---- */

    private void requireAny() {
        ApiException first = null;
        for (PermissionCode code : CHECK_READERS) {
            try { access.require(code); return; }
            catch (ApiException denied) { if (first == null) first = denied; }
        }
        throw first;
    }

    private Policy view(FlightAvoidancePolicy p) {
        boolean manage = canManage();
        return new Policy(p.version(), p.forceLandBlockKm(), p.forceLandWarnKm(), p.forceLandMaxAltitudeM(), p.driveAwayWarnKm(),
                p.driveAwayMaxAltitudeM(), p.updatedAt(), manage);
    }

    private static Policy plain(FlightAvoidancePolicy p) {
        return new Policy(p.version(), p.forceLandBlockKm(), p.forceLandWarnKm(), p.forceLandMaxAltitudeM(), p.driveAwayWarnKm(),
                p.driveAwayMaxAltitudeM(), p.updatedAt(), false);
    }

    private static Check check(FlightCheck.Result r) {
        return new Check(r.level().name(), r.mode().name(), r.zones().blockKm() > 0 ? BigDecimal.valueOf(r.zones().blockKm()) : null,
                BigDecimal.valueOf(r.zones().warnKm()), r.zones().maxAltitudeM(), nearby(r.aircraft()), r.policyVersion());
    }

    private static List<NearbyAircraft> nearby(List<Nearby> list) {
        return list.stream().map(n -> new NearbyAircraft(n.icao24(), n.callsign(), n.airline(), n.distanceKm(), n.altitudeM(),
                n.approaching(), n.zone().name())).toList();
    }

    private static String name(Nearby n) {
        return n.callsign() == null || n.callsign().isBlank() ? n.icao24().toUpperCase() : n.callsign().trim();
    }

    private static String km(double value) {
        return value >= 10 ? String.valueOf(Math.round(value)) : String.valueOf(Math.round(value * 10) / 10.0);
    }

    private static BigDecimal scale(BigDecimal value) { return value.setScale(1, RoundingMode.HALF_UP); }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private Policy decodePolicy(String value) {
        try { return json.readValue(value, Policy.class); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private List<NearbyAircraft> decodeNearby(String value) {
        try { return json.readValue(value, new TypeReference<List<NearbyAircraft>>() { }); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    private static double decimal(Map<String, Object> row, String key) { return ((Number) row.get(key)).doubleValue(); }

    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}
