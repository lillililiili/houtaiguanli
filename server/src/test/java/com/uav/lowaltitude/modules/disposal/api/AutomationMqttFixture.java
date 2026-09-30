package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.worker.OutboxWorker;
import io.moquette.broker.Server;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

/** Actual loopback MQTT transport, formal authorization APIs, and no live device connection. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:disposal_mqtt_flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.outbox.enabled=true", "app.mqtt.enabled=true", "app.live-device.enabled=false",
        "app.fusion.enabled=false", "app.rule-engine.enabled=false", "app.handoff.channel=none",
        "app.lingyun-control.command-timeout-millis=3000"})
@Import({DeviceMonitoringPostgresFixture.NoScheduledJobs.class, AutomationMqttFixture.ClockConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
abstract class AutomationMqttFixture {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository disposalRepository;
    String operator, eventId;
    private static final String ORG="seed-stage3-org", DISTRICT="seed-stage3-district";
    void createEventAndOperator() {
        operator=user("disposal:read","disposal:stop","devices","monitoring");
        eventId=event();
    }

    @Autowired MqttConfigurationService configuration;
    @Autowired MqttRepository mqtt;
    @Autowired MqttSessionSupervisor supervisor;
    @Autowired OutboxWorker outbox;
    @Autowired com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository advisory;
    @Autowired FlowClock clock;
    @LocalServerPort int port;
    @TempDir Path directory;
    Server broker;
    MqttClient deviceClient;
    Binding binding;
    String brokerId;
    final List<JsonNode> frames = new CopyOnWriteArrayList<>();

    @TestConfiguration static class ClockConfiguration {
        @Bean @Primary FlowClock flowClock() { return new FlowClock(); }
    }
    static class FlowClock extends AppClock {
        volatile long offset;
        @Override public Instant now() { return Instant.now().plusMillis(offset); }
        @Override public long nowMillis() { return now().toEpochMilli(); }
    }

    @BeforeEach void connectIsolatedDevice() throws Exception {
        createEventAndOperator();
        clock.offset = 0;
        int mqttPort;
        try (var socket = new java.net.ServerSocket(0)) { mqttPort = socket.getLocalPort(); }
        Properties properties = new Properties();
        properties.setProperty("host", "127.0.0.1"); properties.setProperty("port", String.valueOf(mqttPort));
        properties.setProperty("allow_anonymous", "true"); properties.setProperty("persistence_enabled", "false");
        properties.setProperty("telemetry_enabled", "false"); properties.setProperty("data_path", directory.toString());
        broker = new Server(); broker.startServer(properties);
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        AuthContext.set(new AuthUser(admin, "admin1", "本机协议测试", "ROLE-ADMIN", 1, false, "ALL"));
        brokerId = configuration.create(new BrokerInput("QA disposal loopback", "127.0.0.1", mqttPort, false,
                null, null, "127.0.0.1/32", "replay", "seed-stage3-org", "seed-stage3-district", null), key()).brokerId();
        configuration.enable(brokerId, 0, true, key());
        String registered = configuration.register(new Registration(LingyunEnvelope.PROTOCOL, brokerId, "qa-disposal",
                "ifr-" + key(), "ifr", "replay", "seed-stage3-org", "seed-stage3-district",
                "QA-IFR-" + key(), "隔离反制协议设备", null, null, null), key());
        binding = mqtt.binding(registered, false);
        jdbc.update("update ops_device set enabled=true where device_id=?", binding.opsDeviceId());
        jdbc.update("update ops_device_state set connectivity='ONLINE',health_code='GOOD',has_alarm=false,observed_at=?,received_at=? where device_id=?",
                clock.nowMillis(), clock.nowMillis(), binding.opsDeviceId());
        deviceClient = new MqttClient("tcp://127.0.0.1:" + mqttPort, "qa-ifr-" + key(),
                new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        deviceClient.connect();
        deviceClient.subscribe(binding.controlTopic(), 1, (topic, message) -> frames.add(json.readTree(message.getPayload())));
        supervisor.reconcile();
        AuthContext.clear();
    }

    @AfterEach void closeIsolatedDevice() throws Exception {
        clock.offset = 0;
        if (brokerId != null) jdbc.update("update mqtt_broker set enabled=false where broker_id=?", brokerId);
        supervisor.reconcile();
        if (deviceClient != null) { if (deviceClient.isConnected()) deviceClient.disconnect(); deviceClient.close(); }
        if (broker != null) broker.stopServer();
        jdbc.update("update outbox_event set processed_at=? where topic='device.control.lingyun' and processed_at is null", clock.nowMillis());
        AuthContext.clear();
    }

    String statusOf(String id) {
        return jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, id);
    }

    org.springframework.test.web.servlet.ResultActions stop(String token, String key) throws Exception {
        return request(path(), token, key, Map.of());
    }

    org.springframework.test.web.servlet.ResultActions request(String path, String token, String key, Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    JsonNode data(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    String path() { return "/api/v1/uav-events/" + eventId + "/emergency-stop"; }
    static String key() { return java.util.UUID.randomUUID().toString(); }

    String event() {
        String tag = key().substring(0, 8), alarm = "estop-alarm-" + tag, event = "estop-event-" + tag;
        java.sql.Timestamp at = java.sql.Timestamp.from(Instant.parse("2026-09-07T02:00:00Z"));
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select 'estop-src','ESTOP-TEST','急停测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='estop-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'estop-src',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarm, tag, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'CONFIRMED',?,?,?,?,1)", event, alarm, ORG, DISTRICT, at, at);
        CounterEvidenceFixture.seed(jdbc,event);
        return event;
    }

    String user(String... permissions) {
        String id = key(), role = "ESTOP-" + key().substring(0, 8), token = key();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " values (?,'alarm:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                id, "estop-" + key().substring(0, 8), "急停测试操作员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", id, ORG, DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, id, System.currentTimeMillis() + 3_600_000L);
        return token;
    }
    Map<String, Object> body(String reason) { return Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId,
            "action_type", "COUNTERMEASURE", "channel", "LINGYUN_B", "device_id", binding.opsDeviceId(), "reason", reason); }
    String commandOf(String id) { return jdbc.queryForObject("select execution_command_id from disposal_authorization where authorization_id=?", String.class, id); }
    List<String> children(String id) { return jdbc.queryForList("select authorization_id from disposal_authorization where chained_from_authorization_id=?", String.class, id); }
    void awaitWire(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            // Scheduled jobs are disabled in this fixture; keep consuming bounded outbox
            // batches just as the running service does.
            supervisor.reconcile();
            outbox.poll();
            assertThat(frames).hasSize(count);
        });
    }
    void reply(String command, int code) throws Exception {
        String no = jdbc.queryForObject("select command_no from device_command where command_id=?", String.class, command);
        String payload = json.writeValueAsString(Map.of("head", Map.of("msgNo", no, "deviceId", binding.externalDeviceId(), "time", clock.nowMillis()),
                "data", Map.of("code", code, "msg", code == 0 ? "qa-wire-ok" : "qa-wire-failed")));
        deviceClient.publish(binding.controlRespTopic(), payload.getBytes(StandardCharsets.UTF_8), 1, false);
    }
    void saveEvidence(String label, String authorization) throws Exception {
        String database;
        try (var connection = jdbc.getDataSource().getConnection()) { database = connection.getMetaData().getDatabaseProductName(); }
        Path output = Path.of("target", "disposal-mqtt-evidence", database.toLowerCase()); Files.createDirectories(output);
        Files.writeString(output.resolve(label + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "database", database, "simulated", true, "wire_frames", frames,
                "authorization", jdbc.queryForMap("select authorization_id,authorization_mode,requested_by,approved_by,valid_from,valid_until,status,execution_command_id from disposal_authorization where authorization_id=?", authorization),
                "children", children(authorization),
                "commands", jdbc.queryForList("select command_id,command_no,status,issued_at,result_code from device_command where device_id=?", binding.opsDeviceId()),
                "receipts", jdbc.queryForList("select r.receipt_kind,r.device_result_code,r.command_id from command_receipt r join device_command c on c.command_id=r.command_id where c.device_id=?", binding.opsDeviceId()),
                "history", jdbc.queryForList("select event_kind,actor_id,occurred_at from disposal_authorization_event where authorization_id=? order by occurred_at,event_id", authorization))));
    }
}
