package com.uav.lowaltitude.modules.device.application;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.DeviceAdapterPort;
import com.uav.lowaltitude.integration.DeviceAdapterPort.AdapterResult;
import com.uav.lowaltitude.integration.DeviceAdapterRegistry;
import com.uav.lowaltitude.integration.SourceMode;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec;
import com.uav.lowaltitude.modules.device.infrastructure.Countermeasure4ChControlRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class Countermeasure4ChControlService {
    public static final String TYPE = "COUNTERMEASURE_4CH";
    public static final String TOPIC = "device.control.countermeasure";
    public static final String ACTION_ON = "CHANNEL_ON";
    public static final String ACTION_OFF = "CHANNEL_OFF";
    public static final String ACTION_MASK = "SET_MASK";

    private final DeviceAccessPolicy access;
    private final DeviceRepository devices;
    private final Countermeasure4ChControlRepository controls;
    private final DeviceAdapterRegistry adapters;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    private final long timeoutMillis;

    public Countermeasure4ChControlService(DeviceAccessPolicy access, DeviceRepository devices,
                                           Countermeasure4ChControlRepository controls,
                                           DeviceAdapterRegistry adapters, AppClock clock, AuditService audit,
                                           ObjectMapper json, org.springframework.core.env.Environment environment) {
        this.access = access; this.devices = devices; this.controls = controls; this.adapters = adapters;
        this.clock = clock; this.audit = audit; this.json = json;
        this.timeoutMillis = Long.parseLong(environment.getProperty(
                "app.countermeasure-4ch.command-timeout-millis", "10000"));
    }

    @Transactional
    public String enqueue(String deviceId, String idempotencyKey, String authorizationId, String action,
                          String channel, Integer mask, String reason) {
        AuthUser user = access.requireDevicesOperate();
        return enqueue(user, deviceId, idempotencyKey, authorizationId, action, channel, mask, reason);
    }

    /** 处置停止路径已鉴权 {@code disposal:stop}，不再要求 devices.op。 */
    @Transactional
    public String enqueueUnchecked(AuthUser user, String deviceId, String idempotencyKey, String authorizationId,
                                   String action, String channel, Integer mask, String reason) {
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录");
        return enqueue(user, deviceId, idempotencyKey, authorizationId, action, channel, mask, reason);
    }

    private String enqueue(AuthUser user, String deviceId, String idempotencyKey, String authorizationId,
                           String action, String channel, Integer mask, String reason) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 96)
            throw bad("VALIDATION_ERROR", "Idempotency-Key 必填且最长 96 个字符");
        if (authorizationId == null || authorizationId.trim().length() < 2 || authorizationId.trim().length() > 64)
            throw bad("VALIDATION_ERROR", "authorization_id 长度必须为 2–64 个字符");
        if (reason == null || reason.trim().length() < 2 || reason.trim().length() > 500)
            throw bad("VALIDATION_ERROR", "reason 长度必须为 2–500 个字符");
        String normalizedAction = action == null ? "" : action.trim();
        if (!List.of(ACTION_ON, ACTION_OFF, ACTION_MASK).contains(normalizedAction))
            throw bad("VALIDATION_ERROR", "action 必须是 CHANNEL_ON、CHANNEL_OFF 或 SET_MASK");
        Integer storedMask = mask;
        String storedChannel = null;
        if (ACTION_MASK.equals(normalizedAction)) {
            if (channel != null && !channel.isBlank())
                throw bad("VALIDATION_ERROR", "SET_MASK 不能指定单通道");
            if (mask == null || !Countermeasure4ChCodec.restSetMask(mask))
                throw bad("VALIDATION_ERROR", "SET_MASK 的 mask 只允许 0、13 或 15（全关/驱离/迫降）");
        } else {
            if (mask != null)
                throw bad("VALIDATION_ERROR", "单通道动作不能携带 mask");
            storedChannel = channel == null ? null : channel.trim();
            storedMask = Countermeasure4ChCodec.channelBit(storedChannel);
        }
        Map<String, Object> device = devices.find(deviceId);
        if (device == null) throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        if (!DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(text(device, "protocol_code"))
                || !"live".equals(text(device, "source_mode")))
            throw new ApiException(HttpStatus.CONFLICT, "CONTROL_NOT_ENABLED",
                    "仅已接入的四通道网络控制器可下发继电器设置");
        if (!bool(device, "enabled") || !"ONLINE".equals(text(device, "connectivity")))
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_OPERABLE", "仅已启用且在线的设备可下发控制");
        String key = "countermeasure-4ch:" + idempotencyKey.trim();
        String hash = sha(user.userId() + "|" + deviceId + "|" + normalizedAction + "|"
                + String.valueOf(storedChannel) + "|" + storedMask + "|"
                + authorizationId.trim() + "|" + reason.trim());
        Map<String, Object> replay = devices.findIdempotency(key);
        if (replay != null) {
            if (!hash.equals(text(replay, "request_hash")))
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键对应了不同请求");
            return text(replay, "response_body");
        }
        long now = clock.nowMillis();
        String commandId = UUID.randomUUID().toString();
        String commandNo = "CM4-" + now + "-" + commandId.substring(0, 6).toUpperCase();
        boolean simulated = bool(device, "simulated");
        controls.insert(commandId, commandNo, deviceId, user.userId(), reason.trim(), text(device, "source_mode"),
                simulated, now + timeoutMillis, now, normalizedAction, storedChannel, storedMask, authorizationId.trim());
        devices.insertIdempotency(key, user.userId(), hash, commandId, now);
        controls.addOutbox(UUID.randomUUID().toString(), commandId, now);
        controls.addEvent(deviceId, "COUNTERMEASURE_4CH_QUEUED", "INFO",
                simulated ? "四通道设置已排队（本机模拟）" : "四通道设置已排队", now, simulated);
        audit.record(user.userId(), user.account(), "countermeasure_4ch_requested", "device_command", commandId,
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
        Map<String, Object> device = devices.find(text(command, "device_id"));
        if (device == null) throw new IllegalStateException("device disappeared for command " + commandId);
        DeviceAdapterPort adapter = adapters.require(SourceMode.valueOf(text(device, "source_mode")),
                text(device, "protocol_code"));
        String action = text(command, "action");
        Integer stored = number(command, "mask");
        Integer channelBit = ACTION_MASK.equals(action) ? null : stored;
        Integer mask = ACTION_MASK.equals(action) ? stored : null;
        AdapterResult result = adapter.setRelays(new DeviceAdapterPort.RelayWork(commandId,
                text(command, "device_id"), configuration(device), action, channelBit, mask));
        long completed = clock.nowMillis();
        boolean simulated = bool(command, "simulated");
        if (result.success()) {
            if (controls.updateCommand(commandId, status, "SUCCEEDED", completed, result.resultCode(), result.detail()) == 1) {
                controls.addReceipt(commandId, text(command, "command_no"), result.resultCode(), completed,
                        write(Map.of("simulated", simulated, "detail", result.detail() == null ? "" : result.detail())));
                controls.addEvent(text(command, "device_id"), "COUNTERMEASURE_4CH_SUCCEEDED", "INFO",
                        simulated ? "四通道模拟回码已接收，不代表射频已发射" : "四通道回码已接收，不代表射频已发射",
                        completed, simulated);
            }
        } else {
            controls.updateCommand(commandId, status, "FAILED", completed, result.resultCode(), result.detail());
            controls.addEvent(text(command, "device_id"), "COUNTERMEASURE_4CH_FAILED", "ERROR",
                    (simulated ? "四通道模拟设置失败：" : "四通道设置失败：") + result.detail(), completed, simulated);
        }
    }

    public void timeout(String commandId, String detail) {
        Map<String, Object> command = controls.control(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        long now = clock.nowMillis();
        if (controls.updateCommand(commandId, text(command, "status"), "TIMED_OUT", now, "ADAPTER_TIMEOUT", detail) == 1)
            controls.addEvent(text(command, "device_id"), "COUNTERMEASURE_4CH_TIMED_OUT", "ERROR", detail, now,
                    bool(command, "simulated"));
    }

    private String configuration(Map<String, Object> device) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            Object connection = devices.findProfile(text(device, "device_id"));
            root.put("connection", connection == null ? Map.of() : connection);
            root.put("allowed_cidrs", text(device, "allowed_cidrs"));
            root.put("credential_ref", text(device, "source_credential_ref"));
            Object protocol = devices.findProtocolProfile(text(device, "device_id"),
                    DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0);
            root.put("protocol_configuration", protocol == null ? Map.of() : protocol);
            return json.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot build countermeasure adapter configuration", ex);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize countermeasure receipt", ex); }
    }

    private static boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(status);
    }
    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? null : String.valueOf(value);
    }
    private static Integer number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
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
