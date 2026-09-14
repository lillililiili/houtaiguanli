package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.platform.worker.OutboxWorker;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeviceIncidentLifecycleApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker outboxWorker;

    @Test
    void mockIncidentRebootThenRecoveryPassClosesTheIncident() throws Exception {
        String ops = login("admin1");
        String deviceId = deviceId("DEV-MOCK-004", ops);
        String incidentId = pendingIncident(deviceId);
        String key = "inc-reboot-" + UUID.randomUUID();

        String accepted = mvc.perform(post("/api/v1/device-incidents/{id}/reboot", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"工作台回归模拟重启\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andReturn().getResponse().getContentAsString();
        String commandId = objectMapper.readTree(accepted).path("data").path("command_id").asText();
        mvc.perform(get("/api/v1/device-incidents/{id}", incidentId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident.stage").value("PROCESSING"))
                .andExpect(jsonPath("$.data.incident.reboot_command_id").value(commandId))
                .andExpect(jsonPath("$.data.allowed_actions").isEmpty());

        mvc.perform(post("/api/v1/device-incidents/{id}/reboot", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"工作台回归模拟重启\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.command_id").value(commandId));
        mvc.perform(post("/api/v1/device-incidents/{id}/reboot", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不同原因\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/v1/device-incidents/{id}/reboot", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", "inc-reboot-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"重复下发应拒绝\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));

        makeDeviceOutboxDue();
        outboxWorker.poll();
        mvc.perform(get("/api/v1/device-commands/{id}", commandId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        mvc.perform(get("/api/v1/device-incidents/{id}", incidentId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident.stage").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.data.incident.closed_at").doesNotExist())
                .andExpect(jsonPath("$.data.allowed_actions[0]").value("VERIFY_RECOVERY"));

        String checkKey = "inc-check-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/device-incidents/{id}/recovery-checks", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", checkKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PASS"))
                .andExpect(jsonPath("$.data.incident.stage").value("RECOVERED"));
        mvc.perform(post("/api/v1/device-incidents/{id}/recovery-checks", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", checkKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PASS"));
        assertThat(jdbc.queryForObject("select stage from device_incident where incident_id=?", String.class, incidentId))
                .isEqualTo("RECOVERED");
        assertThat(jdbc.queryForObject("select closed_at from device_incident where incident_id=?", Long.class, incidentId))
                .isNotNull();
    }

    @Test
    void recoveryFailsWhenSnapshotUnhealthyAndTimeoutReturnsToPending() throws Exception {
        String ops = login("admin1");
        String deviceId = deviceId("DEV-MOCK-012", ops);
        String incidentId = pendingIncident(deviceId);
        String key = "inc-timeout-" + UUID.randomUUID();
        String accepted = mvc.perform(post("/api/v1/device-incidents/{id}/reboot", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验证超时回到待处理\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String commandId = objectMapper.readTree(accepted).path("data").path("command_id").asText();
        jdbc.update("update device_command set deadline_at=0 where command_id=?", commandId);
        makeDeviceOutboxDue();
        outboxWorker.poll();
        mvc.perform(get("/api/v1/device-incidents/{id}", incidentId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident.stage").value("PENDING"));
        mvc.perform(post("/api/v1/device-incidents/{id}/recovery-checks", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", "inc-check-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void recoveryCheckFailsWithoutClosingWhenDeviceStillUnhealthy() throws Exception {
        String ops = login("admin1");
        String deviceId = deviceId("DEV-MOCK-004", ops);
        String incidentId = pendingIncident(deviceId);
        String accepted = mvc.perform(post("/api/v1/device-incidents/{id}/reboot", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", "inc-fail-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"校验失败保持待验证\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        makeDeviceOutboxDue();
        outboxWorker.poll();
        jdbc.update("update ops_device_state set connectivity='ABNORMAL', has_alarm=TRUE, health_code='BAD' where device_id=?", deviceId);
        mvc.perform(post("/api/v1/device-incidents/{id}/recovery-checks", incidentId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", "inc-fail-check-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FAIL"))
                .andExpect(jsonPath("$.data.incident.stage").value("PENDING_VERIFICATION"));
        assertThat(jdbc.queryForObject("select closed_at from device_incident where incident_id=?", Long.class, incidentId))
                .isNull();
    }

    @Test
    void abnormalDeviceAndRadarProtocolRejectRebootWithoutChangingStage() throws Exception {
        String ops = login("admin1");
        String abnormal = pendingIncident(deviceId("DEV-MOCK-003", ops));
        mvc.perform(post("/api/v1/device-incidents/{id}/reboot", abnormal)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", "inc-off-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"离线异常不可重启\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEVICE_NOT_OPERABLE"));
        assertThat(jdbc.queryForObject("select stage from device_incident where incident_id=?", String.class, abnormal))
                .isEqualTo("PENDING");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode source = objectMapper.readTree(mvc.perform(post("/api/v1/integration-sources")
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source_code\":\"RAD-" + suffix + "\",\"name\":\"雷达协议异常\",\"protocol_code\":\"RADAR_TCP_V3_0_0\",\"protocol_version\":\"3.0.0\",\"allowed_cidrs\":\"192.0.2.0/24\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode device = objectMapper.readTree(mvc.perform(post("/api/v1/devices")
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source_id\":\"" + source.path("source_id").asText() + "\",\"external_device_id\":\"T02-" + suffix
                                + "\",\"device_no\":\"RAD-INC-" + suffix + "\",\"name\":\"协议未开放重启\",\"device_type_code\":\"radar\",\"device_type_name\":\"雷达\",\"channel\":\"雷达直连\",\"model\":\"T02\",\"connection\":{\"transport\":\"TCP\",\"host\":\"192.0.2.88\",\"port\":5001,\"timeout_millis\":1000}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String radarId = device.path("device").path("device_id").asText();
        jdbc.update("INSERT INTO ops_device_state (device_id,connectivity,has_alarm,health_code,received_at,last_heartbeat_at,simulated,version) VALUES (?,'ONLINE',TRUE,'BAD',1,1,FALSE,0)", radarId);
        String radarIncident = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) VALUES (?,?,?,'LINK_DEGRADED','HIGH','PENDING',1,'雷达协议异常',false)",
                radarIncident, radarId, "INC-" + suffix);
        mvc.perform(post("/api/v1/device-incidents/{id}/reboot", radarIncident)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", "inc-radar-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"协议未声明重启\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEVICE_NOT_OPERABLE"));
        assertThat(jdbc.queryForObject("select stage from device_incident where incident_id=?", String.class, radarIncident))
                .isEqualTo("PENDING");
    }

    @Test
    void monitoringReadCannotRebootIncident() throws Exception {
        String ops = login("admin1");
        String incidentId = pendingIncident(deviceId("DEV-MOCK-004", ops));
        String reader = readerWithMonitoringRead();
        mvc.perform(get("/api/v1/device-incidents/{id}", incidentId).header("Authorization", bearer(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed_actions").isEmpty());
        mvc.perform(post("/api/v1/device-incidents/{id}/reboot", incidentId)
                        .header("Authorization", bearer(reader)).header("Idempotency-Key", "inc-denied-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"无操作权限\"}"))
                .andExpect(status().isForbidden());
    }

    private String pendingIncident(String deviceId) {
        String existing = jdbc.query("select incident_id from device_incident where device_id=? and stage='PENDING'",
                rs -> rs.next() ? rs.getString(1) : null, deviceId);
        if (existing != null) return existing;
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) VALUES (?,?,?,'LINK_DEGRADED','MEDIUM','PENDING',1,'测试异常',true)",
                id, deviceId, "INC-" + id.substring(0, 8));
        return id;
    }

    private String readerWithMonitoringRead() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String role = "ROLE-INC-" + id;
        String user = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", user, "inc-" + id, "只读监测", role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'monitoring','READ',true,current_timestamp)", role);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private void makeDeviceOutboxDue() {
        jdbc.update("update outbox_event set available_at=0 where processed_at is null and topic in ('device.reboot','commission.connect','commission.run')");
    }

    private String deviceId(String deviceNo, String token) throws Exception {
        String body = mvc.perform(get("/api/v1/devices?keyword=" + deviceNo).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("items").get(0).path("device_id").asText();
    }

    private String login(String account) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("session_id").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
