package com.uav.lowaltitude.modules.handoff.api;

import java.math.BigDecimal;
import java.util.List;

public final class HandoffDtos {
    private HandoffDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }
    /** 统计与清单同一范围谓词、同一筛选；每个状态码固定出现，没有记录时计数为 0。 */
    public record CountDto(String code, long count) { }
    public record RecipientCountDto(String recipientId, String name, long count, long delivered) { }
    public record DayCountDto(String date, long count, long delivered, long failed) { }
    public record HandoffStatsDto(long total, List<CountDto> byDelivery, List<CountDto> byReceipt,
            List<RecipientCountDto> byRecipient, List<DayCountDto> byDay, String trendFrom, String trendTo) { }
    public record RecipientDto(String recipientId, String displayName, String handoffType) { }
    public record RecipientListDto(List<RecipientDto> items) { }
    public record CreateRequest(String sourceKind, String sourceId, String handoffType, String recipientId, long expectedVersion) { }
    /** POST 成功保证材料入库；投递状态以通道返回事实为准，不代表处罚办结。 */
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
            String ownerOrgName, String districtName, String submittedByName, String sourceNo,
            com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot) { }
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
            String reasonCode, String reasonText, Long occurredAt, long receivedAt, long version, RiskLocationDto location) {
        public RiskMaterialDto(String riskId, String sourceRiskId, String riskType, String severity, String state,
                String reasonCode, String reasonText, Long occurredAt, long receivedAt, long version) {
            this(riskId, sourceRiskId, riskType, severity, state, reasonCode, reasonText, occurredAt, receivedAt, version, null);
        }
    }
    /** 风险观测位置，随通知冻结；不是目标当前位置。缺失时不提供 location，不回填历史材料。 */
    public record RiskLocationDto(BigDecimal longitude, BigDecimal latitude, String coordinateSystem, Long observedAt,
            BigDecimal altitudeM, String altitudeDatum) { }
    public record VerificationMaterialDto(String conclusion, String note, String resultingState, long version, long createdAt,
            String actorId) { }
    /* 材料快照 schema_version=2：处罚交接的事件形状（决策 14-1…14-4）。
       快照是"提交那一刻的事实"，之后不随源变——所以这里的每一段都是值，不是引用。 */
    public record MaterialV2Dto(int schemaVersion, EventMaterialDto event, List<EventVerificationDto> verifications,
            List<DisposalMaterialDto> disposals, List<EvidenceMaterialDto> evidence, Boolean evidenceOmitted,
            ReferenceMaterialDto references, List<com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Record> advisoryRecords,
            PilotLocationMaterialDto pilotLocation,
            // 2026-10-06 起新提交的材料另冻结研判结论、证据链清单和当事人认定；旧材料没有这三段，按原样读出。
            List<JudgmentMaterialDto> judgments, List<EvidenceChainItemDto> evidenceChain, PartyMaterialDto party) { }
    /**
     * 合法性研判结论（提交时冻结）。basis：EVENT_ALARM 是关联本事件告警的那条（告警依据），LATEST 是同一目标提交时的最新一条；
     * 两条相同时只留 EVENT_ALARM。manual_status/review_state 是人工复核结果，没有复核时省略。
     */
    public record JudgmentMaterialDto(String basis, String evaluationId, String legalStatus, String manualStatus, String reviewState,
            String planMatchCode, String planId, String planNo, String grade, java.math.BigDecimal score, String freshnessCode,
            List<String> violationReasons, List<String> unknownReasons, String decisionAssuranceCode, Long observedAt,
            Long evaluatedAt, String ruleSetVersionId) { }
    /**
     * 证据链清单（提交时冻结），与事件页“证据链”同一来源：录像、轨迹、图片、指令四类。
     * 文件带 sha256，可与证据台账逐项比对；轨迹和指令不是文件，没有哈希。
     */
    public record EvidenceChainItemDto(String category, String sourceKind, String sourceId, String evidenceNo, String name,
            String kindCode, String status, Long capturedAt, Long startedAt, Long endedAt, Long pointCount, Long sizeBytes,
            String sha256) { }
    /**
     * 当事人认定（提交时冻结）。IDENTIFIED：关联本事件的报备计划写明了飞手或运营单位；
     * UNIDENTIFIED：当事人不明，按待补线索移送，reasons 写明原因，uav_sn 等是已有线索。
     */
    public record PartyMaterialDto(String status, String label, List<String> reasons, String planId, String planNo,
            String pilotName, String operatorName, String uavSn) { }
    /**
     * 设备测算的遥控器（飞手）大概位置，提交时冻结（2026-10-04 用户确认用于找飞手）。
     * basis 恒为 DEVICE_ESTIMATE：这是 TDOA/AOA/DCD/RID 等设备推算的位置，不是现场核实的位置。没有位置时整段省略。
     */
    public record PilotLocationMaterialDto(java.math.BigDecimal longitude, java.math.BigDecimal latitude, Long observedAt,
            String basis) { }
    public record EventMaterialDto(String eventId, String alarmId, String sourceAlarmId, String alarmType, String severity,
            Long occurredAt, Long receivedAt, String state, String targetId, String ownerOrgId, String districtId,
            String sourceMode, long version) { }
    public record EventVerificationDto(String conclusion, String note, String resultingState, long version, long createdAt,
            String actorId, String actorName) { }
    /** 该事件已经产生的终态授权；未实施反制也可以移送处罚。 */
    public record DisposalMaterialDto(String authorizationId, String authorizationNo, String actionType, String channel,
            String deviceId, String status, String requestedByName, String approvedByName, Long validFrom, Long validUntil,
            String resultCode, String resultDetail, Long completedAt, String authorizationMode) { }
    public record EvidenceMaterialDto(String evidenceId, String evidenceNo, String kindCode, String sha256,
            Long capturedAt, String status) { }

    /** 只记录提交当时操作者可见的关联引用；读取时再按当前权限裁剪，不可见的字段直接省略。 */
    public record ReferenceMaterialDto(String planId, String routeVersionId, String assessmentId, String targetId, String trackId) { }
}
