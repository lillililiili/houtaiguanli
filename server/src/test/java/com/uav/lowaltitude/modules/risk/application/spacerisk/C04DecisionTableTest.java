package com.uav.lowaltitude.modules.risk.application.spacerisk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.AltitudeBand;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.CorridorRelation;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.Decision;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.Observation;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.Trend;

/**
 * C04 决策表是纯 Java：输入走廊关系、高度带、有无活动计划、数量与趋势，输出是否生成风险、等级与原因码。
 * 所有阈值只经 RuleParams 读取（阶段 7 的 rule_param 机制），本测试用固定 Map 桩提供 DEMO 参数值。
 */
class C04DecisionTableTest {
    private final C04DecisionTable table = new C04DecisionTable();

    @Test
    void insideCorridorWithinBandAndActivePlanIsHigh() {
        Decision decision = table.decide(observation(CorridorRelation.INSIDE, AltitudeBand.CLIMB, true, 5, Trend.FLAT), params());
        assertThat(decision.generate()).isTrue();
        assertThat(decision.severity()).isEqualTo("HIGH");
        assertThat(decision.reasonCode()).isEqualTo(C04DecisionTable.REASON_CORRIDOR_INTRUSION);
    }

    @Test
    void insideCorridorWithUnknownAltitudeBandStaysMedium() {
        // 高度基准不可比时不能因“在走廊内”就判高：未知不是“在带内”。
        Decision decision = table.decide(observation(CorridorRelation.INSIDE, AltitudeBand.UNKNOWN, true, 3, Trend.FLAT), params());
        assertThat(decision.generate()).isTrue();
        assertThat(decision.severity()).isEqualTo("MEDIUM");
        assertThat(decision.reasonCode()).isEqualTo(C04DecisionTable.REASON_ALTITUDE_UNKNOWN);
    }

