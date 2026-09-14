package com.uav.lowaltitude.modules.risk.api;

import java.math.BigDecimal;
import java.util.List;

public final class RiskDtos {

    /** 区域筛选项（决策 15-22）：只含调用者范围内出现过的区域。 */
    public record DistrictOptionDto(String districtId, String name) { }

    private RiskDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }
    public record RiskDto(String riskId, String sourceRiskId, String planId, String routeVersionId,
            String assessmentId, String targetId, String trackId, String riskType, String severity, String state,
            String reasonCode, String reasonText, Long occurredAt, long receivedAt, BigDecimal observedAltitudeM,
            String observedAltitudeDatum, String heightRelation, String sourceCode, String sourceMode,
            String ownerOrgId, String districtId, long version, List<String> allowedActions,
            String sourceName, String ownerOrgName, String districtName, String planNo, String targetNo,
            /* 阶段 9 追加：空中异物风险的判定依据；其它风险类型没有这一段，字段整体缺省。 */
            SpaceRiskDtos.SpaceFactDto spaceFact,
            /* 页面上的风险编号：平台编号优先，没有就用来源编号（F11）。 */
            String riskNo) { }
    public record VerificationDto(String historyId, long version, String previousState, String resultingState,
            String conclusion, String note, String actorId, long createdAt, String actorName) { }
    public record VerifyRequest(String conclusion, String note, Long expectedVersion) { }
}
