package com.uav.lowaltitude.modules.fusion.api;

import java.math.BigDecimal;
import java.util.List;

/** 阶段 8 融合读写接口 DTO。可空字段一律省略（全局 non_null），不用空串或 0 占位。 */
public final class FusionDtos {
    private FusionDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }

    public record ConfigDto(String configVersion, String status, String schemaStatus, Object params, String note,
            Long createdAt, Long activatedAt, long version) { }

    public record ConfigOverviewDto(ConfigDto active, List<ConfigDto> versions) { }

    public record SourceStatusDto(String sourceCode, String sourceType, String schemaStatus, Long lastObservedAt, boolean online) { }

    /** data_interrupted：没有任何在线来源；as_of 取自 AppClock 本次读取。 */
    public record FusionStatusDto(List<SourceStatusDto> availableSources, boolean dataInterrupted, long asOf) { }

    public record LineageDto(String lineageId, String op, long occurredAt, String survivorTargetId, String originTargetId,
            List<String> memberTargetIds, List<String> sourceTargetIds, String algoVersion, String configVersion,
            String operatorKind, String operatorId, String note) { }

    public record ObservationDto(String observationId, String sourceCode, String sourceType, String externalTargetId,
            long observedAt, long receivedAt, BigDecimal longitude, BigDecimal latitude, BigDecimal positionAccuracyM,
            BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps, BigDecimal headingDeg,
            String classCode, BigDecimal classConfidence, String identityClue, String sourceMode,
            /* 阶段 8.5：飞手（遥控器）位置与类别来源（光电跟踪 / 感知数据 / 雷达 / 人工），可空，缺失时不下发。 */
            LocationDto pilotLocation, String classSource,
            /* 阶段 15（决策 15-5）：AOA 方位角、身份置信度、出这条观测的设备，可空。
               class_confidence 与 identity_confidence 分开给：来源面板要能分别说清
               "像不像这一类"和"是不是这一架"，混成一个数会让人以为身份也被确认过。 */
            BigDecimal bearingDeg, BigDecimal identityConfidence, String deviceId) { }

    /** 与目标读侧同形的坐标块：经纬度加坐标系，便于页面直接落图。 */
    public record LocationDto(BigDecimal longitude, BigDecimal latitude, String coordinateSystem) { }

    public record ClassificationRevisionDto(String revisionId, String targetId, String classCode, long version, long updatedAt) { }

    public record MergeResultDto(String lineageId, String survivorTargetId, List<String> mergedTargetIds) { }

    public record SplitResultDto(String lineageId, String originTargetId, List<String> newTargetIds) { }
}
