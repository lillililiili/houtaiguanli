package com.uav.lowaltitude.modules.assessment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvaluationDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvidenceRefDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.HitDetailDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.PageDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.ParamRefDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.ReviewDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.RevisionDto;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.EvaluationQuery;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.EvaluationRow;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.RevisionRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 引擎研判读取：列表 / 详情 / 复核历史。鉴权先于参数解析；关联引用（目标、计划、告警）按各自读权限与同元组可见性脱敏；
 * allowed_actions 与列表、详情共用同一范围谓词，并同时反映状态与操作者的动作权限。
 */
@Service
public class LegalityEvaluationReadService {
    public static final String ACTION_REVIEW = "REVIEW", ACTION_RECOMPUTE = "RECOMPUTE", ACTION_ESCALATE = "ESCALATE";
    private static final Set<String> ALLOWED = Set.of("mode", "latest_only", "legal_status", "plan_match", "review_state", "subject_kind", "target_id",
            "plan_id", "from", "to", "owner_org_id", "district_id", "source_mode", "page", "size");
    private static final Set<String> MODES = Set.of("ACTIVE", "SHADOW");
    private static final Set<String> LEGAL_STATUSES = Set.of("LEGAL", "ABNORMAL", "ILLEGAL", "UNDETERMINED", "NOT_APPLICABLE");
    private static final Set<String> PLAN_MATCHES = Set.of("FULL", "PARTIAL", "NONE", "UNDETERMINED", "NOT_APPLICABLE");
    private static final Set<String> REVIEW_STATES = Set.of("PENDING_REVIEW", "CONFIRMED", "REJECTED", "OVERRIDDEN", "SUPERSEDED");
    private static final Set<String> SUBJECTS = Set.of("TARGET", "PLAN");
    private static final Set<String> SOURCE_MODES = Set.of("mock", "replay", "live");
    private static final Set<String> RESULT_CODES = Set.of("PASS", "FAIL", "UNDETERMINED", "NOT_APPLICABLE");
    private final AccessControlService access;
    private final LegalityEvaluationReadRepository repository;
    private final ObjectMapper json;

    public LegalityEvaluationReadService(AccessControlService access, LegalityEvaluationReadRepository repository, ObjectMapper json) {
        this.access = access; this.repository = repository; this.json = json;
    }

    @Transactional(readOnly = true)
    public PageDto<EvaluationDto> list(MultiValueMap<String, String> values) {
        // 鉴权必须先于参数解析，防止未授权调用者用 400/404 差异探测受保护接口。
        AccessDecision decision = access.require(PermissionCode.ASSESSMENT_READ);
        // 原始请求只要出现关联筛选就先要求关联读取动作；不能先解析其他坏参数泄露筛选能力。
        if (values.containsKey("target_id")) access.require(PermissionCode.TARGET_READ);
        if (values.containsKey("plan_id")) access.require(PermissionCode.FLIGHT_READ);
        Request request = new Request(values);
        Page page = request.page();
        TimeRange range = request.timeRange("from", "to");
        EvaluationQuery query = new EvaluationQuery(request.enumerated("mode", MODES), request.bool("latest_only"),
                request.enumerated("legal_status", LEGAL_STATUSES), request.enumerated("plan_match", PLAN_MATCHES),
                request.enumerated("review_state", REVIEW_STATES), request.enumerated("subject_kind", SUBJECTS),
                request.optional("target_id", 36), request.optional("plan_id", 36), range.from, range.to,
                request.optional("owner_org_id", 36), request.optional("district_id", 36), request.enumerated("source_mode", SOURCE_MODES));
        long total = repository.count(query, decision);
        return new PageDto<>(repository.list(query, decision, page.offset(), page.size).stream().map(row -> dto(row, decision)).toList(), page.page, page.size, total);
    }

    @Transactional(readOnly = true)
    public EvaluationDto detail(String evaluationId) {
        AccessDecision decision = access.require(PermissionCode.ASSESSMENT_READ);
        return detail(id(evaluationId), decision);
    }

    /** 写用例成功后回读同一谓词下的详情；范围内不存在即 404。 */
    @Transactional(readOnly = true)
    public EvaluationDto detail(String evaluationId, AccessDecision decision) {
        EvaluationRow row = repository.find(evaluationId, decision);
        if (row == null) throw notFound();
        return dto(row, decision);
    }

    @Transactional(readOnly = true)
    public PageDto<RevisionDto> revisions(String evaluationId, MultiValueMap<String, String> values) {
        AccessDecision decision = access.require(PermissionCode.ASSESSMENT_READ);
        String id = id(evaluationId);
        values.keySet().stream().filter(key -> !key.equals("page") && !key.equals("size")).findFirst()
                .ifPresent(key -> { throw Request.invalid(key + " 参数无效"); });
        Page page = new Request(values).page();
        if (repository.find(id, decision) == null) throw notFound();
        long total = repository.countRevisions(id, decision);
        return new PageDto<>(repository.revisions(id, decision, page.offset(), page.size).stream().map(this::revision).toList(), page.page, page.size, total);
    }

