package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/** 纯 Java 测试用固定参数：默认为契约 DEMO 参数目录，可逐项覆盖；缺参数与真实实现一样抛 IllegalStateException。 */
final class TestRuleParams implements RuleParams {
    private final Map<String, String> values = new LinkedHashMap<>();
    private final String status;

    private TestRuleParams(String status) { this.status = status; }

    static TestRuleParams demoCatalog() {
        TestRuleParams params = new TestRuleParams("DEMO");
        params.put("C01", "time_window_min", "10").put("C01", "corridor_tolerance_m", "20")
                .put("C02-1", "kinds", "PROHIBITED,RESTRICTED").put("C02-2", "kinds", "ALTITUDE_LIMIT")
                .put("C02-3", "tolerance_m", "20").put("C02-4", "grace_min", "10")
                .put("C02-5", "timezone", "Asia/Shanghai").put("C02-5", "night_from", "20").put("C02-5", "night_to", "6")
                .put("C02-6", "vlos_m", "500").put("C02-8", "kinds", "TEMPORARY_CONTROL")
                .put("C03", "fresh_seconds", "120").put("C03", "track_points", "10").put("C03", "conf_min", "0.75")
                .put("C03", "min_points", "3").put("C03", "gap_seconds", "30").put("C03", "no_plan_status", "ILLEGAL")
                .put("C03", "ignore_undetermined_rules", "C02-6")
                .put("C03", "w.violation", "0.40").put("C03", "w.plan_match", "0.25").put("C03", "w.airspace", "0.15")
                .put("C03", "w.track", "0.10").put("C03", "w.confidence", "0.10")
                .put("C03", "severity.INSIDE_RESTRICTED_AIRSPACE", "1.0").put("C03", "severity.AIRSPACE_ALTITUDE_EXCEEDED", "0.9")
                .put("C03", "severity.TEMPORARY_RESTRICTION_ACTIVE", "0.9").put("C03", "severity.NO_AUTHORIZATION", "0.8")
                .put("C03", "severity.ROUTE_DEVIATION", "0.6").put("C03", "severity.PLAN_ALTITUDE_EXCEEDED", "0.5")
                .put("C03", "severity.TIME_WINDOW_OVERRUN", "0.4").put("C03", "severity.NIGHT_FLIGHT", "0.3")
                .put("C03", "grade.high", "67").put("C03", "grade.medium", "34")
                .put("C06", "dedup_window_min", "5").put("C06", "upgrade_window_min", "10").put("C06", "auto_close_min", "15")
                .put("C06", "severity_by_grade", "HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW");
        return params;
    }

    static TestRuleParams empty() { return new TestRuleParams("DEMO"); }

    TestRuleParams put(String ruleCode, String key, String value) { values.put(ruleCode + "." + key, value); return this; }

    @Override public String ruleSetVersionId() { return "test-rule-set-version"; }
    @Override public String paramStatus(String ruleCode, String key) { require(ruleCode, key); return status; }
    @Override public BigDecimal number(String ruleCode, String key) { return new BigDecimal(require(ruleCode, key)); }
    @Override public int integer(String ruleCode, String key) { return Integer.parseInt(require(ruleCode, key)); }
    @Override public boolean bool(String ruleCode, String key) { return Boolean.parseBoolean(require(ruleCode, key)); }
    @Override public String string(String ruleCode, String key) { return require(ruleCode, key); }
    @Override public List<String> list(String ruleCode, String key) {
        return Arrays.stream(require(ruleCode, key).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String require(String ruleCode, String key) {
        String value = values.get(ruleCode + "." + key);
        if (value == null) throw new IllegalStateException("规则参数缺失: " + ruleCode + "." + key);
        return value;
    }
}