    @Test
    void nearRouteIsMedium() {
        Decision decision = table.decide(observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, 2, Trend.FLAT), params());
        assertThat(decision.generate()).isTrue();
        assertThat(decision.severity()).isEqualTo("MEDIUM");
        assertThat(decision.reasonCode()).isEqualTo(C04DecisionTable.REASON_NEAR_ROUTE);
    }

    @Test
    void withoutActivePlanNoRiskIsGenerated() {
        // 无活动计划就没有“被威胁的飞行活动”，只计入 targets_seen；风险必须挂在计划上（flight_risk.plan_id 非空）。
        for (CorridorRelation relation : CorridorRelation.values()) {
            Decision decision = table.decide(observation(relation, AltitudeBand.CLIMB, false, 50, Trend.RISING), params());
            assertThat(decision.generate()).as(relation.name()).isFalse();
            assertThat(decision.severity()).isNull();
        }
    }

    @Test
    void outsideCorridorGeneratesNothing() {
        Decision decision = table.decide(observation(CorridorRelation.OUTSIDE, AltitudeBand.CRUISE, true, 2, Trend.FLAT), params());
        assertThat(decision.generate()).isFalse();
    }

    @Test
    void flockCountOrRisingTrendEscalatesOneLevelCappedAtCritical() {
        // 数量达到阈值：MEDIUM → HIGH。
        Decision escalatedFromMedium = table.decide(observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, 20, Trend.FLAT), params());
        assertThat(escalatedFromMedium.severity()).isEqualTo("HIGH");
        assertThat(escalatedFromMedium.escalated()).isTrue();
        // 趋势上升同样上调一级。
        assertThat(table.decide(observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, 1, Trend.RISING), params()).severity()).isEqualTo("HIGH");
        // HIGH 上调到 CRITICAL，并且不会越过 CRITICAL。
        Decision capped = table.decide(observation(CorridorRelation.INSIDE, AltitudeBand.APPROACH, true, 200, Trend.RISING), params());
        assertThat(capped.severity()).isEqualTo("CRITICAL");
        // 数量低于阈值且趋势平稳：不上调。
        assertThat(table.decide(observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, 19, Trend.FLAT), params()).severity()).isEqualTo("MEDIUM");
    }

    @Test
    void missingCountOrTrendNeverEscalatesAndIsRecordedAsUnknown() {
        // 决策 9-18：数量与趋势当前没有数据源。"不知道有多少只"不能当成"少于阈值"，
        // 因此既不上调等级，也要如实记下缺了哪项事实。
        Decision decision = table.decide(new Observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, null, Trend.UNKNOWN), params());
        assertThat(decision.generate()).isTrue();
        assertThat(decision.severity()).isEqualTo("MEDIUM");
        assertThat(decision.escalated()).isFalse();
        assertThat(decision.unknownReasons())
                .containsExactly(C04DecisionTable.UNKNOWN_OBJECT_COUNT, C04DecisionTable.UNKNOWN_TREND);
        // 有数量、缺趋势：只记趋势未知，数量仍照常参与上调判断。
        Decision counted = table.decide(new Observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, 20, Trend.UNKNOWN), params());
        assertThat(counted.severity()).isEqualTo("HIGH");
        assertThat(counted.unknownReasons()).containsExactly(C04DecisionTable.UNKNOWN_TREND);
        // 两项都有：没有未知项。
        assertThat(table.decide(new Observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, 3, Trend.FLAT), params()).unknownReasons()).isEmpty();
        // 不生成风险时同样给出未知项：页面要能解释"为什么这次没结论"。
        assertThat(table.decide(new Observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, false, null, Trend.UNKNOWN), params()).unknownReasons())
                .containsExactly(C04DecisionTable.UNKNOWN_OBJECT_COUNT, C04DecisionTable.UNKNOWN_TREND);
    }

    @Test
    void bandIsUnknownWhenDatumsDiffer() {
        // AGL 与 AMSL 不互比：基准不同或缺失时只能给 UNKNOWN，不做换算。
        assertThat(table.band(new BigDecimal("120"), "AGL", "AMSL", params())).isEqualTo(AltitudeBand.UNKNOWN);
        assertThat(table.band(new BigDecimal("120"), null, "AGL", params())).isEqualTo(AltitudeBand.UNKNOWN);
        assertThat(table.band(null, "AGL", "AGL", params())).isEqualTo(AltitudeBand.UNKNOWN);
        // 同基准才按参数分带：< 150 爬升段，< 300 进近段，其上巡航段。
        assertThat(table.band(new BigDecimal("100"), "AGL", "AGL", params())).isEqualTo(AltitudeBand.CLIMB);
        assertThat(table.band(new BigDecimal("250"), "AGL", "AGL", params())).isEqualTo(AltitudeBand.APPROACH);
        assertThat(table.band(new BigDecimal("500"), "AGL", "AGL", params())).isEqualTo(AltitudeBand.CRUISE);
    }

    @Test
    void missingParameterIsADeploymentErrorNotASilentDefault() {
        StubParams empty = new StubParams(Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> table.decide(observation(CorridorRelation.NEAR, AltitudeBand.CRUISE, true, 2, Trend.FLAT), empty));
    }

    private static Observation observation(CorridorRelation relation, AltitudeBand band, boolean activePlan, int count, Trend trend) {
        return new Observation(relation, band, activePlan, count, trend);
    }

    /** DEMO 参数（契约 C04 表）：只在测试里以固定 Map 提供，实现不得内联这些数字。 */
    static StubParams params() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("C04.corridor_near_m", "300");
        values.put("C04.climb_band_agl_m", "150");
        values.put("C04.approach_band_agl_m", "300");
        values.put("C04.flock_count_threshold", "20");
        values.put("C04.trend_window_min", "30");
        values.put("C04.plan_window_pad_min", "15");
        values.put("C05.procedure_buffer_m", "500");
        values.put("C05.protected_target_pad_m", "200");
        return new StubParams(values);
    }

    static final class StubParams implements RuleParams {
        private final Map<String, String> values;
        StubParams(Map<String, String> values) { this.values = values; }
        @Override public String ruleSetVersionId() { return "space-risk-demo-v1"; }
        @Override public String paramStatus(String ruleCode, String key) { return "DEMO"; }
        @Override public BigDecimal number(String ruleCode, String key) { return new BigDecimal(require(ruleCode, key)); }
        @Override public int integer(String ruleCode, String key) { return Integer.parseInt(require(ruleCode, key)); }
        @Override public boolean bool(String ruleCode, String key) { return Boolean.parseBoolean(require(ruleCode, key)); }
        @Override public String string(String ruleCode, String key) { return require(ruleCode, key); }
        @Override public List<String> list(String ruleCode, String key) { return List.of(require(ruleCode, key).split(",")); }
        private String require(String ruleCode, String key) {
            String value = values.get(ruleCode + "." + key);
            if (value == null) throw new IllegalStateException("规则参数缺失: " + ruleCode + "." + key);
            return value;
        }
    }
}
