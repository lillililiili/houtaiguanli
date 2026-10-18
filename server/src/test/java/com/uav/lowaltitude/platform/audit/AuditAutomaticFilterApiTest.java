package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 问题清单 #20：模拟器每秒的“接收规范化模拟观测”等系统自动记录默认不显示，打开“显示系统自动记录”或按动作明确筛选时照常显示；
 * 写入不变。结果筛选“失败”同时覆盖历史写法 FAILED，列表给出中文结果与对象类型、未知代码兜底为“其他操作”。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditAutomaticFilterApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    // 菜单由动作、对象类型推导，不能按模块码隔离；用远未来的独立时间窗隔离本用例的记录。
    final long base = 7_000_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) * 10_000L;
    final List<String> ids = new ArrayList<>();

    @AfterEach void cleanup() { for (String id : ids) jdbc.update("delete from audit_log where audit_id=?", id); }

    void insert(String account, String action, String objectType, String result, long at) {
        String id = UUID.randomUUID().toString();
        ids.add(id);
        jdbc.update("insert into audit_log(audit_id,account,role_code,module_code,action,object_type,object_id,detail,occurred_at,ip,result,user_agent)"
                + " values(?,?,'ROLE-ADMIN','test',?,?,?,'',?,'127.0.0.1',?,'test')", id, account, action, objectType, id, base + at, result);
    }

    JsonNode list(String token, String... params) throws Exception {
        var request = get("/api/v1/audit-logs").header("Authorization", "Bearer " + token).param("from", String.valueOf(base)).param("to", String.valueOf(base + 9_999)).param("size", "100");
        for (int i = 0; i < params.length; i += 2) request = request.param(params[i], params[i + 1]);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    static List<String> actions(JsonNode data) {
        List<String> values = new ArrayList<>();
        data.path("items").forEach(item -> values.add(item.path("action").asText()));
        return values;
    }

    @Test void automaticRecordsAreHiddenByDefaultButKeptAndReachable() throws Exception {
        String token = login();
        // 当前场景：模拟器以人工账号登录后每秒写观测；邻近场景：系统身份的自动核验、设备状态上报，以及人工操作和失败记录。
        for (int i = 0; i < 5; i++) insert("admin1", "local_normalized_observation", "device", "SUCCESS", 1000 + i);
        insert("SYSTEM", "device_maintenance_auto_check", "device_maintenance_task", "SUCCESS", 2000);
        insert("admin2", "local_qa_device_status", "device", "SUCCESS", 2001);
        insert("admin1", "role_permissions_updated", "role", "SUCCESS", 3000);
        insert("AUTO_SMS", "auto_sms_failed", "uav_event", "FAILED", 3001);
        insert("admin1", "brand_new_action", "brand_new_object", "SUCCESS", 3002);

        JsonNode defaults = list(token);
        assertThat(defaults.path("total").asLong()).isEqualTo(3);
        assertThat(actions(defaults)).containsExactlyInAnyOrder("role_permissions_updated", "auto_sms_failed", "brand_new_action");

        JsonNode all = list(token, "include_automatic", "true");
        assertThat(all.path("total").asLong()).isEqualTo(10);
        assertThat(list(token, "action", "local_normalized_observation").path("total").asLong()).as("按动作明确筛选照常显示").isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where occurred_at between ? and ?", Long.class, base, base + 9_999)).as("不删除").isEqualTo(10);

        JsonNode failed = list(token, "result", "FAILURE");
        assertThat(actions(failed)).containsExactly("auto_sms_failed");
        JsonNode item = failed.path("items").get(0);
        assertThat(item.path("result_name").asText()).isEqualTo("失败");
        assertThat(item.path("action_name").asText()).isEqualTo("自动短信发送失败");
        assertThat(item.path("object_type_name").asText()).isEqualTo("无人机事件");

        JsonNode unknown = list(token, "action", "brand_new_action").path("items").get(0);
        assertThat(unknown.path("action_name").asText()).isEqualTo("其他操作");
        assertThat(unknown.path("object_type_name").asText()).isEqualTo("其他对象");
        assertThat(unknown.path("action").asText()).as("原代码保留给详情").isEqualTo("brand_new_action");
        JsonNode automatic = list(token, "action", "device_maintenance_auto_check").path("items").get(0);
        assertThat(automatic.path("automatic").asBoolean()).isTrue();
        assertThat(automatic.path("action_name").asText()).isEqualTo("自动核验设备恢复情况");

        String csv = mvc.perform(get("/api/v1/audit-logs/export.csv").header("Authorization", "Bearer " + token).param("from", String.valueOf(base)).param("to", String.valueOf(base + 9_999)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).doesNotContain("接收规范化模拟观测").contains("失败").contains("其他操作");
        String fullCsv = mvc.perform(get("/api/v1/audit-logs/export.csv").header("Authorization", "Bearer " + token)
                .param("from", String.valueOf(base)).param("to", String.valueOf(base + 9_999)).param("include_automatic", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fullCsv).contains("接收规范化模拟观测").contains("自动核验设备恢复情况");
    }

    String login() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
}
