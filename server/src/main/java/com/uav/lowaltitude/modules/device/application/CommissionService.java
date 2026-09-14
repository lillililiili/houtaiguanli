package com.uav.lowaltitude.modules.device.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.device.application.DeviceService.ConnectionProfile;
import com.uav.lowaltitude.modules.device.infrastructure.CommissionRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.integration.DeviceAdapterRegistry;
import com.uav.lowaltitude.integration.SourceMode;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

@Service
public class CommissionService {

    private final CommissionRepository repository;
    private final DeviceRepository devices;
    private final DeviceAccessPolicy access;
    private final AppClock clock;
    private final AppProperties properties;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final DeviceAdapterRegistry adapters;

    public CommissionService(CommissionRepository repository, DeviceRepository devices, DeviceAccessPolicy access,
                             AppClock clock, AppProperties properties, AuditService audit, ObjectMapper objectMapper,
                             DeviceAdapterRegistry adapters) {
        this.repository = repository;
        this.devices = devices;
        this.access = access;
        this.clock = clock;
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.adapters = adapters;
    }

    public TaskPage list(String deviceId, String status, int page, int size) {
        access.requireCommissionRead();
        int safePage = Math.max(page, 1), safeSize = Math.min(Math.max(size, 1), 100);
        List<Task> items = repository.listTasks(blank(deviceId), blank(status), (safePage - 1) * safeSize, safeSize)
                .stream().map(this::task).toList();
        return new TaskPage(items, safePage, safeSize, repository.countTasks(blank(deviceId), blank(status)));
    }

    public Task get(String id) {
        access.requireCommissionRead();
        return required(id);
    }

