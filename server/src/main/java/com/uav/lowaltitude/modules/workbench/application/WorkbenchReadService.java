package com.uav.lowaltitude.modules.workbench.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.alarm.domain.UavEventState;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceBusinessScopeRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.domain.RiskState;
import com.uav.lowaltitude.modules.workbench.api.WorkbenchDtos.DetailDto;
import com.uav.lowaltitude.modules.workbench.api.WorkbenchDtos.ItemDto;
import com.uav.lowaltitude.modules.workbench.api.WorkbenchDtos.ListDto;
import com.uav.lowaltitude.modules.workbench.api.WorkbenchDtos.TimelineEntryDto;
import com.uav.lowaltitude.modules.workbench.infrastructure.WorkbenchReadRepository;
import com.uav.lowaltitude.modules.workbench.infrastructure.WorkbenchReadRepository.Branches;
import com.uav.lowaltitude.modules.workbench.infrastructure.WorkbenchReadRepository.HandoffRow;
import com.uav.lowaltitude.modules.workbench.infrastructure.WorkbenchReadRepository.ItemRow;
import com.uav.lowaltitude.modules.workbench.infrastructure.WorkbenchReadRepository.VerificationRow;
import com.uav.lowaltitude.modules.workbench.infrastructure.WorkbenchReadRepository.WorkbenchQuery;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 工作台是三类源事项的只读派生视图，不拥有第二套状态，也不提供写接口。
 * workbench:read 只允许“打开工作台”；每一类事项还要再过一遍源模块的读权限与范围，
 * 因为聚合不能成为绕过 alarm:read / risk:read / device:read 可见范围的后门。
 */
@Service
public class WorkbenchReadService {
    static final String UAV_EVENT = WorkbenchReadRepository.UAV_EVENT;
    static final String RISK = WorkbenchReadRepository.RISK;
    static final String DEVICE_INCIDENT = WorkbenchReadRepository.DEVICE_INCIDENT;
    static final List<String> KINDS = List.of(UAV_EVENT, RISK, DEVICE_INCIDENT);
    static final String AVAILABLE = "AVAILABLE", FORBIDDEN = "FORBIDDEN", UNCONFIGURED = "UNCONFIGURED";
    private static final Set<String> ALLOWED = Set.of("kind", "state", "severity", "severity_min", "occurred_from", "occurred_to",
            "owner_org_id", "district_id", "source_mode", "page", "size");
    private static final Map<String, Set<String>> STATES = Map.of(
            UAV_EVENT, Set.of("PENDING_VERIFICATION", "CONFIRMED", "FALSE_POSITIVE"),
            RISK, Set.of("PENDING_VERIFICATION", "PENDING_NOTIFICATION", "NOTIFIED", "ACKNOWLEDGED", "EXCLUDED"),
            DEVICE_INCIDENT, Set.of("PENDING", "PROCESSING", "PENDING_VERIFICATION", "RECOVERED"));
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> SOURCE_MODES = Set.of("mock", "replay", "live");
    private static final Map<String, String> SEVERITY_LABEL = Map.of("CRITICAL", "紧急", "HIGH", "高", "MEDIUM", "中", "LOW", "低");
    /* 标题里的类型代码统一译成业务用语；未收录的代码原样返回，不猜测含义。 */
    private static final Map<String, String> ALARM_TYPE_LABEL = Map.of("UAV_INTRUSION", "无人机入侵", "UAV", "无人机告警",
            "RULE_LEGALITY", "飞行违规");
    private static final Map<String, String> RISK_TYPE_LABEL = Map.of("FLIGHT_OPERATION", "飞行作业风险", "AIRSPACE", "空域风险",
            "SPACE_OBJECT", "空中异物风险", "FOREIGN_OBJECT", "空中异物风险");
    private static final Map<String, String> INCIDENT_TYPE_LABEL = Map.of("OFFLINE", "设备离线", "ABNORMAL", "设备异常", "DEGRADED", "性能降级",
            "ADAPTER_TIMEOUT", "适配器超时", "REBOOT_FAILED", "重启失败",
            // 设备域实际写入的异常类型码（LocalDeviceSeeder / 演示种子）；没收录会把英文码摆到工作台标题上。
            "DEVICE_OFFLINE", "设备离线", "LINK_DEGRADED", "链路降级", "STATE_UNKNOWN", "状态未知", "MQTT_HEARTBEAT_TIMEOUT", "MQTT 心跳超时");
    private static final Map<String, String> SOURCE_MODE_LABEL = Map.of("mock", "模拟", "replay", "回放", "live", "实时");
    private static final Map<String, String> UAV_STATE_LABEL = Map.of("PENDING_VERIFICATION", "待核实",
            "CONFIRMED", "已核实，待处置", "FALSE_POSITIVE", "误报");
    private static final Map<String, String> RISK_STATE_LABEL = Map.of("PENDING_VERIFICATION", "待核验", "PENDING_NOTIFICATION", "待通知",
            "NOTIFIED", "已通知", "ACKNOWLEDGED", "已回执", "EXCLUDED", "已排除");
    private static final Map<String, String> DEVICE_STAGE_LABEL = Map.of("PENDING", "待处理", "PROCESSING", "处理中",
            "PENDING_VERIFICATION", "待验证", "RECOVERED", "已恢复");

