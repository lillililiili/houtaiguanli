package com.uav.lowaltitude.modules.fusion.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ClassificationRevisionDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.MergeResultDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.SplitResultDto;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository.LineageInsert;
import com.uav.lowaltitude.modules.fusion.infrastructure.TargetWriteRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.TargetWriteRepository.LinkRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.TargetWriteRepository.TargetHead;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 人工写操作：类别修订、合并、分裂。事务顺序统一为
 * 鉴权（target:read → fusion:revise）→ 严格解析 body → 按 ID 排序锁目标行 → claim 幂等键 → expected_version/状态守卫
 * → 写业务行与 lineage/alias → 成功审计（同事务）。任何一步失败整体回滚；失败审计由全局异常处理在事务外落库。
 */
@Service
public class FusionCommandService {
    static final String MODULE = "fusion", OBJECT_TYPE = "target";
    static final String ALGO_VERSION = "stage8-manual";
    /** 类别白名单与迁移 053 的 CHECK 一致；越界值在服务端就拒绝，不依赖数据库报错。 */
    static final Set<String> CLASS_CODES = Set.of("UAV", "BIRD", "VEHICLE", "PERSON", "UNKNOWN");
    private static final Set<String> REVISION_FIELDS = Set.of("new_class_code", "note", "expected_version");
    private static final Set<String> MERGE_FIELDS = Set.of("survivor_target_id", "member_target_ids", "note", "expected_versions");
    private static final Set<String> SPLIT_FIELDS = Set.of("link_ids", "note", "expected_version");
    private static final int NOTE_MAX = 1000, ID_MAX = 36, MEMBER_MAX = 5;
    private static final BigDecimal MANUAL_CONFIDENCE = BigDecimal.ONE;

    private final AccessControlService access;
    private final TargetReadRepository targets;
    private final TargetWriteRepository writes;
    private final LineageRepository lineages;
    private final DegradationRepository states;
    private final FusionConfigService config;
    private final FusionEventEmitter events;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public FusionCommandService(AccessControlService access, TargetReadRepository targets, TargetWriteRepository writes, LineageRepository lineages,
            DegradationRepository states, FusionConfigService config, FusionEventEmitter events, IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json) {
        this.access = access; this.targets = targets; this.writes = writes; this.lineages = lineages; this.states = states;
        this.config = config; this.events = events; this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.json = json;
    }

