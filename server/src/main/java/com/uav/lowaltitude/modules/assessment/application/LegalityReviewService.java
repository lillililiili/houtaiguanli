package com.uav.lowaltitude.modules.assessment.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeOutcome;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EscalationResultDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvaluateResultDto;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvaluationDto;
import com.uav.lowaltitude.modules.assessment.engine.LegalityEvaluationService.EvaluationResult;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RuleSetRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunHandle;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.EvaluationRow;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityReviewRepository;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityReviewRepository.HistoryInsert;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityReviewRepository.ReviewRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 合法性研判的写用例：人工复核（确认/驳回/改判）、重新研判、转告警、手动评估。可观察的事务内顺序固定为：
 * 动作鉴权（先 assessment:read 再具体动作）→ 语法与字段校验 → 按范围锁 legality_review → 占用幂等键 → 版本与状态 →
 * 条件更新 → 追加历史 → 成功审计 → 提交；任一步失败整体回滚，拒绝审计由统一异常路径在事务外记录。
 */
@Service
public class LegalityReviewService {
    static final String MODULE = "assessment";
    static final String OBJECT_TYPE = "legality_evaluation";
    static final String STATE_PENDING = "PENDING_REVIEW", STATE_CONFIRMED = "CONFIRMED", STATE_REJECTED = "REJECTED",
            STATE_OVERRIDDEN = "OVERRIDDEN", STATE_SUPERSEDED = "SUPERSEDED";
    private static final Set<String> CONCLUSIONS = Set.of("CONFIRM", "REJECT", "OVERRIDE");
    private static final Set<String> OVERRIDE_STATUSES = Set.of("LEGAL", "ABNORMAL", "ILLEGAL", "UNDETERMINED");
    private static final int NOTE_MAX = 1000;
    private final AccessControlService access;
    private final LegalityReviewRepository reviews;
    private final LegalityEvaluationReadRepository evaluations;
    private final LegalityEvaluationReadService read;
    private final AlarmEscalationService escalation;
    private final RuleRunService runs;
    private final RuleEngineRepository engine;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper objectMapper;

    public LegalityReviewService(AccessControlService access, LegalityReviewRepository reviews, LegalityEvaluationReadRepository evaluations,
            LegalityEvaluationReadService read, AlarmEscalationService escalation, RuleRunService runs, RuleEngineRepository engine,
            IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper objectMapper) {
        this.access = access; this.reviews = reviews; this.evaluations = evaluations; this.read = read; this.escalation = escalation;
        this.runs = runs; this.engine = engine; this.idempotency = idempotency;
        this.audit = audit; this.clock = clock; this.objectMapper = objectMapper;
    }

