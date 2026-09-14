package com.uav.lowaltitude.modules.punishment.api;

import java.util.List;
import java.util.Map;

/**
 * 处罚案件接口的请求与响应形状。全局 SNAKE_CASE + non_null 序列化，null 字段不出现在响应里。
 * 不含证件号、电话、住址等敏感字段（决策 14-12）：当事人只有类型与名称。
 */
public final class PunishmentDtos {

    private PunishmentDtos() { }

    /* ---- 请求 ---- */

    public record FileCaseRequest(String handoffId, String partyType, String partyName, String note) { }
    public record AssignRequest(String officerId, Long expectedVersion) { }
    public record LeadRequest(String kind, String description, Long expectedVersion) { }
    public record ResolveLeadRequest(String note, Long expectedVersion) { }
    public record DiscretionRequest(String ruleCode, String penaltyType, Long fineAmount, List<FactorDto> factors,
            String basisText, Long expectedVersion) { }
    public record ReviewRequest(String conclusion, String note, List<MissingLeadDto> missingLeads, Long expectedVersion) { }
    public record RevokeRequest(String reason, Long expectedVersion) { }
    public record CloseRequest(String note, Long expectedVersion) { }
    public record WithdrawRequest(String reason, Long expectedVersion) { }

    /* ---- 响应 ---- */

    public record FactorDto(String code, String text) { }
    public record MissingLeadDto(String kind, String description) { }

    /**
     * 案件详情。`allowed_actions` 由状态、调用者权限与前置事实共同裁剪（契约 v1.1 十项），
     * 前端照着画按钮即可，不必自己判权限——也就不会出现"能点但一点就 403"。
     */
    public record CaseDto(String caseId, String caseNo, String eventId, String handoffId, String status,
            String partyType, String partyName, String officerId, String officerName, String primaryViolationCode,
            String filedBy, String filedByName, Long filedAt, Long decidedAt, Long closedAt, String closeNote,
            String withdrawReason, String ownerOrgId, String districtId, String sourceMode, long version,
            DiscretionDto currentDiscretion, Integer issuedDocumentCount, List<LeadDto> openLeads,
            List<String> allowedActions) { }

    public record CaseEventDto(String eventId, String eventKind, String actorId, String actorName, String note,
            Map<String, Object> snapshot, long occurredAt) { }

    public record LeadDto(String leadId, String kind, String description, boolean resolved, String resolvedNote,
            long createdAt, Long resolvedAt) { }

    public record DiscretionDto(String discretionId, int versionNo, String status, String violationCode,
            String ruleCode, String penaltyType, long fineAmount, List<FactorDto> factors, String basisText,
            String draftedBy, long draftedAt, String decidedBy, Long decidedAt) { }

    /** 罚则档位；`schema_status` 随每条返回，页面据此标"演示档位，待业务确认"（决策 14-8）。 */
    public record PenaltyRuleDto(String ruleCode, String violationCode, String title, String legalBasis,
            long fineMin, long fineMax, Long fineReference, List<String> penaltyTypes, String schemaStatus) { }

    public record DecisionDocumentDto(String documentId, String documentNo, String caseId, String discretionId,
            String templateVersion, String status, Map<String, Object> fields, String renderedSha256,
            String issuedBy, String issuedByName, long issuedAt, Long revokedAt, String revokeReason, long version) { }

    public record ActionResultDto(String caseId, String status, long version) { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }
}
