package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/**
 * 从 rule_param 加载某个规则集版本的全部参数并缓存在一次评估内（{@link LoadedRuleParams} 是不可变快照）。
 * 缺参数抛 IllegalStateException：参数目录随版本发布，缺项意味着部署/种子错误，不是业务上的"未知事实"，
 * 不能降级成 UNDETERMINED 让一条研判悄悄通过或悄悄不通过。
 */
@Component
public class RuleParamLoader {
    private final JdbcTemplate jdbc;

    public RuleParamLoader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public RuleParams load(String ruleSetVersionId) {
        Map<String, Entry> values = new LinkedHashMap<>();
        jdbc.query("SELECT rule_code,param_key,value_text,value_type,param_status FROM rule_param WHERE rule_set_version_id=? ORDER BY rule_code,param_key",
                rs -> { values.put(key(rs.getString("rule_code"), rs.getString("param_key")),
                        new Entry(rs.getString("value_text"), rs.getString("value_type"), rs.getString("param_status"))); },
                ruleSetVersionId);
        return new LoadedRuleParams(ruleSetVersionId, Map.copyOf(values));
    }

    static String key(String ruleCode, String key) { return ruleCode + "." + key; }

    record Entry(String value, String type, String status) { }

    /** 单次评估内的参数快照；所有取值都经过类型校验，类型不符同样视为部署错误。 */
    public static final class LoadedRuleParams implements RuleParams {
        private final String ruleSetVersionId;
        private final Map<String, Entry> values;

        LoadedRuleParams(String ruleSetVersionId, Map<String, Entry> values) {
            this.ruleSetVersionId = ruleSetVersionId; this.values = values;
        }

        @Override public String ruleSetVersionId() { return ruleSetVersionId; }
        @Override public String paramStatus(String ruleCode, String key) { return require(ruleCode, key).status(); }

        @Override public BigDecimal number(String ruleCode, String key) {
            Entry entry = require(ruleCode, key);
            try { return new BigDecimal(entry.value().trim()); }
            catch (NumberFormatException ex) { throw malformed(ruleCode, key, "NUMBER"); }
        }

        @Override public int integer(String ruleCode, String key) {
            Entry entry = require(ruleCode, key);
            try { return Integer.parseInt(entry.value().trim()); }
            catch (NumberFormatException ex) { throw malformed(ruleCode, key, "INTEGER"); }
        }

        @Override public boolean bool(String ruleCode, String key) {
            String value = require(ruleCode, key).value().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            throw malformed(ruleCode, key, "BOOLEAN");
        }

        @Override public String string(String ruleCode, String key) { return require(ruleCode, key).value().trim(); }

        @Override public List<String> list(String ruleCode, String key) {
            return Arrays.stream(require(ruleCode, key).value().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        private Entry require(String ruleCode, String key) {
            Entry entry = values.get(RuleParamLoader.key(ruleCode, key));
            if (entry == null || entry.value() == null || entry.value().isBlank()) {
                throw new IllegalStateException("规则参数缺失: " + ruleCode + "." + key + "（规则集版本 " + ruleSetVersionId + "）");
            }
            return entry;
        }

        private IllegalStateException malformed(String ruleCode, String key, String expected) {
            return new IllegalStateException("规则参数格式无效: " + ruleCode + "." + key + " 应为 " + expected + "（规则集版本 " + ruleSetVersionId + "）");
        }
    }
}