    /** POST /legality-evaluations/{id}/revisions */
    @Transactional
    public EvaluationDto revise(String evaluationId, String rawBody, String idempotencyKey) {
        AccessDecision readAccess = access.require(PermissionCode.ASSESSMENT_READ);
        access.require(PermissionCode.ASSESSMENT_REVISE);
        String id = LegalityEvaluationReadService.id(evaluationId);
        Body body = parse(rawBody, Set.of("conclusion", "override_status", "note", "expected_version"), Set.of("conclusion", "note", "expected_version"));
        String conclusion = body.text("conclusion");
        if (conclusion == null || !CONCLUSIONS.contains(conclusion)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONCLUSION", "复核结论无效");
        String overrideStatus = body.text("override_status");
        if ("OVERRIDE".equals(conclusion)) {
            if (overrideStatus == null) throw new ApiException(HttpStatus.BAD_REQUEST, "OVERRIDE_STATUS_REQUIRED", "改判必须给出人工结论");
            if (!OVERRIDE_STATUSES.contains(overrideStatus)) throw validation("override_status 无效");
        } else if (overrideStatus != null) {
            throw validation("只有改判可以携带 override_status");
        }
        String note = body.note();
        long expectedVersion = body.version();

        ReviewRow review = reviews.lock(id, readAccess);
        if (review == null) throw LegalityEvaluationReadService.notFound();
        // 先完成读/写权限与范围锁定才声明幂等键，越权者不能靠重放键探测已提交请求。
        idempotency.claim(idempotencyKey, stable("legality-revision", id, conclusion, overrideStatus == null ? "" : overrideStatus, note, expectedVersion));
        if (review.version() != expectedVersion) throw conflict();
        if (STATE_SUPERSEDED.equals(review.reviewState())) throw superseded();
        if (!STATE_PENDING.equals(review.reviewState())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前复核状态不允许该结论");

        String nextState = switch (conclusion) { case "CONFIRM" -> STATE_CONFIRMED; case "REJECT" -> STATE_REJECTED; default -> STATE_OVERRIDDEN; };
        // 确认即采纳系统结论；驳回表示系统误判、没有人工结论；改判以人工结论为准。
        String manualStatus = switch (conclusion) { case "CONFIRM" -> review.legalStatus(); case "REJECT" -> null; default -> overrideStatus; };
        if ("OVERRIDE".equals(conclusion) && overrideStatus.equals(review.legalStatus())) throw validation("改判结论与系统结论相同，请使用确认");
        OffsetDateTime at = now();
        // 条件更新为 0 行代表竞争写入，绝不追加一条与实际状态不一致的复核历史。
        if (reviews.transition(id, expectedVersion, STATE_PENDING, nextState, manualStatus, at) != 1) throw conflict();
        AuthUser actor = AuthContext.require();
        reviews.appendHistory(new HistoryInsert(UUID.randomUUID().toString(), id, expectedVersion + 1, STATE_PENDING, nextState, conclusion,
                review.legalStatus(), manualStatus, note, actor.userId(), null, null, at));
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "legality_evaluation_revised", OBJECT_TYPE, id,
                "conclusion=" + conclusion + (manualStatus == null ? "" : "; manual_status=" + manualStatus) + "; version=" + (expectedVersion + 1), "SUCCESS", "", "");
        return read.detail(id, readAccess);
    }

    /** POST /legality-evaluations/{id}/alarms */
    @Transactional
    public EscalationResultDto escalate(String evaluationId, String rawBody, String idempotencyKey) {
        AccessDecision readAccess = access.require(PermissionCode.ASSESSMENT_READ);
        access.require(PermissionCode.ASSESSMENT_ESCALATE);
        String id = LegalityEvaluationReadService.id(evaluationId);
        Body body = parse(rawBody, Set.of("note", "expected_version"), Set.of("note", "expected_version"));
        String note = body.note();
        long expectedVersion = body.version();

        ReviewRow review = reviews.lock(id, readAccess);
        if (review == null) throw LegalityEvaluationReadService.notFound();
        idempotency.claim(idempotencyKey, stable("legality-escalation", id, "", "", note, expectedVersion));
        if (review.version() != expectedVersion) throw conflict();
        if (STATE_SUPERSEDED.equals(review.reviewState())) throw superseded();
        if ("LEGAL".equals(review.legalStatus())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "系统结论为合法的研判不能转告警");
        if (review.targetId() == null) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "研判没有关联目标，无法生成来源告警");
        // 已有告警关联（引擎回填、合并成员或此前人工转告警）就不能再建第二条；这里读未脱敏的行，不受操作者 alarm:read 影响。
        EvaluationRow linked = evaluations.find(id, readAccess);
        if (review.engineAlarmId() != null || (linked != null && linked.alarmId() != null)) throw alreadyLinked();

        MergeOutcome outcome = escalation.escalate(review);
        if (outcome == null || outcome.alarmId() == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "告警入库未返回结果");
        OffsetDateTime at = now();
        if (reviews.bump(id, expectedVersion, at) != 1) throw conflict();
        AuthUser actor = AuthContext.require();
        reviews.appendHistory(new HistoryInsert(UUID.randomUUID().toString(), id, expectedVersion + 1, review.reviewState(), review.reviewState(), "ESCALATE",
                review.legalStatus(), review.manualStatus(), note, actor.userId(), null, outcome.alarmId(), at));
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "legality_evaluation_escalated", OBJECT_TYPE, id,
                "alarm_id=" + outcome.alarmId() + "; event_id=" + outcome.eventId() + "; version=" + (expectedVersion + 1), "SUCCESS", "", "");
        return new EscalationResultDto(outcome.alarmId(), outcome.eventId(), read.detail(id, readAccess));
    }

    /**
     * POST /legality-evaluations/{id}/recompute：按研判自己的规则集当前 ACTIVE 版本重新评估。
     * 新研判 supersedes 旧研判（旧行不改），旧复核置 SUPERSEDED 并追加 RECOMPUTE 历史；新复核行由引擎钩子在同事务建 PENDING_REVIEW。
     */
    @Transactional
    public EvaluationDto recompute(String evaluationId, String rawBody, String idempotencyKey) {
        AccessDecision readAccess = access.require(PermissionCode.ASSESSMENT_READ);
        access.require(PermissionCode.ASSESSMENT_EVALUATE);
        String id = LegalityEvaluationReadService.id(evaluationId);
        Body body = parse(rawBody, Set.of("note", "expected_version"), Set.of("note", "expected_version"));
        String note = body.note();
        long expectedVersion = body.version();

        ReviewRow review = reviews.lock(id, readAccess);
        if (review == null) throw LegalityEvaluationReadService.notFound();
        // 主体读权限依赖研判的主体类型，只能在读到研判后校验；固定的两项动作权限已先于解析完成。
        access.require(LegalityEvaluationReadService.subjectRead(review.subjectKind()));
        idempotency.claim(idempotencyKey, stable("legality-recompute", id, "", "", note, expectedVersion));
        if (review.version() != expectedVersion) throw conflict();
        if (STATE_SUPERSEDED.equals(review.reviewState())) throw superseded();
        if (!RunMode.ACTIVE.name().equals(review.mode())) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "只有生效模式的研判可以重算");
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = now();
        String subjectId = "PLAN".equals(review.subjectKind()) ? review.planId() : review.targetId();
        if (subjectId == null) throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "研判主体已不存在，无法重算");
        Subject subject = new Subject(SubjectKind.valueOf(review.subjectKind()), subjectId, review.ownerOrgId(), review.districtId(), review.sourceMode());
        RunHandle run = runs.start(review.ruleSetCode(), RunMode.ACTIVE, "RECOMPUTE", null, actor.userId(), at);
        EvaluationResult result = runs.evaluateOne(run, subject, at, id);
        if (reviews.transition(id, expectedVersion, review.reviewState(), STATE_SUPERSEDED, review.manualStatus(), at) != 1) throw conflict();
        reviews.appendHistory(new HistoryInsert(UUID.randomUUID().toString(), id, expectedVersion + 1, review.reviewState(), STATE_SUPERSEDED, "RECOMPUTE",
                review.legalStatus(), result.legalStatus() == null ? null : result.legalStatus().name(), note, actor.userId(), result.evaluationId(), null, at));
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "legality_evaluation_recomputed", OBJECT_TYPE, id,
                "new_evaluation_id=" + result.evaluationId() + "; run_id=" + run.runId() + "; version=" + (expectedVersion + 1), "SUCCESS", "", "");
        return read.detail(result.evaluationId(), readAccess);
    }

    /**
     * POST /legality-evaluations：手动评估一个目标或计划。规则集取有对应（生效/影子）版本的集合；多于一个时按 rule_set_code 取第一个，
     * 没有则 409 NO_ACTIVE_RULE_SET / SHADOW_VERSION_NOT_SET。主体必须在操作者范围内可见，否则 404。
     */
    @Transactional
    public EvaluateResultDto evaluate(String rawBody, String idempotencyKey) {
        AccessDecision readAccess = access.require(PermissionCode.ASSESSMENT_READ);
        access.require(PermissionCode.ASSESSMENT_EVALUATE);
        Body body = parse(rawBody, Set.of("subject_kind", "subject_id", "mode"), Set.of("subject_kind", "subject_id", "mode"));
        String kind = body.text("subject_kind"), subjectId = body.text("subject_id"), modeText = body.text("mode");
        if (kind == null || !Set.of("TARGET", "PLAN").contains(kind)) throw validation("subject_kind 无效");
        if (modeText == null || !Set.of("ACTIVE", "SHADOW").contains(modeText)) throw validation("mode 无效");
        subjectId = LegalityEvaluationReadService.id(subjectId);
        RunMode mode = RunMode.valueOf(modeText);
        access.require(LegalityEvaluationReadService.subjectRead(kind));
        boolean visible = "PLAN".equals(kind) ? evaluations.planVisible(subjectId, null, null, readAccess) : evaluations.targetVisible(subjectId, null, null, readAccess);
        if (!visible) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "PLAN".equals(kind) ? "飞行计划不存在" : "目标不存在");
        idempotency.claim(idempotencyKey, stable("legality-evaluate", subjectId, kind, modeText, "", 0));
        RuleSetRow set = engine.ruleSetsWithVersions().stream()
                .filter(row -> mode == RunMode.ACTIVE ? row.activeVersionId() != null : row.shadowVersionId() != null).findFirst().orElse(null);
        if (set == null) {
            throw mode == RunMode.ACTIVE ? new ApiException(HttpStatus.CONFLICT, "NO_ACTIVE_RULE_SET", "没有生效的规则集版本")
                    : new ApiException(HttpStatus.CONFLICT, "SHADOW_VERSION_NOT_SET", "没有设置影子规则集版本");
        }
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = now();
        RunHandle run = runs.start(set.ruleSetCode(), mode, "MANUAL", null, actor.userId(), at);
        EvaluationResult result = runs.evaluateOne(run, new Subject(SubjectKind.valueOf(kind), subjectId, null, null, null), at, null);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "legality_evaluation_triggered", OBJECT_TYPE, result.evaluationId(),
                "subject_kind=" + kind + "; subject_id=" + subjectId + "; mode=" + modeText + "; run_id=" + run.runId(), "SUCCESS", "", "");
        return new EvaluateResultDto(run.runId(), read.detail(result.evaluationId(), readAccess));
    }

    OffsetDateTime now() { return clock.now().atOffset(ZoneOffset.UTC); }

    /** 长度前缀保留字段边界，避免说明中的分隔符把不同请求错误哈希成同一幂等操作。 */
    static String stable(String kind, String id, String conclusion, String overrideStatus, String note, long expectedVersion) {
        return part(kind) + part(id) + part(conclusion) + part(overrideStatus) + part(note) + part(Long.toString(expectedVersion));
    }
    private static String part(String value) { return value.length() + ":" + value; }

    static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "研判复核已被其他操作更新"); }
    static ApiException superseded() { return new ApiException(HttpStatus.CONFLICT, "EVALUATION_SUPERSEDED", "研判已被重算取代"); }
    static ApiException alreadyLinked() { return new ApiException(HttpStatus.CONFLICT, "ALARM_ALREADY_LINKED", "研判已关联告警"); }
    static ApiException validation(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }

    /**
     * 严格 JSON 对象解析：语法错误 INVALID_REQUEST；白名单外或重复键 UNKNOWN_FIELD；缺少必填键 VALIDATION_ERROR。
     * 字符串键只接受字符串值，expected_version 只接受可装入 long 的 JSON 整数。
     */
    Body parse(String rawBody, Set<String> allowed, Set<String> required) {
        Map<String, Object> values = new LinkedHashMap<>();
        try (JsonParser parser = objectMapper.getFactory().createParser(rawBody == null ? "" : rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalidRequest();
            Set<String> seen = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalidRequest();
                String field = parser.currentName();
                if (!allowed.contains(field) || !seen.add(field)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求包含未知或重复字段：" + field);
                JsonToken value = parser.nextToken();
                if ("expected_version".equals(field)) {
                    if (!value.isNumeric() || !parser.isExpectedNumberIntToken()) throw validation("expected_version 无效");
                    values.put(field, parser.getLongValue());
                } else {
                    if (value == JsonToken.VALUE_NULL) continue;
                    if (value != JsonToken.VALUE_STRING) throw validation(field + " 必须为字符串");
                    values.put(field, parser.getText());
                }
            }
            if (parser.nextToken() != null) throw invalidRequest();
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidRequest();
        }
        for (String key : required) if (!values.containsKey(key)) throw validation("缺少必填字段：" + key);
        return new Body(values);
    }

    private static ApiException invalidRequest() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确"); }

    record Body(Map<String, Object> values) {
        String text(String key) { Object value = values.get(key); if (value == null) return null; String text = value.toString().trim(); return text.isEmpty() ? null : text; }
        String note() {
            String note = text("note");
            if (note == null || note.length() > NOTE_MAX) throw validation("说明长度必须为1至1000");
            return note;
        }
        long version() {
            Object value = values.get("expected_version");
            if (!(value instanceof Long version) || version < 0) throw validation("expected_version 无效");
            return version;
        }
    }
}
