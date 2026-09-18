package com.uav.lowaltitude.modules.automationrule.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.*;

@Repository
public class AutomationRuleRepository {
    private final JdbcTemplate jdbc;
    public AutomationRuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Head(String category, long version, String scope, String schedule,
            String start, String end, int waitSeconds, String actions) { }
    public record Scope(String id, String name) { }
    public record ChangeRow(String id, long version, String action, String actor, long at, String details) { }

    public Head head(String category, boolean lock) {
        return jdbc.query("SELECT * FROM automation_rule_group WHERE category=?" + (lock ? " FOR UPDATE" : ""),
            (r,n) -> new Head(r.getString("category"), r.getLong("version"), r.getString("scope_mode"),
                r.getString("schedule_mode"), r.getString("start_time"), r.getString("end_time"),
                r.getInt("wait_seconds"), r.getString("actions_json")), category).stream().findFirst().orElse(null);
    }
    public List<Rule> rules(String category) {
        return jdbc.query("SELECT * FROM automation_rule_condition WHERE category=? ORDER BY created_at,rule_id",
            (r,n) -> new Rule(r.getString("rule_id"), r.getString("name"), r.getString("item_code"),
                r.getString("value_text"), r.getInt("hold_seconds"), r.getBoolean("enabled"),
                r.getLong("updated_at"), r.getString("updated_by")), category);
    }
    public List<Scope> scopes(String category) {
        return jdbc.query("SELECT s.airspace_id,a.name FROM automation_rule_scope s JOIN airspace a ON a.airspace_id=s.airspace_id WHERE s.category=? ORDER BY s.ordinal",
                (r,n) -> new Scope(r.getString(1), r.getString(2)), category);
    }
    // Called only after the application service requires ALL data scope.
    public boolean airspaceExists(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM airspace WHERE airspace_id=?",Long.class,id)==1;
    }
    public void insert(String category, Rule rule, long now) {
        jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
            rule.ruleId(), category, rule.name(), rule.itemCode(), rule.value(), rule.holdSeconds(), rule.enabled(), now, now, rule.updatedBy());
    }
    public int update(String category, Rule rule) {
        return jdbc.update("UPDATE automation_rule_condition SET name=?,item_code=?,value_text=?,hold_seconds=?,enabled=?,updated_at=?,updated_by=? WHERE category=? AND rule_id=?",
            rule.name(), rule.itemCode(), rule.value(), rule.holdSeconds(), rule.enabled(), rule.updatedAt(), rule.updatedBy(), category, rule.ruleId());
    }
    public void settings(String category, SettingsInput input, String actions) {
        jdbc.update("UPDATE automation_rule_group SET scope_mode=?,schedule_mode=?,start_time=?,end_time=?,wait_seconds=?,actions_json=? WHERE category=?",
            input.scopeMode(), input.scheduleMode(), input.startTime(), input.endTime(), input.insufficientWaitSeconds(), actions, category);
        jdbc.update("DELETE FROM automation_rule_scope WHERE category=?", category);
        for (int i=0; i<input.airspaceIds().size(); i++) {
            jdbc.update("INSERT INTO automation_rule_scope(category,airspace_id,ordinal) VALUES(?,?,?)", category, input.airspaceIds().get(i), i);
        }
    }
    public int advance(String category, long expected, long now) {
        return jdbc.update("UPDATE automation_rule_group SET version=version+1,updated_at=? WHERE category=? AND version=?", now, category, expected);
    }
    public void history(String id, String category, long version, String action, String actorId, String actor,
            long at, String details, String before, String after) {
        jdbc.update("INSERT INTO automation_rule_change(change_id,category,version,action,actor_id,actor,created_at,details_json,before_json,after_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, category, version, action, actorId, actor, at, details, before, after);
    }
    public List<ChangeRow> history(String category, int page, int size) {
        return jdbc.query("SELECT * FROM automation_rule_change WHERE category=? ORDER BY version DESC LIMIT ? OFFSET ?",
            (r,n) -> new ChangeRow(r.getString("change_id"), r.getLong("version"), r.getString("action"),
                r.getString("actor"), r.getLong("created_at"), r.getString("details_json")), category, size, (page-1)*size);
    }
    public long historyCount(String category) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM automation_rule_change WHERE category=?", Long.class, category);
    }
}
