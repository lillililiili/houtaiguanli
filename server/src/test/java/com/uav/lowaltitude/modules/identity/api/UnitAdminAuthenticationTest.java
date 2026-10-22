package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.application.UserDataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 单位管理身份只能打开受限工作区，不能变成平台身份或突破业务授权上限。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UnitAdminAuthenticationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired SqlSessionTemplate sql;
    @Autowired UserDataScopeService scopes;

    private static final String PASSWORD = "UnitLogin#2026";
    private String org, admin, member, role;

    @BeforeEach void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "auth-org-" + suffix;
        admin = "auth-admin-" + suffix;
        member = "auth-member-" + suffix;
        role = "ROLE-UA-" + suffix;
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,true,0,0,0)",
                org, org, "身份隔离单位 " + suffix);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role,owner_org_id,name_scope) VALUES(?,?,'',false,true,0,0,0,false,?,?)",
                role, "值班角色 " + suffix, org, org);
        for (String user : new String[]{admin, member}) {
            jdbc.update("INSERT INTO app_user(user_id,account,name,org_id,role_code,status,password_hash,fail_count,scope_mode,scope_org_rule,must_change_password,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,?,'ACTIVE',?,0,'ASSIGNED','OWN_ORG',false,0,0,0,0)",
                    user, user, user, org, role, passwords.encode(PASSWORD));
            scopes.refreshUser(user);
        }
        jdbc.update("INSERT INTO app_org_delegation(org_id,administrator_user_id,version,created_at,updated_at) VALUES(?,?,0,0,0)", org, admin);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,'alarms','AUTH',true,CURRENT_TIMESTAMP)", role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,'alarm:verify','OP',false,CURRENT_TIMESTAMP)", role);
        jdbc.update("INSERT INTO app_org_permission(org_id,permission_code,permission_level,menu_enabled) VALUES(?,'alarms','READ',true)", org);
        sql.clearCache();
    }

    @Test void administratorUsesBothEntrancesButReceivesOnlyScopedManagementIdentity() throws Exception {
        String backendToken = login(admin, true);
        String frontendToken = login(admin, false);
        for (String token : new String[]{backendToken, frontendToken}) {
            String body = mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.user_type").value("FRONTEND"))
                    .andExpect(jsonPath("$.data.management_context.mode").value("UNIT"))
                    .andExpect(jsonPath("$.data.management_context.org_id").value(org))
                    .andExpect(jsonPath("$.data.management_context.menu_keys[0]").value("unit-users"))
                    .andExpect(jsonPath("$.data.data_scope").value("OWN_ORG"))
                    .andReturn().getResponse().getContentAsString();
            var permissions = json.readTree(body).path("data").path("permission_codes");
            assertThat(permissions.toString()).contains("\"alarms.read\"")
                    .doesNotContain("alarms.auth", "alarms.op", "alarm:verify", "unit-users", "users.auth", "disposal:direct");
        }
    }

    @Test void platformEndpointsRemainForbiddenWithAndWithoutBackendHeader() throws Exception {
        String token = login(admin, true);
        for (String endpoint : new String[]{"/users", "/roles", "/organizations", "/permissions/catalog"}) {
            mvc.perform(get("/api/v1" + endpoint).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1" + endpoint).header("Authorization", "Bearer " + token).header("X-Client-Type", "BACKEND"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void ordinaryMemberHasNoManagementIdentityAndCannotEnterBackend() throws Exception {
        String token = login(member, false);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.management_context.mode").value("NONE"))
                .andExpect(jsonPath("$.data.management_context.menu_keys").isEmpty());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token).header("X-Client-Type", "BACKEND"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test void revokedAppointmentCannotBeRecoveredWithAnOldSessionOrTheClientHeader() throws Exception {
        String token = login(admin, true);
        jdbc.update("UPDATE app_org_delegation SET administrator_user_id=null,version=version+1 WHERE org_id=?", org);
        sql.clearCache();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token).header("X-Client-Type", "BACKEND"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data_scope").value("OWN_ORG"))
                .andExpect(jsonPath("$.data.management_context.mode").value("NONE"));
    }

    @Test void disabledOrganizationCannotKeepItsManagementWorkspace() throws Exception {
        String token = login(admin, true);
        jdbc.update("UPDATE app_org SET enabled=false WHERE org_id=?", org);
        sql.clearCache();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token).header("X-Client-Type", "BACKEND"))
                .andExpect(status().isForbidden());
    }

    private String login(String account, boolean backend) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", account).put("password", PASSWORD)
                                .put("client_type", backend ? "BACKEND" : "FRONTEND").toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("session_id").asText();
    }
}