    public EvaluationDto dto(EvaluationRow row, AccessDecision decision) {
        boolean targetVisible = has(PermissionCode.TARGET_READ) && repository.targetVisible(row.targetId(), row.ownerOrgId(), row.districtId(), decision);
        boolean planVisible = has(PermissionCode.FLIGHT_READ) && repository.planVisible(row.planId(), row.ownerOrgId(), row.districtId(), decision);
        String alarmId = row.alarmId();
        boolean alarmVisible = alarmId != null && has(PermissionCode.ALARM_READ) && repository.alarmVisible(alarmId, row.ownerOrgId(), row.districtId(), decision);
        ReviewDto review = row.reviewState() == null ? null : new ReviewDto(row.reviewState(), row.manualStatus(), row.reviewVersion() == null ? 0 : row.reviewVersion());
        return new EvaluationDto(row.evaluationId(), row.runId(), row.ruleSetCode(), row.ruleSetVersionId(), row.ruleSetVersionNo(), row.paramStatus(),
                row.mode(), row.triggerKind(), row.subjectKind(), targetVisible ? row.targetId() : null, targetVisible ? row.targetNo() : null,
                targetVisible ? row.trackId() : null, planVisible ? row.planId() : null, planVisible ? row.planNo() : null,
                planVisible ? row.routeVersionId() : null, millis(row.observedAt()), requiredMillis(row.asOf()), requiredMillis(row.evaluatedAt()),
                row.freshnessCode(), row.planMatchCode(), row.legalStatus(), row.score(), row.grade(), strings(row.violationReasons()),
                strings(row.unknownReasons()), evidence(row.evidenceReferences()), row.hitDetails() == null ? null : hits(row.hitDetails()),
                review, allowedActions(row, alarmId != null), row.supersedesEvaluationId(), row.supersededByEvaluationId(),
                alarmVisible ? alarmId : null, alarmVisible ? repository.eventIdOfAlarm(alarmId) : null, outcomeKind(row.alarmOutcome(), row.memberKind()),
                row.assessmentId(), row.ownerOrgId(), row.ownerOrgName(), row.districtId(), row.districtName(), row.sourceMode());
    }

    /**
     * REVIEW：PENDING_REVIEW 且有 revise；RECOMPUTE：ACTIVE、有复核行、非 SUPERSEDED 且有 evaluate（含主体读权限）；
     * ESCALATE：ACTIVE、结论 ≠ LEGAL、尚无任何告警关联、有目标且有 escalate。动作权限缺失时不给出误导入口。
     */
    private List<String> allowedActions(EvaluationRow row, boolean linked) {
        List<String> actions = new ArrayList<>();
        boolean active = "ACTIVE".equals(row.mode()) && row.reviewState() != null;
        boolean superseded = "SUPERSEDED".equals(row.reviewState());
        if (active && "PENDING_REVIEW".equals(row.reviewState()) && has(PermissionCode.ASSESSMENT_REVISE)) actions.add(ACTION_REVIEW);
        if (active && !superseded && has(PermissionCode.ASSESSMENT_EVALUATE) && has(subjectRead(row.subjectKind()))) actions.add(ACTION_RECOMPUTE);
        if (active && !superseded && !"LEGAL".equals(row.legalStatus()) && !linked && row.targetId() != null && has(PermissionCode.ASSESSMENT_ESCALATE)) actions.add(ACTION_ESCALATE);
        return List.copyOf(actions);
    }

    public static PermissionCode subjectRead(String subjectKind) { return "PLAN".equals(subjectKind) ? PermissionCode.FLIGHT_READ : PermissionCode.TARGET_READ; }

    private boolean has(PermissionCode permission) { try { access.require(permission); return true; } catch (ApiException ignored) { return false; } }

    private RevisionDto revision(RevisionRow row) {
        return new RevisionDto(row.historyId(), row.version(), row.previousState(), row.resultingState(), row.conclusion(), row.statusBefore(), row.statusAfter(),
                row.note(), row.actorId(), row.actorName(), row.relatedEvaluationId(), row.relatedAlarmId(), requiredMillis(row.createdAt()));
    }

    private String outcomeKind(String alarmOutcome, String memberKind) {
        if (memberKind != null) return memberKind;
        if (alarmOutcome == null) return null;
        try { JsonNode node = tree(alarmOutcome); return node.path("kind").isTextual() ? node.path("kind").asText() : null; }
        catch (Exception ex) { throw invalidStoredJson(); }
    }

    private List<String> strings(String text) {
        try {
            JsonNode root = array(text); List<String> output = new ArrayList<>();
            for (JsonNode item : root) { if (!item.isTextual()) throw invalidStoredJson(); output.add(item.textValue()); }
            return List.copyOf(output);
        } catch (ApiException ex) { throw ex; } catch (Exception ex) { throw invalidStoredJson(); }
    }

