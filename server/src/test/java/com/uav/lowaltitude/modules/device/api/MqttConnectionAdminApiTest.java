package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空库准备：接口配置页只凭 interfaces 权限就能选单位与区域，建好并启用给设备模拟器用的回放连接。
 * test profile 属于显式测试环境，回放与回环地址可用；正式环境只给真实来源，见 MqttConnectionCapabilitiesTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MqttConnectionAdminApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void capabilitiesOfferReplayInTestEnvironment() throws Exception {
        mvc.perform(get("/api/v1/mqtt-brokers/capabilities").header("Authorization", user("interfaces", "READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_modes.length()").value(2))
                .andExpect(jsonPath("$.data.source_modes[0]").value("live"))
                .andExpect(jsonPath("$.data.source_modes[1]").value("replay"))
                .andExpect(jsonPath("$.data.simulation_allowed").value(true));
    }

    @Test void interfaceOperatorPreparesReplayConnectionWithoutDevicePermission() throws Exception {
        String token = user("interfaces", "OP");
        JsonNode scope = data(mvc.perform(get("/api/v1/mqtt-brokers/scopes").header("Authorization", token)).andExpect(status().isOk())).get(0);
        String name = "itest-replay-" + UUID.randomUUID().toString().substring(0, 8), key = "mqtt-create-" + name;
        ObjectNode body = mapper.createObjectNode().put("name", name).put("host", "127.0.0.1").put("port", 1883).put("tls", false)
                .put("allowed_cidrs", "127.0.0.1/32").put("source_mode", "replay")
                .put("owner_org_id", scope.path("org_id").asText()).put("district_id", scope.path("district_id").asText());
        JsonNode created = data(mvc.perform(post("/api/v1/mqtt-brokers").header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_mode").value("replay"))
                .andExpect(jsonPath("$.data.enabled").value(false)));
        String id = created.path("broker_id").asText();
        mvc.perform(post("/api/v1/mqtt-brokers").header("Authorization", token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        mvc.perform(patch("/api/v1/mqtt-brokers/" + id + "/enabled").header("Authorization", token).header("Idempotency-Key", "mqtt-enable-" + name)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"version\":" + created.path("version").asLong() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(get("/api/v1/mqtt-brokers").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.broker_id == '" + id + "')].enabled").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mqtt_broker WHERE name=?", Integer.class, name)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE object_id=? ORDER BY action", String.class, id))
                .containsExactly("mqtt_broker_create", "mqtt_broker_enable");
    }

    @Test void scopeOptionsAndConnectionWritesStillNeedOperatePermission() throws Exception {
        String deviceReader = user("devices", "READ");
        mvc.perform(get("/api/v1/mqtt-brokers/scopes").header("Authorization", deviceReader)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/mqtt-brokers/capabilities").header("Authorization", deviceReader)).andExpect(status().isForbidden());
        String interfaceReader = user("interfaces", "READ");
        mvc.perform(get("/api/v1/mqtt-brokers/scopes").header("Authorization", interfaceReader)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/mqtt-brokers").header("Authorization", interfaceReader).header("Idempotency-Key", "mqtt-create-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"不应创建\",\"source_mode\":\"replay\"}"))
                .andExpect(status().isForbidden());
    }

    private String user(String module, String level) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-MQTT-TEST-" + suffix;
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,'',FALSE,TRUE,0,0,0,FALSE)", role, role);
        jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) VALUES (?,?,?,TRUE,CURRENT_TIMESTAMP)", role, module, level);
        jdbc.update("INSERT INTO app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)",
                user, "mqtt-test-" + suffix, "连接测试", role);
        jdbc.update("INSERT INTO app_session (session_id,user_id,expire_at,ip,permission_version) VALUES (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return "Bearer " + token;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
}
