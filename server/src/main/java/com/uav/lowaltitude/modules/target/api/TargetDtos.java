package com.uav.lowaltitude.modules.target.api;

import java.math.BigDecimal;
import java.util.List;

public final class TargetDtos {

    private TargetDtos() {
    }

    public record PageDto<T>(List<T> items, int page, int size, long total) {
    }

    public record LocationDto(BigDecimal longitude, BigDecimal latitude, String coordinateSystem) {
    }

    public record FieldIssueDto(String field, String reasonCode) {
    }

    public record TargetStateDto(
            long observedAt,
            long receivedAt,
            List<FieldIssueDto> fieldIssues,
            LocationDto location,
            BigDecimal altitudeAmslM,
            BigDecimal heightAglM,
            BigDecimal speedMps,
            BigDecimal headingDeg,
            BigDecimal classificationConfidence,
            BigDecimal fusionConfidence,
            /* 阶段 8.5：飞手（遥控器）位置，只有身份类来源报得出；没有就整个字段不下发。 */
            LocationDto pilotLocation,
            /* 阶段 15（决策 15-5）：最近一条**无位置但有方位**的观测给出的方位角与出方位的设备。
               AOA 只报方位不报位置，页面据此画方位线；设备位置由页面从已加载的设备列表解析，
               后端不在目标接口里再联一次设备表——那会让这个接口为了一条线去背设备域的可见性规则。 */
            BigDecimal bearingDeg,
            String bearingDeviceId) {
    }

    /* 决策 15-4：悬浮卡要的是"现在怎么样"，所以三段各取**最新一条**；没有就整个键省略，
       不给空对象——空对象在页面上会渲染成一行没有内容的标题。 */
    public record RiskSummaryDto(String riskId, String severity, String state, Long occurredAt) { }

    public record LegalitySummaryDto(String evaluationId, String legalStatus, String grade,
            List<String> violationReasons) { }

    public record DisposalSummaryDto(String authorizationId, String authorizationNo, String actionType,
            String status) { }

    public record TargetSummaryDto(
            String targetId,
            String targetNo,
            Long firstSeenAt,
            Long lastSeenAt,
            String objectTypeCode,
            String subtype,
            String uavSn,
            String sourceMode,
            String ownerOrgId,
            String districtId,
            TargetStateDto latestState,
            String ownerOrgName,
            String districtName,
            /* 列表与详情同形（决策 15-4）：同一张悬浮卡在两处都要能画出来。 */
            RiskSummaryDto riskSummary,
            LegalitySummaryDto legalitySummary,
            DisposalSummaryDto disposalSummary) {
    }

    public record TargetDetailDto(
            String targetId,
            String targetNo,
            Long firstSeenAt,
            Long lastSeenAt,
            String objectTypeCode,
            String subtype,
            String uavSn,
            String sourceMode,
            String ownerOrgId,
            String districtId,
            TargetStateDto latestState,
            List<TargetSourceLinkDto> sourceLinks,
            long createdAt,
            long updatedAt,
            String ownerOrgName,
            String districtName,
            /* 阶段 8 追加的可空字段：引擎未接管的目标全部为 null，既有字段与排序不变。 */
            TrackStatusDto trackStatus,
            DegradationDto degradation,
            AttributeSelectionDto attributeSelection,
            LineageSummaryDto lineageSummary,
            List<String> allowedActions,
            /* 目标行版本：修订类别/合并/分裂的 expected_version 依据；阶段 8 验收发现客户端此前无处取得。 */
            Long version,
            /* 阶段 15（决策 15-4）：与列表同形的三段摘要。 */
            RiskSummaryDto riskSummary,
            LegalitySummaryDto legalitySummary,
            DisposalSummaryDto disposalSummary) {
    }

    public record TargetSourceLinkDto(
            String linkId,
            String sourceId,
            String sourceCode,
            String sourceMode,
            String sourceSessionKey,
            String externalTargetId,
            String deviceId,
            String protocolVersion,
            String sourceName,
            /* 阶段 8 追加：来源类型与其字段口径状态（DEMO/CONFIRMED），未登记类型的来源保持 null。 */
            String sourceType,
            String schemaStatus) {
    }

    /** 阶段 8 追加：轨迹状态机快照，可空（引擎未接管的目标没有该行）。 */
    public record TrackStatusDto(String status, long since) {
    }

    /** 阶段 8 追加：降级等级与置信亏损；determined=false 时 target_latest_state.fusion_confidence 为空。 */
    public record DegradationDto(String level, List<String> availableSources, BigDecimal confidenceDeficit, boolean determined) {
    }

    /** 阶段 8 追加：属性优选（对外只给来源编码，不暴露内部来源 ID）。 */
    public record AttributeSelectionDto(String positionSourceCode, String classSourceCode, String identitySourceCode,
            String motionSourceCode, boolean manualClassOverride) {
    }

    /** 阶段 8 追加：血缘摘要；被并目标的 current_target_id 与自身不同。 */
    public record LineageSummaryDto(String currentTargetId, long opCount, String lastOp, Long lastAt) {
    }

    public record TrackSummaryDto(
            String trackId,
            String targetId,
            String linkId,
            String externalTrackId,
            String sourceId,
            String sourceCode,
            String sourceMode,
            String deviceId,
            Long startedAt,
            /* 阶段 8 追加：分层与参数版本；FUSED 层的 link_id/source_id/source_code 为空。 */
            String layer,
            String configVersion,
            Long endedAt) {
    }

    public record TrackPointDto(
            String pointId,
            String trackId,
            long pointSeq,
            long sortTime,
            String timeBasis,
            long receivedAt,
            Long observedAt,
            LocationDto location,
            BigDecimal altitudeAmslM,
            BigDecimal heightAglM,
            /* 阶段 8 追加：融合层点的种类与融合过程事实；原始层点这些字段为空。 */
            String pointKind,
            BigDecimal positionAccuracyM,
            List<ContributionDto> contributing,
            Boolean sourceSwitched,
            String degradationLevel) {
    }

    /** 阶段 8 追加：参与本点位置融合的来源与权重（只给来源编码）。 */
    public record ContributionDto(String sourceCode, BigDecimal weight) {
    }
}
