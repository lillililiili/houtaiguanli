package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.EvaluationSegmentRow;

/**
 * P03（2026-10-08 用户确认）：空中异物（C04）风险的评估历史怎么分段。纯 Java，不读库。
 * 风险发现后每分钟一轮的评估都记，但相邻几次的事实没变就并成一段（次数加一、最近时间后移），记录不会太多，次数和时间又都准：
 * 离航线中心线的距离按 50 米一档、走廊关系、高度档、是否构成风险、等级、规则集版本有一项变了，另起一段；
 * 一直没变时一段最长 5 分钟，满了也另起一段。同一份观测（观测时刻没有往后走）不算新的一次评估。
 * 50 米和 5 分钟是记录粒度，不是判定阈值：判不判风险、什么等级仍只看 {@link C04DecisionTable}，评估历史也不改风险本身的等级。
 */
public final class C04EvaluationHistory {
    public static final int DISTANCE_STEP_M = 50;
    public static final Duration SEGMENT_SPAN = Duration.ofMinutes(5);

    private C04EvaluationHistory() { }

    public enum Step { SKIP, EXTEND, START }

    /** 一次评估的事实；不构成风险时 severity 为 null。distanceM 为 null 表示这次没有距离（走廊关系 UNKNOWN）。 */
    public record Evaluation(BigDecimal distanceM, String corridorRelation, String altitudeBand, boolean riskPresent, String severity,
            String ruleSetVersionId, OffsetDateTime evaluatedAt, OffsetDateTime observedAt) {
        public Integer distanceBandM() { return band(distanceM); }
    }

    /** 距离档的下沿（米）：0–49.99 米为 0，50–99.99 米为 50，依此类推；没有距离返回 null。 */
    public static Integer band(BigDecimal distanceM) {
        if (distanceM == null) return null;
        BigDecimal step = BigDecimal.valueOf(DISTANCE_STEP_M);
        return distanceM.max(BigDecimal.ZERO).divide(step, 0, RoundingMode.FLOOR).multiply(step).intValue();
    }

    /** 这次评估相对风险最近一段该怎么记：没有段就开新段；观测没有更新就不记；事实没变且这段不满 5 分钟就并进去，否则开新段。 */
    public static Step next(EvaluationSegmentRow last, Evaluation evaluation) {
        if (last == null) return Step.START;
        if (evaluation.observedAt() != null && last.lastObservedAt() != null && !evaluation.observedAt().isAfter(last.lastObservedAt())) {
            return Step.SKIP;
        }
        boolean sameFacts = Objects.equals(last.distanceBandM(), evaluation.distanceBandM())
                && Objects.equals(last.corridorRelation(), evaluation.corridorRelation())
                && Objects.equals(last.altitudeBand(), evaluation.altitudeBand())
                && last.riskPresent() == evaluation.riskPresent()
                && Objects.equals(last.severity(), evaluation.severity())
                && Objects.equals(last.ruleSetVersionId(), evaluation.ruleSetVersionId());
        boolean withinSpan = evaluation.evaluatedAt().isBefore(last.firstEvaluatedAt().plus(SEGMENT_SPAN));
        return sameFacts && withinSpan ? Step.EXTEND : Step.START;
    }
}
