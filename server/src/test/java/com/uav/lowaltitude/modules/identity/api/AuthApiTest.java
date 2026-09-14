package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
class AuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void loginAndMeReturnTheOnlySuperAdminWithServerGeneratedFullAccess() throws Exception {
        String token = loginToken();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account").value("admin1"))
                .andExpect(jsonPath("$.data.role_code").value("ROLE-ADMIN"))
                .andExpect(jsonPath("$.data.scope_mode").value("ALL"))
                .andExpect(jsonPath("$.data.menu_keys[?(@ == 'users')]").exists())
                .andExpect(jsonPath("$.data.menu_keys[?(@ == 'roles')]").exists())
                .andExpect(jsonPath("$.data.menu_keys[?(@ == 'archive')]").exists())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'users.auth')]").exists())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'countermeasure.auth')]").exists());
    }

    /**
     * 决策 16-4：`/auth/me` 追加动作码原文。此前只下发模块码，前端没法照它决定动作按钮的显隐，
     * 只能硬编码或等 403——用户点下去才知道自己没权限，这不是"提示"，是让人白点一次。
     */
    @Test
    void meAlsoCarriesActionCodesSoTheFrontEndCanGateButtons() throws Exception {
        String token = loginToken();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                // 动作码给**原文**，不套 .read/.op/.auth：动作本身就是一个动作，没有三级之分。
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'disposal:approve')]").exists())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'punishment:review')]").exists())
                // 模块码原样保留，别为了加动作码把既有的挤掉。
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'users.auth')]").exists());
    }

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongPasswordIsRejectedAndFailureIsPersisted() throws Exception {
        resetLoginState();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        assertThat(jdbc.queryForObject("select fail_count from app_user where account='admin1'", Integer.class))
                .isEqualTo(1);
        resetLoginState();
    }

    @Test
    void fifthFailureLocksAccount() throws Exception {
        resetLoginState();
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"account\":\"admin1\",\"password\":\"wrong\"}"))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
        } finally {
            resetLoginState();
        }
    }

    @Test
    void disabledAccountCannotLogin() throws Exception {
        jdbc.update("update app_user set status='DISABLED' where account='admin1'");
        try {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        } finally {
            jdbc.update("update app_user set status='ACTIVE' where account='admin1'");
        }
    }

    @Test
    void logoutAndExpiredSessionsCannotBeRestored() throws Exception {
        String token = loginToken();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());

        token = loginToken();
        jdbc.update("update app_session set expire_at=0 where session_id=?", token);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationAuditNeverStoresBearerSessionId() throws Exception {
        String token = loginToken();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where object_id=? or detail=? or detail like ?",
                Integer.class, token, token, "%" + token + "%")).isZero();
    }

    @Test
    void authenticatedSuperAdminCanReadBusinessData() throws Exception {
        String token = loginToken();
        mvc.perform(get("/api/v1/devices").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(get("/api/v1/alarms").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
    }

    private String loginToken() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        String token = root.path("data").path("session_id").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private void resetLoginState() {
        jdbc.update("update app_user set fail_count=0, locked_until=null, status='ACTIVE' where account='admin1'");
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
