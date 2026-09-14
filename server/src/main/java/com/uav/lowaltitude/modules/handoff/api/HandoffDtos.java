package com.uav.lowaltitude.modules.handoff.api;

import java.util.List;

public final class HandoffDtos {
    private HandoffDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }
    public record RecipientDto(String recipientId, String displayName, String handoffType) { }
    public record RecipientListDto(List<RecipientDto> items) { }
    public record CreateRequest(String sourceKind, String sourceId, String handoffType, String recipientId, long expectedVersion) { }
    /** POST 成功体固定为契约列出的字段；提交成功只代表材料入库，delivery_status 只可能是 PENDING_DELIVERY。 */
    public record CreatedDto(String handoffId, String sourceKind, String sourceId, String handoffType, String recipientId,
            long sourceVersion, String deliveryStatus, String receiptStatus, String receiptResult, String blockedReason,
            long createdAt) { }
    public record HandoffDto(String handoffId, String sourceKind, String sourceId, String handoffType, String recipientId,
            String recipientName, long sourceVersion, String ownerOrgId, String districtId, String sourceMode, String submittedBy,
            long createdAt, String deliveryStatus, String receiptStatus, String receiptResult, String blockedReason,
            String ownerOrgName, String districtName, String submittedByName, String sourceNo) { }
    public record DeliveryDto(String deliveryId, String handoffId, int attemptNo, String deliveryStatus, String receiptStatus,
            String blockedReason, long createdAt, Long submittedAt, Long deliveredAt, Long acknowledgedAt) { }
    public record HandoffDetailDto(String handoffId, String sourceKind, String sourceId, String handoffType, String recipientId,
            String recipientName, long sourceVersion, String ownerOrgId, String districtId, String sourceMode, String submittedBy,
            long createdAt, String deliveryStatus, String receiptStatus, String receiptResult, String blockedReason,
            // material 有两种形状：v1 是风险材料（MaterialDto），v2 是事件材料（MaterialV2Dto），按 schema_version 分派。
            // 用 Object 而不是共同父类型，是因为两者字段完全不同、也不该互相迁就；序列化按实际类型走。
            Object material,
            DeliveryDto latestDelivery, AvailabilityDto availability,
            String ownerOrgName, String districtName, String submittedByName, String sourceNo) { }
    /**
     * material：AVAILABLE / FORBIDDEN（读者缺来源读权限）/ SOURCE_NOT_VISIBLE（源对象已不在读者可见范围）。
     * evidence（仅 v2）：AVAILABLE / FORBIDDEN（读者缺 evidence:read）/ OMITTED_AT_SUBMISSION（提交人当时就没有该权限）。
     * 两者分开是因为原因不同、补救也不同：前者是读的人权限不够，后者是这份材料**当初就没冻结证据**，
     * 补权限也变不出来——只能重新提交一份交接。
     */
    public record AvailabilityDto(String material, String evidence) { }

    /* 材料快照 schema_version=1：只冻结白名单结构化字段。没有文件就没有文件名、哈希或下载链接字段。 */
    public record MaterialDto(int schemaVersion, RiskMaterialDto risk, List<VerificationMaterialDto> verifications,
            ReferenceMaterialDto references) { }
    public record RiskMaterialDto(String riskId, String sourceRiskId, String riskType, String severity, String state,
            String reasonCode, String reasonText, Long occurredAt, long receivedAt, long version) { }
    public record VerificationMaterialDto(String conclusion, String note, String resultingState, long version, long createdAt,
            String actorId) { }
    /* 材料快照 schema_version=2：处罚交接的事件形状（决策 14-1…14-4）。
       快照是"提交那一刻的事实"，之后不随源变——所以这里的每一段都是值，不是引用。 */
    public record MaterialV2Dto(int schemaVersion, EventMaterialDto event, List<EventVerificationDto> verifications,
            List<DisposalMaterialDto> disposals, List<EvidenceMaterialDto> evidence, Boolean evidenceOmitted,
            ReferenceMaterialDto references) { }
    public record EventMaterialDto(String eventId, String alarmId, String sourceAlarmId, String alarmType, String severity,
            Long occurredAt, Long receivedAt, String state, String targetId, String ownerOrgId, String districtId,
            String sourceMode, long version) { }
    public record EventVerificationDto(String conclusion, String note, String resultingState, long version, long createdAt,
            String actorId, String actorName) { }
    /** 该事件的全部终态授权；COMPLETED 至少一条由提交前提保证（决策 14-2）。 */
    public record DisposalMaterialDto(String authorizationId, String authorizationNo, String actionType, String channel,
            String deviceId, String status, String requestedByName, String approvedByName, Long validFrom, Long validUntil,
            String resultCode, String resultDetail, Long completedAt) { }
    public record EvidenceMaterialDto(String evidenceId, String evidenceNo, String kindCode, String sha256,
            Long capturedAt, String status) { }

    /** 只记录提交当时操作者可见的关联引用；读取时再按当前权限裁剪，不可见的字段直接省略。 */
    public record ReferenceMaterialDto(String planId, String routeVersionId, String assessmentId, String targetId, String trackId) { }
}
