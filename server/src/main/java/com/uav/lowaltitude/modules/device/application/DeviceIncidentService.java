package com.uav.lowaltitude.modules.device.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class DeviceIncidentService {
    static final String RULE_VERSION = "ops-incident-recovery-v1";

    private final DeviceRepository repository;
    private final DeviceService devices;
    private final DeviceAccessPolicy access;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public DeviceIncidentService(DeviceRepository repository, DeviceService devices, DeviceAccessPolicy access,
                                 AppClock clock, AuditService audit, ObjectMapper objectMapper) {
        this.repository = repository;
        this.devices = devices;
        this.access = access;
        this.clock = clock;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public IncidentDetail get(String incidentId) {
        AuthUser user = access.requireMonitoringRead();
        Map<String, Object> row = requiredIncident(incidentId);
        return detail(row, access.canOperateMonitoring(user));
    }

    @Transactional
    public DeviceService.Command reboot(String incidentId, String idempotencyKey, String reason) {
        AuthUser user = access.requireMonitoringOperate();
        String key = requireIdempotencyKey(idempotencyKey);
        if (reason == null || reason.trim().length() < 2 || reason.trim().length() > 500)
            throw bad("VALIDATION_ERROR", "重启原因长度必须为 2–500 个字符");
        Map<String, Object> incident = requiredIncident(incidentId);
        String stage = text(incident, "stage");
        String deviceId = text(incident, "device_id");
        if ("MQTT_HEARTBEAT_TIMEOUT".equals(text(incident, "incident_type")))
            throw new ApiException(HttpStatus.CONFLICT, "REBOOT_NOT_SUPPORTED", "该 MQTT 协议未定义重启指令，请恢复心跳后执行恢复核验");
        if (!"PENDING".equals(stage)) {
            DeviceService.Command replayed = replayReboot(incident, user, deviceId, key, reason.trim());
            if (replayed != null) return replayed;
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前阶段不能下发重启");
        }
        DeviceService.Command command = devices.createReboot(deviceId, key, reason.trim());
        if (repository.startIncidentReboot(incidentId, command.commandId()) != 1) {
            Map<String, Object> latest = requiredIncident(incidentId);
            if (command.commandId().equals(text(latest, "reboot_command_id"))) return command;
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前阶段不能下发重启");
        }
        audit.record(user.userId(), user.account(), "device_incident_reboot_requested", "device_incident",
                incidentId, reason.trim(), null);
        return command;
    }

    @Transactional
    public RecoveryResult recoveryCheck(String incidentId, String idempotencyKey) {
        AuthUser user = access.requireMonitoringOperate();
        String key = "incident-recovery:" + incidentId + ":" + requireIdempotencyKey(idempotencyKey);
        String hash = sha256(user.userId() + "|" + incidentId + "|VERIFY_RECOVERY");
        Map<String, Object> replay = repository.findIdempotency(key);
        if (replay != null) {
            if (!hash.equals(text(replay, "request_hash")))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键对应了不同请求");
            return recoveryFromRow(repository.findRecoveryCheck(text(replay, "response_body")));
        }
        Map<String, Object> incident = requiredIncident(incidentId);
        boolean heartbeatIncident = "MQTT_HEARTBEAT_TIMEOUT".equals(text(incident, "incident_type"));
        String expectedStage = text(incident, "stage");
        if (!"PENDING_VERIFICATION".equals(expectedStage) && !(heartbeatIncident && "PENDING".equals(expectedStage)))
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前阶段不能做恢复校验");
        Map<String, Object> device = repository.find(text(incident, "device_id"));
        if (device == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        String connectivity = text(device, "connectivity");
        boolean hasAlarm = bool(device, "has_alarm");
        String health = text(device, "health_code");
        String result;
        String reason;
        if (heartbeatIncident) {
            Long heartbeat = longValue(device, "last_heartbeat_at");
            boolean fresh = heartbeat != null && heartbeat > longNumber(incident, "detected_at")
                    && heartbeat <= clock.nowMillis() && clock.nowMillis() - heartbeat <= 30_000;
            result = "ONLINE".equals(connectivity) && fresh ? "PASS" : "FAIL";
            reason = "PASS".equals(result) ? "离线事件恢复核验：事件发生后收到新心跳且设备在线；未推定其他健康指标"
                    : "尚未取得事件发生后的新鲜在线心跳";
        } else if (connectivity == null || "UNKNOWN".equals(connectivity)) {
            result = "UNKNOWN";
            reason = "设备连接状态未知，不能关闭异常";
        } else if ("ONLINE".equals(connectivity) && !hasAlarm && "GOOD".equals(health)) {
            result = "PASS";
            reason = "平台状态快照：在线、无告警、健康良好";
        } else {
            result = "FAIL";
            reason = "平台状态快照未恢复：connectivity=" + nullToDash(connectivity)
                    + "，has_alarm=" + hasAlarm + "，health_code=" + nullToDash(health);
        }
        long now = clock.nowMillis();
        Map<String, Object> snapshot = snapshot(device);
        String checkId = UUID.randomUUID().toString();
        repository.insertRecoveryCheck(checkId, incidentId, user.userId(), now, result, RULE_VERSION, json(snapshot), reason);
        repository.insertIdempotency(key, user.userId(), hash, checkId, now);
        if ("PASS".equals(result) && repository.closeIncident(incidentId, expectedStage, now) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前阶段不能关闭异常");
        audit.record(user.userId(), user.account(), "device_incident_recovery_checked", "device_incident",
                incidentId, result + ":" + reason, null);
        return recoveryFromRow(repository.findRecoveryCheck(checkId));
    }

    private DeviceService.Command replayReboot(Map<String, Object> incident, AuthUser user, String deviceId,
                                               String key, String reason) {
        Map<String, Object> replay = repository.findIdempotency("reboot:" + key);
        if (replay == null) return null;
        String expected = sha256(user.userId() + "|" + deviceId + "|REBOOT|" + reason);
        if (!expected.equals(text(replay, "request_hash")))
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键对应了不同请求");
        String commandId = text(replay, "response_body");
        if (commandId == null || !commandId.equals(text(incident, "reboot_command_id"))) return null;
        return devices.command(commandId);
    }

    private IncidentDetail detail(Map<String, Object> row, boolean operate) {
        DeviceService.Incident incident = toIncident(row);
        Map<String, Object> check = repository.latestRecoveryCheck(incident.incidentId());
        List<String> actions = "MQTT_HEARTBEAT_TIMEOUT".equals(incident.incidentType())
                ? (operate && "PENDING".equals(incident.stage()) ? List.of("VERIFY_RECOVERY") : List.of())
                : allowedActions(incident.stage(), operate);
        return new IncidentDetail(incident, check == null ? null : checkView(check), actions);
    }

    private RecoveryResult recoveryFromRow(Map<String, Object> check) {
        if (check == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_INCIDENT_NOT_FOUND", "恢复校验记录不存在");
        Map<String, Object> incident = requiredIncident(text(check, "incident_id"));
        return new RecoveryResult(text(check, "result"), RULE_VERSION, text(check, "reason"),
                parseSnapshot(text(check, "state_snapshot")), toIncident(incident));
    }

    private DeviceService.Incident toIncident(Map<String, Object> r) {
        return new DeviceService.Incident(text(r, "incident_id"), text(r, "incident_no"), text(r, "device_id"),
                text(r, "device_no"), text(r, "device_name"), text(r, "incident_type"), text(r, "severity"),
                text(r, "stage"), longNumber(r, "detected_at"), text(r, "reason"), longValue(r, "closed_at"),
                text(r, "block_reason"), bool(r, "simulated"), text(r, "reboot_command_id"));
    }

    private RecoveryCheckView checkView(Map<String, Object> check) {
        return new RecoveryCheckView(text(check, "check_id"), text(check, "result"), text(check, "rule_version"),
                longNumber(check, "checked_at"), text(check, "reason"), parseSnapshot(text(check, "state_snapshot")));
    }

    static List<String> allowedActions(String stage, boolean operate) {
        if (!operate) return List.of();
        if ("PENDING".equals(stage)) return List.of("REBOOT");
        if ("PENDING_VERIFICATION".equals(stage)) return List.of("VERIFY_RECOVERY");
        return List.of();
    }

    private Map<String, Object> snapshot(Map<String, Object> device) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("connectivity", text(device, "connectivity"));
        snapshot.put("has_alarm", bool(device, "has_alarm"));
        snapshot.put("health_code", text(device, "health_code"));
        snapshot.put("last_heartbeat_at", longValue(device, "last_heartbeat_at"));
        return snapshot;
    }

    private Map<String, Object> requiredIncident(String incidentId) {
        String id = incidentId == null ? "" : incidentId.trim();
        if (id.isEmpty() || id.length() > 36)
            throw bad("VALIDATION_ERROR", "ID 格式无效");
        Map<String, Object> row = repository.findIncident(id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_INCIDENT_NOT_FOUND", "设备异常不存在");
        return row;
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() < 8 || value.trim().length() > 96)
            throw bad("VALIDATION_ERROR", "Idempotency-Key 必填且长度为 8–96 个字符");
        return value.trim();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize recovery snapshot", ex); }
    }

    private Map<String, Object> parseSnapshot(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { return Map.of("raw", json); }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String text(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? null : String.valueOf(v);
    }
    private static boolean bool(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v));
    }
    private static long longNumber(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }
    private static Long longValue(Map<String, Object> r, String key) {
        return r.get(key) == null ? null : longNumber(r, key);
    }
    private static String nullToDash(String value) { return value == null || value.isBlank() ? "—" : value; }

    public record IncidentDetail(DeviceService.Incident incident, RecoveryCheckView latestCheck, List<String> allowedActions) { }
    public record RecoveryCheckView(String checkId, String result, String ruleVersion, long checkedAt, String reason,
                                    Map<String, Object> snapshot) { }
    public record RecoveryResult(String result, String ruleVersion, String reason, Map<String, Object> snapshot,
                                 DeviceService.Incident incident) { }
}