    private final AccessControlService access;
    private final AccessService menuAccess;
    private final DeviceBusinessScopeRepository deviceScope;
    private final WorkbenchReadRepository repository;
    private final AppClock clock;

    public WorkbenchReadService(AccessControlService access, AccessService menuAccess, DeviceBusinessScopeRepository deviceScope,
            WorkbenchReadRepository repository, AppClock clock) {
        this.access = access; this.menuAccess = menuAccess; this.deviceScope = deviceScope; this.repository = repository; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListDto list(MultiValueMap<String, String> values) {
        // 鉴权先于任何参数解析：未授权者不能借 400 与 403 的差异探测接口。
        access.require(PermissionCode.WORKBENCH_READ);
        Sources sources = sources();
        Request request = new Request(values);
        Page page = request.page();
        WorkbenchQuery query = request.query();
        Branches branches = sources.branches();
        Map<String, Long> counted = repository.countByKind(query, branches);
        long total = counted.values().stream().mapToLong(Long::longValue).sum();
        Capabilities capabilities = capabilities(sources);
        List<ItemDto> items = repository.list(query, branches, page.offset(), page.size()).stream()
                .map(row -> item(row, capabilities)).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> availability = new LinkedHashMap<>();
        for (String kind : KINDS) {
            String state = sources.availability.get(kind);
            availability.put(kind, state);
            // 无权限/未配置的类别 count 必须是 null：0 会被误读成“该类没有事项”。
            counts.put(kind, AVAILABLE.equals(state) ? counted.getOrDefault(kind, 0L) : null);
        }
        return new ListDto(items, total, page.page(), page.size(), counts, availability, clock.nowMillis());
    }

    @Transactional(readOnly = true)
    public DetailDto detail(String kindValue, String sourceIdValue) {
        access.require(PermissionCode.WORKBENCH_READ);
        Sources sources = sources();
        String kind = kind(kindValue);
        AccessDecision decision = sources.decisions.get(kind);
        // 详情同样要求该类源读权限；没有权限时统一 403，不区分对象是否存在。
        if (decision == null) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问");
        String sourceId = id(sourceIdValue);
        // 有权限但设备归属未配置：该类事项对任何人都不存在，按 404 处理而不是 403。
        if (!AVAILABLE.equals(sources.availability.get(kind))) throw notFound();
        ItemRow row = repository.find(kind, sourceId, decision);
        if (row == null) throw notFound();
        Capabilities capabilities = capabilities(sources);
        List<TimelineEntryDto> timeline = new ArrayList<>();
        Map<String, String> availability = new LinkedHashMap<>();
        switch (kind) {
            case UAV_EVENT -> {
                repository.uavVerifications(sourceId, decision).forEach(v -> timeline.add(verification(v)));
                availability.put("verifications", AVAILABLE);
            }
            case RISK -> {
                repository.riskVerifications(sourceId, decision).forEach(v -> timeline.add(verification(v)));
                availability.put("verifications", AVAILABLE);
                // 交接记录是独立权限域：缺 handoff:read 时省略该段并标记，不把整个详情变成 403。
                if (capabilities.handoffRead()) {
                    repository.riskHandoffs(sourceId, decision).forEach(h -> timeline.add(handoff(h)));
                    availability.put("handoffs", AVAILABLE);
                } else {
                    availability.put("handoffs", FORBIDDEN);
                }
            }
            default -> {
                // 设备异常只有已保存的事实，没有核实历史，也没有可委托的动作；禁止去查全局审计表补时间线。
                timeline.add(TimelineEntryDto.deviceFact("DEVICE_INCIDENT_DETECTED", row.receivedAt(), row.state(), row.typeCode(),
                        row.deviceNo(), row.deviceName(), row.reasonText()));
                if (row.updatedAt() != null) timeline.add(TimelineEntryDto.deviceFact("DEVICE_INCIDENT_CLOSED", row.updatedAt(),
                        row.state(), row.typeCode(), row.deviceNo(), row.deviceName(), null));
                // 设备异常时间线仍只读本单事实；重启/校验过程走源模块接口，不查全局审计表。
                availability.put("incident_facts", AVAILABLE);
            }
        }
        timeline.sort(Comparator.comparingLong(TimelineEntryDto::at));
        return new DetailDto(item(row, capabilities), timeline, availability);
    }

    /** 逐类探测源读权限：探测失败只影响该类的可用性，不抛出。 */
    private Sources sources() {
        Map<String, AccessDecision> decisions = new LinkedHashMap<>();
        Map<String, String> availability = new LinkedHashMap<>();
        AccessDecision uav = probe(PermissionCode.ALARM_READ);
        if (uav != null) decisions.put(UAV_EVENT, uav);
        availability.put(UAV_EVENT, uav == null ? FORBIDDEN : AVAILABLE);
        AccessDecision risk = probe(PermissionCode.RISK_READ);
        if (risk != null) decisions.put(RISK, risk);
        availability.put(RISK, risk == null ? FORBIDDEN : AVAILABLE);
        // 设备异常需要 device:read 动作，还要既有运维菜单读取许可 monitoring.read（requireBusinessData 语义）。
        AccessDecision device = probe(PermissionCode.DEVICE_READ);
        if (device != null && !menuReadable("monitoring.read")) device = null;
        if (device == null) {
            availability.put(DEVICE_INCIDENT, FORBIDDEN);
        } else {
            decisions.put(DEVICE_INCIDENT, device);
            // 映射表为空表示设备归属尚未配置；不能解释成“没有异常”，该支也不进入 UNION。
            availability.put(DEVICE_INCIDENT, deviceScope.configured() ? AVAILABLE : UNCONFIGURED);
        }
        return new Sources(decisions, availability);
    }

    private Capabilities capabilities(Sources sources) {
        boolean riskVisible = sources.decisions.containsKey(RISK);
        return new Capabilities(probe(PermissionCode.ALARM_VERIFY) != null, probe(PermissionCode.RISK_VERIFY) != null,
                probe(PermissionCode.HANDOFF_CREATE) != null, probe(PermissionCode.HANDOFF_READ) != null,
                riskVisible && repository.riskNoticeRecipientConfigured(), monitoringOperate());
    }

    private AccessDecision probe(PermissionCode permission) { try { return access.require(permission); } catch (ApiException ignored) { return null; } }
    private boolean menuReadable(String code) { try { menuAccess.requireBusinessData(code); return true; } catch (ApiException ignored) { return false; } }
    private boolean monitoringOperate() { try { menuAccess.requireBusinessData("monitoring.op"); return true; } catch (ApiException ignored) { return false; } }

    private ItemDto item(ItemRow row, Capabilities capabilities) {
        return switch (row.kind()) {
            case UAV_EVENT -> new ItemDto(UAV_EVENT, row.sourceId(), row.sourceNo(), row.state(), row.severity(), row.receivedAt(), row.occurredAt(),
                    row.updatedAt(), row.version(),
                    "无人机告警核实 · " + label(ALARM_TYPE_LABEL, row.typeCode()),
                    "告警等级" + label(SEVERITY_LABEL, row.severity()) + "，事件" + label(UAV_STATE_LABEL, row.state()) + "；来源模式 " + label(SOURCE_MODE_LABEL, row.sourceMode()),
                    UavEventState.verifiable(row.state()) && capabilities.alarmVerify() ? List.of("VERIFY") : List.of(),
                    // 属实只表示已核实待处置：反制/干扰尚未接入，工作台不能给出可点击的“反制”。
                    "CONFIRMED".equals(row.state()) ? "COUNTERMEASURE_NOT_CONNECTED" : null,
                    row.sourceMode(), Map.of("source", "#/alarms?alarm_id=" + encode(row.relatedId())));
            case RISK -> {
                List<String> actions = new ArrayList<>();
                String blocked = null;
                if (RiskState.verifiable(row.state()) && capabilities.riskVerify()) actions.add("VERIFY");
                if ("PENDING_NOTIFICATION".equals(row.state())) {
                    if (!capabilities.recipientConfigured()) blocked = "RECIPIENT_NOT_CONFIGURED";
                    else if (capabilities.handoffCreate()) actions.add("NOTIFY");
                }
                yield new ItemDto(RISK, row.sourceId(), row.sourceNo(), row.state(), row.severity(), row.receivedAt(), row.occurredAt(), row.updatedAt(),
                        row.version(), "飞行风险 · " + label(RISK_TYPE_LABEL, row.typeCode()), row.reasonText(), List.copyOf(actions), blocked, row.sourceMode(),
                        Map.of("source", "#/risk?risk_id=" + encode(row.sourceId())));
            }
            // 设备异常写路径在源模块；工作台只按阶段委托 REBOOT / VERIFY_RECOVERY，PROCESSING 等待回执。
            default -> {
                List<String> actions = new ArrayList<>();
                String blocked = null;
                if ("PROCESSING".equals(row.state())) blocked = "WAITING_RECEIPT";
                else if ("PENDING".equals(row.state()) && capabilities.monitoringOperate())
                    actions.add("MQTT_HEARTBEAT_TIMEOUT".equals(row.typeCode()) ? "VERIFY_RECOVERY" : "REBOOT");
                else if ("PENDING_VERIFICATION".equals(row.state()) && capabilities.monitoringOperate()) actions.add("VERIFY_RECOVERY");
                yield new ItemDto(DEVICE_INCIDENT, row.sourceId(), row.sourceNo(), row.state(), row.severity(), row.receivedAt(), null, row.updatedAt(), null,
                        "设备异常 · " + label(INCIDENT_TYPE_LABEL, row.typeCode()) + " · " + row.deviceNo(),
                        row.deviceName() + "，当前阶段" + label(DEVICE_STAGE_LABEL, row.state()),
                        List.copyOf(actions), blocked, row.sourceMode(),
                        Map.of("source", "#/monitor?device_id=" + encode(row.relatedId())));
            }
        };
    }

    private static TimelineEntryDto verification(VerificationRow v) {
        return TimelineEntryDto.verification(v.createdAt(), v.version(), v.previousState(), v.resultingState(), v.conclusion(), v.note(), v.actorId(), v.actorName());
    }
    private static TimelineEntryDto handoff(HandoffRow h) {
        return TimelineEntryDto.handoff(h.createdAt(), h.handoffId(), h.handoffType(), h.recipientId(), h.recipientName(), h.sourceVersion(),
                h.deliveryStatus(), h.blockedReason());
    }
    private static String label(Map<String, String> labels, String code) { return labels.getOrDefault(code, code == null ? "未知" : code); }
    private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }

    private static String kind(String value) {
        String kind = value == null ? "" : value.trim();
        if (!KINDS.contains(kind)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KIND", "事项类型无效");
        return kind;
    }
    private static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 36) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "工作台事项不存在"); }

    private record Sources(Map<String, AccessDecision> decisions, Map<String, String> availability) {
        Branches branches() { return new Branches(available(UAV_EVENT), available(RISK), available(DEVICE_INCIDENT)); }
        private AccessDecision available(String kind) { return AVAILABLE.equals(availability.get(kind)) ? decisions.get(kind) : null; }
    }
    private record Capabilities(boolean alarmVerify, boolean riskVerify, boolean handoffCreate, boolean handoffRead,
            boolean recipientConfigured, boolean monitoringOperate) { }

    static final class Request {
        private final MultiValueMap<String, String> values;
        Request(MultiValueMap<String, String> values) {
            this.values = values;
            values.keySet().stream().filter(key -> !ALLOWED.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        }
        Page page() { int page = integer("page", 1), size = integer("size", 20); if (page < 1 || size < 1 || size > 100) throw invalid("分页参数无效"); return new Page(page, size); }
        WorkbenchQuery query() {
            String kind = optional("kind", 32);
            if (kind != null && !KINDS.contains(kind)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KIND", "kind 参数无效");
            // 三类源的状态词典互不相同，没有 kind 的 state 过滤没有确定语义。
            if (values.containsKey("state") && kind == null) throw new ApiException(HttpStatus.BAD_REQUEST, "STATE_REQUIRES_KIND", "state 必须与 kind 成对出现");
            String state = kind == null ? null : enumerated("state", STATES.get(kind));
            TimeRange occurred = timeRange();
            return new WorkbenchQuery(kind, state, enumerated("severity", SEVERITIES), enumerated("severity_min", SEVERITIES), occurred.from, occurred.to,
                    optional("owner_org_id", 36), optional("district_id", 36), enumerated("source_mode", SOURCE_MODES));
        }
        private String optional(String name, int max) { if (!values.containsKey(name)) return null; String value = single(name); if (value.length() > max) throw invalid(name + " 参数无效"); return value; }
        private String enumerated(String name, Set<String> allowed) { String value = optional(name, 32); if (value != null && !allowed.contains(value)) throw invalid(name + " 参数无效"); return value; }
        private TimeRange timeRange() {
            boolean hasFrom = values.containsKey("occurred_from"), hasTo = values.containsKey("occurred_to");
            if (!hasFrom && !hasTo) return new TimeRange(null, null);
            if (hasFrom != hasTo) throw badTime();
            try {
                long from = Long.parseLong(single("occurred_from")), to = Long.parseLong(single("occurred_to"));
                if (from >= to) throw badTime();
                return new TimeRange(from, to);
            } catch (NumberFormatException ex) { throw badTime(); }
        }
        private int integer(String name, int fallback) { if (!values.containsKey(name)) return fallback; try { return Integer.parseInt(single(name)); } catch (NumberFormatException ex) { throw invalid("分页参数无效"); } }
        private String single(String name) { List<String> found = values.get(name); if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效"); return found.get(0).trim(); }
        static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
        static ApiException badTime() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); }
    }
    record Page(int page, int size) { int offset() { try { return Math.multiplyExact(page - 1, size); } catch (ArithmeticException ex) { throw Request.invalid("分页参数无效"); } } }
    record TimeRange(Long from, Long to) { }
}