    @Transactional
    public Task create(String deviceId, String previousTaskId) {
        AuthUser user = access.requireCommissionOperate();
        Map<String, Object> device = devices.find(deviceId);
        if (device == null) throw notFound("DEVICE_NOT_FOUND", "设备不存在");
        if (DeviceProtocolCodes.LINGYUN_MQTT_V8_6.equals(device.get("protocol_code"))
                || DeviceProtocolCodes.EO_EDGE_MQTT_20250826.equals(device.get("protocol_code")))
            throw new ApiException(HttpStatus.CONFLICT,"PROTOCOL_UNSUPPORTED","MQTT 协议本轮不提供调测能力");
        if (!asBoolean(device.get("enabled")))
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NOT_OPERABLE", "停用设备不能发起调测");
        if (previousTaskId != null) {
            Task previous = required(previousTaskId);
            if (!deviceId.equals(previous.deviceId()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "前次任务必须属于同一设备");
        }
        long now = clock.nowMillis();
        String id = UUID.randomUUID().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("commission_id", id);
        p.put("commission_no", "CT-" + now + "-" + id.substring(0, 6).toUpperCase());
        p.put("previous_task_id", blank(previousTaskId));
        p.put("device_id", deviceId);
        p.put("requested_by", user.userId());
        String sourceMode = text(device, "source_mode");
        String protocolCode = text(device, "protocol_code");
        p.put("source_mode", sourceMode);
        p.put("protocol_code", protocolCode);
        p.put("protocol_version", text(device, "protocol_version"));
        Map<String, Object> protocolProfile = devices.findProtocolProfile(deviceId, protocolCode);
        p.put("protocol_configuration_json", protocolProfile == null ? null : write(protocolProfile));
        p.put("allowed_cidrs_snapshot", text(device, "allowed_cidrs"));
        p.put("source_credential_ref_snapshot", text(device, "source_credential_ref"));
        p.put("simulated", asBoolean(device.get("simulated")));
        p.put("created_at", now);
        p.put("updated_at", now);
        repository.insertTask(p);
        repository.addEvent(UUID.randomUUID().toString(), id, "CREATED", "INFO",
                "调测任务已创建，等待建立连接", now, asBoolean(device.get("simulated")));
        audit.record(user.userId(), user.account(), "commission_create", "commission_task", id, deviceId, null);
        return required(id);
    }

    @Transactional
    public Task connect(String id, long version) {
        AuthUser user = access.requireCommissionOperate();
        Task before = required(id);
        if (!"CREATED".equals(before.status())) throw illegal("任务仅可从 CREATED 发起连接");
        Map<String, Object> device = devices.find(before.deviceId());
        String sourceMode = text(device, "source_mode");
        if (!adapters.supports(SourceMode.valueOf(sourceMode), text(device, "protocol_code")))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ADAPTER_UNAVAILABLE", "设备协议适配器不可用");
        long now = clock.nowMillis();
        if (repository.transition(id, version, "CREATED", "CONNECTING", now) != 1) throw conflict();
        repository.addEvent(UUID.randomUUID().toString(), id, "CONNECTING", "INFO",
                before.simulated() ? "模拟适配器正在建立逻辑连接" : "协议适配器正在建立只读连接",
                now, before.simulated());
        devices.addOutbox(UUID.randomUUID().toString(), "commission.connect", id, now, now + 600L);
        audit.record(user.userId(), user.account(), "commission_connect", "commission_task", id, null, null);
        return required(id);
    }

    @Transactional
    public Task saveConfiguration(String id, long version, ConnectionProfile configuration) {
        AuthUser user = access.requireCommissionOperate();
        Task before = required(id);
        if (!"CONNECTED".equals(before.status())) throw illegal("仅已连接任务可以保存配置");
        validate(configuration);
        long now = clock.nowMillis();
        String json = write(configuration);
        if (repository.saveConfiguration(id, version, json, now) != 1) throw conflict();
        repository.addEvent(UUID.randomUUID().toString(), id, "READY", "INFO",
                "连接参数快照已保存，任务可以开始", now, before.simulated());
        audit.record(user.userId(), user.account(), "commission_configuration", "commission_task", id, null, null);
        return required(id);
    }

    @Transactional
    public Task start(String id, long version) {
        AuthUser user = access.requireCommissionOperate();
        Task before = required(id);
        if (!"READY".equals(before.status())) throw illegal("任务仅可从 READY 开始调测");
        long now = clock.nowMillis();
        if (repository.transition(id, version, "READY", "RUNNING", now) != 1) throw conflict();
        repository.markStarted(id, now);
        repository.addEvent(UUID.randomUUID().toString(), id, "RUNNING", "INFO",
                before.simulated() ? "模拟调测已开始；结果不代表真实设备验收" : "协议链路调测已开始",
                now, before.simulated());
        devices.addOutbox(UUID.randomUUID().toString(), "commission.run", id, now, now + 800L);
        audit.record(user.userId(), user.account(), "commission_start", "commission_task", id, null, null);
        return required(id);
    }

    @Transactional
    public Task cancel(String id, long version) {
        AuthUser user = access.requireCommissionOperate();
        Task before = required(id);
        if (isTerminal(before.status())) throw illegal("终态任务不能取消");
        long now = clock.nowMillis();
        if (repository.cancel(id, version, now) != 1) throw conflict();
        repository.addEvent(UUID.randomUUID().toString(), id, "CANCELLED", "WARN", "调测任务已取消", now, before.simulated());
        audit.record(user.userId(), user.account(), "commission_cancel", "commission_task", id, null, null);
        return required(id);
    }

    public EventBatch events(String id, long afterSeq, int limit) {
        access.requireCommissionRead();
        required(id);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<TaskEvent> items = repository.events(id, Math.max(afterSeq, 0), safeLimit).stream()
                .map(r -> new TaskEvent(longNumber(r, "event_seq"), text(r, "event_id"), text(r, "stage_code"),
                        text(r, "level_code"), text(r, "message"), longNumber(r, "occurred_at"),
                        asBoolean(r.get("simulated")))).toList();
        long next = items.isEmpty() ? Math.max(afterSeq, 0) : items.get(items.size() - 1).eventSeq();
        return new EventBatch(items, next);
    }

    public Report report(String id) {
        access.requireCommissionRead();
        Task task = required(id);
        if (!List.of("PASSED", "FAILED", "UNTESTABLE").contains(task.status()))
            throw illegal("任务尚未形成报告");
        return new Report(task.commissionId(), task.commissionNo(), task.deviceId(), task.deviceNo(), task.deviceName(),
                task.status(), task.protocolCode(), task.sourceMode(), task.simulated(), task.startedAt(), task.finishedAt(),
                parse(task.criteriaSnapshot()), parse(task.resultsJson()), reportWarning(task));
    }

    private static String reportWarning(Task task) {
        if (task.simulated()) return "开发模拟结果，不代表真实设备验收或投运依据";
        if ("COUNTERMEASURE_TCP_4CH_V2_0".equals(task.protocolCode()))
            return "协议链路调测结果；不包含射频发射验证";
        if ("RADAR_TCP_V3_0_0".equals(task.protocolCode()))
            return "协议链路调测结果；雷达待机无航迹时结论可为不可判定";
        return "协议链路调测结果";
    }

    private Task required(String id) {
        Map<String, Object> row = repository.findTask(id);
        if (row == null) throw notFound("COMMISSION_TASK_NOT_FOUND", "调测任务不存在");
        return task(row);
    }

    private Task task(Map<String, Object> r) {
        return new Task(text(r, "commission_id"), text(r, "commission_no"), text(r, "previous_task_id"),
                text(r, "device_id"), text(r, "device_no"), text(r, "device_name"),
                text(r, "device_type_name"), text(r, "channel"), text(r, "status"),
                text(r, "resolved_protocol_code"), text(r, "resolved_protocol_version"), parse(text(r, "configuration_json")),
                text(r, "criteria_snapshot"), text(r, "results_json"), text(r, "source_mode"),
                asBoolean(r.get("simulated")), longNumber(r, "version"), longValue(r, "started_at"),
                longValue(r, "finished_at"), longNumber(r, "created_at"), longNumber(r, "updated_at"));
    }

    private void validate(ConnectionProfile c) {
        if (c == null || blank(c.transport()) == null || blank(c.host()) == null || c.port() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "传输方式、主机和端口必填");
        if (!List.of("TCP", "HTTP", "WS").contains(c.transport()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "transport 不在允许范围内");
        if (c.port() < 1 || c.port() > 65535)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "端口必须在 1 到 65535 之间");
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize commission configuration", ex); }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readTree(json); }
        catch (Exception ex) { return null; }
    }

    private static boolean isTerminal(String status) {
        return List.of("PASSED", "FAILED", "UNTESTABLE", "CANCELLED").contains(status);
    }
    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "任务已被更新，请刷新后重试"); }
    private static ApiException illegal(String message) { return new ApiException(HttpStatus.CONFLICT, "ILLEGAL_STATE", message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
    private static String blank(String v) { return v == null || v.trim().isEmpty() ? null : v.trim(); }
    private static String text(Map<String, Object> r, String key) { Object v = r.get(key); return v == null ? null : String.valueOf(v); }
    private static long longNumber(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Number n ? n.longValue() : 0; }
    private static Long longValue(Map<String, Object> r, String key) { return r.get(key) == null ? null : longNumber(r, key); }
    private static boolean asBoolean(Object v) { return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v)); }

    public record TaskPage(List<Task> items, int page, int size, long total) { }
    public record Task(String commissionId, String commissionNo, String previousTaskId, String deviceId,
                       String deviceNo, String deviceName, String deviceTypeName, String channel, String status,
                       String protocolCode, String protocolVersion, JsonNode configuration, String criteriaSnapshot, String resultsJson,
                       String sourceMode, boolean simulated, long version, Long startedAt, Long finishedAt,
                       long createdAt, long updatedAt) { }
    public record TaskEvent(long eventSeq, String eventId, String stageCode, String levelCode,
                            String message, long occurredAt, boolean simulated) { }
    public record EventBatch(List<TaskEvent> items, long nextSeq) { }
    public record Report(String commissionId, String commissionNo, String deviceId, String deviceNo,
                         String deviceName, String status, String protocolCode, String sourceMode, boolean simulated,
                         Long startedAt, Long finishedAt, JsonNode criteria, JsonNode results, String warning) { }
}
