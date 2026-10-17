package com.uav.lowaltitude.integration.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;

/**
 * 本地告警到处罚每类预置两条自动执行条件。只补缺失的预置，不删除、不覆盖任何规则：
 * 管理员改过（含启停）的预置原样保留；管理员在规则页删掉的预置不再补回；
 * 同类已有同判定项或同名规则时不再插入，重启可重复执行。
 */
@Component
@Profile("local & qa & !prod & !production")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(80)
public class LocalAlarmFlowRuleSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalAlarmFlowRuleSeeder.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    public LocalAlarmFlowRuleSeeder(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }
    @Override public void run(ApplicationArguments args) {
        seedPrincipal();
        int added = preset("verify", "alarm-flow-verify-confidence", "高置信度自动核实", "confidence", "80")
                + preset("verify", "alarm-flow-verify-freshness", "核实观测仍有效", "freshness", "60")
                + preset("counter", "alarm-flow-counter-freshness", "反制观测仍有效", "counterFreshness", "60")
                + preset("counter", "alarm-flow-counter-risk", "达到高风险", "riskLevel", "高风险")
                + preset("dispose", "alarm-flow-dispose-link", "事件已关联当前目标", "eventLink", "风险与当前目标已关联")
                + preset("dispose", "alarm-flow-dispose-freshness", "通知依据仍有效", "disposeFreshness", "120");
        log.info("alarm-to-punishment automatic rule presets checked, {} missing preset(s) added", added);
    }
    /** 停用账号，密码不可登录，角色没有任何菜单或操作权限。 */
    private void seedPrincipal() {
        long now = System.currentTimeMillis();
        Integer roles = jdbc.queryForObject("SELECT COUNT(*) FROM app_role WHERE role_code=?", Integer.class, AutomationPrincipal.ROLE);
        if (roles == null || roles == 0) {
            jdbc.update("INSERT INTO app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,?,FALSE,TRUE,?,?,0,FALSE)",
                    AutomationPrincipal.ROLE, "自动规则", "只作为自动规则发起人，不授予菜单和操作权限", now, now);
        }
        Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE user_id=?", Integer.class, AutomationPrincipal.USER_ID);
        if (users != null && users > 0) return;
        String orgId = jdbc.query("SELECT org_id FROM app_user WHERE account='admin1'", rs -> rs.next() ? rs.getString(1) : null);
        jdbc.update("""
                INSERT INTO app_user
                  (user_id, account, name, role_code, status, password_hash, fail_count, org_id,
                   scope_mode, must_change_password, permission_version, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'DISABLED', ?, 0, ?, 'ALL', FALSE, 0, ?, ?, 0)
                """,
                AutomationPrincipal.USER_ID, AutomationPrincipal.ACCOUNT, "自动规则", AutomationPrincipal.ROLE,
                passwords.encode(java.util.UUID.randomUUID().toString()), orgId, now, now);
        log.info("seeded disabled automation principal {}", AutomationPrincipal.ACCOUNT);
    }

    /** 预置缺失时补一条；已存在、与已有规则冲突或被管理员删掉过时跳过。返回插入条数。 */
    private int preset(String category, String ruleId, String name, String item, String value) {
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM automation_rule_condition
                 WHERE rule_id=? OR (category=? AND (item_code=? OR name=?))
                """, Integer.class, ruleId, category, item, name);
        if (existing != null && existing > 0) return 0;
        // 规则页每次保存都留变更记录：删除前快照有这条、删除后快照没有，说明是管理员删的，不再补回。
        String quoted = "%\"" + ruleId + "\"%";
        Integer removed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM automation_rule_change
                 WHERE category=? AND before_json LIKE ? AND after_json NOT LIKE ?
                """, Integer.class, category, quoted, quoted);
        if (removed != null && removed > 0) return 0;
        long now = System.currentTimeMillis();
        try {
            return jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES(?,?,?,?,?,0,TRUE,?,?, 'alarm-flow-preset')",
                    ruleId, category, name, item, value, now, now);
        } catch (DuplicateKeyException concurrent) {
            log.info("automatic rule preset {} already configured, skipped", ruleId);
            return 0;
        }
    }
}
