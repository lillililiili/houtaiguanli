package com.uav.lowaltitude.modules.assessment.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.ActivationDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.MemberDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.ParamDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleRunDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleSetDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleSetVersionDetailDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleSetVersionDto;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.VersionChangeRequest;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.ActivationRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RuleSetRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RunDetailRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RunQuery;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.VersionDetailRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.VersionRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 规则集读取与激活/回滚/影子。写事务顺序：两个动作鉴权 → 解析请求体 → 锁头行 → claim 幂等键 → expected_version → 版本守卫
 * → 头行条件更新 → rule_set_activation → 成功审计（同事务）。任何一步失败整体回滚（含幂等键领用），失败审计由全局异常处理在事务外落库。
 * 规则集是全局配置，没有组织/区域范围；ASSIGNED 与 ALL 只影响是否拥有动作，不影响可见集合。
 */
@Service
public class RuleSetManagementService {
    static final String MODULE = "rules", OBJECT_TYPE = "rule_set";
    static final String KIND_ACTIVATE = "ACTIVATE", KIND_ROLLBACK = "ROLLBACK", KIND_SHADOW_SET = "SHADOW_SET", KIND_SHADOW_CLEAR = "SHADOW_CLEAR";
    static final String STATUS_PUBLISHED = "PUBLISHED", PARAM_STATUS_DEMO = "DEMO";
    private static final Set<String> ACTIVATE_FIELDS = Set.of("rule_set_version_id", "note", "expected_version");
    private static final Set<String> ROLLBACK_FIELDS = Set.of("note", "expected_version");
    private static final Set<String> RUN_FILTERS = Set.of("mode", "trigger_kind", "from", "to", "page", "size");
    private static final Set<String> PAGE_ONLY = Set.of("page", "size");
    private static final Set<String> MODES = Set.of("ACTIVE", "SHADOW");
    private static final Set<String> TRIGGERS = Set.of("SCHEDULED", "MANUAL", "RECOMPUTE", "REPLAY");
    private static final int NOTE_MAX = 1000, CODE_MAX = 64, ID_MAX = 36, PAGE_DEFAULT = 20, PAGE_MAX = 100;
    private final AccessControlService access;
    private final RuleEngineRepository repository;
    private final RuleEngineProperties properties;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public RuleSetManagementService(AccessControlService access, RuleEngineRepository repository, RuleEngineProperties properties,
            IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json) {
        this.access = access; this.repository = repository; this.properties = properties; this.idempotency = idempotency;
        this.audit = audit; this.clock = clock; this.json = json;
    }

    // ---- 读取 ----

    @Transactional(readOnly = true)
    public PageDto<RuleSetDto> listRuleSets(MultiValueMap<String, String> parameters) {
        access.require(PermissionCode.RULE_READ);
        Page page = page(parameters, PAGE_ONLY);
        return new PageDto<>(repository.listRuleSets(page.offset(), page.size()).stream().map(RuleSetManagementService::dto).toList(), page.page(), page.size(), repository.countRuleSets());
    }

    @Transactional(readOnly = true)
    public PageDto<RuleSetVersionDto> listVersions(String code, MultiValueMap<String, String> parameters) {
        access.require(PermissionCode.RULE_READ);
        RuleSetRow set = requireSet(code(code));
        Page page = page(parameters, PAGE_ONLY);
        return new PageDto<>(repository.listVersions(set.ruleSetId(), page.offset(), page.size()).stream().map(RuleSetManagementService::dto).toList(),
                page.page(), page.size(), repository.countVersions(set.ruleSetId()));
    }

    @Transactional(readOnly = true)
    public RuleSetVersionDetailDto versionDetail(String versionId) {
        access.require(PermissionCode.RULE_READ);
        VersionDetailRow row = repository.findVersionDetail(id(versionId));
        if (row == null) throw versionNotFound();
        List<MemberDto> members = repository.members(row.ruleSetVersionId()).stream().map(m -> new MemberDto(m.ruleCode(), m.ruleVersionId(), m.priority(), m.enabled())).toList();
        List<ParamDto> params = repository.params(row.ruleSetVersionId()).stream().map(p -> new ParamDto(p.ruleCode(), p.key(), p.value(), p.type(), p.unit(), p.status(), p.note())).toList();
        return new RuleSetVersionDetailDto(row.ruleSetVersionId(), row.ruleSetId(), row.ruleSetCode(), row.versionNo(), row.statusCode(), row.paramStatus(),
                millis(row.validFrom()), optionalMillis(row.validTo()), row.description(), row.sourceMode(), millis(row.createdAt()), optionalMillis(row.publishedAt()),
                row.active(), row.shadow(), members, params);
    }

