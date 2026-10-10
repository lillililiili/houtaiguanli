package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
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
import com.uav.lowaltitude.modules.device.application.DeviceOperationsProcessor;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.disposal.application.DirectDisposalAccess;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.worker.OutboxWorker;
import io.moquette.broker.Server;
import org.awaitility.Awaitility;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@Import({DeviceMonitoringPostgresFixture.NoScheduledJobs.class, DisposalMqttFlowTest.ClockConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisposalMqttFlowTest extends EmergencyStopApiTest {
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttRepository mqtt;
    @Autowired MqttSessionSupervisor supervisor;
    @Autowired OutboxWorker outbox;
    @Autowired DeviceOperationsProcessor processor;
    @Autowired DirectDisposalAccess directAccess;
    @Autowired com.uav.lowaltitude.modules.device.application.LingyunControlService lingyun;
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

    @org.springframework.test.context.DynamicPropertySource
    static void browserCommandWindow(org.springframework.test.context.DynamicPropertyRegistry registry) {
        if (Boolean.getBoolean("qa.disposal.emergency.browser")) {
            registry.add("app.lingyun-control.command-timeout-millis", () -> 600_000);
        }
    }

    @BeforeEach void connectIsolatedDevice() throws Exception {
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

    @ParameterizedTest @ValueSource(strings = {"VALID", "DIRECT_REVOKED", "DEVICE_REVOKED", "SCOPE_REVOKED", "DEVICE_SCOPE_CHANGED", "EXPIRED"})
    void realReceiptNeverCreatesASecondWireCommandEvenWhenStillEligible(String condition) throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        JsonNode parent = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(), body("续链间隙资格验证"))
                .andExpect(status().isCreated()));
        String authorization = parent.path("authorization_id").asText();
        String command = commandOf(authorization);
        outbox.poll();
        awaitWire(1);
        assertThat(frames.get(0).path("data").path("operationCmd").asInt()).isEqualTo(60003);
        String actorId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, actor);
        String role = jdbc.queryForObject("select role_code from app_user where user_id=?", String.class, actorId);
        switch (condition) {
            case "DIRECT_REVOKED" -> jdbc.update("delete from app_role_permission where role_code=? and permission_code='disposal:direct'", role);
            case "DEVICE_REVOKED" -> jdbc.update("delete from app_role_permission where role_code=? and permission_code='devices'", role);
            case "SCOPE_REVOKED" -> jdbc.update("delete from app_user_data_scope where user_id=?", actorId);
            case "DEVICE_SCOPE_CHANGED" -> jdbc.update("update device_business_scope set owner_org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where ops_device_id=?", binding.opsDeviceId());
            case "EXPIRED" -> {
                long until = jdbc.queryForObject("select valid_until from disposal_authorization where authorization_id=?", Timestamp.class, authorization).getTime();
                clock.offset = until - System.currentTimeMillis() + 1;
                // Keep current observation independently valid so expiry, not stale evidence, blocks this branch.
                CounterEvidenceFixture.seed(jdbc, eventId, clock.now());
                supervisor.reconcile();
            }
            default -> { }
        }
        assertThat(advisory.counterBlockReason(eventId)).as("Only the changed authorization qualification should block this branch").isEmpty();
        if (!"VALID".equals(condition)) assertThat(directAccess.eligibleRequester(disposalRepository.findUnlocked(authorization), true)).isNull();
        reply(command, 0);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(authorization)).isEqualTo("COMPLETED"));
        outbox.poll();
        assertThat(children(authorization)).isEmpty();
        assertThat(frames).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, binding.opsDeviceId())).isEqualTo(1);
        chain.scheduleAfterComplete(authorization); outbox.poll();
        assertThat(frames).hasSize(1);
        saveEvidence("direct-" + condition, authorization);
    }

    @Test void repeatedDispatchOfSentCommandDoesNotPublishAgain() throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        String authorization = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(), body("隔离重复投递验证"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        String command = commandOf(authorization);
        outbox.poll();
        awaitWire(1);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("SENT");
        lingyun.dispatch(command);
        lingyun.dispatch(command);
        Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(frames).hasSize(1));
        assertThat(children(authorization)).isEmpty();
        saveEvidence("sent-command-no-repeat", authorization);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIRECT_REVOKED", "DEVICE_REVOKED", "SCOPE_REVOKED", "EVENT_SCOPE_CHANGED", "DEVICE_SCOPE_CHANGED", "EXPIRED"})
    void queuedDirectCommandRechecksScopeAndExpiryBeforeAnyWireOutput(String condition) throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        String authorization = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(), body("隔离排队资格验证"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        String command = commandOf(authorization);
        assertThat(frames).isEmpty();
        String actorId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, actor);
        String role = jdbc.queryForObject("select role_code from app_user where user_id=?", String.class, actorId);
        switch (condition) {
            case "DIRECT_REVOKED" -> jdbc.update("delete from app_role_permission where role_code=? and permission_code='disposal:direct'", role);
            case "DEVICE_REVOKED" -> jdbc.update("delete from app_role_permission where role_code=? and permission_code='devices'", role);
            case "SCOPE_REVOKED" -> jdbc.update("delete from app_user_data_scope where user_id=?", actorId);
            case "EVENT_SCOPE_CHANGED" -> jdbc.update("update uav_event set owner_org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where event_id=?", eventId);
            case "DEVICE_SCOPE_CHANGED" -> jdbc.update("update device_business_scope set owner_org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where ops_device_id=?", binding.opsDeviceId());
            case "EXPIRED" -> {
                long until = jdbc.queryForObject("select valid_until from disposal_authorization where authorization_id=?", Timestamp.class, authorization).getTime();
                clock.offset = until - System.currentTimeMillis() + 1;
                CounterEvidenceFixture.seed(jdbc, eventId, clock.now());
            }
            default -> throw new IllegalArgumentException(condition);
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            supervisor.reconcile(); outbox.poll();
            assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command))
                    .isIn("CANCELLED", "TIMED_OUT");
        });
        outbox.poll(); chain.scheduleAfterComplete(authorization); outbox.poll();
        saveEvidence("queued-direct-" + condition, authorization);
        assertThat(frames).isEmpty();
        assertThat(jdbc.queryForObject("select issued_at from device_command where command_id=?", Long.class, command)).isNull();
        assertThat(children(authorization)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, binding.opsDeviceId())).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void ordinaryAuthorizationTimeoutKeepsLateWireResultWithoutCompletingOrChaining(int code) throws Exception {
        String authorization = approvedOrdinary();
        request("/api/v1/disposal-authorizations/" + authorization + "/execute", operator, key(), Map.of("expected_version", 1)).andExpect(status().isOk());
        String command = commandOf(authorization);
        outbox.poll(); awaitWire(1);
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            processor.expireCommands(clock.nowMillis());
            assertThat(statusOf(authorization)).isEqualTo("FAILED");
        });
        var history = jdbc.queryForList("select * from disposal_authorization_event where authorization_id=? order by occurred_at,event_id", authorization);
        reply(command, code); reply(command, code);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from command_receipt where command_id=? and receipt_kind='PROTOCOL_B_LATE'", Integer.class, command)).isEqualTo(1));
        assertThat(statusOf(authorization)).isEqualTo("FAILED");
        assertThat(jdbc.queryForList("select * from disposal_authorization_event where authorization_id=? order by occurred_at,event_id", authorization))
                .usingRecursiveComparison().isEqualTo(history);
        assertThat(children(authorization)).isEmpty();
        assertThat(frames).hasSize(1);
        saveEvidence("ordinary-late-" + code, authorization);
    }

    @Test @EnabledIfSystemProperty(named = "qa.disposal.mqtt.browser", matches = "true")
    void serveOrdinaryLateReplyBrowser() throws Exception {
        String authorization = approvedOrdinary();
        Path output = Path.of("target", "disposal-mqtt-browser").toAbsolutePath(); Files.createDirectories(output);
        Path stop = output.resolve("stop"), respond = output.resolve("reply"); Files.deleteIfExists(stop); Files.deleteIfExists(respond);
        Files.writeString(output.resolve("manifest.json"), json.writeValueAsString(Map.of("port", port,
                "authorization_id", authorization, "event_id", eventId, "device_id", binding.opsDeviceId(), "simulated", true)));
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) {
            supervisor.reconcile(); outbox.poll(); processor.expireCommands(clock.nowMillis());
            if (Files.exists(respond)) { int code = Integer.parseInt(Files.readString(respond).trim()); Files.delete(respond); reply(commandOf(authorization), code); }
            Files.writeString(output.resolve("state.json"), json.writeValueAsString(Map.of("frames", frames,
                    "authorization", jdbc.queryForMap("select authorization_id,status,execution_command_id from disposal_authorization where authorization_id=?", authorization))));
            Thread.sleep(250);
        }
        Files.deleteIfExists(stop);
        assertThat(statusOf(authorization)).isEqualTo("FAILED");
        assertThat(children(authorization)).isEmpty();
        assertThat(frames).hasSize(1);
        saveEvidence("browser-ordinary-late", authorization);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void emergencyStopKeepsLateStartReceiptSeparateFromShutdown(int lateCode) throws Exception {
        String authorization = approvedOrdinary();
        request("/api/v1/disposal-authorizations/" + authorization + "/execute", operator, key(),
                Map.of("expected_version", 1)).andExpect(status().isOk());
        String startCommand = commandOf(authorization);
        awaitWire(1);
        assertThat(frames.get(0).path("data").path("operationCmd").asInt()).isEqualTo(60003);
        String stopKey = key();
        JsonNode stopped = data(stop(operator, stopKey).andExpect(status().isOk())).path("latest_stop");
        String stopId = stopped.path("stop_id").asText();
        // The current Lingyun protocol does not expose a verified remote shutdown.
        // Cancellation must not pretend that the physical device has stopped.
        assertThat(stopped.path("devices").get(0).path("command_id").asText()).isEmpty();
        assertThat(stopped.path("devices").get(0).path("stop_status").asText()).isEqualTo("UNSUPPORTED");
        assertThat(statusOf(authorization)).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, startCommand)).isEqualTo("CANCELLED");
        var history = jdbc.queryForList("select * from disposal_authorization_event where authorization_id=? order by occurred_at,event_id", authorization);
        reply(startCommand, lateCode);
        reply(startCommand, lateCode);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=? and receipt_kind='PROTOCOL_B_LATE'", Integer.class, startCommand)).isEqualTo(1);
        });
        JsonNode feedback = data(stop(operator, stopKey).andExpect(status().isOk())).path("latest_stop");
        assertThat(feedback.path("devices").get(0).path("stop_status").asText()).isEqualTo("UNSUPPORTED");
        assertThat(feedback.path("reason_pending").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select confirmed_at from disposal_emergency_stop_device where stop_id=? and device_id=?", Long.class, stopId, binding.opsDeviceId())).isNull();
        assertThat(statusOf(authorization)).isEqualTo("STOPPED");
        assertThat(jdbc.queryForList("select * from disposal_authorization_event where authorization_id=? order by occurred_at,event_id", authorization))
                .usingRecursiveComparison().isEqualTo(history);
        chain.scheduleAfterComplete(authorization); outbox.poll();
        assertThat(children(authorization)).isEmpty();
        assertThat(frames).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, binding.opsDeviceId())).isEqualTo(1);
        saveEvidence("emergency-late-" + lateCode, authorization);
    }

    @Test @EnabledIfSystemProperty(named = "qa.disposal.emergency.browser", matches = "true")
    void serveEmergencyStopLateReplyBrowser() throws Exception {
        String authorization = approvedOrdinary();
        Path output = Path.of("target", "disposal-emergency-browser").toAbsolutePath(); Files.createDirectories(output);
        Path stopFile = output.resolve("stop"), respond = output.resolve("reply");
        Files.deleteIfExists(stopFile); Files.deleteIfExists(respond);
        Files.writeString(output.resolve("manifest.json"), json.writeValueAsString(Map.of("port", port,
                "authorization_id", authorization, "event_id", eventId, "device_id", binding.opsDeviceId(), "simulated", true)));
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stopFile) && System.nanoTime() < deadline) {
            // The opt-in browser command window keeps the requested executing -> emergency
            // stop path observable. Ordinary timeout tests retain their three-second window.
            supervisor.reconcile(); outbox.poll();
            if (Files.exists(respond)) {
                Files.delete(respond);
                reply(commandOf(authorization), 0); reply(commandOf(authorization), 0);
            }
            Files.writeString(output.resolve("state.json"), json.writeValueAsString(Map.of("frames", frames,
                    "authorization", jdbc.queryForMap("select authorization_id,status,execution_command_id from disposal_authorization where authorization_id=?", authorization))));
            Thread.sleep(250);
        }
        Files.deleteIfExists(stopFile);
        assertThat(statusOf(authorization)).isEqualTo("STOPPED");
        assertThat(children(authorization)).isEmpty();
        assertThat(frames).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=? and receipt_kind='PROTOCOL_B_LATE'", Integer.class, commandOf(authorization))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select confirmed_at from disposal_emergency_stop_device where authorization_id=?", Long.class, authorization)).isNotNull();
        saveEvidence("browser-emergency-late", authorization);
    }

    String approvedOrdinary() throws Exception {
        String id = data(request("/api/v1/disposal-authorizations", requester, key(), body("普通审批迟到回执验证"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + id + "/approve", operator, key(), Map.of("expected_version", 0)).andExpect(status().isOk());
        return id;
    }
    Map<String, Object> body(String reason) { return Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId,
            "action_type", "COUNTERMEASURE", "channel", "LINGYUN_B", "device_id", binding.opsDeviceId(), "reason", reason); }
    String commandOf(String id) { return jdbc.queryForObject("select execution_command_id from disposal_authorization where authorization_id=?", String.class, id); }
    List<String> children(String id) { return jdbc.queryForList("select authorization_id from disposal_authorization where chained_from_authorization_id=?", String.class, id); }
    void awaitWire(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            // Scheduled jobs are disabled in this fixture; keep consuming bounded outbox
            // batches just as the running service does, including inherited-test backlog.
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
