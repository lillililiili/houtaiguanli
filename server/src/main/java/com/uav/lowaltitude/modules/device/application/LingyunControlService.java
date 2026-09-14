package com.uav.lowaltitude.modules.device.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.LingyunControlEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunControlEnvelope.Rejected;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.LingyunControlRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class LingyunControlService {
    public static final String TYPE = "LINGYUN_CONTROL";
    public static final String TOPIC = "device.control.lingyun";
    private final DeviceAccessPolicy access;
    private final DeviceRepository devices;
    private final MqttRepository mqtt;
    private final LingyunControlRepository controls;
    private final ObjectProvider<MqttSessionSupervisor> sessions;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    private final long timeoutMillis;

    public LingyunControlService(DeviceAccessPolicy access, DeviceRepository devices, MqttRepository mqtt,
                                 LingyunControlRepository controls, ObjectProvider<MqttSessionSupervisor> sessions,
                                 AppClock clock, AuditService audit, ObjectMapper json,
                                 org.springframework.core.env.Environment environment) {
        this.access = access; this.devices = devices; this.mqtt = mqtt; this.controls = controls;
        this.sessions = sessions; this.clock = clock; this.audit = audit; this.json = json;
        this.timeoutMillis = Long.parseLong(environment.getProperty("app.lingyun-control.command-timeout-millis", "10000"));
    }

    @Transactional
    public String enqueue(String deviceId, String idempotencyKey, String authorizationId, Integer operationType,
                          Integer operationCmd, Map<String, Object> params, String reason) {
        return enqueue(access.requireDevicesOperate(), deviceId, idempotencyKey, authorizationId, operationType,
                operationCmd, params, reason);
    }

    /** 反制完成后自动接下发：调用方已选定执行人，不再从当前会话取 devices.op。 */
    @Transactional
    public String enqueueUnchecked(AuthUser user, String deviceId, String idempotencyKey, String authorizationId,
                                   Integer operationType, Integer operationCmd, Map<String, Object> params, String reason) {
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录");
        return enqueue(user, deviceId, idempotencyKey, authorizationId, operationType, operationCmd, params, reason);
    }

    private String enqueue(AuthUser user, String deviceId, String idempotencyKey, String authorizationId,
                           Integer operationType, Integer operationCmd, Map<String, Object> params, String reason) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 96)
            throw bad("VALIDATION_ERROR", "Idempotency-Key 必填且最长 96 个字符");
        if (authorizationId == null || authorizationId.trim().length() < 2 || authorizationId.trim().length() > 64)
            throw bad("VALIDATION_ERROR", "authorization_id 长度必须为 2–64 个字符");
        if (reason == null || reason.trim().length() < 2 || reason.trim().length() > 500)
            throw bad("VALIDATION_ERROR", "reason 长度必须为 2–500 个字符");
        if (operationType == null || operationType < 0 || operationType > 2)
            throw bad("VALIDATION_ERROR", "operation_type 必须为 0、1 或 2");
        if (operationCmd == null || !LingyunControlEnvelope.COMMANDS.contains(operationCmd))
            throw bad("VALIDATION_ERROR", "operation_cmd 不在协议 B 白名单");
        String family = LingyunControlEnvelope.family(operationCmd);
        if (family == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROTOCOL_UNSUPPORTED", "该指令码对应的设备类型缩写尚未确认，不能下发");
        Binding binding = mqtt.binding(deviceId, true);
        if (binding == null) throw new ApiException(HttpStatus.CONFLICT, "CONTROL_NOT_ENABLED", "该设备未登记凌云 MQTT，不能按协议 B 控制");
        if (!family.equals(binding.deviceTypeAbbr()))
            throw bad("VALIDATION_ERROR", "指令码与设备类型不匹配");
        Map<String, Object> device = devices.find(deviceId);
        if (device == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        if (!bool(device, "enabled") || !"ONLINE".equals(text(device, "connectivity")))
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_OPERABLE", "仅已启用且在线的设备可下发控制");
        Map<String, Object> protocolParams;
        try {
            protocolParams = LingyunControlEnvelope.protocolParams(params);
        } catch (Rejected ex) {
            if ("DURATION_RANGE".equals(ex.getMessage())) throw bad("VALIDATION_ERROR", "duration 必须在 10–300 秒");
            throw bad("VALIDATION_ERROR", "operation_params 含未知字段");
        }
        if (operationType == 0 && !protocolParams.isEmpty())
            throw bad("VALIDATION_ERROR", "停止指令不能携带 operation_params");
        if (operationType != 0 && LingyunControlEnvelope.REQUIRES_TARGET_ID.contains(operationCmd)
                && (protocolParams.get("targetId") == null || String.valueOf(protocolParams.get("targetId")).isBlank()))
            throw bad("VALIDATION_ERROR", "定向指令必须提供 target_id");
        String key = "lingyun-control:" + idempotencyKey.trim();
        String hash = sha(user.userId() + "|" + deviceId + "|" + operationType + "|" + operationCmd + "|"
                + authorizationId.trim() + "|" + reason.trim() + "|" + write(protocolParams));
        Map<String, Object> replay = devices.findIdempotency(key);
        if (replay != null) {
            if (!hash.equals(text(replay, "request_hash")))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键对应了不同请求");
            return text(replay, "response_body");
        }
        long now = clock.nowMillis();
        String commandId = UUID.randomUUID().toString();
        String commandNo = "LY-" + now + "-" + commandId.substring(0, 6).toUpperCase();
        boolean simulated = "replay".equals(binding.sourceMode());
        controls.insert(commandId, commandNo, deviceId, user.userId(), reason.trim(), binding.sourceMode(), simulated,
                now + timeoutMillis, now, operationType, operationCmd, write(protocolParams), authorizationId.trim());
        devices.insertIdempotency(key, user.userId(), hash, commandId, now);
        controls.addOutbox(UUID.randomUUID().toString(), commandId, now);
        controls.addEvent(deviceId, "LINGYUN_CONTROL_QUEUED", "INFO",
                simulated ? "协议 B 控制已排队（回放）" : "协议 B 控制已排队", now, simulated);
        audit.record(user.userId(), user.account(), "lingyun_control_requested", "device_command", commandId,
                authorizationId.trim(), null);
        return commandId;
    }

    @Transactional
    public void dispatch(String commandId) {
        Map<String, Object> command = controls.control(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        long now = clock.nowMillis();
        String status = text(command, "status");
        if ("QUEUED".equals(status)) {
            controls.updateCommand(commandId, "QUEUED", "SENT", now, null, null);
            status = "SENT";
        }
        MqttSessionSupervisor supervisor = sessions.getIfAvailable();
        if (supervisor == null) throw new IllegalStateException("MQTT_DISABLED");
        String topic = LingyunControlEnvelope.controlTopic(text(command, "provider_code"),
                text(command, "device_type_abbr"), text(command, "external_device_id"));
        Map<String, Object> params = read(text(command, "params_json"));
        int type = ((Number) command.get("operation_type")).intValue();
        int cmd = ((Number) command.get("operation_cmd")).intValue();
        supervisor.publish(text(command, "broker_id"), topic,
                LingyunControlEnvelope.encode(text(command, "command_no"), text(command, "external_device_id"),
                        now, type, cmd, params));
    }

    @Transactional
    public void receive(String broker, String owner, String topic, byte[] bytes, int qos, boolean retained, long receivedAt) {
        if (!mqtt.fence(broker, owner, clock.nowMillis())) throw new IllegalStateException("MQTT_LEASE_LOST");
        if (retained || qos != 1) return;
        final LingyunControlEnvelope.Response response;
        try { response = LingyunControlEnvelope.decodeResponse(topic, bytes); }
        catch (Rejected ignored) { return; }
        Map<String, Object> command = controls.byMsgNo(response.msgNo());
        if (command == null || terminal(text(command, "status"))) return;
        if (!broker.equals(text(command, "broker_id"))) return;
        if (!response.externalId().equals(text(command, "external_device_id"))) return;
        String next = response.code() == 0 ? "SUCCEEDED" : "FAILED";
        String code = response.code() == 0 ? "PROTOCOL_B_OK" : "PROTOCOL_B_FAILED";
        String current = text(command, "status");
        int updated = controls.updateCommand(text(command, "command_id"), current, next, receivedAt, code, response.msg());
        // dispatch() publishes inside the same transaction as QUEUED→SENT. A local echo can
        // arrive while this row is still committed as QUEUED; retry from SENT after that commit.
        if (updated == 0 && "QUEUED".equals(current)) {
            updated = controls.updateCommand(text(command, "command_id"), "SENT", next, receivedAt, code, response.msg());
        }
        if (updated == 1) {
            controls.addReceipt(text(command, "command_id"), text(command, "command_no"), code, receivedAt, response.json());
            controls.addEvent(text(command, "device_id"),
                    next.equals("SUCCEEDED") ? "LINGYUN_CONTROL_SUCCEEDED" : "LINGYUN_CONTROL_FAILED",
                    next.equals("SUCCEEDED") ? "INFO" : "ERROR",
                    response.msg().isBlank() ? next : response.msg(), receivedAt, bool(command, "simulated"));
        }
    }

    public void timeout(String commandId, String detail) {
        Map<String, Object> command = controls.control(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        long now = clock.nowMillis();
        if (controls.updateCommand(commandId, text(command, "status"), "TIMED_OUT", now, "ADAPTER_TIMEOUT", detail) == 1)
            controls.addEvent(text(command, "device_id"), "LINGYUN_CONTROL_TIMED_OUT", "ERROR", detail, now,
                    bool(command, "simulated"));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize control params", ex); }
    }
    private Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (Exception ex) { return Map.of(); }
    }
    private static boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(status);
    }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? null : String.valueOf(value);
    }
    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value));
    }
    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