    @Transactional(readOnly = true)
    public PageDto<ActivationDto> listActivations(String code, MultiValueMap<String, String> parameters) {
        access.require(PermissionCode.RULE_READ);
        RuleSetRow set = requireSet(code(code));
        Page page = page(parameters, PAGE_ONLY);
        return new PageDto<>(repository.listActivations(set.ruleSetId(), page.offset(), page.size()).stream().map(RuleSetManagementService::dto).toList(),
                page.page(), page.size(), repository.countActivations(set.ruleSetId()));
    }

    @Transactional(readOnly = true)
    public PageDto<RuleRunDto> listRuns(MultiValueMap<String, String> parameters) {
        access.require(PermissionCode.RULE_READ);
        Page page = page(parameters, RUN_FILTERS);
        String mode = enumerated(parameters, "mode", MODES), trigger = enumerated(parameters, "trigger_kind", TRIGGERS);
        OffsetDateTime[] range = timeRange(parameters, "from", "to");
        RunQuery query = new RunQuery(mode, trigger, range[0], range[1]);
        return new PageDto<>(repository.listRuns(query, page.offset(), page.size()).stream().map(RuleSetManagementService::dto).toList(), page.page(), page.size(), repository.countRuns(query));
    }

    @Transactional(readOnly = true)
    public RuleRunDto runDetail(String runId) {
        access.require(PermissionCode.RULE_READ);
        RunDetailRow row = repository.findRunDetail(id(runId));
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "规则运行不存在");
        return dto(row);
    }

    // ---- 写入 ----

    @Transactional
    public RuleSetDto activate(String code, String rawRequest, String key) {
        requireManage();
        VersionChangeRequest request = parse(rawRequest, ACTIVATE_FIELDS, false);
        String ruleSetCode = code(code);
        RuleSetRow set = lockSet(ruleSetCode);
        idempotency.claim(key, operation(KIND_ACTIVATE, ruleSetCode, request));
        requireVersion(set, request.expectedVersion());
        VersionRow version = requirePublishedVersion(set, request.ruleSetVersionId());
        // DEMO 参数只能做影子验证；生产（allow-demo-active=false）不允许成为生效规则。
        if (PARAM_STATUS_DEMO.equals(version.paramStatus()) && !properties.isAllowDemoActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "DEMO_PARAMS_NOT_ALLOWED", "演示参数版本不允许激活");
        }
        if (version.ruleSetVersionId().equals(set.activeVersionId())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "该版本已是生效版本");
        // 影子版本被激活后不再是影子：同一版本同时作为生效与影子没有验证意义。
        String shadow = version.ruleSetVersionId().equals(set.shadowVersionId()) ? null : set.shadowVersionId();
        return change(set, KIND_ACTIVATE, "rule_set_activated", version.ruleSetVersionId(), shadow, set.activeVersionId(), set.activeVersionId(), version.ruleSetVersionId(), request.note());
    }

    @Transactional
    public RuleSetDto rollback(String code, String rawRequest, String key) {
        requireManage();
        VersionChangeRequest request = parse(rawRequest, ROLLBACK_FIELDS, false);
        String ruleSetCode = code(code);
        RuleSetRow set = lockSet(ruleSetCode);
        idempotency.claim(key, operation(KIND_ROLLBACK, ruleSetCode, request));
        requireVersion(set, request.expectedVersion());
        if (set.previousActiveVersionId() == null) throw new ApiException(HttpStatus.CONFLICT, "NO_PREVIOUS_VERSION", "没有可回滚的上一生效版本");
        VersionRow previous = requirePublishedVersion(set, set.previousActiveVersionId());
        if (PARAM_STATUS_DEMO.equals(previous.paramStatus()) && !properties.isAllowDemoActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "DEMO_PARAMS_NOT_ALLOWED", "上一版本为演示参数，不允许回滚为生效版本");
        }
        String shadow = previous.ruleSetVersionId().equals(set.shadowVersionId()) ? null : set.shadowVersionId();
        return change(set, KIND_ROLLBACK, "rule_set_rolled_back", previous.ruleSetVersionId(), shadow, set.activeVersionId(), set.activeVersionId(), previous.ruleSetVersionId(), request.note());
    }

    @Transactional
    public RuleSetDto shadow(String code, String rawRequest, String key) {
        requireManage();
        VersionChangeRequest request = parse(rawRequest, ACTIVATE_FIELDS, true);
        String ruleSetCode = code(code);
        RuleSetRow set = lockSet(ruleSetCode);
        idempotency.claim(key, operation(KIND_SHADOW_SET, ruleSetCode, request));
        requireVersion(set, request.expectedVersion());
        if (request.ruleSetVersionId() == null) {
            if (set.shadowVersionId() == null) throw new ApiException(HttpStatus.CONFLICT, "SHADOW_VERSION_NOT_SET", "规则集没有影子版本可清除");
            return change(set, KIND_SHADOW_CLEAR, "rule_set_shadow_changed", set.activeVersionId(), null, set.previousActiveVersionId(), set.shadowVersionId(), null, request.note());
        }
        VersionRow version = requirePublishedVersion(set, request.ruleSetVersionId());
        if (version.ruleSetVersionId().equals(set.shadowVersionId())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "该版本已是影子版本");
        return change(set, KIND_SHADOW_SET, "rule_set_shadow_changed", set.activeVersionId(), version.ruleSetVersionId(), set.previousActiveVersionId(), set.shadowVersionId(), version.ruleSetVersionId(), request.note());
    }

    private RuleSetDto change(RuleSetRow set, String kind, String auditAction, String active, String shadow, String previous, String from, String to, String note) {
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        // 锁已持有，这里的条件更新仍以 version 为准：受影响行数不为 1 一律视为冲突，不写激活记录。
        if (repository.updateHead(set.ruleSetId(), set.version(), active, shadow, previous, at) != 1) throw versionConflict();
        long resulting = set.version() + 1;
        AuthUser actor = AuthContext.require();
        repository.insertActivation(UUID.randomUUID().toString(), set.ruleSetId(), kind, from, to, actor.userId(), note, resulting, at);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, auditAction, OBJECT_TYPE, set.ruleSetId(),
                "kind=" + kind + "; from_version_id=" + from + "; to_version_id=" + to + "; resulting_version=" + resulting, "SUCCESS", "", "");
        RuleSetRow updated = repository.findRuleSetByCode(set.ruleSetCode());
        if (updated == null) throw new IllegalStateException("规则集在更新后消失: " + set.ruleSetCode());
        return dto(updated);
    }

    // ---- 守卫 ----

    /** 激活/回滚/影子都同时要求 rule:read 与 rule:manage，且先于路径、请求体与幂等键解析。 */
    private void requireManage() {
        access.require(PermissionCode.RULE_READ);
        access.require(PermissionCode.RULE_MANAGE);
    }

    private RuleSetRow requireSet(String code) {
        RuleSetRow set = repository.findRuleSetByCode(code);
        if (set == null) throw setNotFound();
        return set;
    }

    private RuleSetRow lockSet(String code) {
        RuleSetRow set = repository.lockRuleSetByCode(code);
        if (set == null) throw setNotFound();
        return set;
    }

    private static void requireVersion(RuleSetRow set, long expected) {
        if (set.version() != expected) throw versionConflict();
    }

    /** 版本必须属于该规则集且已发布；不属于本规则集的版本与不存在同样是 404，避免探测其他规则集的版本 ID。 */
    private VersionRow requirePublishedVersion(RuleSetRow set, String versionId) {
        VersionRow version = repository.findVersion(versionId);
        if (version == null || !version.ruleSetId().equals(set.ruleSetId())) throw versionNotFound();
        if (!STATUS_PUBLISHED.equals(version.statusCode())) throw new ApiException(HttpStatus.CONFLICT, "RULE_VERSION_NOT_PUBLISHED", "规则集版本尚未发布");
        return version;
    }

    // ---- 解析 ----

    private VersionChangeRequest parse(String raw, Set<String> fields, boolean nullableVersion) {
        if (raw == null || raw.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            node.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name); });
            if (node.size() != fields.size()) throw invalidRequest();
            JsonNode note = node.get("note"), expected = node.get("expected_version");
            if (!note.isTextual() || note.textValue().isBlank() || note.textValue().trim().length() > NOTE_MAX) throw invalidRequest();
            if (!expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0) throw invalidRequest();
            String versionId = null;
            if (fields.contains("rule_set_version_id")) {
                JsonNode version = node.get("rule_set_version_id");
                if (version.isNull()) { if (!nullableVersion) throw invalidRequest(); }
                else if (!version.isTextual() || version.textValue().isBlank() || version.textValue().trim().length() > ID_MAX) throw invalidRequest();
                else versionId = version.textValue().trim();
            }
            return new VersionChangeRequest(versionId, note.textValue().trim(), expected.longValue());
        } catch (IOException ex) {
            throw invalidRequest();
        }
    }

    private static Page page(MultiValueMap<String, String> parameters, Set<String> allowed) {
        parameters.keySet().stream().filter(key -> !allowed.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        int page = integer(parameters, "page", 1), size = integer(parameters, "size", PAGE_DEFAULT);
        if (page < 1 || size < 1 || size > PAGE_MAX) throw invalid("分页参数无效");
        try { return new Page(page, size, Math.multiplyExact(page - 1, size)); }
        catch (ArithmeticException ex) { throw invalid("分页参数无效"); }
    }

    private static int integer(MultiValueMap<String, String> parameters, String name, int fallback) {
        if (!parameters.containsKey(name)) return fallback;
        try { return Integer.parseInt(single(parameters, name)); }
        catch (NumberFormatException ex) { throw invalid("分页参数无效"); }
    }

    private static String enumerated(MultiValueMap<String, String> parameters, String name, Set<String> allowed) {
        if (!parameters.containsKey(name)) return null;
        String value = single(parameters, name);
        if (!allowed.contains(value)) throw invalid(name + " 参数无效");
        return value;
    }

    private static OffsetDateTime[] timeRange(MultiValueMap<String, String> parameters, String fromKey, String toKey) {
        boolean hasFrom = parameters.containsKey(fromKey), hasTo = parameters.containsKey(toKey);
        if (!hasFrom && !hasTo) return new OffsetDateTime[] { null, null };
        if (hasFrom != hasTo) throw badTime();
        try {
            OffsetDateTime from = Instant.ofEpochMilli(Long.parseLong(single(parameters, fromKey))).atOffset(ZoneOffset.UTC);
            OffsetDateTime to = Instant.ofEpochMilli(Long.parseLong(single(parameters, toKey))).atOffset(ZoneOffset.UTC);
            if (!from.isBefore(to)) throw badTime();
            return new OffsetDateTime[] { from, to };
        } catch (NumberFormatException ex) {
            throw badTime();
        }
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        List<String> found = parameters.get(name);
        if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效");
        return found.get(0).trim();
    }

    private static String code(String value) {
        String code = value == null ? "" : value.trim();
        if (code.isEmpty() || code.length() > CODE_MAX) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "规则集编码无效");
        return code;
    }

    private static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > ID_MAX) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }

    /** 长度前缀序列化：编码与 ID 可含分隔符，直接拼接会把不同请求误判为重放。 */
    private static String operation(String kind, String code, VersionChangeRequest request) {
        return framed("rule-set:" + kind) + framed(code) + framed(request.ruleSetVersionId() == null ? "" : request.ruleSetVersionId()) + framed(Long.toString(request.expectedVersion()));
    }
    private static String framed(String value) { return value.getBytes(StandardCharsets.UTF_8).length + ":" + value; }

    // ---- 映射 ----

    private static RuleSetDto dto(RuleSetRow row) {
        return new RuleSetDto(row.ruleSetId(), row.ruleSetCode(), row.name(), row.activeVersionId(), row.shadowVersionId(), row.previousActiveVersionId(), row.version(),
                millis(row.createdAt()), millis(row.updatedAt()));
    }
    private static RuleSetVersionDto dto(VersionDetailRow row) {
        return new RuleSetVersionDto(row.ruleSetVersionId(), row.ruleSetId(), row.ruleSetCode(), row.versionNo(), row.statusCode(), row.paramStatus(), millis(row.validFrom()),
                optionalMillis(row.validTo()), row.description(), row.sourceMode(), millis(row.createdAt()), optionalMillis(row.publishedAt()), row.active(), row.shadow());
    }
    private static ActivationDto dto(ActivationRow row) {
        return new ActivationDto(row.activationId(), row.ruleSetId(), row.kind(), row.fromVersionId(), row.toVersionId(), row.actorId(), row.note(), row.resultingVersion(), millis(row.createdAt()));
    }
    private static RuleRunDto dto(RunDetailRow row) {
        return new RuleRunDto(row.runId(), row.ruleSetId(), row.ruleSetCode(), row.ruleSetVersionId(), row.mode(), row.triggerKind(), row.replayDatasetCode(), row.triggeredBy(),
                millis(row.asOf()), millis(row.startedAt()), optionalMillis(row.finishedAt()), row.status(), row.subjectCount(), row.evaluatedCount(), row.alarmCreatedCount(),
                row.alarmMergedCount(), row.errorSummary(), row.sourceMode(), millis(row.createdAt()));
    }
    private static long millis(OffsetDateTime value) {
        if (value == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
        return value.toInstant().toEpochMilli();
    }
    private static Long optionalMillis(OffsetDateTime value) { return value == null ? null : value.toInstant().toEpochMilli(); }

    private static ApiException invalidRequest() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求体无效"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException badTime() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); }
    private static ApiException setNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "RULE_SET_NOT_FOUND", "规则集不存在"); }
    private static ApiException versionNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "RULE_VERSION_NOT_FOUND", "规则集版本不存在"); }
    private static ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "规则集已被其他操作更新"); }

    private record Page(int page, int size, int offset) { }
}
