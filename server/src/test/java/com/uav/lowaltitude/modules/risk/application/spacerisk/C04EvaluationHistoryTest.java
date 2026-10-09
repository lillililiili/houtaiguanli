package com.uav.lowaltitude.modules.risk.application.spacerisk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.risk.application.spacerisk.C04EvaluationHistory.Evaluation;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04EvaluationHistory.Step;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.EvaluationSegmentRow;

/** P03：评估历史的分段规则——事实没变且不满 5 分钟并进同一段，有一项变了或满 5 分钟另起一段，同一份观测不重复计。 */
class C04EvaluationHistoryTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 8, 2, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void distanceBandsAreFiftyMetresWideAndUnknownDistanceHasNoBand() {
        assertThat(C04EvaluationHistory.band(null)).isNull();
        assertThat(C04EvaluationHistory.band(new BigDecimal("0.00"))).isZero();
        assertThat(C04EvaluationHistory.band(new BigDecimal("49.99"))).isZero();
        assertThat(C04EvaluationHistory.band(new BigDecimal("50.00"))).isEqualTo(50);
        assertThat(C04EvaluationHistory.band(new BigDecimal("149.50"))).isEqualTo(100);
        assertThat(C04EvaluationHistory.band(new BigDecimal("812.30"))).isEqualTo(800);
        assertThat(C04EvaluationHistory.band(new BigDecimal("-3.00"))).as("距离不会是负数；万一是，按 0 档记").isZero();
    }

    @Test
    void theFirstEvaluationOfARiskStartsItsHistory() {
        assertThat(C04EvaluationHistory.next(null, evaluation("120.00", "NEAR", true, "MEDIUM", 0, 0))).isEqualTo(Step.START);
    }

    @Test
    void unchangedFactsWithinFiveMinutesJoinTheSegmentAndTheFifthMinuteStartsANewOne() {
        EvaluationSegmentRow last = segment("120.00", "NEAR", true, "MEDIUM", 0, 0);
        assertThat(C04EvaluationHistory.next(last, evaluation("149.99", "NEAR", true, "MEDIUM", 60, 60))).isEqualTo(Step.EXTEND);
        assertThat(C04EvaluationHistory.next(last, evaluation("120.00", "NEAR", true, "MEDIUM", 299, 299))).isEqualTo(Step.EXTEND);
        assertThat(C04EvaluationHistory.next(last, evaluation("120.00", "NEAR", true, "MEDIUM", 300, 300)))
                .as("一直没变时每 5 分钟另起一段").isEqualTo(Step.START);
    }

    @Test
    void anyChangedFactStartsANewSegment() {
        EvaluationSegmentRow last = segment("120.00", "NEAR", true, "MEDIUM", 0, 0);
        assertThat(C04EvaluationHistory.next(last, evaluation("150.00", "NEAR", true, "MEDIUM", 60, 60))).as("距离换档").isEqualTo(Step.START);
        assertThat(C04EvaluationHistory.next(last, evaluation("99.00", "NEAR", true, "MEDIUM", 60, 60))).as("距离换档").isEqualTo(Step.START);
        assertThat(C04EvaluationHistory.next(last, evaluation("120.00", "INSIDE", true, "MEDIUM", 60, 60))).as("走廊关系").isEqualTo(Step.START);
        assertThat(C04EvaluationHistory.next(last, evaluation("120.00", "NEAR", true, "HIGH", 60, 60))).as("等级").isEqualTo(Step.START);
        assertThat(C04EvaluationHistory.next(last, evaluation("120.00", "OUTSIDE", false, null, 60, 60))).as("不再构成风险").isEqualTo(Step.START);
        assertThat(C04EvaluationHistory.next(last, new Evaluation(new BigDecimal("120.00"), "NEAR", "APPROACH", true, "MEDIUM",
                "space-risk-demo-v1", T0.plusSeconds(60), T0.plusSeconds(60)))).as("高度档").isEqualTo(Step.START);
        assertThat(C04EvaluationHistory.next(last, new Evaluation(new BigDecimal("120.00"), "NEAR", "CLIMB", true, "MEDIUM",
                "space-risk-demo-v2", T0.plusSeconds(60), T0.plusSeconds(60)))).as("规则集换了版本").isEqualTo(Step.START);
        assertThat(C04EvaluationHistory.next(last, evaluation(null, "UNKNOWN", false, null, 60, 60))).as("没有距离").isEqualTo(Step.START);
    }

    @Test
    void theSameObservationIsNotCountedTwice() {
        EvaluationSegmentRow last = segment("120.00", "NEAR", true, "MEDIUM", 0, 30);
        // 定时窗口前后回叠 30 秒；设备停报时下一轮读到的还是同一份最新状态。
        assertThat(C04EvaluationHistory.next(last, evaluation("120.00", "NEAR", true, "MEDIUM", 60, 30))).isEqualTo(Step.SKIP);
        assertThat(C04EvaluationHistory.next(last, evaluation("400.00", "OUTSIDE", false, null, 60, 10))).as("更早的观测").isEqualTo(Step.SKIP);
        assertThat(C04EvaluationHistory.next(last, new Evaluation(new BigDecimal("120.00"), "NEAR", "CLIMB", true, "MEDIUM",
                "space-risk-demo-v1", T0.plusSeconds(60), null))).as("没有观测时刻时照常计").isEqualTo(Step.EXTEND);
    }

    private static Evaluation evaluation(String distance, String relation, boolean risk, String severity, int evaluatedAfter, int observedAfter) {
        return new Evaluation(distance == null ? null : new BigDecimal(distance), relation, "CLIMB", risk, severity, "space-risk-demo-v1",
                T0.plusSeconds(evaluatedAfter), T0.plusSeconds(observedAfter));
    }

    private static EvaluationSegmentRow segment(String distance, String relation, boolean risk, String severity, int evaluatedAfter, int observedAfter) {
        BigDecimal d = new BigDecimal(distance);
        return new EvaluationSegmentRow("segment", "risk", 1, T0.plusSeconds(evaluatedAfter), T0.plusSeconds(evaluatedAfter), 1,
                T0.plusSeconds(observedAfter), T0.plusSeconds(observedAfter), C04EvaluationHistory.band(d), d, d, relation, "CLIMB",
                risk, severity, "space-risk-demo-v1", true);
    }
}