    @Transactional
    public ClassificationRevisionDto reviseClassification(String targetId, String rawBody, String idempotencyKey) {
        AccessDecision decision = requireRevise();
        Revision request = parseRevision(rawBody);
        String id = FusionReadService.id(targetId);
        TargetHead target = lockVisible(id, decision);
        idempotency.claim(idempotencyKey, framed("target-class-revision") + framed(id) + framed(request.classCode()) + framed(request.note()) + framed(Long.toString(request.expectedVersion())));
        if (target.version() != request.expectedVersion()) throw versionConflict();
        requireNotMerged(id);
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String previousClass = target.objectTypeCode();
        if (writes.updateClass(id, request.expectedVersion(), request.classCode(), at) != 1) throw versionConflict();
        long version = target.version() + 1;
        AuthUser actor = AuthContext.require();
        writes.insertClassificationRevision(UUID.randomUUID().toString(), id, previousClass, request.classCode(), request.note(), actor.userId(), target.version(), at);
        // 人工结论优先于来源类别：置信度记 1.0，并在属性优选上打 manual_class_override，引擎后续不再覆盖。
        states.markManualClass(id, request.classCode(), MANUAL_CONFIDENCE, at, config.activeVersion());
        writes.updateClassificationConfidence(id, MANUAL_CONFIDENCE, at);
        String lineageId = UUID.randomUUID().toString();
        lineages.insertLineage(new LineageInsert(lineageId, "CLASS_REVISION", at, id, null, write(List.of(id)), write(List.of()),
                write(Map.of("previous_class_code", previousClass == null ? "" : previousClass, "new_class_code", request.classCode())),
                ALGO_VERSION, config.activeVersion(), "USER", actor.userId(), request.note(), write(Map.of()), at));
        events.emit(FusionEventEmitter.CLASS_REVISED, id, at, Map.of("class_code", request.classCode(), "lineage_id", lineageId));
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "target_class_revised", OBJECT_TYPE, id,
                "new_class_code=" + request.classCode() + "; expected_version=" + request.expectedVersion(), "SUCCESS", "", "");
        String revisionId = writes.latestRevisionId(id, target.version());
        return new ClassificationRevisionDto(revisionId, id, request.classCode(), version, at.toInstant().toEpochMilli());
    }

    @Transactional
    public MergeResultDto merge(String rawBody, String idempotencyKey) {
        AccessDecision decision = requireRevise();
        MergeRequest request = parseMerge(rawBody);
        // 死锁防护：多行加锁必须有全局顺序，否则两个并发合并各自先锁自己的 survivor 就会互等。
        List<String> ordered = new ArrayList<>(new TreeSet<>(request.allIds()));
        Map<String, TargetHead> locked = new LinkedHashMap<>();
        for (String id : ordered) locked.put(id, lockVisible(id, decision));
        idempotency.claim(idempotencyKey, framed("targets-merge") + framed(request.survivorId()) + framed(String.join(",", ordered)) + framed(request.note())
                + framed(request.expectedVersions().toString()));
        TargetHead survivor = locked.get(request.survivorId());
        for (Map.Entry<String, Long> expected : request.expectedVersions().entrySet()) {
            TargetHead head = locked.get(expected.getKey());
            if (head == null || head.version() != expected.getValue()) throw versionConflict();
        }
        for (String memberId : request.memberIds()) {
            TargetHead member = locked.get(memberId);
            // 跨分区永不合并：不同 source_mode 或不同归属元组的同名目标是两个事实，不是一个目标的两次观测。
            if (!sameDomain(survivor, member)) throw new ApiException(HttpStatus.CONFLICT, "FUSION_DOMAIN_MISMATCH", "目标不在同一融合分区");
            if (lineages.findAlias(memberId) != null) throw new ApiException(HttpStatus.CONFLICT, "TARGET_ALREADY_MERGED", "目标已被合并");
        }
        if (lineages.findAlias(request.survivorId()) != null) throw new ApiException(HttpStatus.CONFLICT, "TARGET_ALREADY_MERGED", "幸存目标已被合并到其他目标");
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        AuthUser actor = AuthContext.require();
        String lineageId = UUID.randomUUID().toString();
        Map<String, Object> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, TargetHead> entry : locked.entrySet()) {
            snapshots.put(entry.getKey(), Map.of("target_no", entry.getValue().targetNo(), "object_type_code", entry.getValue().objectTypeCode() == null ? "" : entry.getValue().objectTypeCode(),
                    "version", entry.getValue().version()));
        }
        lineages.insertLineage(new LineageInsert(lineageId, "MERGE", at, request.survivorId(), null, write(request.memberIds()), write(List.of(request.survivorId())),
                write(Map.of("reason", "manual")), ALGO_VERSION, config.activeVersion(), "USER", actor.userId(), request.note(), write(snapshots), at));
        for (String memberId : request.memberIds()) {
            // 被并目标行不删不改名：告警、事件、风险、交接、研判里的历史外键必须继续可解析，只加别名与状态。
            lineages.upsertAlias(memberId, request.survivorId(), lineageId, at);
            lineages.redirectAliases(memberId, request.survivorId(), lineageId, at);
            lineages.upsertTrackStatus(memberId, "MERGE", at);
            if (writes.bumpVersion(memberId, locked.get(memberId).version(), at) != 1) throw versionConflict();
        }
        if (writes.bumpVersion(request.survivorId(), survivor.version(), at) != 1) throw versionConflict();
        events.emit(FusionEventEmitter.MERGED, request.survivorId(), at, Map.of("lineage_id", lineageId, "merged_target_ids", request.memberIds()));
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "targets_merged", OBJECT_TYPE, request.survivorId(),
                "member_target_ids=" + String.join(",", request.memberIds()) + "; lineage_id=" + lineageId, "SUCCESS", "", "");
        return new MergeResultDto(lineageId, request.survivorId(), List.copyOf(request.memberIds()));
    }

    @Transactional
    public SplitResultDto split(String targetId, String rawBody, String idempotencyKey) {
        AccessDecision decision = requireRevise();
        SplitRequest request = parseSplit(rawBody);
        String id = FusionReadService.id(targetId);
        TargetHead origin = lockVisible(id, decision);
        idempotency.claim(idempotencyKey, framed("target-split") + framed(id) + framed(String.join(",", request.linkIds())) + framed(request.note()) + framed(Long.toString(request.expectedVersion())));
        if (origin.version() != request.expectedVersion()) throw versionConflict();
        requireNotMerged(id);
        List<LinkRow> links = writes.links(id);
        if (links.size() < 2) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "目标只有一条来源关联，无法分裂");
        List<String> moving = new ArrayList<>();
        for (String linkId : request.linkIds()) {
            if (links.stream().noneMatch(l -> l.linkId().equals(linkId))) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "来源关联不存在");
            moving.add(linkId);
        }
        if (moving.size() >= links.size()) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "分裂必须至少保留一条来源关联");
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        AuthUser actor = AuthContext.require();
        String newTargetId = UUID.randomUUID().toString();
        String targetNo = writes.nextTargetNo(at);
        writes.insertTarget(newTargetId, targetNo, origin, at);
        for (String linkId : moving) writes.moveLink(linkId, newTargetId);
        // 新目标从 TENTATIVE 起步：它还没有经过连续命中确认，不能直接继承原目标的 STABLE。
        lineages.upsertTrackStatus(newTargetId, "TENTATIVE", at);
        lineages.upsertTrackStatus(id, "SPLIT", at);
        String lineageId = UUID.randomUUID().toString();
        lineages.insertLineage(new LineageInsert(lineageId, "SPLIT", at, null, id, write(List.of(newTargetId)), write(List.of(id)),
                write(Map.of("link_ids", moving)), ALGO_VERSION, config.activeVersion(), "USER", actor.userId(), request.note(),
                write(Map.of(id, Map.of("target_no", origin.targetNo(), "version", origin.version()))), at));
        if (writes.bumpVersion(id, origin.version(), at) != 1) throw versionConflict();
        events.emit(FusionEventEmitter.SPLIT, id, at, Map.of("lineage_id", lineageId, "new_target_ids", List.of(newTargetId)));
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "target_split", OBJECT_TYPE, id,
                "new_target_ids=" + newTargetId + "; link_ids=" + String.join(",", moving) + "; lineage_id=" + lineageId, "SUCCESS", "", "");
        return new SplitResultDto(lineageId, id, List.of(newTargetId));
    }

    // ---- 守卫 ----

    /** 三个写动作都要求目标读权限与 fusion:revise，且先于路径、请求体与幂等键解析。 */
    private AccessDecision requireRevise() {
        AccessDecision decision = access.require(PermissionCode.TARGET_READ);
        access.require(PermissionCode.FUSION_REVISE);
        return decision;
    }

    private TargetHead lockVisible(String targetId, AccessDecision decision) {
        // 先按阶段 2 范围谓词确认可见，再锁行：不可见目标一律 404，不能靠锁失败或版本冲突泄露存在性。
        if (targets.findTarget(targetId, decision) == null) throw FusionReadService.notFound();
        TargetHead head = writes.lock(targetId);
        if (head == null) throw FusionReadService.notFound();
        return head;
    }

    private void requireNotMerged(String targetId) {
        if (lineages.findAlias(targetId) != null) throw new ApiException(HttpStatus.CONFLICT, "TARGET_ALREADY_MERGED", "目标已被合并，请对幸存目标操作");
    }

    private static boolean sameDomain(TargetHead a, TargetHead b) {
        return a.sourceMode().equals(b.sourceMode())
                && java.util.Objects.equals(a.ownerOrgId(), b.ownerOrgId())
                && java.util.Objects.equals(a.districtId(), b.districtId());
    }

    // ---- 解析 ----

    private Revision parseRevision(String raw) {
        JsonNode node = object(raw, REVISION_FIELDS);
        JsonNode code = node.get("new_class_code");
        if (code == null || !code.isTextual()) throw invalidRequest();
        String classCode = code.textValue().trim();
        if (!CLASS_CODES.contains(classCode)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLASS_CODE", "目标类别无效");
        return new Revision(classCode, note(node), expectedVersion(node.get("expected_version")));
    }

    private MergeRequest parseMerge(String raw) {
        JsonNode node = object(raw, MERGE_FIELDS);
        JsonNode survivor = node.get("survivor_target_id");
        if (survivor == null || !survivor.isTextual() || survivor.textValue().isBlank() || survivor.textValue().trim().length() > ID_MAX) throw invalidRequest();
        JsonNode members = node.get("member_target_ids");
        if (members == null || !members.isArray() || members.isEmpty() || members.size() > MEMBER_MAX) throw invalidRequest();
        String survivorId = survivor.textValue().trim();
        List<String> memberIds = new ArrayList<>();
        for (JsonNode member : members) {
            if (!member.isTextual() || member.textValue().isBlank() || member.textValue().trim().length() > ID_MAX) throw invalidRequest();
            String memberId = member.textValue().trim();
            if (memberId.equals(survivorId) || memberIds.contains(memberId)) throw invalidRequest();
            memberIds.add(memberId);
        }
        JsonNode expected = node.get("expected_versions");
        if (expected == null || !expected.isObject() || expected.size() != memberIds.size() + 1) throw invalidRequest();
        Map<String, Long> versions = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().equals(survivorId) && !memberIds.contains(field.getKey())) throw invalidRequest();
            versions.put(field.getKey(), expectedVersion(field.getValue()));
        }
        if (!versions.containsKey(survivorId)) throw invalidRequest();
        return new MergeRequest(survivorId, List.copyOf(memberIds), note(node), Map.copyOf(versions));
    }

    private SplitRequest parseSplit(String raw) {
        JsonNode node = object(raw, SPLIT_FIELDS);
        JsonNode links = node.get("link_ids");
        if (links == null || !links.isArray() || links.isEmpty()) throw invalidRequest();
        List<String> linkIds = new ArrayList<>();
        for (JsonNode link : links) {
            if (!link.isTextual() || link.textValue().isBlank() || link.textValue().trim().length() > ID_MAX) throw invalidRequest();
            String linkId = link.textValue().trim();
            if (linkIds.contains(linkId)) throw invalidRequest();
            linkIds.add(linkId);
        }
        return new SplitRequest(List.copyOf(linkIds), note(node), expectedVersion(node.get("expected_version")));
    }

    private JsonNode object(String raw, Set<String> fields) {
        if (raw == null || raw.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!fields.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name);
            }
            if (node.size() != fields.size()) throw invalidRequest();
            return node;
        } catch (IOException ex) {
            throw invalidRequest();
        }
    }

    private static String note(JsonNode node) {
        JsonNode note = node.get("note");
        if (note == null || !note.isTextual()) throw invalidRequest();
        String text = note.textValue().trim();
        if (text.isEmpty() || text.length() > NOTE_MAX) throw invalidRequest();
        return text;
    }

    private static long expectedVersion(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) throw invalidRequest();
        return node.longValue();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("fusion payload unserializable", ex); }
    }

    /** 长度前缀序列化：ID 与备注都可能含分隔符，直接拼接会把不同请求误判为重放。 */
    private static String framed(String value) { return value.getBytes(StandardCharsets.UTF_8).length + ":" + value; }
    static ApiException invalidRequest() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确"); }
    static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "对象已被其他操作更新"); }

    record Revision(String classCode, String note, long expectedVersion) { }
    record MergeRequest(String survivorId, List<String> memberIds, String note, Map<String, Long> expectedVersions) {
        List<String> allIds() { List<String> all = new ArrayList<>(memberIds); all.add(survivorId); return all; }
    }
    record SplitRequest(List<String> linkIds, String note, long expectedVersion) { }
}
