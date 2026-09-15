package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class DeviceOperationsApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker outboxWorker;

    @Test
    void monitorInformationUsesMonitoringPermissionAndRedactsConnectionForReadOnlyUsers() throws Exception {
        String token = login("admin1");
        String id = getJson("/api/v1/devices", token).path("data").path("items").get(0).path("device_id").asText();
        String url = "/api/v1/device-monitor/devices/" + id + "/information";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        JsonNode monitor = getJson(url, token).path("data");
        JsonNode commission = getJson("/api/v1/commission-tasks/device-information/" + id, token).path("data");
        assertThat(monitor.path("sections")).isEqualTo(commission.path("sections"));
        assertThat(monitor.path("device_id").asText()).isEqualTo(id);
        assertThat(monitor.path("generated_at").asLong()).isPositive();
        mvc.perform(get("/api/v1/device-monitor/devices/missing/information").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());

        jdbc.update("INSERT INTO app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES ('ROLE-MONITOR-TEST','监测只读测试','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,menu_enabled) VALUES ('ROLE-MONITOR-TEST','monitoring','READ',TRUE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-MONITOR-TEST',scope_mode='ALL' WHERE account='admin1'");
        JsonNode readOnly = getJson(url, token).path("data");
        JsonNode connection = java.util.stream.StreamSupport.stream(readOnly.path("sections").spliterator(), false)
                .filter(section -> section.path("code").asText().equals("connection")).findFirst().orElseThrow();
        for (JsonNode field : connection.path("fields")) {
            assertThat(field.path("status").asText()).isEqualTo("REDACTED");
            assertThat(field.path("value").isNull() || field.path("value").isMissingNode()).isTrue();
        }
        mvc.perform(get("/api/v1/commission-tasks/device-information/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE account='admin1'");
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=(SELECT user_id FROM app_user WHERE account='admin1')");
        mvc.perform(get(url).header("Authorization", bearer(token))).andExpect(status().isNotFound());
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        mvc.perform(get(url).header("Authorization", bearer(token))).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'");
        jdbc.update("DELETE FROM app_role_permission WHERE role_code='ROLE-MONITOR-TEST'");
        mvc.perform(get(url).header("Authorization", bearer(token))).andExpect(status().isForbidden());
    }

    @Test
    void deleteRequiresDisabledDeviceAndVersionAndPreservesHistory() throws Exception {
        String token = login("admin1");
        JsonNode created = objectMapper.readTree(mvc.perform(post("/api/v1/devices")
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(deviceBody("DELETE-" + UUID.randomUUID(), "待删除设备")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("device");
        String id = created.path("device_id").asText();
        long version = created.path("version").asLong();
        String body = "{\"version\":%d,\"reason\":\"设备退役\"}";
        mvc.perform(delete("/api/v1/devices/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(version))).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/devices/{id}", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(version)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEVICE_ENABLED"));
        mvc.perform(patch("/api/v1/devices/{id}/enabled", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"version\":" + version + ",\"reason\":\"设备退役\"}"))
                .andExpect(status().isOk());
        long before = getJson("/api/v1/devices", token).path("data").path("total").asLong();
        mvc.perform(delete("/api/v1/devices/{id}", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(version)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(delete("/api/v1/devices/{id}", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(version + 1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(delete("/api/v1/devices/{id}", id).header("Authorization", bearer(token))
                .header("Idempotency-Key", "device-delete-test-key")
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(version + 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.device_id").value(id));
        for (String url : new String[]{"/api/v1/devices", "/api/v1/device-monitor/tree", "/api/v1/device-monitor/overview"})
            assertThat(getJson(url, token).path("data").path("total").asLong()).isEqualTo(before - 1);
        mvc.perform(get("/api/v1/devices/{id}", id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/device-monitor/devices/{id}/information", id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/devices/{id}", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body.formatted(version + 1)))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/devices/{id}/enabled", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":" + (version + 2) + ",\"reason\":\"不应恢复\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_id=? AND deleted_at IS NOT NULL AND enabled=FALSE", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_event_log WHERE device_id=?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE object_id=? AND action='device_delete'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void catalogOverviewTreeAndSensitiveVisibilityUseOneSourceOfTruth() throws Exception {
        String duty = login("admin1");
        String ops = duty;

        JsonNode page = getJson("/api/v1/devices?size=100", duty).path("data");
        JsonNode overview = getJson("/api/v1/device-monitor/overview", duty).path("data");
        JsonNode tree = getJson("/api/v1/device-monitor/tree", duty).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(12);
        assertThat(overview.path("total").asLong()).isEqualTo(page.path("total").asLong());
        assertThat(tree.path("total").asLong()).isEqualTo(page.path("total").asLong());
        assertThat(overview.path("online").asLong() + overview.path("offline").asLong()
                + overview.path("abnormal").asLong() + overview.path("unknown").asLong()).isEqualTo(12);

        String deviceId = page.path("items").get(0).path("device_id").asText();
        mvc.perform(get("/api/v1/devices/{id}", deviceId).header("Authorization", bearer(duty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection_visible").value(true))
                .andExpect(jsonPath("$.data.connection.host").isNotEmpty());
        mvc.perform(get("/api/v1/devices/{id}", deviceId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection_visible").value(true))
                .andExpect(jsonPath("$.data.connection.host").isNotEmpty());
    }

    @Test
    void catalogWritesEnforcePermissionUniquenessAndOptimisticLock() throws Exception {
        String ops = login("admin1");
        String no = "DEV-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String body = deviceBody(no, "回归测试设备");

        String createdBody = mvc.perform(post("/api/v1/devices").header("Authorization", bearer(ops))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device.device_no").value(no))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(createdBody).path("data");
        String id = created.path("device").path("device_id").asText();
        long version = created.path("device").path("version").asLong();
        JsonNode listed = getJson("/api/v1/devices?keyword=" + no, ops).path("data").path("items").get(0);
        assertThat(listed.path("longitude").decimalValue()).isEqualByComparingTo("118.67");
        assertThat(listed.path("latitude").decimalValue()).isEqualByComparingTo("37.43");

        mvc.perform(post("/api/v1/devices").header("Authorization", bearer(ops))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEVICE_NO_CONFLICT"));

        String disabledBody = mvc.perform(patch("/api/v1/devices/{id}/enabled", id)
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"version\":" + version + ",\"reason\":\"回归验证停用审计\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device.enabled").value(false))
                .andReturn().getResponse().getContentAsString();
        long nextVersion = objectMapper.readTree(disabledBody).path("data").path("device").path("version").asLong();
        assertThat(nextVersion).isEqualTo(version + 1);

        mvc.perform(patch("/api/v1/devices/{id}/enabled", id)
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"version\":" + version + ",\"reason\":\"使用旧版本冲突\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void rebootIsIdempotentAndCompletesFromPersistentOutbox() throws Exception {
        String ops = login("admin1");
        String deviceId = deviceId("DEV-MOCK-001", ops);
        String key = "reboot-test-" + UUID.randomUUID();
        String request = "{\"reason\":\"回归测试模拟重启\"}";

        String accepted = mvc.perform(post("/api/v1/devices/{id}/commands/reboot", deviceId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andReturn().getResponse().getContentAsString();
        String commandId = objectMapper.readTree(accepted).path("data").path("command_id").asText();

        mvc.perform(post("/api/v1/devices/{id}/commands/reboot", deviceId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.command_id").value(commandId));
        mvc.perform(post("/api/v1/devices/{id}/commands/reboot", deviceId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不同请求\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

        makeDeviceOutboxDue();
        outboxWorker.poll();
        mvc.perform(get("/api/v1/device-commands/{id}", commandId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result_code").value("MOCK_REBOOTED"));
        assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=?", Long.class, commandId))
                .isEqualTo(1L);
    }

    @Test
    void overdueRebootTimesOutWithoutInventingAReceipt() throws Exception {
        String ops = login("admin1");
        String deviceId = deviceId("DEV-MOCK-001", ops);
        String body = mvc.perform(post("/api/v1/devices/{id}/commands/reboot", deviceId)
                        .header("Authorization", bearer(ops)).header("Idempotency-Key", "timeout-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验证持久化超时扫描\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String commandId = objectMapper.readTree(body).path("data").path("command_id").asText();
        jdbc.update("update device_command set deadline_at=0 where command_id=?", commandId);
        makeDeviceOutboxDue();
        outboxWorker.poll();

        mvc.perform(get("/api/v1/device-commands/{id}", commandId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TIMED_OUT"))
                .andExpect(jsonPath("$.data.result_code").value("ADAPTER_TIMEOUT"))
                .andExpect(jsonPath("$.data.receipts").isEmpty());
    }

    @Test
    void commissioningStateMachinePersistsEventsAndSimulationReport() throws Exception {
        String ops = login("admin1");
        String deviceId = deviceId("DEV-MOCK-002", ops);
        String createdBody = mvc.perform(post("/api/v1/commission-tasks").header("Authorization", bearer(ops))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\"" + deviceId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode task = objectMapper.readTree(createdBody).path("data");
        String taskId = task.path("commission_id").asText();

        mvc.perform(post("/api/v1/commission-tasks/{id}/start", taskId)
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + task.path("version").asLong() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE"));

        String connectingBody = mvc.perform(post("/api/v1/commission-tasks/{id}/connect", taskId)
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + task.path("version").asLong() + "}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("CONNECTING"))
                .andReturn().getResponse().getContentAsString();
        makeDeviceOutboxDue();
        outboxWorker.poll();
        task = getJson("/api/v1/commission-tasks/" + taskId, ops).path("data");
        assertThat(task.path("status").asText()).isEqualTo("CONNECTED");

        String config = """
                {"version":%d,"transport":"TCP","host":"192.0.2.80","port":9001,
                 "data_format":"JSON","charset_name":"UTF-8","auth_mode":"Token",
                 "heartbeat_interval_seconds":30,"report_interval_millis":1000,"timeout_millis":3000,
                 "retry_count":3,"time_sync_mode":"NTP","time_server":"time.example.invalid",
                 "timezone_name":"Asia/Shanghai","time_sync_interval_seconds":60}
                """.formatted(task.path("version").asLong());
        String readyBody = mvc.perform(put("/api/v1/commission-tasks/{id}/configuration", taskId)
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON).content(config))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        long readyVersion = objectMapper.readTree(readyBody).path("data").path("version").asLong();

        mvc.perform(post("/api/v1/commission-tasks/{id}/start", taskId)
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + readyVersion + "}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("RUNNING"));
        makeDeviceOutboxDue();
        outboxWorker.poll();
        mvc.perform(get("/api/v1/commission-tasks/{id}/report", taskId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PASSED"))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andExpect(jsonPath("$.data.warning").isNotEmpty())
                .andExpect(jsonPath("$.data.results.items").isArray());
        mvc.perform(get("/api/v1/commission-tasks/{id}/events", taskId).header("Authorization", bearer(ops)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String deviceId(String deviceNo, String token) throws Exception {
        return getJson("/api/v1/devices?keyword=" + deviceNo, token).path("data").path("items").get(0)
                .path("device_id").asText();
    }

    private void makeDeviceOutboxDue() {
        jdbc.update("update outbox_event set available_at=0 where processed_at is null and topic in ('device.reboot','commission.connect','commission.run','eo.track.begin','eo.track.end','eo.camera.status')");
    }

    private String login(String account) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).path("data").path("session_id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String deviceBody(String deviceNo, String name) {
        return """
                {"device_no":"%s","name":"%s","device_type_code":"radar","device_type_name":"雷达",
                 "channel":"融合感知管","model":"TEST-MODEL","vendor":"回归厂商","owner_name":"测试单位",
                 "region_name":"东营区","address":"回归测试点","longitude":118.67,"latitude":37.43,
                 "coordinate_system":"WGS-84","altitude_m":12,"altitude_datum":"AMSL",
                 "firmware_version":"test-1.0","connection":{"transport":"TCP","host":"192.0.2.90",
                 "port":9001,"data_format":"JSON","charset_name":"UTF-8","auth_mode":"Token",
                 "heartbeat_interval_seconds":30,"report_interval_millis":1000,"timeout_millis":3000,
                 "retry_count":3,"time_sync_mode":"NTP","timezone_name":"Asia/Shanghai"}}
                """.formatted(deviceNo, name);
    }
}
