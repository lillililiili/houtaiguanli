package com.uav.lowaltitude.modules.punishment.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.ActionResultDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.CaseDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.DecisionDocumentDto;
import com.uav.lowaltitude.modules.punishment.domain.DecisionDocumentRenderer;
import com.uav.lowaltitude.modules.punishment.domain.PunishmentRules;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.CaseInsert;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.CaseRow;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.DiscretionInsert;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.DiscretionRow;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.DocumentInsert;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.DocumentRow;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.HandoffRefRow;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.RuleRow;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 处罚案件写侧。
 *
 * 事务顺序（全仓统一）：鉴权 → 严格解析请求体 → 锁行 → claim 幂等键 → 核对 expected_version →
 * 状态机 → 写入 → 成功审计（同事务）。失败审计由全局异常处理在事务外落库。
 *
 * 审计 detail 一律不带当事人名（决策 14-19 ②）：审计要能查"谁在何时做了什么"，
 * 不需要把当事人姓名复制到又一处；个人信息多存一份就多一处泄露面。
 */
@Service
public class PunishmentCaseService {
    private static final Set<String> FILE_FIELDS = Set.of("handoff_id", "party_type", "party_name", "note");
    private static final Set<String> ASSIGN_FIELDS = Set.of("officer_id", "expected_version");
    private static final Set<String> LEAD_FIELDS = Set.of("kind", "description", "expected_version");
    private static final Set<String> RESOLVE_FIELDS = Set.of("note", "expected_version");
    private static final Set<String> DISCRETION_FIELDS = Set.of("rule_code", "penalty_type", "fine_amount",
            "factors", "basis_text", "expected_version");
    private static final Set<String> REVIEW_FIELDS = Set.of("conclusion", "note", "missing_leads", "expected_version");
    private static final Set<String> VERSION_ONLY = Set.of("expected_version");
    private static final Set<String> REVOKE_FIELDS = Set.of("reason", "expected_version");
    private static final Set<String> CLOSE_FIELDS = Set.of("note", "expected_version");
    private static final Set<String> WITHDRAW_FIELDS = Set.of("reason", "expected_version");
    private static final String ISSUER_ORG = "东营市低空安全管理平台";

    private final AccessControlService access;
    private final PunishmentRepository repository;
    private final PunishmentReadService read;
    private final IdempotencyGuard idempotency;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;

    public PunishmentCaseService(AccessControlService access, PunishmentRepository repository,
            PunishmentReadService read, IdempotencyGuard idempotency, AppClock clock, AuditService audit,
            ObjectMapper json) {
        this.access = access; this.repository = repository; this.read = read; this.idempotency = idempotency;
        this.clock = clock; this.audit = audit; this.json = json;
    }

    /* ---- 立案 ---- */

    @Transactional
    public CaseDto file(String rawRequest, String key) {
        access.require(PermissionCode.PUNISHMENT_FILE);
        AccessDecision handoffDecision = access.require(PermissionCode.HANDOFF_READ);
        JsonNode body = strict(rawRequest, FILE_FIELDS);
        String handoffId = text(body, "handoff_id", true, 64);
        String partyType = text(body, "party_type", true, 8);
        PunishmentRules.requireKnown(partyType, PunishmentRules.PARTY_TYPES, "当事人类型无效");
        String partyName = text(body, "party_name", false, 128);
        // 当事人不详时不能同时留着名字：那样"不详"和"叫某某"会同时成立，文书上说不清认定了谁（决策 14-12）。
        if (PunishmentRules.PARTY_UNKNOWN.equals(partyType) && partyName != null)
            throw bad("VALIDATION_ERROR", "当事人类型为不详时不能填写名称");
        if (!PunishmentRules.PARTY_UNKNOWN.equals(partyType) && partyName == null)
            throw bad("VALIDATION_ERROR", "请填写当事人名称，或把类型改为不详");

        HandoffRefRow handoff = repository.punishmentHandoff(handoffId, handoffDecision);
        if (handoff == null) throw notFound();
        idempotency.claim(key, "punishment:file:" + handoffId);

        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String caseId = UUID.randomUUID().toString();
        String caseNo = PunishmentRules.caseNo(dayKey(at), repository.nextSequence(dayKey(at)));
        try {
            repository.insertCase(new CaseInsert(caseId, caseNo, handoff.eventId(), handoffId, PunishmentRules.FILED,
                    partyType, partyName, actor.userId(), actorName(actor), at, handoff.ownerOrgId(),
                    handoff.districtId(), handoff.sourceMode()));
        } catch (DuplicateKeyException duplicate) {
            // 一事件一案（决策 14-5）。并发下靠 event_id 唯一约束定胜负，先到的赢。
            throw new ApiException(HttpStatus.CONFLICT, "CASE_ALREADY_EXISTS", "该事件已经立案");
        }
        event(caseId, "FILE", actor, text(body, "note", false, 500),
                Map.of("status", PunishmentRules.FILED, "case_no", caseNo, "handoff_id", handoffId), at);
        audit(actor, "punishment_case_filed", caseId,
                "case_no=" + caseNo + "; handoff_id=" + handoffId + "; event_id=" + handoff.eventId());
        return read.detail(caseId);
    }

