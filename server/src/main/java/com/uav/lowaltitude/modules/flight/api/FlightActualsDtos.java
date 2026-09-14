package com.uav.lowaltitude.modules.flight.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 飞行计划"计划与实际对照"的响应形状。每一段自带 availability，缺权限的段只留 availability，
 * 其余字段一律为 null（全局 non_null 序列化会把它们省掉）——不能用 0 或空列表冒充"看过了，没有"。
 */
public final class FlightActualsDtos {
    /** 段可用性：有数据可给 / 无该段读权限 / 该主体尚无引擎研判 / 依赖的数据暂不可用。 */
    public static final String AVAILABLE = "AVAILABLE", FORBIDDEN = "FORBIDDEN",
            NO_EVALUATION = "NO_EVALUATION", UNAVAILABLE = "UNAVAILABLE";

    private FlightActualsDtos() { }

    public record ActualsDto(String planId, String planNo, MatchDto match, AltitudeRelationDto altitudeRelation,
            LatestRisksDto latestRisks, LegalityDto legality) { }

    /** param_status 是产生这条结论的规则集版本的参数状态（DEMO|CONFIRMED）：DEMO 的结论不能当已确认口径用。 */
    /** target_id 是产生这条研判的感知目标：飞行计划页"合法性判定"按钮据此跳到研判页并选中该目标。 */
    public record MatchDto(String availability, String planMatchCode, String evaluationId, Long evaluatedAt,
            String paramStatus, List<HitDetailDto> hitDetailsC01, String targetId) {
        public static MatchDto only(String availability) { return new MatchDto(availability, null, null, null, null, null, null); }
    }

    /** C01 明细：facts 是引擎已写入的安全字段，原样透出，前端不再自行推导匹配结论。 */
    public record HitDetailDto(String ruleCode, String resultCode, String reasonCode, String message,
            Map<String, Object> facts) { }

    /**
     * 高度关系取自同一次研判的 C02-7 明细：ABOVE/WITHIN/BELOW 只在目标高度与计划高度带处于同一基准时给出，
     * 否则 UNDETERMINED 并带上计划侧基准与不可判定原因。AGL 与 AMSL 永不互推。
     */
    public record AltitudeRelationDto(String availability, String relation, String datum,
            BigDecimal minAltitudeM, BigDecimal maxAltitudeM, BigDecimal targetAltitudeM, String unknownReason) {
        public static AltitudeRelationDto only(String availability) {
            return new AltitudeRelationDto(availability, null, null, null, null, null, null);
        }
    }

    public record LatestRisksDto(String availability, List<RiskItemDto> items) {
        public static LatestRisksDto only(String availability) { return new LatestRisksDto(availability, null); }
    }

    public record RiskItemDto(String riskId, String sourceRiskId, String riskType, String severity, String stateCode,
            String reasonCode, String reasonText, Long occurredAt, long receivedAt) { }

    public record LegalityDto(String availability, String legalStatus, String evaluationId, Long evaluatedAt,
            String paramStatus) {
        public static LegalityDto only(String availability) { return new LegalityDto(availability, null, null, null, null); }
    }
}
