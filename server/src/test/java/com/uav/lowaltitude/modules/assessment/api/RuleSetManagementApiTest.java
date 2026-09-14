package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 规则集管理接口：鉴权先于解析、发布/DEMO/回滚守卫、幂等重放、版本冲突、成功审计同事务且失败整体回滚。
 * 与 RiskVerificationApiTest 同样不套测试事务：失败请求的幂等键领用必须真正随业务事务回滚，@AfterEach 按前缀清理夹具。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleSetManagementApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String code, setId, confirmedVersion, demoVersion, draftVersion, manager;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        code = "RS-TEST-" + suffix; setId = "rs-" + suffix;
        confirmedVersion = "rsv-confirmed-" + suffix; demoVersion = "rsv-demo-" + suffix; draftVersion = "rsv-draft-" + suffix;
        Timestamp at = ts(System.currentTimeMillis() - 60_000);
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,version,created_at,updated_at) values (?,?,?,0,?,?)", setId, code, "测试规则集", at, at);
        version(confirmedVersion, 1, "PUBLISHED", "CONFIRMED", at);
        version(demoVersion, 2, "PUBLISHED", "DEMO", at);
        version(draftVersion, 3, "DRAFT", "DEMO", at);
        String ruleVersion = "rs-test-rv-" + suffix;
        jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at) values (?,?,?,?,?,?,?)",
                ruleVersion, "C03", ThreadLocalRandom.current().nextInt(100_000, 999_999), "ACTIVE", at, "mock", at);
        jdbc.update("insert into rule_set_member (rule_set_version_id,rule_version_id,priority,enabled) values (?,?,300,true)", confirmedVersion, ruleVersion);
        jdbc.update("insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note) values (?,?,?,?,?,?,?,?,?)",
                "rp-" + suffix, confirmedVersion, "C03", "fresh_seconds", "120", "INTEGER", "s", "CONFIRMED", "新鲜度窗口");
        manager = user("ALL", true, true);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("update rule_set set active_version_id=null,shadow_version_id=null,previous_active_version_id=null where rule_set_code like 'RS-TEST-%'");
        jdbc.update("delete from rule_set_activation where rule_set_id in (select rule_set_id from rule_set where rule_set_code like 'RS-TEST-%')");
        jdbc.update("delete from rule_run where rule_set_id in (select rule_set_id from rule_set where rule_set_code like 'RS-TEST-%')");
        jdbc.update("delete from rule_param where rule_set_version_id in (select rule_set_version_id from rule_set_version where rule_set_id in (select rule_set_id from rule_set where rule_set_code like 'RS-TEST-%'))");
        jdbc.update("delete from rule_set_member where rule_set_version_id in (select rule_set_version_id from rule_set_version where rule_set_id in (select rule_set_id from rule_set where rule_set_code like 'RS-TEST-%'))");
        jdbc.update("delete from rule_set_version where rule_set_id in (select rule_set_id from rule_set where rule_set_code like 'RS-TEST-%')");
        jdbc.update("delete from rule_set where rule_set_code like 'RS-TEST-%'");
        jdbc.update("delete from rule_version where rule_version_id like 'rs-test-rv-%'");
        jdbc.update("delete from audit_log where account like 'rs-test-%'");
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 'rs-test-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'rs-test-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'rs-test-%')");
        jdbc.update("delete from app_user where account like 'rs-test-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-RS-TEST-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-RS-TEST-%'");
    }

    @Test
    void missingManagePermissionIsForbiddenBeforeBodyOrPathIsParsed() throws Exception {
        String readOnly = user("ALL", true, false);
        mvc.perform(post("/api/v1/rule-sets/no-such-code/activate").header("Authorization", bearer(readOnly)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("not json")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/rule-sets/" + code + "/rollback").header("Authorization", bearer(readOnly)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\",\"expected_version\":0}")).andExpect(status().isForbidden());
        String noRead = user("ALL", false, false);
        mvc.perform(get("/api/v1/rule-sets").header("Authorization", bearer(noRead))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/rule-runs?mode=bad").header("Authorization", bearer(noRead))).andExpect(status().isForbidden());
        // 只有 manage 没有 read 同样拒绝：两个动作权限都要。
        String manageOnly = user("ALL", false, true);
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manageOnly)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(activate(confirmedVersion, 0))).andExpect(status().isForbidden());
    }

    @Test
    void activationGuardsUnpublishedDemoAndUnknownVersions() throws Exception {
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(activate(draftVersion, 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RULE_VERSION_NOT_PUBLISHED"));
        // allow-demo-active 默认 false：演示参数不能成为生效规则。
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(activate(demoVersion, 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEMO_PARAMS_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(activate("rsv-missing", 0)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RULE_VERSION_NOT_FOUND"));
        mvc.perform(post("/api/v1/rule-sets/no-such-code/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(activate(confirmedVersion, 0)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RULE_SET_NOT_FOUND"));
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rule_set_version_id\":\"" + confirmedVersion + "\",\"note\":\"n\",\"expected_version\":0,\"status_code\":\"PUBLISHED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        assertThat(jdbc.queryForObject("select count(*) from rule_set_activation where rule_set_id=?", Long.class, setId)).isZero();
        assertThat(jdbc.queryForObject("select version from rule_set where rule_set_id=?", Long.class, setId)).isZero();
    }

    @Test
    void rollbackWithoutPreviousVersionConflicts() throws Exception {
        mvc.perform(post("/api/v1/rule-sets/" + code + "/rollback").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"回滚\",\"expected_version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NO_PREVIOUS_VERSION"));
    }

    @Test
    void successfulActivationAuditsInSameTransactionAndReplayIsRejected() throws Exception {
        String key = key();
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(activate(confirmedVersion, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_version_id").value(confirmedVersion))
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='rule_set_activated' and object_id=? and result='SUCCESS'", Long.class, setId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select kind from rule_set_activation where rule_set_id=? and resulting_version=1", String.class, setId)).isEqualTo("ACTIVATE");
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(activate(confirmedVersion, 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        mvc.perform(get("/api/v1/rule-sets/" + code + "/versions").header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].version_no").value(3))
                .andExpect(jsonPath("$.data.items[0].is_active").value(false))
                .andExpect(jsonPath("$.data.items[2].version_no").value(1))
                .andExpect(jsonPath("$.data.items[2].is_active").value(true))
                .andExpect(jsonPath("$.data.items[2].is_shadow").value(false));
        mvc.perform(get("/api/v1/rule-set-versions/" + confirmedVersion).header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members[0].rule_code").value("C03"))
                .andExpect(jsonPath("$.data.params[0].key").value("fresh_seconds"))
                .andExpect(jsonPath("$.data.params[0].status").value("CONFIRMED"));
        mvc.perform(get("/api/v1/rule-sets/" + code + "/activations").header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].to_version_id").value(confirmedVersion));
    }

    @Test
    void versionConflictRollsBackEverythingIncludingIdempotencyClaim() throws Exception {
        String key = key();
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(activate(confirmedVersion, 7)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from rule_set_activation where rule_set_id=?", Long.class, setId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='rule_set_activated' and object_id=?", Long.class, setId)).isZero();
        // 失败事务回滚了幂等键领用：同一键携带正确版本重试必须成功，否则客户端永远无法从冲突中恢复。
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(activate(confirmedVersion, 0)))
                .andExpect(status().isOk());
    }

    @Test
    void rollbackSwapsPreviousAndShadowCanBeSetAndCleared() throws Exception {
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(activate(confirmedVersion, 0))).andExpect(status().isOk());
        jdbc.update("update rule_set_version set param_status='CONFIRMED' where rule_set_version_id=?", demoVersion);
        mvc.perform(post("/api/v1/rule-sets/" + code + "/activate").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(activate(demoVersion, 1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previous_active_version_id").value(confirmedVersion));
        mvc.perform(post("/api/v1/rule-sets/" + code + "/rollback").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"回滚到上一版\",\"expected_version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_version_id").value(confirmedVersion))
                .andExpect(jsonPath("$.data.previous_active_version_id").value(demoVersion))
                .andExpect(jsonPath("$.data.version").value(3));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='rule_set_rolled_back' and object_id=?", Long.class, setId)).isEqualTo(1L);
        mvc.perform(post("/api/v1/rule-sets/" + code + "/shadow").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rule_set_version_id\":\"" + demoVersion + "\",\"note\":\"影子验证\",\"expected_version\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shadow_version_id").value(demoVersion));
        mvc.perform(post("/api/v1/rule-sets/" + code + "/shadow").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rule_set_version_id\":null,\"note\":\"清除影子\",\"expected_version\":4}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shadow_version_id").doesNotExist());
        mvc.perform(post("/api/v1/rule-sets/" + code + "/shadow").header("Authorization", bearer(manager)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rule_set_version_id\":null,\"note\":\"再清\",\"expected_version\":5}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SHADOW_VERSION_NOT_SET"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='rule_set_shadow_changed' and object_id=?", Long.class, setId)).isEqualTo(2L);
        mvc.perform(get("/api/v1/rule-sets/" + code + "/activations").header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.items[2].kind").value("ROLLBACK"))
                .andExpect(jsonPath("$.data.items[4].kind").value("SHADOW_CLEAR"));
    }

    @Test
    void ruleRunsAreListedNewestFirstWithStrictFilters() throws Exception {
        Timestamp at = ts(System.currentTimeMillis());
        run("run-old-" + setId, at, "ACTIVE", "SCHEDULED", 1_000);
        run("run-new-" + setId, at, "SHADOW", "MANUAL", 2_000);
        mvc.perform(get("/api/v1/rule-runs").header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].run_id").value("run-new-" + setId));
        // 没有测试事务，其他用例或种子可能留下运行记录：筛选同时限定时间窗，只命中本用例插入的那一条。
        mvc.perform(get("/api/v1/rule-runs?mode=ACTIVE&trigger_kind=SCHEDULED&from=" + at.getTime() + "&to=" + (at.getTime() + 1_500)).header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].run_id").value("run-old-" + setId));
        mvc.perform(get("/api/v1/rule-runs/run-new-" + setId).header("Authorization", bearer(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.evaluated_count").value(2000));
        for (String query : new String[]{"mode=bad", "mode=ACTIVE&mode=SHADOW", "from=1", "wat=1", "trigger_kind=NOPE"}) {
            mvc.perform(get("/api/v1/rule-runs?" + query).header("Authorization", bearer(manager))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/rule-runs/run-missing").header("Authorization", bearer(manager))).andExpect(status().isNotFound());
    }

    private void run(String id, Timestamp at, String mode, String trigger, int evaluated) {
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,finished_at,status,subject_count,evaluated_count,source_mode,created_at) values (?,?,?,?,?,?,?,?,'DONE',?,?,'mock',?)",
                id, setId, confirmedVersion, mode, trigger, at, ts(at.getTime() + evaluated), ts(at.getTime() + evaluated + 1), evaluated, evaluated, at);
    }

    private void version(String id, int no, String status, String paramStatus, Timestamp at) {
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at) values (?,?,?,?,?,?,?,?,?,?)",
                id, setId, no, status, paramStatus, at, "v" + no, "mock", at, "DRAFT".equals(status) ? null : at);
    }

    private String user(String scope, boolean read, boolean manage) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-RS-TEST-" + suffix;
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        if (read) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'rule:read','READ',false,current_timestamp)", role);
        if (manage) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'rule:manage','OP',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)", user, "rs-test-" + suffix, "规则测试", role, scope);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private static String activate(String versionId, long expected) {
        return "{\"rule_set_version_id\":\"" + versionId + "\",\"note\":\"激活测试\",\"expected_version\":" + expected + "}";
    }
    private static String key() { return "key-" + UUID.randomUUID(); }
    private static Timestamp ts(long millis) { return Timestamp.from(Instant.ofEpochMilli(millis)); }
    private static String bearer(String token) { return "Bearer " + token; }
}
