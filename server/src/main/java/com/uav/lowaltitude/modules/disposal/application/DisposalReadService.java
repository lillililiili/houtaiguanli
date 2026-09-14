package com.uav.lowaltitude.modules.disposal.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.AuthorizationDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.EventDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.PageDto;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.PolicyDto;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.EventRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/** 处置授权读侧。allowed_actions 与 device_stop_result 都在这里派生，前端不自己推。 */
@Service
public class DisposalReadService {
    private static final int MAX_SIZE = 100;

    private final AccessControlService access;
    private final DisposalRepository repository;
    private final DisposalPolicyRepository policies;
    private final DisposalReceiptSync receipts;
    private final ObjectMapper json;
    private final com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy deviceAccess;

    public DisposalReadService(AccessControlService access, DisposalRepository repository,
            DisposalPolicyRepository policies, DisposalReceiptSync receipts, ObjectMapper json,
            com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy deviceAccess) {
        this.access = access; this.repository = repository; this.policies = policies;
        this.receipts = receipts; this.json = json;
        this.deviceAccess = deviceAccess;
    }

    @Transactional(readOnly = true)
    public PageDto<AuthorizationDto> list(String subjectKind, String subjectId, String status, String actionType,
                                          Integer page, Integer size) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_READ);
        int p = page == null ? 1 : page, s = size == null ? 20 : size;
        if (p < 1 || s < 1 || s > MAX_SIZE)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页参数无效");
        DisposalRepository.Query query = new DisposalRepository.Query(subjectKind, subjectId, status, actionType);
        long total = repository.count(decision, query);
        List<AuthorizationRow> rows = repository.list(decision, query, (p - 1) * s, s);
        // 一次取回本页所有事件种类，避免逐条查事件流（列表页 N+1）。
        Map<String, List<String>> kinds = repository.eventKinds(rows.stream().map(AuthorizationRow::authorizationId).toList());
        Set<String> permissions = permissions();
        DisposalPolicy policy = activeOrNull();
        List<AuthorizationDto> items = new ArrayList<>();
        for (AuthorizationRow row : rows) {
            items.add(dto(row, kinds.getOrDefault(row.authorizationId(), List.of()), permissions, policy));
        }
        return new PageDto<>(items, p, s, total);
    }

    @Transactional
    public AuthorizationDto detail(String id) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_READ);
        AuthorizationRow row = repository.find(id, decision);
        if (row == null) throw notFound();
        // 读时同步：有人真正在看这条授权时，让它反映设备已经回了什么，不依赖定时任务是否开着。
        receipts.syncOne(row);
        row = repository.find(id, decision);
        List<String> kinds = repository.events(id).stream().map(EventRow::eventKind).toList();
        return dto(row, kinds, permissions(), activeOrNull());
    }

    @Transactional(readOnly = true)
    public List<EventDto> events(String id) {
        AccessDecision decision = access.require(PermissionCode.DISPOSAL_READ);
        // 先确认授权本身可见：否则任何人都能拿 ID 探到别的组织有没有处置过某个目标。
        if (repository.find(id, decision) == null) throw notFound();
        return repository.events(id).stream().map(this::event).toList();
    }

    @Transactional(readOnly = true)
    public List<PolicyDto> policies() {
        access.require(PermissionCode.DISPOSAL_READ);
        return policies.all().stream()
                .map(p -> new PolicyDto(p.policyCode(), p.schemaStatus(), p.params())).toList();
    }

    /** 供处罚交接判断前提（决策 13-6）：该事件是否已有完成的处置授权。 */
    @Transactional(readOnly = true)
    public boolean completedExists(String subjectKind, String subjectId) {
        return repository.completedExists(subjectKind, subjectId);
    }

    private AuthorizationDto dto(AuthorizationRow row, List<String> eventKinds, Set<String> permissions,
                                 DisposalPolicy policy) {
        AuthUser actor = AuthContext.require();
        Set<String> actions = DisposalRules.allowedActions(row.status(), row.channel(), row.requestedBy(), actor.userId(), permissions);
        if (!DisposalRules.MANUAL.equals(row.channel()) && !permissions.contains("devices.op")) actions.remove(DisposalRules.EXECUTE);
        return new AuthorizationDto(row.authorizationId(), row.authorizationNo(), row.actionType(), row.subjectKind(),
                row.subjectId(), row.targetId(), row.deviceId(), row.channel(), row.reason(), row.requestedBy(),
                row.requestedByName(), millis(row.requestedAt()), row.approvedBy(), row.approvedByName(),
                millis(row.approvedAt()), row.decisionNote(),
                millis(row.validFrom()), millis(row.validUntil()), row.status(), row.executionCommandId(),
                row.resultCode(), row.resultDetail(), DisposalRules.deviceStopResult(row.channel(), eventKinds),
                DisposalRules.executionBlockReason(row.status(), eventKinds), row.policyVersion(), policy == null ? null : policy.schemaStatus(), row.ownerOrgId(), row.districtId(),
                row.sourceMode(), row.version(),
                List.copyOf(actions));
    }

    private EventDto event(EventRow row) {
        return new EventDto(row.eventId(), row.eventKind(), row.actorId(), null, row.note(),
                snapshot(row.snapshot()), row.occurredAt().toInstant().toEpochMilli());
    }

    private Map<String, Object> snapshot(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = json.readTree(raw);
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            if (node == null || !node.isObject()) return null;
            return json.convertValue(node, new TypeReference<>() { });
        } catch (Exception ex) {
            // 快照读不出来不该让整条事件流打不开：事件本身（谁、何时、做了什么）仍然是有效证据。
            return null;
        }
    }

    /** 当前登录者持有的处置相关权限；allowed_actions 据此裁剪，前端不用自己判权限。 */
    private Set<String> permissions() {
        Set<String> held = new LinkedHashSet<>();
        for (PermissionCode code : List.of(PermissionCode.DISPOSAL_READ, PermissionCode.DISPOSAL_REQUEST,
                PermissionCode.DISPOSAL_APPROVE, PermissionCode.DISPOSAL_EXECUTE, PermissionCode.DISPOSAL_STOP)) {
            try { access.require(code); held.add(code.value()); }
            catch (ApiException denied) { /* 没有这项权限就不加，不是错误 */ }
        }
        try { deviceAccess.requireDevicesOperate(); held.add("devices.op"); }
        catch (ApiException denied) { /* 设备通道还需设备控制权限；人工通道不受影响。 */ }
        return held;
    }

    private DisposalPolicy activeOrNull() {
        try { return policies.active(); }
        catch (ApiException none) { return null; }
    }

    private static Long millis(OffsetDateTime at) { return at == null ? null : at.toInstant().toEpochMilli(); }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "授权不存在或不可见");
    }
}
