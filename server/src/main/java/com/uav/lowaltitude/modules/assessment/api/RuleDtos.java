package com.uav.lowaltitude.modules.assessment.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 规则集管理与运行记录的传输形状：snake_case、字符串 ID、epoch 毫秒；参数值随版本明细返回（需要 rule:read）。 */
public final class RuleDtos {
    private RuleDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }

    public record RuleSetDto(String ruleSetId, String ruleSetCode, String name, String activeVersionId, String shadowVersionId,
            String previousActiveVersionId, long version, long createdAt, long updatedAt) { }

    public record RuleSetVersionDto(String ruleSetVersionId, String ruleSetId, String ruleSetCode, int versionNo, String statusCode, String paramStatus,
            long validFrom, Long validTo, String description, String sourceMode, long createdAt, Long publishedAt,
            @JsonProperty("is_active") boolean active, @JsonProperty("is_shadow") boolean shadow) { }

    public record MemberDto(String ruleCode, String ruleVersionId, int priority, boolean enabled) { }

    public record ParamDto(String ruleCode, String key, String value, String type, String unit, String status, String note) { }

    public record RuleSetVersionDetailDto(String ruleSetVersionId, String ruleSetId, String ruleSetCode, int versionNo, String statusCode, String paramStatus,
            long validFrom, Long validTo, String description, String sourceMode, long createdAt, Long publishedAt,
            @JsonProperty("is_active") boolean active, @JsonProperty("is_shadow") boolean shadow, List<MemberDto> members, List<ParamDto> params) { }

    public record ActivationDto(String activationId, String ruleSetId, String kind, String fromVersionId, String toVersionId, String actorId, String note,
            long resultingVersion, long createdAt) { }

    public record RuleRunDto(String runId, String ruleSetId, String ruleSetCode, String ruleSetVersionId, String mode, String triggerKind, String replayDatasetCode,
            String triggeredBy, long asOf, long startedAt, Long finishedAt, String status, int subjectCount, int evaluatedCount, int alarmCreatedCount,
            int alarmMergedCount, String errorSummary, String sourceMode, long createdAt) { }

    /** 写请求经严格白名单解析后的内部形状；版本 ID 在 shadow 清除时为 null。 */
    public record VersionChangeRequest(String ruleSetVersionId, String note, long expectedVersion) { }
}
