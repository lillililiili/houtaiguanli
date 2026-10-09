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

    /**
     * P03 评估历史（只有空中异物 C04 风险有）：applicable=false 表示这类风险不记评估历史，页面不显示这一栏。
     * evaluation_count 是各段次数之和；first/last_evaluated_at 是最早、最近一次评估的时刻，没有记录时省略。
     * from_detection=false 表示发现这条风险的那次评估没有记录（改动以前产生的风险），前面的评估不在这里。
     * items 按时间先后分页，total 是段数。
     */
    public record EvaluationHistoryDto(boolean applicable, long evaluationCount, Long firstEvaluatedAt, Long lastEvaluatedAt,
            boolean fromDetection, List<EvaluationSegmentDto> items, int page, int size, long total) { }

    /**
     * 一段评估：这段时间里每次评估的事实都一样。distance_band_m 是距离档下沿（按 50 米一档），min/max_distance_m 是这段里测到的
     * 最近、最远距离；没有距离时三项都省略。risk_present=false 表示当时不构成风险，此时没有 severity。
     */
    public record EvaluationSegmentDto(int segmentNo, long firstEvaluatedAt, long lastEvaluatedAt, int evaluationCount,
            Integer distanceBandM, BigDecimal minDistanceM, BigDecimal maxDistanceM, String corridorRelation, String altitudeBand,
            boolean riskPresent, String severity) { }

    public record EvaluationRequest(String ruleCode, Long windowFrom, Long windowTo) { }
}
