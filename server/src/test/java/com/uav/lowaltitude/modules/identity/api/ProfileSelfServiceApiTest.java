package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ZT-28：本人改姓名电话、主动改密。当前密码输错是表单错误（400），不能用 401 把人踢回登录页；
 * 输错次数与登录共用计数，失败记录在独立事务里落库。不加 @Transactional：要验证业务回滚后计数和审计仍在。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileSelfServiceApiTest {

    private static final String TEMP_PASSWORD = "TempUser#2026A";
    private static final String PASSWORD = "Profile#2026Self";
    private static final String NEW_PASSWORD = "Profile#2026Next";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private String account, userId, role;

    @BeforeEach
    void createAccount() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        account = "itest-self-" + suffix;
        role = "ROLE-SELF-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)",
                role, "集成测试角色-本人资料-" + suffix);
        String admin = login("admin1", "changeme");
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        userId = data(mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "self-create-" + suffix).contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", account).put("name", "改资料前")
                                .put("phone", "13800000001").put("org_id", orgId).put("role_code", role)
                                .put("temporary_password", TEMP_PASSWORD).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("user_id").asText();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from app_session where user_id=?", userId);
        jdbc.update("delete from app_user_data_scope where user_id=?", userId);
        jdbc.update("delete from app_user where user_id=?", userId);
        jdbc.update("delete from app_role_permission where role_code=?", role);
        jdbc.update("delete from app_role where role_code=?", role);
    }

    @Test
    void userEditsOwnNameAndPhoneAndStaysLoggedIn() throws Exception {
        String token = firstLogin();
        JsonNode me = data(mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data_scope").value("OWN_ORG"))
                .andReturn().getResponse().getContentAsString());
        int version = me.path("version").asInt();

        mvc.perform(patch("/api/v1/auth/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", " 改资料后 ").put("phone", "0546-1234567")
                                .put("expected_version", version).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("改资料后"))
                .andExpect(jsonPath("$.data.phone").value("0546-1234567"));
        // 会话不受影响，刷新后仍是新值；所属单位、角色这些本人改不了的字段原样。
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("改资料后"))
                .andExpect(jsonPath("$.data.role_code").value(role));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='profile_updated' and object_id=? and result='SUCCESS'",
                Integer.class, userId)).isEqualTo(1);

        mvc.perform(patch("/api/v1/auth/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", "旧版本").put("expected_version", version).toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(patch("/api/v1/auth/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", "电话不对").put("phone", "call me")
                                .put("expected_version", version + 1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(patch("/api/v1/auth/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", "  ").put("expected_version", version + 1).toString()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select name from app_user where user_id=?", String.class, userId)).isEqualTo("改资料后");
    }

    @Test
    void temporaryPasswordHolderCannotEditProfileBeforeChangingPassword() throws Exception {
        String token = login(account, TEMP_PASSWORD);
        mvc.perform(patch("/api/v1/auth/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", "未改密").put("expected_version", 0).toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void wrongCurrentPasswordIsAFormErrorCountedLikeALoginFailure() throws Exception {
        String token = firstLogin();
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(change("wrong-" + PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INCORRECT"));
        // 仍是登录状态，失败计数和审计在业务回滚后依旧落库。
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select fail_count from app_user where user_id=?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='password_change_failed' and object_id=? and result='FAILURE'",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where detail like ?", Integer.class,
                "%" + PASSWORD + "%")).isZero();

        // 正确改密：成功后旧会话作废，用新密码重新登录，计数清零。
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(change(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        login(account, NEW_PASSWORD);
        assertThat(jdbc.queryForObject("select fail_count from app_user where user_id=?", Integer.class, userId)).isZero();
    }

    @Test
    void repeatedWrongCurrentPasswordLocksWithoutEndingTheSession() throws Exception {
        String token = firstLogin();
        for (int attempt = 1; attempt < 5; attempt++) {
            mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(change("wrong-" + attempt, NEW_PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INCORRECT"));
        }
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(change("wrong-5", NEW_PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_ATTEMPTS_LOCKED"));
        // 锁定期间正确的当前密码也不放行，重新登录同样被锁；当前会话不被踢掉。
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(change(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", account).put("password", PASSWORD).toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    /** 临时密码首次登录后改成 PASSWORD，再用它登录拿到正常会话。 */
    private String firstLogin() throws Exception {
        String first = login(account, TEMP_PASSWORD);
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON).content(change(TEMP_PASSWORD, PASSWORD)))
                .andExpect(status().isOk());
        return login(account, PASSWORD);
    }

    private String change(String current, String next) {
        return json.createObjectNode().put("current_password", current).put("new_password", next).toString();
    }

    private String login(String loginAccount, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", loginAccount).put("password", password).toString()))
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
