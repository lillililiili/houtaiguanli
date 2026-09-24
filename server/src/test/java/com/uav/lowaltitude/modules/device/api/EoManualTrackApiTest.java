package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mqtt_eo_manual;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false",
        "app.eo-edge.auto-track.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EoManualTrackApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MqttConfigurationService configuration;
    @Autowired EoEdgeRepository edges;
    @Autowired MqttRepository mqtt;
    @Autowired AppClock clock;
    String org, district, token, owner, brokerId;
    Binding binding;

    @BeforeEach void setup() throws Exception {
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "EO manual", "ROLE-ADMIN", 1, false, "ALL"));
        org = jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY", String.class);
        district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY", String.class);
        token = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        owner = UUID.randomUUID().toString();
        brokerId = configuration.create(new BrokerInput("EO manual", "127.0.0.1", 1883, false, null, null,
                "127.0.0.1/32", "replay", org, district, null), key()).brokerId();
        configuration.enable(brokerId, 0, true, key());
        assertThat(mqtt.claim(brokerId, owner, clock.nowMillis())).isTrue();
        binding = register("edge-man-" + UUID.randomUUID().toString().substring(0, 6), "eo-man-1");
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',last_heartbeat_at=? WHERE device_id=?",
                clock.nowMillis(), binding.opsDeviceId());
    }

    @AfterEach void cleanup() {
        jdbc.update("UPDATE mqtt_broker SET enabled=FALSE");
        jdbc.update("UPDATE outbox_event SET processed_at=? WHERE processed_at IS NULL AND topic LIKE 'eo.%'", clock.nowMillis());
        AuthContext.clear();
    }

    @Test void beginRequiresLoginThenPositionThenIdleCamera() throws Exception {
        String noLocation = insertTarget(false);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", noLocation)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", noLocation)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TARGET_POSITION_UNAVAILABLE"));
        jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?", binding.opsDeviceId());
        String located = insertTarget(true);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", located)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EO_DEVICE_UNAVAILABLE"));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", UUID.randomUUID())
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TARGET_NOT_FOUND"));
        String idle = insertTarget(true);
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-tasks", idle).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test void operatorBeginThenGetThenSecondBeginConflictsThenEndReleases() throws Exception {
        String targetId = insertTarget(true);
        JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", targetId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"值班员点选\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.device_id").value(binding.opsDeviceId()))
                .andReturn().getResponse().getContentAsString()).path("data");
        String taskId = created.path("task_id").asText();
        assertThat(jdbc.queryForObject("SELECT reason FROM device_command WHERE command_id=?", String.class,
                created.path("command_id").asText())).isEqualTo("OPERATOR_BEGIN_TRACK");
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-tasks", targetId).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.task_id").value(taskId));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", targetId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACK_ALREADY_OPEN"));
        mvc.perform(post("/api/v1/eo-tracking-tasks/{id}/end", taskId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("ENDING"));
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-tasks", targetId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDING"));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", targetId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACK_ALREADY_OPEN"));
    }

    @Test void videoReadIsScopedAndDoesNotCreateWork() throws Exception {
        String target = insertTarget(true);
        long tasks = jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class);
        long commands = jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class);
        mvc.perform(get("/api/v1/targets/{id}/video", target)).andExpect(status().isUnauthorized());
        video(target, "NO_TASK", "NONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class)).isEqualTo(tasks);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class)).isEqualTo(commands);
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        try {
            mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'"); }
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE account='admin1'");
        try {
            mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'"); }
        String role = "ROLE-VIDEO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,'视频只读测试','',FALSE,TRUE,0,0,0,FALSE)", role);
        jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,menu_enabled) VALUES (?,'devices','READ',TRUE)", role);
        jdbc.update("UPDATE app_user SET role_code=? WHERE account='admin1'", role);
        try {
            mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET role_code='ROLE-ADMIN' WHERE account='admin1'"); }
    }

    @Test void videoRequiresMatchingSimulatedReceiptAndNeverPlaysLiveSources() throws Exception {
        String target = insertTarget(true);
        String command = UUID.randomUUID().toString(), task = UUID.randomUUID().toString();
        long now = clock.nowMillis();
        edges.insertCommand(command, "VIDEO-" + command.substring(0, 6), binding.opsDeviceId(), null,
                "EO_BEGIN_TRACK", "video fixture", "replay", true, now + 10000, now);
        edges.insertTask(task, target, null, binding.opsDeviceId(), command, "video fixture", "{}", now);
        video(target, "QUEUED", "NONE");
        edges.updateCommand(command, "QUEUED", "SENT", now, null, null);
        video(target, "WAITING", "NONE");
        edges.updateCommand(command, "SENT", "SUCCEEDED", now, "200", null);
        video(target, "RECEIPT_UNAVAILABLE", "NONE");
        String payload = mapper.writeValueAsString(java.util.Map.of("event", "BeginTracking", "edgeId", binding.edgeId(), "timestamp", now,
                "metadata", java.util.Map.of("taskId", task, "deviceId", binding.externalDeviceId(), "codeStatus", 200)));
        String inbox = edges.inbox(binding, EoEdgeEnvelope.decode(binding.reportingTopic(),
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)), now);
        edges.addReceipt(command, inbox, "200", now, payload);
        video(target, "AVAILABLE", "SIMULATED_CANVAS");
        jdbc.update("UPDATE command_receipt SET payload=? WHERE command_id=?", payload.replace(task, UUID.randomUUID().toString()), command);
        video(target, "RECEIPT_UNAVAILABLE", "NONE");
        jdbc.update("UPDATE command_receipt SET payload=? WHERE command_id=?", payload, command);
        jdbc.update("UPDATE target SET source_mode='live' WHERE target_id=?", target);
        video(target, "NOT_INTEGRATED", "NONE");
        jdbc.update("UPDATE target SET source_mode='replay' WHERE target_id=?", target);
        jdbc.update("UPDATE device_command SET simulated=FALSE WHERE command_id=?", command);
        video(target, "NOT_INTEGRATED", "NONE");
        jdbc.update("UPDATE device_command SET simulated=TRUE,status='TIMED_OUT' WHERE command_id=?", command);
        video(target, "TIMED_OUT", "NONE");
        jdbc.update("UPDATE device_command SET status='FAILED' WHERE command_id=?", command);
        video(target, "FAILED", "NONE");
        jdbc.update("UPDATE eo_tracking_task SET status='ENDED' WHERE task_id=?", task);
        video(target, "ENDED", "NONE");
    }

    private void video(String target, String expected, String playback) throws Exception {
        mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.target_id").value(target))
                .andExpect(jsonPath("$.data.status").value(expected))
                .andExpect(jsonPath("$.data.playback_type").value(playback))
                .andExpect(jsonPath("$.data.checked_at").isNumber());
    }

    private Binding register(String edgeId, String deviceId) {
        String opsId = configuration.register(new Registration(EoEdgeEnvelope.PROTOCOL, brokerId, null, deviceId, null,
                "replay", org, district, "EO-" + UUID.randomUUID(), "光电夹具", null, null, null, edgeId), key());
        return edges.binding(opsId, false);
    }

    private String insertTarget(boolean withLocation) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                VALUES (?,?,'UAV','replay',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, id, "EO-T-" + id.substring(0, 8), org, district);
        if (withLocation) {
            jdbc.update("""
                    INSERT INTO target_latest_state
                        (target_id,location,altitude_amsl_m,speed_mps,heading_deg,observed_at,received_at,created_at,updated_at,version)
                    VALUES (?,GEOMETRY 'SRID=4326;POINT (118.5 37.4)',40,12,90,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                    """, id);
        }
        return id;
    }

    private String bearer() { return "Bearer " + token; }
    private static String key() { return UUID.randomUUID().toString(); }
}
