package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.testsupport.BackendAccountFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditPresentationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void legacyRowsUseTheSameMenuAndRoleProjectionForListDetailCountAndCsv() throws Exception {
        String token = BackendAccountFixture.session(jdbc, "ALL", null, null);
        String user = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, token);
        String account = jdbc.queryForObject("select account from app_user where user_id=?", String.class, user);
        String role = "R-" + UUID.randomUUID().toString().substring(0, 20);
        String roleName = "测试调测角色-" + role;
        jdbc.update("insert into app_role(role_code,name,created_at,updated_at) values(?,?,1,1)", role, roleName);
        String recorded = insert(user, account, role, "commission_start", "commission_task", "2001:db8::27");
        String missing = insert(user, account, null, "commission_configuration", "commission_task", null);
        insert(user, account, null, "device_maintenance_start", "device_maintenance_task", "192.0.2.25");

        JsonNode result = json.readTree(mvc.perform(get("/api/v1/audit-logs").header("Authorization", "Bearer " + token)
                        .param("account", account).param("module", "commissioning").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].module_name").value("设备接入调测"))
                .andReturn().getResponse().getContentAsString());
        assertThat(result.path("data").path("items").size()).isEqualTo(1);
        mvc.perform(get("/api/v1/audit-logs/" + recorded).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role_name").value(roleName))
                .andExpect(jsonPath("$.data.role_source").value("RECORDED"))
                .andExpect(jsonPath("$.data.module_code").value("system"))
                .andExpect(jsonPath("$.data.menu_code").value("commissioning"))
                .andExpect(jsonPath("$.data.ip").value("2001:db8::27"));
        mvc.perform(get("/api/v1/audit-logs/" + missing).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role_source").value("CURRENT"))
                .andExpect(jsonPath("$.data.role_name").value(org.hamcrest.Matchers.containsString("当前角色，历史未记录")));
        String csv = mvc.perform(get("/api/v1/audit-logs/export.csv").header("Authorization", "Bearer " + token)
                        .param("account", account).param("module", "commissioning"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains(roleName, "设备接入调测", "2001:db8::27", "当前角色，历史未记录", "未记录")
                .doesNotContain("设备异常处理");
        assertThat(jdbc.queryForObject("select role_code from audit_log where audit_id=?", String.class, missing)).isNull();
        assertThat(jdbc.queryForObject("select ip from audit_log where audit_id=?", String.class, missing)).isNull();
        assertThat(jdbc.queryForObject("select module_code from audit_log where audit_id=?", String.class, recorded)).isEqualTo("system");
    }

    @Test void missingHistoricalUserIsNotMatchedByReusedAccountOrViewerIdentity() throws Exception {
        String token = BackendAccountFixture.session(jdbc, "ALL", null, null);
        String id = insert(null, "admin1", null, "device_update", "device", null);
        mvc.perform(get("/api/v1/audit-logs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role_source").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.role_name").value("未记录"));
    }

    @Test void failedRequestsUseMenuAndKeepDeletedRoleCode() throws Exception {
        String token = BackendAccountFixture.session(jdbc, "ALL", null, null);
        String id = UUID.randomUUID().toString();
        String path = "/api/v1/commission-tasks/" + UUID.randomUUID() + "/start";
        String role = "R-" + UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("insert into audit_log(audit_id,account,role_code,module_code,action,object_type,object_id,occurred_at,ip,result) values(?,'old-user',?,'devices',?,'request',?,1,'::1','FAILURE')",
                id, role, "POST " + path, path);
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", "Bearer " + token)
                        .param("module", "commissioning").param("objectId", path).param("result", "FAILURE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].role_name").value(role))
                .andExpect(jsonPath("$.data.items[0].role_source").value("RECORDED"))
                .andExpect(jsonPath("$.data.items[0].module_name").value("设备接入调测"))
                .andExpect(jsonPath("$.data.items[0].ip").value("::1"));
    }

    @Test void actualRequestUsingShortAuditOverloadStoresRoleAndIp() throws Exception {
        String token = BackendAccountFixture.session(jdbc, "ALL", null, null);
        String code = "audit-" + UUID.randomUUID().toString().substring(0, 12);
        mvc.perform(post("/api/v1/integration-sources").header("Authorization", "Bearer " + token)
                        .header("User-Agent", "audit-integration-client").header("X-Forwarded-For", "203.0.113.99")
                        .with(request -> { request.setRemoteAddr("192.0.2.39"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"source_code":"%s","name":"审计回归来源","protocol_code":"RADAR_TCP_V3_0_0",
                                "protocol_version":"3.0.0","allowed_cidrs":"192.0.2.0/24"}
                                """.formatted(code)))
                .andExpect(status().isOk());
        String user = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, token);
        var row = jdbc.queryForMap("select role_code,module_code,ip,user_agent from audit_log where user_id=? and action='integration_source_create'", user);
        assertThat(row).containsEntry("role_code", "ROLE-BACKEND").containsEntry("module_code", "interfaces")
                .containsEntry("ip", "192.0.2.39").containsEntry("user_agent", "audit-integration-client");
    }

    private String insert(String user, String account, String role, String action, String object, String ip) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into audit_log(audit_id,user_id,account,role_code,module_code,action,object_type,object_id,occurred_at,ip,result) values(?,?,?,?,'system',?,?,?,? ,?,'SUCCESS')",
                id, user, account, role, action, object, UUID.randomUUID().toString(), System.currentTimeMillis(), ip);
        return id;
    }
}
