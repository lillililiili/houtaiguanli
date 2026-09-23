package com.uav.lowaltitude.integration.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本地告警到处罚只预置三条可判定规则。某类已有规则时不追加。 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(80)
public class LocalAlarmFlowRuleSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalAlarmFlowRuleSeeder.class);
    private final JdbcTemplate jdbc;
    public LocalAlarmFlowRuleSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void run(ApplicationArguments args) {
        preset("verify", "alarm-flow-verify", "最新观测仍有效", "freshness", "120");
        preset("counter", "alarm-flow-counter", "反制依据仍有效", "counterFreshness", "120");
        preset("dispose", "alarm-flow-dispose", "事件已关联当前目标", "eventLink", "风险与当前目标已关联");
    }
    private void preset(String category, String ruleId, String name, String item, String value) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM automation_rule_condition WHERE category=?", Integer.class, category);
        if (count != null && count > 0) return;
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES(?,?,?,?,?,0,TRUE,?,?, 'alarm-flow-preset')",
                ruleId, category, name, item, value, now, now);
        log.info("preset one {} rule for the alarm-to-punishment flow", category);
    }
}
