package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.identity.application.UserDataScopeService;

/**
 * ZT-14：管理员在用户管理里给账号设“全部单位 / 本单位 / 本单位及下级单位”，服务端按既有“单位 + 区域”元组
 * 收窄告警、证据、统计及其导出；实时推送只带主题，前端收到后重读的正是这些接口，所以重读同样不会带出别的单位。
 * 夹具单位、区域、账号每次随机生成，不依赖演示数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserDataScopeApiTest {

    private static final String TEMP_PASSWORD = "TempUser#2026A";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SqlSessionTemplate sqlSession;
    @Autowired UserDataScopeService dataScopes;

    private String suffix, admin, role, orgA, orgB, district, source;

    @BeforeEach
    void fixture() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = login("admin1", "changeme");
        orgA = "ds-a-" + suffix;
        orgB = "ds-b-" + suffix;
        district = "ds-d-" + suffix;
        org(orgA, null, "甲单位-" + suffix);
        org(orgB, null, "乙单位-" + suffix);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DS-D-" + suffix, "范围区域-" + suffix);
        role = "ROLE-DS-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)",
                role, "数据范围测试-" + suffix);
        grant(role, "alarm:read", "READ");
        grant(role, "evidence:read", "READ");
        grant(role, "evidence:download", "READ");
        grant(role, "statistics", "READ");
        source = "ds-src-" + suffix;
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)",
                source, "DS-SRC-" + suffix, "范围来源");
        sqlSession.clearCache();
    }

    @Test
    void newAccountDefaultsToOwnUnitAndEveryReadAndExportStaysInsideIt() throws Exception {
        String alarmA = alarm(orgA), alarmB = alarm(orgB);
        String uploader = uploader();
        String fileA = evidence(uploader, orgA, "jia-" + suffix + ".jpg");
        String fileB = evidence(uploader, orgB, "yi-" + suffix + ".jpg");

        JsonNode user = createUser(orgA, null);
        assertThat(user.path("data_scope").asText()).isEqualTo("OWN_ORG");
        assertThat(tupleOrgs(user)).containsOnly(orgA);
        assertThat(jdbc.queryForObject("select count(*) from app_user_data_scope where user_id=?", Integer.class,
                user.path("user_id").asText())).isEqualTo(jdbc.queryForObject("select count(*) from app_district", Integer.class));
        String token = session(user);

        // 列表、详情：乙单位的告警不出现，按 ID 直接打开也只得到“不存在”。
        mvc.perform(get("/api/v1/alarms").param("size", "100").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.alarm_id == '" + alarmA + "')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.alarm_id == '" + alarmB + "')]").doesNotExist())
                .andExpect(jsonPath("$.data.items[?(@.owner_org_id != '" + orgA + "')]").doesNotExist());
        mvc.perform(get("/api/v1/alarms/" + alarmB).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        String alarmCsv = mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(alarmCsv).contains("甲单位-" + suffix).doesNotContain("乙单位-" + suffix);

        // 证据：列表、下载、台账导出都只剩本单位。
        mvc.perform(get("/api/v1/evidence-files").param("size", "100").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.evidence_id == '" + fileA + "')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.evidence_id == '" + fileB + "')]").doesNotExist());
        mvc.perform(get("/api/v1/evidence-files/" + fileB + "/content").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/evidence-files/" + fileA + "/content").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        String ledgerCsv = mvc.perform(get("/api/v1/evidence-ledger/export.csv").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(ledgerCsv).contains(fileA).doesNotContain(fileB);

        // 统计：单位下拉只有本单位，指定别的单位查询或导出被拒。
        mvc.perform(get("/api/v1/stats/operations/organizations").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].org_id").value(orgA));
        mvc.perform(get("/api/v1/stats/operations").param("owner_org_id", orgB).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ORGANIZATION"));
        mvc.perform(get("/api/v1/stats/operations/export.csv").param("owner_org_id", orgB).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        // 管理员换到“全部单位”：派生元组清掉，旧会话失效，重新登录后能看到乙单位。
        user = updateUser(user, orgA, "ALL");
        assertThat(user.path("data_scope").asText()).isEqualTo("ALL");
        assertThat(jdbc.queryForObject("select count(*) from app_user_data_scope where user_id=?", Integer.class,
                user.path("user_id").asText())).isZero();
        mvc.perform(get("/api/v1/alarms").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        token = session(user);
        mvc.perform(get("/api/v1/alarms/" + alarmB).header("Authorization", bearer(token))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='user_data_scope_changed' and object_id=? and detail like ?",
                Integer.class, user.path("user_id").asText(), "%\"to\":\"ALL\"%")).isEqualTo(1);
    }

    @Test
    void ownUnitTreeFollowsSubUnitsDistrictsAndReparenting() throws Exception {
        JsonNode user = createUser(orgA, "OWN_ORG_TREE");
        assertThat(user.path("data_scope").asText()).isEqualTo("OWN_ORG_TREE");
        String child = data(mvc.perform(post("/api/v1/organizations").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-child-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", "甲单位下级-" + suffix).put("parent_id", orgA).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("org_id").asText();
        // 新建的下级单位当场并入“本单位及下级单位”。
        assertThat(tupleOrgs(user)).containsOnly(orgA, child);
        String childAlarm = alarm(child);
        String token = session(user);
        mvc.perform(get("/api/v1/alarms/" + childAlarm).header("Authorization", bearer(token))).andExpect(status().isOk());

        // 新建区域同样纳入，不需要管理员再去补授权。
        String newDistrict = data(mvc.perform(post("/api/v1/districts").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-district-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("district_code", "DS-NEW-" + suffix).put("name", "新区域-" + suffix).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("district_id").asText();
        assertThat(jdbc.queryForObject("select count(*) from app_user_data_scope where user_id=? and district_id=? and org_id in (?,?)",
                Integer.class, user.path("user_id").asText(), newDistrict, orgA, child)).isEqualTo(2);

        // 下级单位改挂到乙单位下：同一事务里收回，按 ID 也打不开。
        int childVersion = jdbc.queryForObject("select version from app_org where org_id=?", Integer.class, child);
        mvc.perform(patch("/api/v1/organizations/" + child).header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-move-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", "甲单位下级-" + suffix).put("parent_id", orgB)
                                .put("expected_version", childVersion).toString()))
                .andExpect(status().isOk());
        assertThat(tupleOrgs(user)).containsOnly(orgA);
        sqlSession.clearCache();
        mvc.perform(get("/api/v1/alarms/" + childAlarm).header("Authorization", bearer(token))).andExpect(status().isNotFound());

        // 账号调到乙单位：范围跟着单位走，并按权限变更让旧会话失效。
        user = updateUser(user, orgB, null);
        assertThat(user.path("data_scope").asText()).isEqualTo("OWN_ORG_TREE");
        assertThat(tupleOrgs(user)).containsOnly(orgB, child);
        mvc.perform(get("/api/v1/alarms").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void superAdminStaysAllAndOnlyTheThreeChoicesCanBeSet() throws Exception {
        String adminId = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        JsonNode adminUser = data(mvc.perform(get("/api/v1/users/" + adminId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data_scope").value("ALL"))
                .andReturn().getResponse().getContentAsString());
        ObjectNode narrow = json.createObjectNode().put("name", adminUser.path("name").asText())
                .put("org_id", adminUser.path("org_id").asText()).put("data_scope", "OWN_ORG")
                .put("expected_version", adminUser.path("version").asInt());
        mvc.perform(patch("/api/v1/users/" + adminId).header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-admin-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(narrow.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("SUPER_ADMIN_PROTECTED"));

        ObjectNode custom = createBody(orgA, "CUSTOM");
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-custom-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(custom.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void ruleTuplesDoNotPinDistrictsButManualGrantsStillDo() throws Exception {
        createUser(orgA, "OWN_ORG");
        int version = jdbc.queryForObject("select version from app_district where district_id=?", Integer.class, district);
        mvc.perform(put("/api/v1/districts/" + district + "/status").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-disable-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"expected_version\":" + version + "}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/districts/" + district + "/status").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-enable-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"expected_version\":" + (version + 1) + "}"))
                .andExpect(status().isOk());

        String manual = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                manual, "ds-manual-" + suffix, "手工授权账号", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", manual, orgA, district);
        sqlSession.clearCache();
        mvc.perform(get("/api/v1/users/" + manual).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data_scope").value("CUSTOM"));
        mvc.perform(put("/api/v1/districts/" + district + "/status").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-disable-again-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"expected_version\":" + (version + 2) + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DISTRICT_IN_USE"));
    }

    @Test
    void reconciliationRebuildsStaleRuleTuplesAndLeavesOthersAlone() {
        String ruled = UUID.randomUUID().toString(), manual = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,org_id,role_code,status,password_hash,fail_count,scope_mode,scope_org_rule,permission_version,created_at,updated_at,version) values (?,?,?,?,?,'ACTIVE','unused',0,'ASSIGNED','OWN_ORG',0,0,0,0)",
                ruled, "ds-ruled-" + suffix, "规则账号", orgA, role);
        jdbc.update("insert into app_user (user_id,account,name,org_id,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                manual, "ds-manual2-" + suffix, "手工账号", orgA, role);
        // 规则账号残留一条别的单位的元组（例如库里直接改过单位），手工账号的元组不该被动。
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", ruled, orgB, district);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", manual, orgB, district);
        sqlSession.clearCache();

        dataScopes.refreshAll();

        assertThat(jdbc.queryForList("select distinct org_id from app_user_data_scope where user_id=?", String.class, ruled))
                .containsOnly(orgA);
        assertThat(jdbc.queryForList("select org_id from app_user_data_scope where user_id=?", String.class, manual))
                .containsExactly(orgB);
    }

    private JsonNode createUser(String orgId, String dataScope) throws Exception {
        return data(mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "ds-user-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(orgId, dataScope).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ObjectNode createBody(String orgId, String dataScope) {
        ObjectNode body = json.createObjectNode().put("account", "ds-" + UUID.randomUUID().toString().substring(0, 12))
                .put("name", "范围账号").put("org_id", orgId).put("role_code", role).put("temporary_password", TEMP_PASSWORD);
        if (dataScope != null) body.put("data_scope", dataScope);
        return body;
    }

    private JsonNode updateUser(JsonNode user, String orgId, String dataScope) throws Exception {
        ObjectNode body = json.createObjectNode().put("name", user.path("name").asText()).put("org_id", orgId)
                .put("expected_version", user.path("version").asInt());
        if (dataScope != null) body.put("data_scope", dataScope);
        JsonNode updated = data(mvc.perform(patch("/api/v1/users/" + user.path("user_id").asText())
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "ds-update-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        sqlSession.clearCache();
        return updated;
    }

    private List<String> tupleOrgs(JsonNode user) {
        return new ArrayList<>(jdbc.queryForList("select distinct org_id from app_user_data_scope where user_id=?",
                String.class, user.path("user_id").asText()));
    }

    /** 用户要先改临时密码才能看业务数据；这里直接签一个与当前权限版本一致的会话。 */
    private String session(JsonNode user) {
        String userId = user.path("user_id").asText();
        jdbc.update("update app_user set must_change_password=false where user_id=?", userId);
        long permissionVersion = jdbc.queryForObject("select permission_version from app_user where user_id=?", Long.class, userId);
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',?)",
                token, userId, System.currentTimeMillis() + 3_600_000L, permissionVersion);
        sqlSession.clearCache();
        return token;
    }

    private String uploader() {
        String uploaderRole = "ROLE-DS-UP-" + suffix, userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)",
                uploaderRole, "证据上传-" + suffix);
        for (String permission : List.of("evidence:ingest", "evidence:read", "evidence:link", "target:read")) {
            grant(uploaderRole, permission, "READ");
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)",
                userId, "ds-uploader-" + suffix, "证据上传人", uploaderRole);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, userId, System.currentTimeMillis() + 3_600_000L);
        sqlSession.clearCache();
        return token;
    }

    private String evidence(String token, String orgId, String filename) throws Exception {
        String target = "ds-target-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                target, "T-" + target, orgId, district, now, now, now, now);
        sqlSession.clearCache();
        String body = mvc.perform(multipart("/api/v1/evidence-files")
                        .file(new MockMultipartFile("file", filename, "application/octet-stream",
                                ("scope-" + filename).getBytes(StandardCharsets.UTF_8)))
                        .param("kind_code", "EO_STILL").param("owner_org_id", orgId).param("district_id", district)
                        .param("subject_kind", "TARGET").param("subject_id", target)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "ds-ev-" + filename))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("evidence_id").asText();
    }

    private String alarm(String orgId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into alarm (alarm_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,'UAV','HIGH',current_timestamp,'mock',?,?,current_timestamp)",
                id, source, "ds-" + id, orgId, district);
        sqlSession.clearCache();
        return id;
    }

    private void org(String id, String parentId, String name) {
        jdbc.update("insert into app_org (org_id,parent_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,?,true,0,0,0)",
                id, parentId, id.toUpperCase(), name);
    }

    private void grant(String roleCode, String permission, String level) {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,?,false,current_timestamp)",
                roleCode, permission, level);
    }

    private String login(String account, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", account).put("password", password).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return data(body).path("session_id").asText();
    }

    private JsonNode data(String body) throws Exception {
        return json.readTree(body).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
