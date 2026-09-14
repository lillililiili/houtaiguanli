package com.uav.lowaltitude.modules.punishment.application;

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
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.CaseDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.CaseEventDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.DecisionDocumentDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.DiscretionDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.FactorDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.LeadDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.PageDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.PenaltyRuleDto;
import com.uav.lowaltitude.modules.punishment.domain.PunishmentRules;
import com.uav.lowaltitude.modules.punishment.domain.PunishmentRules.CaseFacts;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.CaseRow;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.DiscretionRow;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.DocumentRow;
import com.uav.lowaltitude.modules.punishment.infrastructure.PunishmentRepository.LeadRow;
import com.uav.lowaltitude.platform.api.ApiException;

/** 处罚案件读侧。allowed_actions 在这里派生，前端不自己推。 */
@Service
public class PunishmentReadService {
    private static final int MAX_SIZE = 100;

    private final AccessControlService access;
    private final PunishmentRepository repository;
    private final ObjectMapper json;

    public PunishmentReadService(AccessControlService access, PunishmentRepository repository, ObjectMapper json) {
        this.access = access; this.repository = repository; this.json = json;
    }

    @Transactional(readOnly = true)
    public PageDto<CaseDto> list(String status, String eventId, String handoffId, Integer page, Integer size) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_READ);
        int p = page == null ? 1 : page, s = size == null ? 20 : size;
        if (p < 1 || s < 1 || s > MAX_SIZE) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页参数无效");
        long total = repository.count(decision, status, eventId, handoffId);
        Set<String> permissions = permissions();
        List<CaseDto> items = new ArrayList<>();
        for (CaseRow row : repository.list(decision, status, eventId, handoffId, (p - 1) * s, s)) {
            items.add(dto(row, permissions));
        }
        return new PageDto<>(items, p, s, total);
    }

    @Transactional(readOnly = true)
    public CaseDto detail(String caseId) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_READ);
        CaseRow row = repository.find(caseId, decision);
        if (row == null) throw notFound();
        return dto(row, permissions());
    }

    @Transactional(readOnly = true)
    public List<CaseEventDto> events(String caseId) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_READ);
        // 先确认案件可见：否则拿 ID 就能探到别的辖区办过什么案子。
        if (repository.find(caseId, decision) == null) throw notFound();
        return repository.caseEvents(caseId).stream()
                .map(e -> new CaseEventDto(e.eventId(), e.eventKind(), e.actorId(), e.actorName(), e.note(),
                        map(e.snapshot()), e.occurredAt().toInstant().toEpochMilli()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PenaltyRuleDto> rules() {
        access.require(PermissionCode.PUNISHMENT_READ);
        return repository.rules().stream()
                .map(r -> new PenaltyRuleDto(r.ruleCode(), r.violationCode(), r.title(), r.legalBasis(),
                        r.fineMin(), r.fineMax(), r.fineReference(), penaltyTypes(r.penaltyTypes()),
                        r.schemaStatus()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DecisionDocumentDto> documents(String caseId) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_READ);
        if (repository.find(caseId, decision) == null) throw notFound();
        return repository.documents(caseId).stream().map(this::documentDto).toList();
    }

    @Transactional(readOnly = true)
    public DecisionDocumentDto document(String documentId) {
        AccessDecision decision = access.require(PermissionCode.PUNISHMENT_READ);
        DocumentRow row = repository.document(documentId);
        if (row == null || repository.find(row.caseId(), decision) == null) throw notFound();
        return documentDto(row);
    }

    /**
     * 文书正文：按 fields 重新渲染而不是另存一份文本。
     * 存两份就会有两份不一致的风险；渲染是确定性的，`rendered_sha256` 正好用来验证这一点。
     */
    @Transactional(readOnly = true)
    public RenderedDocument content(String documentId) {
        DecisionDocumentDto dto = document(documentId);
        String text = com.uav.lowaltitude.modules.punishment.domain.DecisionDocumentRenderer.render(dto.fields());
        return new RenderedDocument(dto.documentNo(), text,
                com.uav.lowaltitude.modules.punishment.domain.DecisionDocumentRenderer.sha256(text));
    }

    public record RenderedDocument(String documentNo, String text, String sha256) { }

    private CaseDto dto(CaseRow row, Set<String> permissions) {
        List<LeadRow> openLeads = repository.leads(row.caseId(), Boolean.FALSE);
        DiscretionRow current = repository.currentDiscretion(row.caseId());
        int issued = repository.issuedDocumentCount(row.caseId());
        CaseFacts facts = new CaseFacts(openLeads.size(),
                current != null && PunishmentRules.DRAFT.equals(current.status()),
                current != null && PunishmentRules.CONFIRMED.equals(current.status()), issued, row.officerId());
        return new CaseDto(row.caseId(), row.caseNo(), row.eventId(), row.handoffId(), row.status(), row.partyType(),
                row.partyName(), row.officerId(), row.officerName(), row.primaryViolationCode(), row.filedBy(),
                row.filedByName(), millis(row.filedAt()), millis(row.decidedAt()), millis(row.closedAt()),
                row.closeNote(), row.withdrawReason(), row.ownerOrgId(), row.districtId(), row.sourceMode(),
                row.version(), current == null ? null : discretionDto(current), issued,
                openLeads.stream().map(PunishmentReadService::leadDto).toList(),
                List.copyOf(PunishmentRules.allowedActions(row.status(), permissions,
                        com.uav.lowaltitude.platform.security.AuthContext.require().userId(), facts)));
    }

    private DiscretionDto discretionDto(DiscretionRow row) {
        return new DiscretionDto(row.discretionId(), row.versionNo(), row.status(), row.violationCode(),
                row.ruleCode(), row.penaltyType(), row.fineAmount(), factors(row.factorsJson()), row.basisText(),
                row.draftedBy(), row.draftedAt().toInstant().toEpochMilli(), row.decidedBy(), millis(row.decidedAt()));
    }

    private static LeadDto leadDto(LeadRow row) {
        return new LeadDto(row.leadId(), row.kind(), row.description(), row.resolved(), row.resolvedNote(),
                row.createdAt().toInstant().toEpochMilli(), millis(row.resolvedAt()));
    }

    private DecisionDocumentDto documentDto(DocumentRow row) {
        return new DecisionDocumentDto(row.documentId(), row.documentNo(), row.caseId(), row.discretionId(),
                row.templateVersion(), row.status(), map(row.fieldsJson()), row.renderedSha256(), row.issuedBy(),
                row.issuedByName(), row.issuedAt().toInstant().toEpochMilli(), millis(row.revokedAt()),
                row.revokeReason(), row.version());
    }

    /** penalty_types 存的是 JSON 数组文本（决策 14-24）。 */
    private List<String> penaltyTypes(String raw) {
        JsonNode node = tree(raw);
        if (node == null || !node.isArray()) return List.of();
        List<String> types = new ArrayList<>();
        for (JsonNode item : node) types.add(item.asText());
        return types;
    }

    private List<FactorDto> factors(String raw) {
        JsonNode node = tree(raw);
        if (node == null || !node.isArray()) return null;
        List<FactorDto> factors = new ArrayList<>();
        for (JsonNode item : node) {
            factors.add(new FactorDto(item.path("code").asText(null), item.path("text").asText(null)));
        }
        return factors;
    }

    private Map<String, Object> map(String raw) {
        JsonNode node = tree(raw);
        if (node == null || !node.isObject()) return null;
        return json.convertValue(node, new TypeReference<>() { });
    }

    /** H2 把 CAST(? AS JSON) 的字符串包成 JSON 文本，PostgreSQL 直接存对象；两种形态都要能读回。 */
    private JsonNode tree(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = json.readTree(raw);
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            return node;
        } catch (Exception ex) {
            // 快照/字段读不出来不该让整条记录打不开：记录本身仍是有效事实。
            return null;
        }
    }

    /** 当前登录者持有的处罚相关权限；allowed_actions 据此裁剪。 */
    private Set<String> permissions() {
        Set<String> held = new LinkedHashSet<>();
        for (PermissionCode code : List.of(PermissionCode.PUNISHMENT_READ, PermissionCode.PUNISHMENT_FILE,
                PermissionCode.PUNISHMENT_DECIDE, PermissionCode.PUNISHMENT_REVIEW, PermissionCode.PUNISHMENT_CLOSE)) {
            try { access.require(code); held.add(code.value()); }
            catch (ApiException denied) { /* 没有这项就不加，不是错误 */ }
        }
        return held;
    }

    private static Long millis(OffsetDateTime at) { return at == null ? null : at.toInstant().toEpochMilli(); }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "对象不存在或不可见");
    }
}
