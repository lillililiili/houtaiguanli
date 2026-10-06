package com.uav.lowaltitude.modules.device.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;
    private final long timeoutMillis;
    private final com.uav.lowaltitude.modules.disposal.application.DisposalCommandGuard disposalGuard;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public LingyunControlService(DeviceAccessPolicy access, DeviceRepository devices, MqttRepository mqtt,
                                 LingyunControlRepository controls, ObjectProvider<MqttSessionSupervisor> sessions,
                                 AppClock clock, AuditService audit, ObjectMapper json, ApplicationEventPublisher events,
                                 org.springframework.core.env.Environment environment,
                                 com.uav.lowaltitude.modules.disposal.application.DisposalCommandGuard disposalGuard,
                                 org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.access = access; this.devices = devices; this.mqtt = mqtt; this.controls = controls;
        this.sessions = sessions; this.clock = clock; this.audit = audit; this.json = json; this.events = events;
        this.disposalGuard = disposalGuard;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        if (operationType != 0 && !disposalGuard.mayStart(authorizationId.trim()))
            throw new ApiException(HttpStatus.CONFLICT,"AUTHORIZATION_STOPPED","该处置已停止或当前现场核查不允许继续执行，请查看事件处置记录");
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
        if (operationType != 0 && "BAD".equals(text(device,"health_code")))
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_OPERABLE", "设备已上报故障，不能下发启动指令");
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

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void dispatch(String commandId) {
        PreparedDispatch prepared = transactions.execute(transaction -> prepareDispatch(commandId));
        if (prepared == null) return;
        transactions.executeWithoutResult(transaction -> {
            // Keep the event -> authorization -> command lock order shared by emergency stop.
            Map<String, Object> initial = controls.control(commandId);
            if (initial == null || !"SENT".equals(text(initial,"status"))) return;
            boolean allowed = ((Number)initial.get("operation_type")).intValue()==0
                    || disposalGuard.mayStart(text(initial,"control_authorization_id"));
            controls.lockCommand(commandId);
            Map<String,Object> current = controls.control(commandId);
            if (current == null || !"SENT".equals(text(current,"status"))) return;
            if (!dispatchAllowed(current, allowed)) return;
            try {
                prepared.supervisor().publish(prepared.brokerId(), prepared.topic(), prepared.payload());
            } catch (RuntimeException uncertain) {
                // Publication may have reached the device. A failed acknowledgment must not cause another start.
                if (controls.updateCommand(commandId,"SENT","TIMED_OUT",clock.nowMillis(),"PUBLISH_RESULT_UNKNOWN",
                        "指令发送结果未知，等待设备回执或现场核查；不自动重复下发") == 1) finished(commandId);
            }
        });
    }

    private PreparedDispatch prepareDispatch(String commandId) {
        Map<String, Object> command = controls.control(commandId);
        if (command == null || !"QUEUED".equals(text(command, "status"))) return null;
        boolean allowed = ((Number)command.get("operation_type")).intValue()==0
                || disposalGuard.mayStart(text(command,"control_authorization_id"));
        controls.lockCommand(commandId);
        command = controls.control(commandId);
        if (command == null || !"QUEUED".equals(text(command,"status"))) return null;
        if (!dispatchAllowed(command, allowed)) return null;
        MqttSessionSupervisor supervisor = sessions.getIfAvailable();
        if (supervisor == null) throw new IllegalStateException("MQTT_DISABLED");
        long now = clock.nowMillis();
        String topic = LingyunControlEnvelope.controlTopic(text(command, "provider_code"),
                text(command, "device_type_abbr"), text(command, "external_device_id"));
        byte[] payload = LingyunControlEnvelope.encode(text(command,"command_no"),text(command,"external_device_id"),now,
                ((Number)command.get("operation_type")).intValue(),((Number)command.get("operation_cmd")).intValue(),
                read(text(command,"params_json")));
        // Commit the send attempt before touching the network. SENT is never blindly republished.
        if (controls.updateCommand(commandId,"QUEUED","SENT",now,null,null)!=1) return null;
        return new PreparedDispatch(supervisor,text(command,"broker_id"),topic,payload);
    }

    private boolean dispatchAllowed(Map<String,Object> command, boolean allowed) {
        String commandId = text(command,"command_id");
        if (!allowed) {
            if (controls.updateCommand(commandId,text(command,"status"),"CANCELLED",clock.nowMillis(),
                    "AUTHORIZATION_STOPPED","处置已停止或当前现场核查不允许执行，禁止重投旧启动指令；此前设备动作仍需核查") == 1) {
                finished(commandId);
            }
            return false;
        }
        long now = clock.nowMillis();
        String status = text(command, "status");
        if (((Number)command.get("deadline_at")).longValue()<=now) {
            if (controls.updateCommand(commandId,status,"TIMED_OUT",now,"COMMAND_EXPIRED","发送窗口已到期，不重复下发；实际设备状态需回执核查") == 1) {
                finished(commandId);
            }
            return false;
        }
        Map<String, Object> device = devices.find(text(command,"device_id"));
        if (((Number)command.get("operation_type")).intValue()!=0
                && (device==null || !bool(device,"enabled") || !"ONLINE".equals(text(device,"connectivity"))
                    || "BAD".equals(text(device,"health_code")))) {
            if (controls.updateCommand(commandId,status,"CANCELLED",now,"DEVICE_NOT_OPERABLE",
                    "设备已停用、离线或上报故障，取消尚未发送的启动指令；此前设备动作仍需核查") == 1) {
                finished(commandId);
            }
            return false;
        }
        return true;
    }

    /** 指令进入任一终态都通知处置结案（含取消和发送结果未知），处置授权不能停在“执行中”等人打开详情才同步。 */
    private void finished(String commandId) { events.publishEvent(new DeviceCommandFinished(commandId)); }

    private record PreparedDispatch(MqttSessionSupervisor supervisor,String brokerId,String topic,byte[] payload) { }

    @Transactional
    public void receive(String broker, String owner, String topic, byte[] bytes, int qos, boolean retained, long receivedAt) {
        if (!mqtt.fence(broker, owner, clock.nowMillis())) throw new IllegalStateException("MQTT_LEASE_LOST");
        if (retained || qos != 1) return;
        final LingyunControlEnvelope.Response response;
        try { response = LingyunControlEnvelope.decodeResponse(topic, bytes); }
        catch (Rejected ignored) { return; }
        Map<String, Object> command = controls.byMsgNo(response.msgNo());
        if (!matchesSource(command, broker, response)) return;
        controls.lockCommand(text(command, "command_id"));
        command = controls.control(text(command, "command_id"));
        if (!matchesSource(command, broker, response)) return;
        if (terminal(text(command, "status"))) {
            if (List.of("TIMED_OUT", "CANCELLED").contains(text(command, "status")) && command.get("issued_at") != null
                    && controls.addLateReceipt(text(command, "command_id"), text(command, "command_no"),
                            response.code() == 0 ? "PROTOCOL_B_OK" : "PROTOCOL_B_FAILED", receivedAt, response.json())) {
                controls.addEvent(text(command, "device_id"), "LINGYUN_CONTROL_LATE_RESPONSE", "WARN",
                        "原指令已超时或取消，收到关联该指令的迟到" + (response.code() == 0 ? "成功" : "失败")
                                + "回执；保留原办理结论，不自动重发或续链", receivedAt, bool(command, "simulated"));
            }
            return;
        }
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
            events.publishEvent(new DeviceCommandFinished(text(command, "command_id")));
        }
    }

    public void timeout(String commandId, String detail) {
        Map<String, Object> command = controls.control(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        long now = clock.nowMillis();
        if (controls.updateCommand(commandId, text(command, "status"), "TIMED_OUT", now, "ADAPTER_TIMEOUT", detail) == 1) {
            controls.addEvent(text(command, "device_id"), "LINGYUN_CONTROL_TIMED_OUT", "ERROR", detail, now,
                    bool(command, "simulated"));
            events.publishEvent(new DeviceCommandFinished(commandId));
        }
    }

    private static boolean matchesSource(Map<String, Object> command, String broker,
                                         LingyunControlEnvelope.Response response) {
        return command != null && broker.equals(text(command, "broker_id"))
                && response.provider().equals(text(command, "provider_code"))
                && response.type().equals(text(command, "device_type_abbr"))
                && response.externalId().equals(text(command, "external_device_id"));
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
