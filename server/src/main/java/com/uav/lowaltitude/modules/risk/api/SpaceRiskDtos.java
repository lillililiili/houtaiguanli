package com.uav.lowaltitude.modules.risk.api;

import java.math.BigDecimal;
import java.util.List;

/** 阶段 9 空间安全风险 DTO。可空字段一律省略（全局 non_null），不用 0 或空串占位。 */
public final class SpaceRiskDtos {
    private SpaceRiskDtos() { }

    public record SpaceFactDto(String riskId, String subtypeCode, String subtypeName, String ruleVersionId,
            String ruleSetVersionId, int ruleSetVersionNo, BigDecimal distanceToRouteM, String corridorRelation,
            String altitudeBand, String altitudeDatum, Integer objectCount, String trend,
            /* 决策 9-18：本次判定缺了哪些事实（数量/趋势当前没有数据源）。 */
            List<String> unknownReasons,
            /* 决策 9-19：评估时刻的目标位置快照；没有可信坐标时整体缺省，页面不画点。 */
            BigDecimal longitude, BigDecimal latitude, BigDecimal targetAltitudeRaw,
            long windowFrom, long windowTo) { }

    public record SubtypeDto(String subtypeCode, String displayName, List<String> aliases, boolean enabled) { }

    public record BucketDto(String bucket, long count) { }

    public record RuleVersionDto(String ruleSetCode, int versionNo, String paramStatus) { }

    /**
     * 汇总：分母为 0 时 `value` 为 null 并给 availability（与阶段 7/8 同一风格），
     * 避免把"没有数据"显示成"0 起风险"。
     */
    public record MetricDto(Long value, String availability) { }

    public record SummaryDto(List<BucketDto> bySubtype, List<BucketDto> bySeverity, List<BucketDto> byState,
            List<BucketDto> byAltitudeBand, MetricDto total, MetricDto highSeverity, MetricDto mediumSeverity,
            MetricDto birdEvents, MetricDto pendingVerification, MetricDto routesInvolved,
            RuleVersionDto ruleVersion, long asOf) { }

    public record RunDto(String runId, String ruleCode, String triggerKind, long windowFrom, long windowTo,
            String status, int targetsSeen, int risksCreated, int risksDeduplicated, String message,
            String actorId, long startedAt, Long finishedAt) { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }

    public record EvaluationRequest(String ruleCode, Long windowFrom, Long windowTo) { }
}
