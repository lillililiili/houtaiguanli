package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.moquette.broker.Server;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.integration.mqtt.LingyunControlEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.integration.mqtt.MqttNetworkPolicy;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.application.LingyunControlService;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.application.MqttIngressService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.worker.OutboxWorker;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mqtt_p5;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false",
        "app.lingyun-control.command-timeout-millis=10000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LingyunControlMqttTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttRepository mqtt;
    @Autowired LingyunControlService control;
    @Autowired OutboxWorker outboxWorker;
    @Autowired MqttNetworkPolicy network;
    @Autowired EnvironmentCredentialResolver credentials;
    @Autowired AppClock clock;
    @Autowired MqttIngressService ingress;
    @TempDir Path temporary;
    String org, district, token, brokerId, owner;

    @BeforeEach void setup() throws Exception {
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "P5 test", "ROLE-ADMIN", 1, false, "ALL"));
        org = jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY", String.class);
        district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY", String.class);
        token = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        owner = UUID.randomUUID().toString();
        brokerId = configuration.create(new BrokerInput("P5", "127.0.0.1", 1883, false, null, null, "127.0.0.1/32",
                "replay", org, district, null), key()).brokerId();
        configuration.enable(brokerId, 0, true, key());
        assertThat(mqtt.claim(brokerId, owner, clock.nowMillis())).isTrue();
    }
    @AfterEach void cleanup() {
        jdbc.update("UPDATE mqtt_broker SET enabled=FALSE");
        jdbc.update("UPDATE outbox_event SET processed_at=? WHERE processed_at IS NULL AND topic=?",
                clock.nowMillis(), LingyunControlService.TOPIC);
        AuthContext.clear();
    }

    @Test void rejectsUnknownCommandMissingAuthStopWithParamsAndEmergencyStop() throws Exception {
        Binding radar = register("radar");
        online(radar);
        mvc.perform(post("/api/v1/devices/{id}/commands/lingyun-control", radar.opsDeviceId())
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_id\":\"AUTH-1\",\"operation_type\":1,\"operation_cmd\":99999,\"reason\":\"非法码\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/devices/{id}/commands/lingyun-control", radar.opsDeviceId())
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_id\":\"A\",\"operation_type\":1,\"operation_cmd\":10000,\"reason\":\"授权过短\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/devices/{id}/commands/lingyun-control", radar.opsDeviceId())
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_id\":\"AUTH-1\",\"operation_type\":0,\"operation_cmd\":10000,\"operation_params\":{\"duration\":20},\"reason\":\"停止带参\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/devices/{id}/commands/lingyun-control", radar.opsDeviceId())
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_id\":\"AUTH-1\",\"operation_type\":1,\"operation_cmd\":50002,\"reason\":\"诱骗码打到雷达\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/devices/{id}/commands/emergency-stop", radar.opsDeviceId())
                        .header("Authorization", bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTROL_NOT_ENABLED"));
        mvc.perform(post("/api/v1/devices/{id}/commands/lingyun-control", radar.opsDeviceId())
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_id\":\"AUTH-1\",\"operation_type\":1,\"operation_cmd\":30002,\"reason\":\"类型不符\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void decoyCommandSucceedsOnMatchingDeviceAndDoesNotWriteFusionInbox() {
        Binding decoy = register("dec");
        online(decoy);
        String ok = control.enqueue(decoy.opsDeviceId(), key(), "AUTH-DEC", 1, 50002, Map.of("direction", 0), "开启诱骗方向驱离");
        swallowOutbox(ok);
        control.receive(brokerId, owner, decoy.controlRespTopic(),
                resp(jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?", String.class, ok),
                        decoy.externalDeviceId(), 0, "ok").getBytes(StandardCharsets.UTF_8), 1, false, clock.nowMillis());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, ok)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source LIKE 'lingyun:%' AND source_msg_id=?",
                Long.class, jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?", String.class, ok))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source LIKE 'control-resp:%' AND source_msg_id=(SELECT command_no FROM device_command WHERE command_id=?)",
                Long.class, ok)).isEqualTo(1L);
    }

    @Test void successFailureTimeoutAndDoesNotWriteFusionInbox() {
        Binding radar = register("radar");
        online(radar);
        String ok = control.enqueue(radar.opsDeviceId(), key(), "AUTH-OK", 1, 10000, null, "开启探测");
        swallowOutbox(ok);
        control.receive(brokerId, owner, radar.controlRespTopic(),
                resp(jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?", String.class, ok),
                        radar.externalDeviceId(), 0, "ok").getBytes(StandardCharsets.UTF_8), 1, false, clock.nowMillis());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, ok)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source LIKE 'lingyun:%' AND source_msg_id=?",
                Long.class, jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?", String.class, ok))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source LIKE 'control-resp:%' AND source_msg_id=(SELECT command_no FROM device_command WHERE command_id=?)",
                Long.class, ok)).isEqualTo(1L);

        String fail = control.enqueue(radar.opsDeviceId(), key(), "AUTH-FAIL", 0, 10000, null, "停止探测");
        swallowOutbox(fail);
        String failNo = jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?", String.class, fail);
        control.receive(brokerId, owner, radar.controlRespTopic(),
                resp(failNo, radar.externalDeviceId(), 1, "busy").getBytes(StandardCharsets.UTF_8), 1, false, clock.nowMillis());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, fail)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT result_detail FROM device_command WHERE command_id=?", String.class, fail)).isEqualTo("busy");

        String timed = control.enqueue(radar.opsDeviceId(), key(), "AUTH-TO", 1, 10000, null, "超时");
        jdbc.update("UPDATE device_command SET deadline_at=0 WHERE command_id=?", timed);
        outboxWorker.poll();
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, timed)).isEqualTo("TIMED_OUT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_receipt WHERE command_id=?", Long.class, timed)).isZero();
    }

    @Test void realMqttPublishesCommandAndCompletesOnResponse() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Properties props = new Properties();
        props.setProperty("host", "127.0.0.1"); props.setProperty("port", String.valueOf(port));
        props.setProperty("allow_anonymous", "true"); props.setProperty("persistence_enabled", "false");
        props.setProperty("data_path", temporary.toString()); props.setProperty("telemetry_enabled", "false");
        Server server = new Server(); server.startServer(props);
        configuration.enable(brokerId, 1, false, key()); mqtt.release(brokerId, owner, clock.nowMillis());
        brokerId = configuration.create(new BrokerInput("P5-live", "127.0.0.1", port, false, null, null, "127.0.0.1/32",
                "replay", org, district, null), key()).brokerId();
        configuration.enable(brokerId, 0, true, key());
        Binding radar = register("radar");
        online(radar);
        MqttSessionSupervisor supervisor = new MqttSessionSupervisor(mqtt, ingress, configuration, network, credentials, clock,
                null, null, 3000, control);
        MqttClient device = new MqttClient("tcp://127.0.0.1:" + port, "dev-" + key(),
                new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        AtomicInteger seen = new AtomicInteger();
        AtomicReference<String> msgNo = new AtomicReference<>();
        try {
            device.connect();
            device.subscribe(radar.controlTopic(), 1, (topic, message) -> {
                seen.incrementAndGet();
                JsonNode root = mapper.readTree(message.getPayload());
                msgNo.set(root.path("head").path("msgNo").asText());
                device.publish(radar.controlRespTopic(),
                        resp(msgNo.get(), radar.externalDeviceId(), 0, "ok").getBytes(StandardCharsets.UTF_8), 1, false);
            });
            supervisor.reconcile();
            String commandId = control.enqueue(radar.opsDeviceId(), key(), "AUTH-MQTT", 1, 10000, null, "真实会话");
            swallowOutbox(commandId);
            String commandNo = jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?", String.class, commandId);
            supervisor.publish(brokerId, radar.controlTopic(),
                    LingyunControlEnvelope.encode(commandNo, radar.externalDeviceId(), clock.nowMillis(), 1, 10000, Map.of()));
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                supervisor.reconcile();
                assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, commandId))
                        .isEqualTo("SUCCEEDED");
            });
            assertThat(seen.get()).isPositive();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=?", Long.class, radar.source())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source LIKE 'control-resp:%' AND source_msg_id=?",
                    Long.class, commandNo)).isEqualTo(1L);
        } finally {
            supervisor.shutdown();
            if (device.isConnected()) device.disconnect();
            device.close();
            server.stopServer();
        }
    }

    private Binding register(String type) {
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "P5 test", "ROLE-ADMIN", 1, false, "ALL"));
        return mqtt.binding(configuration.register(new Registration(LingyunEnvelope.PROTOCOL, brokerId, "fixture-provider",
                "ext-" + type, type, "replay", org, district, "P5-" + type + "-" + UUID.randomUUID().toString().substring(0, 6),
                "P5 " + type, null, null, null), key()), false);
    }
    private void online(Binding b) {
        jdbc.update("UPDATE ops_device SET enabled=TRUE WHERE device_id=?", b.opsDeviceId());
        jdbc.update("""
                UPDATE ops_device_state SET connectivity='ONLINE',work_state_code='REPORTING',health_code='GOOD',
                    unknown_reason=NULL WHERE device_id=?
                """, b.opsDeviceId());
        int updated = jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE' WHERE device_id=?", b.opsDeviceId());
        if (updated == 0) jdbc.update("""
                INSERT INTO ops_device_state (device_id,connectivity,work_state_code,has_alarm,health_code,observed_at,received_at,simulated,version)
                VALUES (?,'ONLINE','REPORTING',FALSE,'GOOD',?,?,FALSE,0)
                """, b.opsDeviceId(), clock.nowMillis(), clock.nowMillis());
    }
    private static String resp(String msgNo, String deviceId, int code, String msg) {
        return "{\"head\":{\"msgNo\":\"" + msgNo + "\",\"deviceId\":\"" + deviceId + "\",\"time\":1},\"data\":{\"code\":"
                + code + ",\"msg\":\"" + msg + "\"}}";
    }
    private void swallowOutbox(String commandId) {
        jdbc.update("UPDATE outbox_event SET processed_at=? WHERE payload=? AND topic=? AND processed_at IS NULL",
                clock.nowMillis(), commandId, LingyunControlService.TOPIC);
    }
    private String bearer() { return "Bearer " + token; }
    private static String key() { return UUID.randomUUID().toString(); }
}
