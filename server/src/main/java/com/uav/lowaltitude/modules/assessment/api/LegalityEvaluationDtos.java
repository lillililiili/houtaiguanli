package com.uav.lowaltitude.modules.assessment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 阶段 7 引擎研判 / 复核 / 规则效果的传输对象。全局 Jackson 为 snake_case 且忽略 null：
 * 可省略字段（目标/告警引用、评分等级）在无权限或未知时不出现，不用 0 或空串顶替。
 */
public final class LegalityEvaluationDtos {
    private LegalityEvaluationDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }

    /** 复核头行快照；SHADOW 研判没有复核行时整个 review 为 null。 */
    public record ReviewDto(String state, String manualStatus, long version) { }

    /** 单条规则命中明细（与 RuleContracts.HitDetail 同构，facts 只含安全字段）。 */
    public record HitDetailDto(String ruleCode, String ruleVersionId, String resultCode, String reasonCode, BigDecimal severity,
            Map<String, Object> facts, List<ParamRefDto> params, List<EvidenceRefDto> evidence, String message) { }

    public record ParamRefDto(String key, String value, String status) { }

    public record EvidenceRefDto(String kind, String id) { }

    /**
     * 研判列表项/详情。列表不带 hit_details（体积），详情带；alarm_id/event_id 只在具备 alarm:read 且告警仍在同一有效元组时返回，
     * target_id/target_no 同理受 target:read 约束，plan_id/plan_no 受 flight:read 约束。
     */
    public record EvaluationDto(String evaluationId, String runId, String ruleSetCode, String ruleSetVersionId, Integer ruleSetVersionNo,
            String paramStatus, String mode, String triggerKind, String subjectKind, String targetId, String targetNo, String trackId,
            String planId, String planNo, String routeVersionId, Long observedAt, long asOf, long evaluatedAt, String freshnessCode,
            String planMatchCode, String legalStatus, BigDecimal score, String grade, List<String> violationReasons,
            List<String> unknownReasons, List<EvidenceRefDto> evidenceReferences, List<HitDetailDto> hitDetails,
            ReviewDto review, List<String> allowedActions, String supersedesEvaluationId, String supersededByEvaluationId,
            String alarmId, String eventId, String alarmOutcomeKind, String assessmentId, String ownerOrgId, String ownerOrgName,
            String districtId, String districtName, String sourceMode) { }

    /** 复核历史项；actor_id 只提供操作归属 ID，actor_name 仅用于展示。 */
    public record RevisionDto(String historyId, long version, String previousState, String resultingState, String conclusion,
            String statusBefore, String statusAfter, String note, String actorId, String actorName, String relatedEvaluationId,
            String relatedAlarmId, long createdAt) { }

    /** POST /legality-evaluations 的成功响应。 */
    public record EvaluateResultDto(String runId, EvaluationDto evaluation) { }

    /** POST …/{id}/alarms 的成功响应。 */
    public record EscalationResultDto(String alarmId, String eventId, EvaluationDto evaluation) { }

    /** 规则效果事实行（v_rule_effect_fact 投影，引用 ID 同样按权限脱敏）。 */
    public record RuleEffectFactDto(String evaluationId, long evaluatedAt, String mode, String subjectKind, String targetId, String planId,
            String ruleSetVersionId, String paramStatus, String legalStatus, String manualStatus, String reviewState, boolean hasAlarm,
            String mergeKind, String alarmId, String groupId, String supersedesEvaluationId, String sourceMode, String ownerOrgId,
            String districtId) { }

    /** 比率：分母为 0 时 value 为 null 且 availability=NO_DENOMINATOR，不用 0 冒充“没有误报”。 */
    public record RatioDto(@JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal value, String availability) {
        public static final String AVAILABLE = "AVAILABLE";
        public static final String NO_DENOMINATOR = "NO_DENOMINATOR";
    }

    /** mode 回显汇总口径（默认 ACTIVE）：evaluations 与全部比率只在该模式内计数。 */
    public record RuleEffectSummaryDto(long from, long to, String timezone, String mode, long evaluations, long alarmWorthy, long alarmsCreated,
            long alarmsMerged, RatioDto convergenceRatio, long reviewed, RatioDto falsePositiveRate, RatioDto missRate,
            RatioDto manualOverrideRate) { }
}
