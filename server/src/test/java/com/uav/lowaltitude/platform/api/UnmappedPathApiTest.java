package com.uav.lowaltitude.platform.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 决策 10-3：未映射路径返回 404 `NOT_FOUND`，不回显路径。
 *
 * 阶段 9 审查发现：已登录后访问不存在的路径会落到 {@code GlobalExceptionHandler} 的兜底分支，
 * 变成 500 `INTERNAL_ERROR`——把"你打错地址"报成"服务坏了"，既误导前端重试，也污染错误告警。
 * 这里钉住四件事：鉴权仍在路由之前（未登录先 401）；已登录的未映射路径是 404 包络；
 * 已映射路径不受影响；`/api/` 之外（不过滤器的路径）同样是 404 包络而不是 Spring 的白页。
 * 响应不带路径回显：探测者拿不到"哪段路径存在/不存在"的额外线索。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UnmappedPathApiTest {

    private static final String UNMAPPED = "/api/v1/no-such-path-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String sessionId;

    @BeforeEach
    void seedSession() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = "ROLE-404-" + suffix;
        String userId = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)
                values (?,?,'',false,true,0,0,0,false)
                """, role, "Unmapped path reader " + suffix);
        // 已映射路径的对照组要真的能过授权，否则 403 也会被误读成"未受影响"。
        jdbc.update("""
                insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)
                values (?,'target:read','READ',false,current_timestamp)
                """, role);
        jdbc.update("""
                insert into app_user
                    (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,
                     permission_version,created_at,updated_at,version)
                values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)
                """, userId, "unmapped-" + suffix, "Unmapped path reader", role);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                sessionId, userId, System.currentTimeMillis() + 3_600_000L);
    }

    @Test
    void unauthenticatedUnmappedPathIsStill401() throws Exception {
        // 鉴权在路由之前：不能让未登录者靠 401/404 的差别探测哪些路径存在。
        mvc.perform(get(UNMAPPED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get(UNMAPPED).header("Authorization", "Bearer " + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void authenticatedUnmappedPathIs404EnvelopeWithoutEchoingPath() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get(UNMAPPED), post(UNMAPPED), get(UNMAPPED + "/child/" + UUID.randomUUID()) }) {
            mvc.perform(request.header("Authorization", "Bearer " + sessionId))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.error.message").value("资源不存在"))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(content().string(not(containsString("no-such-path"))));
        }
    }

    @Test
    void mappedPathIsUnaffected() throws Exception {
        mvc.perform(get("/api/v1/targets").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.items").isArray());
        // 已映射路径下的业务 404（对象不存在）仍是各模块自己的错误码，不被平台级 NOT_FOUND 吞掉。
        mvc.perform(get("/api/v1/targets/" + UUID.randomUUID()).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("TARGET_NOT_FOUND"));
    }

    /**
     * 决策 12-6：已映射路径用错 HTTP 方法是 405 `METHOD_NOT_ALLOWED`，不是兜底的 500。
     * 与 404 同因：把"客户端打错了"报成"服务坏了"，前端会照 500 重试，错误告警也被污染。
     * `Allow` 是 HTTP 规范要求的必要信息（告诉客户端该用哪个方法），但响应体仍不回显路径。
     */
    @Test
    void wrongMethodOnMappedPathIs405WithAllowHeader() throws Exception {
        mvc.perform(post("/api/v1/targets").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.message").value("请求方法不支持"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(not(containsString("/api/v1/targets"))));
    }

    /** 鉴权仍在路由之前：未登录时错方法也只能得到 401，405 不该泄露"这条路由存在"。 */
    @Test
    void wrongMethodWithoutSessionIsStill401() throws Exception {
        mvc.perform(post("/api/v1/targets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void pathOutsideApiPrefixIs404EnvelopeToo() throws Exception {
        // `/api/` 之外不经过 BearerAuthFilter，也不托管前端静态资源（Vite 代理）：同一包络，不是 Spring 默认错误页。
        mvc.perform(get("/no-such-path-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("资源不存在"))
                .andExpect(content().string(not(containsString("no-such-path"))));
    }
}