    /** 证据引用统一成 {kind,id}；早期以纯字符串保存的引用保留原文放在 id。 */
    private List<EvidenceRefDto> evidence(String text) {
        try {
            JsonNode root = array(text); List<EvidenceRefDto> output = new ArrayList<>();
            for (JsonNode item : root) {
                if (item.isTextual()) output.add(new EvidenceRefDto(null, item.textValue()));
                else if (item.isObject()) output.add(new EvidenceRefDto(item.path("kind").asText(null), item.path("id").asText(null)));
                else throw invalidStoredJson();
            }
            return List.copyOf(output);
        } catch (ApiException ex) { throw ex; } catch (Exception ex) { throw invalidStoredJson(); }
    }

    private List<HitDetailDto> hits(String text) {
        try {
            JsonNode root = array(text); List<HitDetailDto> output = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isObject() || item.path("rule_code").asText().isBlank()) throw invalidStoredJson();
                String result = item.path("result_code").asText();
                if (!RESULT_CODES.contains(result)) throw invalidStoredJson();
                Map<String, Object> facts = item.path("facts").isObject() ? json.convertValue(item.path("facts"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { }) : Map.of();
                List<ParamRefDto> params = new ArrayList<>();
                for (JsonNode p : item.path("params")) params.add(new ParamRefDto(p.path("key").asText(null), p.path("value").asText(null), p.path("status").asText(null)));
                List<EvidenceRefDto> refs = new ArrayList<>();
                for (JsonNode ref : item.path("evidence")) refs.add(new EvidenceRefDto(ref.path("kind").asText(null), ref.path("id").asText(null)));
                BigDecimal severity = item.path("severity").isNumber() ? item.path("severity").decimalValue() : null;
                output.add(new HitDetailDto(item.path("rule_code").asText(), item.path("rule_version_id").asText(null), result, item.path("reason_code").asText(null),
                        severity, facts, List.copyOf(params), List.copyOf(refs), item.path("message").asText(null)));
            }
            return List.copyOf(output);
        } catch (ApiException ex) { throw ex; } catch (Exception ex) { throw invalidStoredJson(); }
    }

    private JsonNode array(String text) throws Exception { JsonNode root = tree(text); if (!root.isArray()) throw invalidStoredJson(); return root; }
    /** H2 的 JSON 列可能以带引号的字符串回读，先解一层。 */
    private JsonNode tree(String text) throws Exception { JsonNode root = json.readTree(text == null ? "null" : text); if (root.isTextual()) root = json.readTree(root.textValue()); return root; }

    public static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 36) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "LEGALITY_EVALUATION_NOT_FOUND", "合法性研判不存在"); }
    private static ApiException invalidStoredJson() { return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "已保存研判数据格式无效"); }
    private static Long millis(OffsetDateTime value) { return value == null ? null : value.toInstant().toEpochMilli(); }
    private static long requiredMillis(OffsetDateTime value) { if (value == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误"); return value.toInstant().toEpochMilli(); }

    /** 严格 query 解析：未知/重复/空白参数 400；沿用阶段 4 风险读取的口径。 */
    static final class Request {
        private final MultiValueMap<String, String> values;
        Request(MultiValueMap<String, String> values) { this(values, ALLOWED); }
        Request(MultiValueMap<String, String> values, Set<String> allowed) {
            this.values = values;
            values.keySet().stream().filter(key -> !allowed.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        }
        Page page() { int page = integer("page", 1), size = integer("size", 20); if (page < 1 || size < 1 || size > 100) throw invalid("分页参数无效"); return new Page(page, size); }
        String optional(String name, int max) { if (!values.containsKey(name)) return null; String value = single(name); if (value.length() > max) throw invalid(name + " 参数无效"); return value; }
        String enumerated(String name, Set<String> allowed) { String value = optional(name, 32); if (value != null && !allowed.contains(value)) throw invalid(name + " 参数无效"); return value; }
        boolean bool(String name) { String value = optional(name, 8); if (value == null) return false; if ("true".equals(value)) return true; if ("false".equals(value)) return false; throw invalid(name + " 参数无效"); }
        TimeRange timeRange(String fromKey, String toKey) {
            boolean hasFrom = values.containsKey(fromKey), hasTo = values.containsKey(toKey);
            if (!hasFrom && !hasTo) return new TimeRange(null, null); if (hasFrom != hasTo) throw badTime();
            try {
                OffsetDateTime from = Instant.ofEpochMilli(Long.parseLong(single(fromKey))).atOffset(ZoneOffset.UTC);
                OffsetDateTime to = Instant.ofEpochMilli(Long.parseLong(single(toKey))).atOffset(ZoneOffset.UTC);
                if (!from.isBefore(to)) throw badTime(); return new TimeRange(from, to);
            } catch (NumberFormatException ex) { throw badTime(); }
        }
        private int integer(String name, int fallback) { if (!values.containsKey(name)) return fallback; try { return Integer.parseInt(single(name)); } catch (NumberFormatException ex) { throw invalid("分页参数无效"); } }
        private String single(String name) { List<String> found = values.get(name); if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效"); return found.get(0).trim(); }
        static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
        static ApiException badTime() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); }
    }
    record Page(int page, int size) { int offset() { try { return Math.multiplyExact(page - 1, size); } catch (ArithmeticException ex) { throw Request.invalid("分页参数无效"); } } }
    record TimeRange(OffsetDateTime from, OffsetDateTime to) { }
}
