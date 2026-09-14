package com.uav.lowaltitude.modules.handoff.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.AvailabilityDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.DeliveryDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.HandoffDetailDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.HandoffDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.PageDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.RecipientDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.RecipientListDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.ReferenceMaterialDto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.MaterialV2Dto;
import com.uav.lowaltitude.modules.handoff.api.HandoffDtos.EvidenceMaterialDto;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.DeliveryRow;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.HandoffQuery;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.HandoffRow;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.SnapshotRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.RiskDto;
import com.uav.lowaltitude.modules.risk.application.RiskReadService;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class HandoffReadService {
    private static final Set<String> LIST_PARAMETERS = Set.of("source_kind", "source_id", "delivery_status", "receipt_status", "created_from", "created_to",
            "source_mode", "page", "size");
    private static final Set<String> PAGE_PARAMETERS = Set.of("page", "size");
    private static final Set<String> RECIPIENT_PARAMETERS = Set.of("handoff_type");
    private final AccessControlService access;
    private final HandoffRepository repository;
    private final RiskRepository risks;
    private final RiskReadService riskRead;
    private final UavEventRepository events;
    private final ObjectMapper objectMapper;

    public HandoffReadService(AccessControlService access, HandoffRepository repository, RiskRepository risks,
            RiskReadService riskRead, UavEventRepository events, ObjectMapper objectMapper) {
        this.access = access; this.repository = repository; this.risks = risks; this.riskRead = riskRead;
        this.events = events; this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RecipientListDto recipients(MultiValueMap<String, String> values) {
        // 目录对 handoff:read 或 handoff:create 任一开放：只有发起权限的角色也要能选接收方；两者皆无仍 403。
        // 鉴权先于参数解析，避免用 400 差异探测目录接口是否存在。
        requireReadOrCreate();
        Request request = new Request(values, RECIPIENT_PARAMETERS);
        String type = request.enumerated("handoff_type", HandoffRules.HANDOFF_TYPES);
        return new RecipientListDto(repository.enabledRecipients(type).stream()
                .map(row -> new RecipientDto(row.recipientId(), row.displayName(), row.handoffType())).toList());
    }

    private void requireReadOrCreate() {
        try { access.require(PermissionCode.HANDOFF_READ); }
        catch (ApiException denied) { access.require(PermissionCode.HANDOFF_CREATE); }
    }

    @Transactional(readOnly = true)
    public PageDto<HandoffDto> list(MultiValueMap<String, String> values) {
        AccessDecision decision = access.require(PermissionCode.HANDOFF_READ);
        Request request = new Request(values, LIST_PARAMETERS);
        Page page = request.page();
        TimeRange created = request.timeRange("created_from", "created_to");
        HandoffQuery query = new HandoffQuery(request.enumerated("source_kind", HandoffRules.SOURCE_KINDS), request.optional("source_id", 36),
                request.enumerated("delivery_status", HandoffRules.DELIVERY_STATUSES), created.from, created.to,
                request.enumerated("source_mode", HandoffRules.SOURCE_MODES), request.enumerated("receipt_status", HandoffRules.RECEIPT_STATUSES));
        long total = repository.count(query, decision);
        return new PageDto<>(repository.list(query, decision, page.offset(), page.size).stream().map(HandoffReadService::dto).toList(),
                page.page, page.size, total);
    }

    @Transactional(readOnly = true)
    public HandoffDetailDto detail(String handoffId) {
        AccessDecision decision = access.require(PermissionCode.HANDOFF_READ);
        HandoffRow row = repository.find(id(handoffId), decision);
        if (row == null) throw notFound();
        DeliveryRow latest = repository.latestDelivery(row.handoffId());
        SourceVisibility source = sourceVisibility(row);
        return new HandoffDetailDto(row.handoffId(), row.sourceKind(), row.sourceId(), row.handoffType(), row.recipientId(), row.recipientName(),
                row.sourceVersion(), row.ownerOrgId(), row.districtId(), row.sourceMode(), row.submittedBy(), requiredMillis(row.createdAt()),
                row.deliveryStatus(), row.receiptStatus(), row.receiptResult(), row.blockedReason(), material(row, source), latest == null ? null : dto(latest),
                new AvailabilityDto(source.availability, evidenceAvailability(row, repository.snapshot(row.handoffId()), source.availability)), row.ownerOrgName(), row.districtName(), row.submittedByName(), row.sourceNo());
    }

    @Transactional(readOnly = true)
    public PageDto<DeliveryDto> deliveries(String handoffId, MultiValueMap<String, String> values) {
        AccessDecision decision = access.require(PermissionCode.HANDOFF_READ);
        Page page = new Request(values, PAGE_PARAMETERS).page();
        HandoffRow row = repository.find(id(handoffId), decision);
        if (row == null) throw notFound();
        long total = repository.countDeliveries(row.handoffId());
        return new PageDto<>(repository.deliveries(row.handoffId(), page.offset(), page.size).stream().map(HandoffReadService::dto).toList(),
                page.page, page.size, total);
    }

    /**
     * 读取快照时重新校验：交接归属已由范围谓词保证，但材料内容还要按“当前”源对象权限与范围复核。
     * 读者缺 risk:read，或源风险已不在其可见范围（归属变化、目录停用）时，风险字段、核实历史与关联引用整体省略，
     * 只保留 schema_version 并以 availability 标记原因；可见时关联引用再逐项按当前关联权限裁剪，不提示被省略的数量。
     */
    private Object material(HandoffRow row, SourceVisibility source) {
        SnapshotRow snapshot = repository.snapshot(row.handoffId());
        if (snapshot == null) return null;
        if (snapshot.schemaVersion() >= HandoffSubmissionService.MATERIAL_SCHEMA_V2) return materialV2(snapshot, source);
        MaterialDto stored = parse(snapshot.json());
        int schemaVersion = stored.schemaVersion() == 0 ? snapshot.schemaVersion() : stored.schemaVersion();
        if (source.current == null) return new MaterialDto(schemaVersion, null, null, null);
        ReferenceMaterialDto references = stored.references() == null ? null : visibleReferences(source.current, stored.references());
        return new MaterialDto(schemaVersion, stored.risk(), stored.verifications(), references);
    }

    /**
     * 处罚材料（v2）：来源不可见时整体省略，只留 schema_version；证据段另按**读者当前**的 evidence:read 裁剪。
     * 提交时就没冻结证据的（evidence_omitted），补多少权限也变不出来——这两种情况在 availability.evidence 上分开表达。
     */
    private Object materialV2(SnapshotRow snapshot, SourceVisibility source) {
        MaterialV2Dto stored = parseV2(snapshot.json());
        int schemaVersion = stored.schemaVersion() == 0 ? snapshot.schemaVersion() : stored.schemaVersion();
        if (!"AVAILABLE".equals(source.availability))
            return new MaterialV2Dto(schemaVersion, null, null, null, null, null, null);
        List<EvidenceMaterialDto> evidence = stored.evidence();
        if (evidence != null && !mayReadEvidence()) evidence = null;
        return new MaterialV2Dto(schemaVersion, stored.event(), stored.verifications(), stored.disposals(),
                evidence, stored.evidenceOmitted(), stored.references());
    }

    /** 证据段的可用性：提交时就没冻结 > 读者没权限 > 可用。三者互斥，取最先成立的那个。 */
    private String evidenceAvailability(HandoffRow row, SnapshotRow snapshot, String materialAvailability) {
        if (snapshot == null || snapshot.schemaVersion() < HandoffSubmissionService.MATERIAL_SCHEMA_V2) return null;
        // 材料整体不可用时，证据段跟着它（决策 14-25）：材料都看不到却说"证据可用"，
        // 等于告诉对方"这份材料里是有证据的"——那本身就是不该漏出去的信息。
        if (!"AVAILABLE".equals(materialAvailability)) return materialAvailability;
        MaterialV2Dto stored = parseV2(snapshot.json());
        if (Boolean.TRUE.equals(stored.evidenceOmitted())) return "OMITTED_AT_SUBMISSION";
        return mayReadEvidence() ? "AVAILABLE" : "FORBIDDEN";
    }

    private boolean mayReadEvidence() {
        try { access.require(PermissionCode.EVIDENCE_READ); return true; }
        catch (ApiException denied) { return false; }
    }

    private MaterialV2Dto parseV2(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            // 与 v1 同一处理：H2 把 CAST(? AS JSON) 的字符串包成 JSON 文本，PostgreSQL 直接存对象，两种形态都要能读回。
            if (root != null && root.isTextual()) root = objectMapper.readTree(root.textValue());
            if (root == null || !root.isObject()) throw invalidSnapshot();
            return objectMapper.treeToValue(root, MaterialV2Dto.class);
        } catch (java.io.IOException ex) {
            throw invalidSnapshot();
        }
    }

    /**
     * 来源可见性。处罚交接看的是事件（决策 14-4）：缺 alarm:read → FORBIDDEN；
     * 事件已不在读者范围 → SOURCE_NOT_VISIBLE。两者不能混：前者补权限就能看，后者补权限也看不到。
     */
    private SourceVisibility sourceVisibility(HandoffRow row) {
        if (HandoffRules.KIND_UAV_EVENT.equals(row.sourceKind())) {
            AccessDecision alarmDecision;
            try { alarmDecision = access.require(PermissionCode.ALARM_READ); }
            catch (ApiException denied) { return new SourceVisibility(null, "FORBIDDEN"); }
            return events.find(row.sourceId(), alarmDecision) == null
                    ? new SourceVisibility(null, "SOURCE_NOT_VISIBLE") : new SourceVisibility(null, "AVAILABLE");
        }
        if (!HandoffRules.KIND_RISK.equals(row.sourceKind())) return new SourceVisibility(null, "SOURCE_NOT_VISIBLE");
        AccessDecision riskDecision;
        try { riskDecision = access.require(PermissionCode.RISK_READ); } catch (ApiException denied) { return new SourceVisibility(null, "FORBIDDEN"); }
        RiskRow current = risks.find(row.sourceId(), riskDecision);
        return current == null ? new SourceVisibility(null, "SOURCE_NOT_VISIBLE") : new SourceVisibility(current, "AVAILABLE");
    }

    private ReferenceMaterialDto visibleReferences(RiskRow current, ReferenceMaterialDto stored) {
        RiskDto visible = riskRead.dto(current);
        return new ReferenceMaterialDto(keep(stored.planId(), visible.planId()), keep(stored.routeVersionId(), visible.routeVersionId()),
                keep(stored.assessmentId(), visible.assessmentId()), keep(stored.targetId(), visible.targetId()),
                keep(stored.trackId(), visible.trackId()));
    }

    private static String keep(String stored, String currentlyVisible) {
        return stored != null && stored.equals(currentlyVisible) ? stored : null;
    }

    private MaterialDto parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            // H2 把 CAST(? AS JSON) 的字符串包成 JSON 文本，PostgreSQL 直接存对象；两种形态都要能读回。
            if (root != null && root.isTextual()) root = objectMapper.readTree(root.textValue());
            if (root == null || !root.isObject()) throw invalidSnapshot();
            return objectMapper.treeToValue(root, MaterialDto.class);
        } catch (java.io.IOException ex) {
            throw invalidSnapshot();
        }
    }

    public static HandoffDto dto(HandoffRow row) {
        return new HandoffDto(row.handoffId(), row.sourceKind(), row.sourceId(), row.handoffType(), row.recipientId(), row.recipientName(),
                row.sourceVersion(), row.ownerOrgId(), row.districtId(), row.sourceMode(), row.submittedBy(), requiredMillis(row.createdAt()),
                row.deliveryStatus(), row.receiptStatus(), row.receiptResult(), row.blockedReason(),
                row.ownerOrgName(), row.districtName(), row.submittedByName(), row.sourceNo());
    }

    public static DeliveryDto dto(DeliveryRow row) {
        return new DeliveryDto(row.deliveryId(), row.handoffId(), row.attemptNo(), row.deliveryStatus(), row.receiptStatus(), row.blockedReason(),
                requiredMillis(row.createdAt()), millis(row.submittedAt()), millis(row.deliveredAt()), millis(row.acknowledgedAt()));
    }

    public static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 36) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "交接记录不存在"); }
    private static ApiException invalidSnapshot() { return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误"); }
    private static Long millis(OffsetDateTime value) { return value == null ? null : value.toInstant().toEpochMilli(); }
    private static long requiredMillis(OffsetDateTime value) { if (value == null) throw invalidSnapshot(); return value.toInstant().toEpochMilli(); }

    static final class Request {
        private final MultiValueMap<String, String> values;
        Request(MultiValueMap<String, String> values, Set<String> allowed) {
            this.values = values;
            values.keySet().stream().filter(key -> !allowed.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        }
        Page page() { int page = integer("page", 1), size = integer("size", 20); if (page < 1 || size < 1 || size > 100) throw invalid("分页参数无效"); return new Page(page, size); }
        String optional(String name, int max) { if (!values.containsKey(name)) return null; String value = single(name); if (value.length() > max) throw invalid(name + " 参数无效"); return value; }
        String enumerated(String name, Set<String> allowed) { String value = optional(name, 32); if (value != null && !allowed.contains(value)) throw invalid(name + " 参数无效"); return value; }
        TimeRange timeRange(String fromName, String toName) {
            boolean hasFrom = values.containsKey(fromName), hasTo = values.containsKey(toName);
            if (!hasFrom && !hasTo) return new TimeRange(null, null); if (hasFrom != hasTo) throw badTime();
            try { OffsetDateTime from = Instant.ofEpochMilli(Long.parseLong(single(fromName))).atOffset(ZoneOffset.UTC);
                OffsetDateTime to = Instant.ofEpochMilli(Long.parseLong(single(toName))).atOffset(ZoneOffset.UTC);
                if (!from.isBefore(to)) throw badTime(); return new TimeRange(from, to); }
            catch (NumberFormatException ex) { throw badTime(); }
        }
        private int integer(String name, int fallback) { if (!values.containsKey(name)) return fallback; try { return Integer.parseInt(single(name)); } catch (NumberFormatException ex) { throw invalid("分页参数无效"); } }
        private String single(String name) { List<String> found = values.get(name); if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效"); return found.get(0).trim(); }
        static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
        static ApiException badTime() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); }
    }
    record Page(int page, int size) { int offset() { try { return Math.multiplyExact(page - 1, size); } catch (ArithmeticException ex) { throw Request.invalid("分页参数无效"); } } }
    record TimeRange(OffsetDateTime from, OffsetDateTime to) { }
    private record SourceVisibility(RiskRow current, String availability) { }
}
