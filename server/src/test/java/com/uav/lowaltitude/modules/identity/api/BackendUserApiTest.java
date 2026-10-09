package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.AuthService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendUserApiTest {
    static final String TEMP = "TempUser#2026A", PASSWORD = "Changed#2026UserA";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessControlService actions;
    @Autowired AuthService auth;
    String admin, org, role;

    @BeforeEach
    void fixture() throws Exception {
        admin = login("admin1", "changeme", false);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "bu-org-" + suffix; role = "ROLE-BU-" + suffix;
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", org, org, org);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version) VALUES (?,?, '',FALSE,TRUE,0,0,0)", role, role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) SELECT ?,permission_code,'NONE',FALSE FROM app_permission", role);
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
        jdbc.update("DELETE FROM app_session WHERE user_id IN (SELECT user_id FROM app_user WHERE org_id=?)", org);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id IN (SELECT user_id FROM app_user WHERE org_id=?)", org);
        jdbc.update("DELETE FROM app_user WHERE org_id=?", org);
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=?", role);
        jdbc.update("DELETE FROM app_role WHERE role_code=?", role);
        jdbc.update("DELETE FROM app_org WHERE org_id=?", org);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ", "AUTH"})
    void frontendCannotEnterBackendEvenWithLegacyManagementGrants(String level) throws Exception {
        jdbc.update("UPDATE app_role_permission SET permission_level=?,menu_enabled=TRUE WHERE role_code=? AND permission_code IN ('devices','users','roles','audit','interfaces','maps','monitoring','commissioning','organizations')", level, role);
        JsonNode user = create("FRONTEND", admin);
        String session = activate(user, false);
        int sessions = jdbc.queryForObject("SELECT count(*) FROM app_session WHERE user_id=?", Integer.class, user.path("user_id").asText());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("account", user.path("account").asText()).put("password", PASSWORD).put("client_type", "BACKEND"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BACKEND_ACCESS_DENIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_session WHERE user_id=?", Integer.class, user.path("user_id").asText())).isEqualTo(sessions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='login_fail' AND detail LIKE '%backend_access_denied%'", Integer.class, user.path("user_id").asText())).isEqualTo(1);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(session)).header("X-Client-Type", "BACKEND"))
                .andExpect(status().isForbidden());
        for (String path : new String[]{"/users", "/roles", "/audit-logs", "/mqtt-brokers", "/commission-tasks"}) {
            mvc.perform(get("/api/v1" + path).header("Authorization", bearer(session))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/devices/onboard").header("Authorization", bearer(session)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BACKEND_ACCESS_DENIED"));
        mvc.perform(post("/api/v1/organization-profiles").header("Authorization", bearer(session))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"越权单位\",\"organization_type\":\"OTHER\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BACKEND_ACCESS_DENIED"));
        // 共享设备读取仍供前台业务使用，后台身份门禁不能把它一并切断。
        mvc.perform(get("/api/v1/devices").header("Authorization", bearer(session))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_type").value("FRONTEND"))
                .andExpect(jsonPath("$.data.menu_keys[?(@ == 'devices')]").doesNotExist());
    }

    @Test
    void backendGetsAllManagementFunctionsWithoutDisposalActions() throws Exception {
        JsonNode user = create("BACKEND", admin);
        assertThat(user.path("role_code").asText()).isEqualTo("ROLE-BACKEND");
        assertThat(user.path("data_scope").asText()).isEqualTo("ALL");
        String session = activate(user, true);
        JsonNode me = data(mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(session)).header("X-Client-Type", "BACKEND"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(me.path("user_type").asText()).isEqualTo("BACKEND");
        assertThat(me.path("menu_keys").toString()).contains("devices", "monitor", "commission", "interfaces", "maps", "stats", "users", "responsePlans", "roles", "archive");
        assertThat(me.path("permission_codes").toString()).contains("users.auth", "roles.auth", "interfaces.op", "notificationSettings.auth", "map:activate", "rule:manage");
        assertThat(me.path("permission_codes").toString()).doesNotContain("disposal:direct", "disposal:execute", "disposal:approve", "disposal:request");
        for (String path : new String[]{"/users", "/roles", "/audit-logs", "/mqtt-brokers", "/commission-tasks"}) {
            mvc.perform(get("/api/v1" + path).header("Authorization", bearer(session))).andExpect(status().isOk());
        }
        // 后台用户也可维护用户，身份判断不写死为唯一超级管理员。
        JsonNode created = create("FRONTEND", session);
        assertThat(created.path("user_type").asText()).isEqualTo("FRONTEND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='user_created' AND detail LIKE '%后台用户直接创建用户%'", Integer.class,
                created.path("user_id").asText())).isEqualTo(1);
        AuthContext.set(auth.resolve(session));
        try {
            actions.require(PermissionCode.RULE_MANAGE);
            actions.require(PermissionCode.TARGET_READ);
            assertThatThrownBy(() -> actions.require(PermissionCode.DISPOSAL_DIRECT)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> actions.require(PermissionCode.DISPOSAL_EXECUTE)).isInstanceOf(ApiException.class);
        } finally { AuthContext.clear(); }
    }

    @Test
    void changingTypeRevokesOldSessionsInBothDirections() throws Exception {
        JsonNode user = create("FRONTEND", admin);
        String old = activate(user, false);
        user = current(user);
        user = update(user, json.createObjectNode().put("user_type", "BACKEND"));
        assertThat(user.path("role_code").asText()).isEqualTo("ROLE-BACKEND");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(old))).andExpect(status().isUnauthorized());
        String backendSession = login(user.path("account").asText(), PASSWORD, true);
        JsonNode before = user;
        user = update(user, json.createObjectNode().put("user_type", "FRONTEND").put("role_code", role));
        assertThat(user.path("role_code").asText()).isEqualTo(role);
        assertThat(user.path("data_scope").asText()).isEqualTo("OWN_ORG");
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(backendSession))).andExpect(status().isUnauthorized());
        // 同一旧版本不能再把账号升级回后台，资料及身份都不能部分保存。
        ObjectNode stale = profile(before).put("user_type", "BACKEND");
        mvc.perform(patch("/api/v1/users/" + user.path("user_id").asText()).header("Authorization", bearer(admin))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(stale)))
                .andExpect(status().isConflict());
        assertThat(current(user).path("user_type").asText()).isEqualTo("FRONTEND");
    }

    @Test
    void userTypeAndRoleMustAgreeAndSuperAdminStaysProtected() throws Exception {
        ObjectNode invalid = payload("FRONTEND").put("role_code", "ROLE-BACKEND");
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        invalid = payload("BACKEND").put("role_code", role);
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        String adminId = jdbc.queryForObject("SELECT user_id FROM app_user WHERE role_code='ROLE-ADMIN'", String.class);
        JsonNode superUser = data(mvc.perform(get("/api/v1/users/" + adminId).header("Authorization", bearer(admin))).andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/api/v1/users/" + adminId).header("Authorization", bearer(admin)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(profile(superUser).put("user_type", "FRONTEND").put("role_code", role))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("SUPER_ADMIN_PROTECTED"));
        mvc.perform(patch("/api/v1/roles/ROLE-BACKEND").header("Authorization", bearer(admin)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"description\":\"cannot edit\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void backendCanBeDisabledAndUserTypeFilterIsAppliedBeforePaging() throws Exception {
        JsonNode user = create("BACKEND", admin);
        String session = activate(user, true);
        user = current(user);
        mvc.perform(put("/api/v1/users/" + user.path("user_id").asText() + "/status").header("Authorization", bearer(admin))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("status", "DISABLED").put("expected_version", user.path("version").asInt())))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(session))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(json.createObjectNode()
                .put("account", user.path("account").asText()).put("password", PASSWORD).put("client_type", "BACKEND"))))
                .andExpect(status().isUnauthorized());
        create("FRONTEND", admin);
        JsonNode filtered = data(mvc.perform(get("/api/v1/users").param("user_type", "FRONTEND").param("orgId", org).param("size", "1")
                .header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("user_type").asText()).isEqualTo("FRONTEND");
    }

    ObjectNode payload(String type) {
        ObjectNode node = json.createObjectNode().put("account", "backend-test-" + UUID.randomUUID().toString().substring(0, 8))
                .put("name", "类型测试").put("org_id", org).put("temporary_password", TEMP).put("user_type", type);
        if ("FRONTEND".equals(type)) node.put("role_code", role);
        return node;
    }
    JsonNode create(String type, String actor) throws Exception {
        return data(mvc.perform(post("/api/v1/users").header("Authorization", bearer(actor)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload(type))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    String activate(JsonNode user, boolean backend) throws Exception {
        String session = login(user.path("account").asText(), TEMP, backend);
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(session)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("current_password", TEMP).put("new_password", PASSWORD)))).andExpect(status().isOk());
        return login(user.path("account").asText(), PASSWORD, backend);
    }
    String login(String account, String password, boolean backend) throws Exception {
        return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(json.createObjectNode()
                .put("account", account).put("password", password).put("client_type", backend ? "BACKEND" : "FRONTEND"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("session_id").asText();
    }
    JsonNode current(JsonNode user) throws Exception {
        return data(mvc.perform(get("/api/v1/users/" + user.path("user_id").asText()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    ObjectNode profile(JsonNode user) { return json.createObjectNode().put("name", user.path("name").asText()).put("org_id", user.path("org_id").asText()).put("expected_version", user.path("version").asInt()); }
    JsonNode update(JsonNode user, ObjectNode changes) throws Exception {
        ObjectNode body = profile(user); body.setAll(changes);
        return data(mvc.perform(patch("/api/v1/users/" + user.path("user_id").asText()).header("Authorization", bearer(admin))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode data(String response) throws Exception { return json.readTree(response).path("data"); }
    String bearer(String session) { return "Bearer " + session; }
}