    /* ---- 承办与线索 ---- */

    @Transactional
    public ActionResultDto assign(String caseId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_FILE);
        JsonNode body = strict(rawRequest, ASSIGN_FIELDS);
        long expected = version(body);
        String officerId = text(body, "officer_id", true, 64);
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:assign:" + caseId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.ASSIGN, row.status());
        String officerName = repository.enabledUserName(officerId);
        if (officerName == null) throw notFound();
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.transition(caseId, expected, PunishmentRules.INVESTIGATING, at,
                Map.of("officer_id", officerId, "officer_name", officerName)) != 1) throw versionConflict();
        event(caseId, "ASSIGN", actor, null, Map.of("status", PunishmentRules.INVESTIGATING, "officer_id", officerId), at);
        audit(actor, "punishment_case_assigned", caseId, "case_no=" + row.caseNo() + "; officer_id=" + officerId);
        return new ActionResultDto(caseId, PunishmentRules.INVESTIGATING, row.version() + 1);
    }

    @Transactional
    public ActionResultDto addLead(String caseId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_FILE);
        JsonNode body = strict(rawRequest, LEAD_FIELDS);
        long expected = version(body);
        String kind = text(body, "kind", true, 24);
        PunishmentRules.requireKnown(kind, PunishmentRules.LEAD_KINDS, "线索类型无效");
        String description = text(body, "description", true, 500);
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:lead:" + caseId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.ADD_LEAD, row.status());
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String leadId = UUID.randomUUID().toString();
        repository.insertLead(leadId, caseId, kind, description, actor.userId(), at);
        // 线索不改变案件状态，但仍然要推进版本：否则并发下两个人可以基于同一版本各加各的，谁也不知道对方加过。
        if (repository.transition(caseId, expected, row.status(), at, Map.of()) != 1) throw versionConflict();
        event(caseId, "LEAD_ADDED", actor, description, Map.of("lead_id", leadId, "kind", kind), at);
        audit(actor, "punishment_lead_added", caseId, "case_no=" + row.caseNo() + "; kind=" + kind);
        return new ActionResultDto(caseId, row.status(), row.version() + 1);
    }

    @Transactional
    public ActionResultDto resolveLead(String caseId, String leadId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_FILE);
        JsonNode body = strict(rawRequest, RESOLVE_FIELDS);
        long expected = version(body);
        String note = text(body, "note", true, 500);
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:lead-resolve:" + leadId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.RESOLVE_LEAD, row.status());
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        // 返回 0 有两种可能：线索不存在，或已经被解决过。两者对调用者都是"这条线索不在待办里"，统一 404。
        if (repository.resolveLead(leadId, caseId, note, actor.userId(), at) != 1) throw notFound();
        if (repository.transition(caseId, expected, row.status(), at, Map.of()) != 1) throw versionConflict();
        event(caseId, "LEAD_RESOLVED", actor, note, Map.of("lead_id", leadId), at);
        audit(actor, "punishment_lead_resolved", caseId, "case_no=" + row.caseNo() + "; lead_id=" + leadId);
        return new ActionResultDto(caseId, row.status(), row.version() + 1);
    }

    /* ---- 裁量 ---- */

    @Transactional
    public ActionResultDto draftDiscretion(String caseId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_DECIDE);
        JsonNode body = strict(rawRequest, DISCRETION_FIELDS);
        long expected = version(body);
        String ruleCode = text(body, "rule_code", true, 32);
        String penaltyType = text(body, "penalty_type", true, 24);
        PunishmentRules.requireKnown(penaltyType, PunishmentRules.PENALTY_TYPES, "处罚种类无效");
        long fineAmount = longValue(body, "fine_amount");
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:discretion:" + caseId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.DRAFT_DISCRETION, row.status());
        RuleRow rule = repository.rule(ruleCode);
        if (rule == null) throw notFound();
        if (!allowedPenaltyTypes(rule.penaltyTypes()).contains(penaltyType))
            throw bad("PENALTY_TYPE_NOT_ALLOWED", "该档位不支持这种处罚种类");
        // 区间来自 penalty_rule，代码里不写任何金额（决策 14-8/14-9）。
        PunishmentRules.requireFineWithin(penaltyType, fineAmount, rule.fineMin(), rule.fineMax());

        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        // 一案同时只能有一份待确认裁量：旧草稿作废，否则"以哪一版为准"说不清（决策 14-9）。
        repository.supersedeDrafts(caseId);
        int versionNo = repository.nextDiscretionVersion(caseId);
        String discretionId = UUID.randomUUID().toString();
        repository.insertDiscretion(new DiscretionInsert(discretionId, caseId, versionNo, rule.violationCode(),
                ruleCode, penaltyType, fineAmount, write(body.get("factors")), text(body, "basis_text", false, 1000),
                actor.userId(), at));
        if (repository.transition(caseId, expected, row.status(), at,
                Map.of("primary_violation_code", rule.violationCode())) != 1) throw versionConflict();
        event(caseId, "DISCRETION_DRAFTED", actor, null,
                Map.of("discretion_id", discretionId, "version_no", versionNo, "rule_code", ruleCode), at);
        audit(actor, "punishment_discretion_drafted", caseId,
                "case_no=" + row.caseNo() + "; rule_code=" + ruleCode + "; version_no=" + versionNo);
        return new ActionResultDto(caseId, row.status(), row.version() + 1);
    }

    @Transactional
    public ActionResultDto confirmDiscretion(String caseId, String discretionId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_DECIDE);
        JsonNode body = strict(rawRequest, VERSION_ONLY);
        long expected = version(body);
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:discretion-confirm:" + discretionId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.CONFIRM_DISCRETION, row.status());
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.confirmDiscretion(discretionId, caseId, actor.userId(), at) != 1) throw notFound();
        // 确认裁量即提请复核（契约 v1.1）：没有单独的"提交复核"动作，少一步就少一处能停住不动的地方。
        if (repository.transition(caseId, expected, PunishmentRules.UNDER_REVIEW, at, Map.of()) != 1) throw versionConflict();
        event(caseId, "DISCRETION_CONFIRMED", actor, null, Map.of("discretion_id", discretionId), at);
        event(caseId, "REVIEW_REQUESTED", actor, null, Map.of("status", PunishmentRules.UNDER_REVIEW), at);
        audit(actor, "punishment_discretion_confirmed", caseId,
                "case_no=" + row.caseNo() + "; discretion_id=" + discretionId);
        return new ActionResultDto(caseId, PunishmentRules.UNDER_REVIEW, row.version() + 1);
    }

    /* ---- 复核 ---- */

    @Transactional
    public ActionResultDto review(String caseId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_REVIEW);
        JsonNode body = strict(rawRequest, REVIEW_FIELDS);
        long expected = version(body);
        String conclusion = text(body, "conclusion", true, 16);
        PunishmentRules.requireKnown(conclusion, PunishmentRules.REVIEW_CONCLUSIONS, "复核结论无效");
        String note = text(body, "note", true, 1000);
        // 请求体的结构校验全部排在锁行/版本/状态之前（决策 14-33）：畸形的待补线索是**请求本身**的问题，
        // 让它因为"案件状态不对"先吃 409，会把调用者引去查案件状态，而真正错的是他刚发过来的那段 JSON。
        List<MissingLead> missingLeads = parseMissingLeads(body.get("missing_leads"));
        // 维持原结论就意味着没有要补的：一份写着"维持"却挂着待补线索的复核意见自相矛盾，
        // 落到案件上更糟——线索会挂着没人处理，而案件已经进了 DECIDED。
        if (PunishmentRules.UPHELD.equals(conclusion) && !missingLeads.isEmpty())
            throw bad("VALIDATION_ERROR", "维持不能同时列待补线索");
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:review:" + caseId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.REVIEW, row.status());
        AuthUser actor = AuthContext.require();
        PunishmentRules.requireDifferentReviewer(row.officerId(), actor.userId());

        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String next = PunishmentRules.statusAfterReview(conclusion);
        repository.insertReview(UUID.randomUUID().toString(), caseId, actor.userId(), actorName(actor), conclusion,
                note, missingLeads.isEmpty() ? null : write(missingLeads), at);
        if (!PunishmentRules.UPHELD.equals(conclusion)) {
            // 退回调查：已确认的裁量作废，待补线索挂在案件上（决策 14-11）。
            // 不作废会让下一份决定书基于一份已经被否掉的裁量——那是最难发现的错。
            repository.supersedeConfirmed(caseId);
            for (MissingLead lead : missingLeads) {
                repository.insertLead(UUID.randomUUID().toString(), caseId, lead.kind(), lead.description(),
                        actor.userId(), at);
            }
        }
        Map<String, Object> extra = PunishmentRules.DECIDED.equals(next) ? Map.of("decided_at", at) : Map.of();
        if (repository.transition(caseId, expected, next, at, extra) != 1) throw versionConflict();
        event(caseId, "REVIEW_CONCLUDED", actor, note, Map.of("conclusion", conclusion, "status", next), at);
        // 审计带结论与去向（决策 14-19 ②）：只记"复核过了"事后说不清复核到底认了什么。
        audit(actor, "punishment_reviewed", caseId,
                "case_no=" + row.caseNo() + "; conclusion=" + conclusion + "; resulting_status=" + next);
        return new ActionResultDto(caseId, next, row.version() + 1);
    }

    /* ---- 决定书 ---- */

    @Transactional
    public DecisionDocumentDto issueDocument(String caseId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_DECIDE);
        JsonNode body = strict(rawRequest, VERSION_ONLY);
        long expected = version(body);
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:document:" + caseId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.ISSUE_DOCUMENT, row.status());
        DiscretionRow discretion = repository.currentDiscretion(caseId);
        // 决定书只能基于**冻结的**裁量（决策 14-10）：基于草稿出具，等于文书的依据还可以被改。
        if (discretion == null || !PunishmentRules.CONFIRMED.equals(discretion.status()))
            throw conflict("DISCRETION_NOT_CONFIRMED", "尚无已确认的裁量，不能出具决定书");
        RuleRow rule = repository.rule(discretion.ruleCode());
        if (rule == null) throw conflict("DISCRETION_NOT_CONFIRMED", "裁量引用的罚则档位已不可用");

        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String documentId = UUID.randomUUID().toString();
        String documentNo = PunishmentRules.documentNo(row.caseNo(), repository.nextDocumentSequence(caseId));
        // legal_basis 原样带出，不补条款号（决策 14-20）。
        Map<String, Object> fields = DecisionDocumentRenderer.fields(documentNo, row.caseNo(), row.partyName(),
                rule.title(), rule.legalBasis(), discretion.penaltyType(), discretion.fineAmount(),
                discretion.basisText(), ISSUER_ORG, actorName(actor), Instant.ofEpochMilli(clock.nowMillis()));
        String rendered = DecisionDocumentRenderer.render(fields);
        repository.insertDocument(new DocumentInsert(documentId, documentNo, caseId, discretion.discretionId(),
                DecisionDocumentRenderer.TEMPLATE_VERSION, write(fields), DecisionDocumentRenderer.sha256(rendered),
                actor.userId(), actorName(actor), at));
        if (repository.transition(caseId, expected, row.status(), at, Map.of()) != 1) throw versionConflict();
        event(caseId, "DOCUMENT_ISSUED", actor, null, Map.of("document_id", documentId, "document_no", documentNo), at);
        audit(actor, "punishment_decision_issued", caseId, "case_no=" + row.caseNo() + "; document_no=" + documentNo);
        return read.document(documentId);
    }

    @Transactional
    public DecisionDocumentDto revokeDocument(String documentId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_DECIDE);
        JsonNode body = strict(rawRequest, REVOKE_FIELDS);
        long expected = version(body);
        String reason = text(body, "reason", true, 500);
        DocumentRow document = repository.document(documentId);
        if (document == null) throw notFound();
        CaseRow row = locked(document.caseId(), decision);
        idempotency.claim(key, "punishment:document-revoke:" + documentId + ":" + expected);
        PunishmentRules.requireTransition(PunishmentRules.REVOKE_DOCUMENT, row.status());
        // 先按读到的状态预检（决策 14-27 ②）：UPDATE 的 WHERE 里带 status='ISSUED'，
        // 返回 0 时"已经作废过"和"版本冲突"混成同一个返回值，分不开。
        // 重复作废答 INVALID_TRANSITION，别让人以为是并发问题去重试。
        if (!PunishmentRules.ISSUED.equals(document.status()))
            throw conflict("INVALID_TRANSITION", "该决定书已作废，不能重复作废");
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        // 版本核对针对文书本身：作废的是这份文书，不是案件；案件可能同时在被别人推进。
        if (repository.revokeDocument(documentId, expected, reason, at) != 1) throw versionConflict();
        event(row.caseId(), "DOCUMENT_REVOKED", actor, reason,
                Map.of("document_id", documentId, "document_no", document.documentNo()), at);
        audit(actor, "punishment_decision_revoked", row.caseId(),
                "case_no=" + row.caseNo() + "; document_no=" + document.documentNo());
        return read.document(documentId);
    }

    /* ---- 结案与撤案 ---- */

    @Transactional
    public ActionResultDto close(String caseId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_CLOSE);
        JsonNode body = strict(rawRequest, CLOSE_FIELDS);
        long expected = version(body);
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:close:" + caseId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.CLOSE, row.status());
        // 结案是有文书的结案（决策 14-14）：没有出具过决定书就结案，卷宗里没有处罚这件事的结果。
        if (repository.issuedDocumentCount(caseId) < 1)
            throw conflict("DECISION_DOCUMENT_REQUIRED", "结案前必须至少出具一份有效的处罚决定书");
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String note = text(body, "note", false, 500);
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("closed_at", at);
        extra.put("close_note", note);
        if (repository.transition(caseId, expected, PunishmentRules.CLOSED, at, extra) != 1) throw versionConflict();
        event(caseId, "CLOSE", actor, note, Map.of("status", PunishmentRules.CLOSED), at);
        audit(actor, "punishment_case_closed", caseId, "case_no=" + row.caseNo());
        return new ActionResultDto(caseId, PunishmentRules.CLOSED, row.version() + 1);
    }

    @Transactional
    public ActionResultDto withdraw(String caseId, String rawRequest, String key) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_CLOSE);
        JsonNode body = strict(rawRequest, WITHDRAW_FIELDS);
        long expected = version(body);
        // 撤案理由必填：没有理由的撤案在事后无法交代（决策 14-14）。
        String reason = text(body, "reason", true, 500);
        CaseRow row = locked(caseId, decision);
        idempotency.claim(key, "punishment:withdraw:" + caseId + ":" + expected);
        requireVersion(row, expected);
        PunishmentRules.requireTransition(PunishmentRules.WITHDRAW, row.status());
        AuthUser actor = AuthContext.require();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.transition(caseId, expected, PunishmentRules.WITHDRAWN, at,
                Map.of("withdraw_reason", reason)) != 1) throw versionConflict();
        event(caseId, "WITHDRAW", actor, reason, Map.of("status", PunishmentRules.WITHDRAWN), at);
        audit(actor, "punishment_case_withdrawn", caseId, "case_no=" + row.caseNo());
        return new ActionResultDto(caseId, PunishmentRules.WITHDRAWN, row.version() + 1);
    }

    /* ---- 公共 ---- */

    private CaseRow locked(String caseId, AccessDecision decision) {
        CaseRow row = repository.lock(caseId, decision);
        if (row == null) throw notFound();
        return row;
    }

    private void requireVersion(CaseRow row, long expected) {
        if (row.version() != expected) throw versionConflict();
    }

    private void event(String caseId, String kind, AuthUser actor, String note, Map<String, Object> snapshot,
                       OffsetDateTime at) {
        repository.insertCaseEvent(UUID.randomUUID().toString(), caseId, kind, actor.userId(), actorName(actor),
                note, write(snapshot), at);
    }

    /**
     * 当前操作者的显示名（决策 14-27 ①）：一律取 app_user.name，不用 account。
     * account 是登录名，落进卷宗与决定书的"出具人"里读起来像个账号；姓名才是对外要交代的那个身份。
     * 取不到时退回 account——宁可显示登录名，也不留空让文书上出现一个没有署名的出具人。
     */
    private String actorName(AuthUser actor) {
        String name = repository.enabledUserName(actor.userId());
        return name == null ? actor.account() : name;
    }

    private void audit(AuthUser actor, String action, String objectId, String detail) {
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "punishment", action,
                "punishment_case", objectId, detail, "SUCCESS", "", "");
    }

    /**
     * 待补线索的结构校验（决策 14-33）。在动任何库之前做完，因此它只会产生 400，不会与状态冲突的 409 混在一起。
     * 缺 kind 或 description 一律拒绝：一条说不清"缺什么、属于哪一类"的线索挂在案件上，没有人知道该去补什么。
     */
    private List<MissingLead> parseMissingLeads(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw bad("VALIDATION_ERROR", "missing_leads 必须是数组");
        List<MissingLead> leads = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw bad("VALIDATION_ERROR", "待补线索必须是对象");
            String kind = item.path("kind").isTextual() ? item.path("kind").asText() : null;
            String description = item.path("description").isTextual() ? item.path("description").asText().trim() : null;
            if (kind == null || description == null || description.isEmpty())
                throw bad("VALIDATION_ERROR", "待补线索必须同时填写类型与说明");
            if (description.length() > 500) throw bad("VALIDATION_ERROR", "待补线索说明最长 500 个字符");
            PunishmentRules.requireKnown(kind, PunishmentRules.LEAD_KINDS, "线索类型无效");
            leads.add(new MissingLead(kind, description));
        }
        return leads;
    }

    private record MissingLead(String kind, String description) { }

    /** penalty_types 存的是 JSON 数组文本（决策 14-24）。读不出来时返回空集合——宁可全部拒绝，也不放行一个说不清依据的档位。 */
    private List<String> allowedPenaltyTypes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isArray()) return List.of();
            List<String> types = new java.util.ArrayList<>();
            for (JsonNode item : node) types.add(item.asText());
            return types;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String write(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize punishment payload", ex); }
    }

    /* ---- 严格请求体解析：未知字段一律拒绝 ---- */

    private JsonNode strict(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) throw bad("VALIDATION_ERROR", "请求体不能为空");
        final JsonNode node;
        try (JsonParser parser = json.getFactory().createParser(raw)) {
            node = json.readTree(parser);
            if (parser.nextToken() != null) throw bad("VALIDATION_ERROR", "请求体不是单个 JSON 对象");
        } catch (ApiException ex) { throw ex;
        } catch (Exception ex) { throw bad("VALIDATION_ERROR", "请求体不是合法 JSON"); }
        if (node == null || !node.isObject()) throw bad("VALIDATION_ERROR", "请求体不是 JSON 对象");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw bad("UNKNOWN_FIELD", "请求体含未知字段：" + name);
        });
        return node;
    }

    private long version(JsonNode node) {
        JsonNode value = node.get("expected_version");
        if (value == null || !value.canConvertToLong() || value.asLong() < 0)
            throw bad("VALIDATION_ERROR", "expected_version 必填且不能为负");
        return value.asLong();
    }

    private long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) throw bad("VALIDATION_ERROR", field + " 必填且必须是整数");
        return value.asLong();
    }

    private String text(JsonNode node, String field, boolean required, int max) {
        JsonNode value = node.get(field);
        String raw = value == null || value.isNull() ? null : value.asText();
        if (raw != null) raw = raw.trim();
        if (raw != null && raw.isEmpty()) raw = null;
        if (required && raw == null) throw bad("VALIDATION_ERROR", field + " 必填");
        if (raw != null && raw.length() > max) throw bad("VALIDATION_ERROR", field + " 最长 " + max + " 个字符");
        return raw;
    }

    private static String dayKey(OffsetDateTime at) {
        return String.format("%04d%02d%02d", at.getYear(), at.getMonthValue(), at.getDayOfMonth());
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    private static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "该案件已被其他操作更新");
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
    }
}
