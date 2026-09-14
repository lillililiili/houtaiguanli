package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.MqttNetworkPolicy;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.application.EoAutoTrackService;
import com.uav.lowaltitude.modules.device.application.EoEdgeIngressService;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

import io.moquette.broker.Server;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mqtt_p3;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false",
        "app.eo-edge.heartbeat-timeout-millis=3000"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EoEdgeMqttTest {
    @Autowired MqttConfigurationService configuration;
    @Autowired EoEdgeIngressService ingress;
    @Autowired EoEdgeRepository edges;
    @Autowired MqttRepository mqtt;
    @Autowired EoAutoTrackService autoTrack;
    @Autowired MqttNetworkPolicy network;
    @Autowired EnvironmentCredentialResolver credentials;
    @Autowired AppClock clock;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    String org, district, owner, brokerId;
    Binding binding;

    @BeforeEach void setup() {
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "EO test", "ROLE-ADMIN", 1, false, "ALL"));
        org = jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY", String.class);
        district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY", String.class);
        owner = UUID.randomUUID().toString();
        brokerId = configuration.create(input(1883), key()).brokerId();
        configuration.enable(brokerId, 0, true, key());
        assertThat(mqtt.claim(brokerId, owner, clock.nowMillis())).isTrue();
        binding = register("edge-" + UUID.randomUUID().toString().substring(0, 8), "eo-dev-1");
    }
    @AfterEach void cleanup() {
        jdbc.update("UPDATE mqtt_broker SET enabled=FALSE");
        jdbc.update("UPDATE outbox_event SET processed_at=? WHERE processed_at IS NULL AND topic LIKE 'eo.%'", clock.nowMillis());
        AuthContext.clear();
    }

    @Test void heartbeatGoesOnlineWithoutInboxAndTimeoutGoesOffline() {
        receive(heartbeat(binding, 0, null), 1, false);
        assertThat(jdbc.queryForObject("SELECT connectivity FROM ops_device_state WHERE device_id=?", String.class, binding.opsDeviceId()))
                .isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("SELECT work_state_code FROM ops_device_state WHERE device_id=?", String.class, binding.opsDeviceId()))
                .isEqualTo("0");
        assertThat(inboxCount()).isZero();
        receive(heartbeat(binding, 1, "task-open"), 2, false);
        assertThat(jdbc.queryForObject("SELECT work_state_code FROM ops_device_state WHERE device_id=?", String.class, binding.opsDeviceId()))
                .isEqualTo("1");
        edges.expire(clock.nowMillis() + 4_000, 3_000);
        assertThat(jdbc.queryForObject("SELECT connectivity FROM ops_device_state WHERE device_id=?", String.class, binding.opsDeviceId()))
                .isEqualTo("OFFLINE");
    }

    @Test void beginTrackingReportWritesInboxAndEndTrackingStopsFurtherReports() {
        String taskId = openTask("SENT");
        receive(beginReport(binding, taskId, 200, 1731731306000L, "drone"), 3, false);
        assertThat(inboxCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT source FROM inbox_message WHERE source LIKE 'eo-edge:%' AND source_id=?",
                String.class, binding.sourceId())).isEqualTo("eo-edge:" + binding.edgeId());
        assertThat(jdbc.queryForObject("SELECT CAST(payload AS VARCHAR) FROM inbox_message WHERE source=?",
                String.class, binding.source())).contains("aiStatus");
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=(SELECT begin_command_id FROM eo_tracking_task WHERE task_id=?)",
                String.class, taskId)).isEqualTo("SUCCEEDED");
        receive(beginReport(binding, taskId, 200, 1731731306000L, "drone"), 4, false);
        assertThat(inboxCount()).isOne();
        receive(beginReport(binding, taskId, 200, 1731731306000L, "bird"), 5, false);
        assertThat(((Number) edges.status(binding.opsDeviceId()).get("conflict_count")).longValue()).isOne();
        receive(beginReport(binding, taskId, 200, 1731731307000L, "drone"), 6, false);
        assertThat(inboxCount()).isEqualTo(2);
        receive(endReport(binding, taskId), 7, false);
        assertThat(jdbc.queryForObject("SELECT status FROM eo_tracking_task WHERE task_id=?", String.class, taskId)).isEqualTo("ENDED");
        long afterEnd = inboxCount();
        receive(beginReport(binding, taskId, 200, 1731731308000L, "drone"), 8, false);
        assertThat(inboxCount()).isEqualTo(afterEnd);
    }

    @Test void codeStatus400FailsCommandWithoutInbox() {
        String taskId = openTask("SENT");
        receive(beginReport(binding, taskId, 400, 1731731306000L, "drone"), 9, false);
        assertThat(inboxCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=(SELECT begin_command_id FROM eo_tracking_task WHERE task_id=?)",
                String.class, taskId)).isEqualTo("FAILED");
    }

    @Test void cameraStatusUpdatesRuntimeWithoutInbox() {
        String commandId = UUID.randomUUID().toString();
        edges.insertCommand(commandId, "EO-CAM", binding.opsDeviceId(), null, "EO_CAMERA_STATUS", "test",
                "replay", true, clock.nowMillis() + 10_000, clock.nowMillis());
        edges.updateCommand(commandId, "QUEUED", "SENT", clock.nowMillis(), null, null);
        receive(cameraReport(binding), 10, false);
        assertThat(inboxCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, commandId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT camera_status_json FROM eo_device_binding WHERE ops_device_id=?",
                String.class, binding.opsDeviceId())).contains("hfov");
    }

    @Test void unsupportedEventIsRejectedAndUnregisteredDeviceDoesNotCreateInbox() {
        receive("{\"event\":\"AbsMoveByAngle\",\"edgeId\":\"" + binding.edgeId() + "\",\"timestamp\":1,"
                + "\"metadata\":{\"deviceId\":\"" + binding.externalDeviceId() + "\",\"codeStatus\":200}}", 11, false);
        assertThat(jdbc.queryForObject("SELECT reason FROM mqtt_receive_diagnostic WHERE ops_device_id=? ORDER BY received_at DESC FETCH FIRST 1 ROWS ONLY",
                String.class, binding.opsDeviceId())).isEqualTo("PROTOCOL_EVENT_UNSUPPORTED");
        receive("{\"event\":\"HeartBeat\",\"edgeId\":\"" + binding.edgeId() + "\",\"timestamp\":1,"
                + "\"metadata\":{\"deviceId\":\"unknown-device\",\"codeStatus\":200,\"message\":\"\",\"taskId\":null,\"workState\":0,"
                + "\"cameraStatus\":{\"hfov\":1,\"vfov\":1,\"panOrientAngle\":1,\"tiltOrientAngle\":1,\"focalLen\":1,\"detectDist\":1,\"zoomIndex\":1}}}",
                12, false);
        assertThat(inboxCount()).isZero();
    }

    @Test void fusionEventWithAlarmEnqueuesTrackingAndMissingFieldsDoNot() {
        String targetId = insertTarget();
        receive(heartbeat(binding, 0, null), 100, false);
        insertOpenAlarm(targetId);
        // 稳定事件产生时告警尚未建立的真实时序：自动跟踪必须按当前业务状态重新判断，不能只信旧 payload。
        insertStable(targetId, "{\"alarm_active\":false,\"class_code\":\"UAV\",\"latest_state\":{\"longitude\":104.0,\"latitude\":30.5,\"altitude_raw\":42.1,\"speed_mps\":10,\"heading_deg\":90}}");
        assertThat(autoTrack.poll()).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task WHERE target_id=?", Long.class, targetId)).isOne();
        String other = insertTarget();
        insertStable(other, "{\"status\":\"STABLE\"}");
        autoTrack.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task WHERE target_id=?", Long.class, other)).isZero();
    }

    @Test void automaticTrackingWaitsForAnOnlineCameraAndRetriesTheSameStableEvent() {
        String targetId = insertTarget();
        insertOpenAlarm(targetId);
        insertStable(targetId, "{\"class_code\":\"UAV\",\"latest_state\":{\"longitude\":104.0,\"latitude\":30.5}}");
        assertThat(autoTrack.poll()).isZero();
        receive(heartbeat(binding, 0, null), 101, false);
        assertThat(autoTrack.poll()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task WHERE target_id=?", Long.class, targetId)).isOne();
        assertThat(autoTrack.poll()).isZero();
    }

    @Test void realMqttPublishesBeginTrackingAndReceivesReport() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Properties props = new Properties();
        props.setProperty("host", "127.0.0.1"); props.setProperty("port", String.valueOf(port));
        props.setProperty("allow_anonymous", "true"); props.setProperty("persistence_enabled", "false");
        props.setProperty("data_path", temporary.toString()); props.setProperty("telemetry_enabled", "false");
        Server server = new Server(); server.startServer(props);
        configuration.enable(brokerId, 1, false, key()); mqtt.release(brokerId, owner, clock.nowMillis());
        brokerId = configuration.create(input(port), key()).brokerId(); configuration.enable(brokerId, 0, true, key());
        binding = register("edge-live-" + UUID.randomUUID().toString().substring(0, 6), "eo-dev-mqtt");
        MqttSessionSupervisor supervisor = new MqttSessionSupervisor(mqtt, new com.uav.lowaltitude.modules.device.application.MqttIngressService(mqtt, clock),
                configuration, network, credentials, clock, ingress, edges, 3000);
        MqttClient edge = new MqttClient("tcp://127.0.0.1:" + port, "edge-" + key(), new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        AtomicInteger commands = new AtomicInteger();
        try {
            edge.connect();
            edge.subscribe(binding.dispatcherTopic(), 1, (topic, message) -> {
                commands.incrementAndGet();
                String taskId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.getPayload()).path("metadata").path("taskId").asText();
                edge.publish(binding.reportingTopic(), beginReport(binding, taskId, 200, clock.nowMillis(), "drone").getBytes(StandardCharsets.UTF_8), 1, false);
            });
            supervisor.reconcile();
            String taskId = openTask("QUEUED");
            java.util.Map<String, Object> objectData = new java.util.LinkedHashMap<>();
            objectData.put("latitude", 1); objectData.put("longitude", 2); objectData.put("altitude", 3);
            objectData.put("speedX", 0); objectData.put("speedY", 0); objectData.put("speedZ", 0);
            objectData.put("dataId", "x"); objectData.put("length", 0); objectData.put("width", 0); objectData.put("height", 0);
            objectData.put("objectType", 30); objectData.put("probability", 1);
            java.util.Map<String, Object> aiData = new java.util.LinkedHashMap<>();
            aiData.put("className", "drone"); aiData.put("isDetect", 1); aiData.put("isTrack", 1);
            java.util.Map<String, Object> extention = new java.util.LinkedHashMap<>();
            extention.put("mode", "full-auto"); extention.put("bootstrapSourceId", "x"); extention.put("bootstrapSourceType", 0); extention.put("msgId", "m");
            byte[] payload = EoEdgeEnvelope.encode("BeginTracking", binding.edgeId(), clock.nowMillis(),
                    EoEdgeEnvelope.beginMetadata(taskId, binding.externalDeviceId(), objectData, aiData, extention));
            supervisor.publish(brokerId, binding.dispatcherTopic(), payload);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                supervisor.reconcile();
                assertThat(inboxCount()).isOne();
            });
            assertThat(commands.get()).isGreaterThan(0);
        } finally {
            supervisor.shutdown();
            if (edge.isConnected()) edge.disconnect();
            edge.close();
            server.stopServer();
        }
    }

    private Binding register(String edgeId, String deviceId) {
        String opsId = configuration.register(new Registration(EoEdgeEnvelope.PROTOCOL, brokerId, null, deviceId, null,
                "replay", org, district, "EO-" + UUID.randomUUID(), "光电夹具", null, null, null, edgeId), key());
        return edges.binding(opsId, false);
    }
    private String openTask(String commandStatus) {
        String taskId = UUID.randomUUID().toString();
        String commandId = UUID.randomUUID().toString();
        long now = clock.nowMillis();
        edges.insertCommand(commandId, "EO-" + commandId.substring(0, 6), binding.opsDeviceId(), null, "EO_BEGIN_TRACK",
                "test", "replay", true, now + 10_000, now);
        if ("SENT".equals(commandStatus)) edges.updateCommand(commandId, "QUEUED", "SENT", now, null, null);
        edges.insertTask(taskId, null, null, binding.opsDeviceId(), commandId, "test", "{}", now);
        return taskId;
    }
    private void receive(String body, int packet, boolean retained) {
        ingress.receive(brokerId, owner, binding.reportingTopic(), body.getBytes(StandardCharsets.UTF_8), packet, 1, retained, false, clock.nowMillis());
    }
    private long inboxCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=?", Long.class, binding.source());
        return count == null ? 0 : count;
    }
    private String insertTarget() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO target(target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,'replay',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                id, "EO-T-" + id.substring(0, 8), org, district);
        return id;
    }
    private void insertStable(String targetId, String payload) {
        jdbc.update("INSERT INTO fusion_event(event_id,event_type,target_id,payload,occurred_at,created_at) VALUES (?,'STATUS_STABLE',?,CAST(? AS JSON),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), targetId, payload);
    }
    private void insertOpenAlarm(String targetId) {
        String alarmId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,
                    source_mode,owner_org_id,district_id,created_at)
                VALUES (?,?,?,?, 'UAV_INTRUSION','HIGH',CURRENT_TIMESTAMP,'replay',?,?,CURRENT_TIMESTAMP)
                """, alarmId, targetId, binding.sourceId(), "EO-A-" + alarmId, org, district);
        jdbc.update("""
                INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)
                VALUES (?,?,'PENDING_VERIFICATION',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, UUID.randomUUID().toString(), alarmId, org, district);
    }
    private BrokerInput input(int port) {
        return new BrokerInput("EO MQTT", "127.0.0.1", port, false, null, null, "127.0.0.1/32", "replay", org, district, null);
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String heartbeat(Binding b, int workState, String taskId) {
        String task = taskId == null ? "null" : "\"" + taskId + "\"";
        return "{\"event\":\"HeartBeat\",\"edgeId\":\"" + b.edgeId() + "\",\"timestamp\":1731731424000,\"metadata\":{"
                + "\"deviceId\":\"" + b.externalDeviceId() + "\",\"codeStatus\":200,\"message\":\"\",\"taskId\":" + task + ","
                + "\"workState\":" + workState + ",\"cameraStatus\":{\"hfov\":0.8,\"vfov\":0.4,\"panOrientAngle\":1.0,"
                + "\"tiltOrientAngle\":2.0,\"focalLen\":4.8,\"detectDist\":10.0,\"zoomIndex\":32}}}";
    }
    private static String beginReport(Binding b, String taskId, int code, long timestamp, String className) {
        return "{\"event\":\"BeginTracking\",\"edgeId\":\"" + b.edgeId() + "\",\"timestamp\":" + timestamp + ",\"metadata\":{"
                + "\"taskId\":\"" + taskId + "\",\"deviceId\":\"" + b.externalDeviceId() + "\",\"codeStatus\":" + code + ","
                + "\"message\":\"\",\"workState\":1,\"objectData\":{\"latitude\":1,\"longitude\":2,\"altitude\":3,\"speedX\":0,"
                + "\"speedY\":0,\"speedZ\":0,\"dataId\":\"x\",\"length\":1,\"width\":1,\"height\":1,\"objectType\":30,\"probability\":0.5},"
                + "\"cameraStatus\":{\"hfov\":1,\"vfov\":1,\"panOrientAngle\":1,\"tiltOrientAngle\":1,\"focalLen\":1,\"detectDist\":1},"
                + "\"aiStatus\":{\"className\":\"" + className + "\",\"latitude\":1.1,\"longitude\":2.2,\"altitude\":3.3,\"width\":1,\"height\":1,"
                + "\"detectConfidence\":0.9,\"trackConfidence\":0.8},"
                + "\"extention\":{\"mode\":\"full-auto\",\"bootstrapSourceId\":\"t\",\"bootstrapSourceType\":0,\"msgId\":\"msg-1\"}}}";
    }
    private static String endReport(Binding b, String taskId) {
        return "{\"event\":\"EndTracking\",\"edgeId\":\"" + b.edgeId() + "\",\"timestamp\":1731731400000,\"metadata\":{"
                + "\"taskId\":\"" + taskId + "\",\"deviceId\":\"" + b.externalDeviceId() + "\",\"codeStatus\":200,\"message\":\"\","
                + "\"workState\":0,\"cameraStatus\":{\"hfov\":1,\"vfov\":1,\"panOrientAngle\":1,\"tiltOrientAngle\":1,\"focalLen\":1,\"detectDist\":1}}}";
    }
    private static String cameraReport(Binding b) {
        return "{\"event\":\"CameraStatus\",\"edgeId\":\"" + b.edgeId() + "\",\"timestamp\":1731731424000,\"metadata\":{"
                + "\"deviceId\":\"" + b.externalDeviceId() + "\",\"codeStatus\":200,\"message\":\"\",\"taskId\":null,\"workState\":0,"
                + "\"cameraStatus\":{\"hfov\":0.8,\"vfov\":0.4,\"panOrientAngle\":1.0,\"tiltOrientAngle\":2.0,\"focalLen\":4.8,"
                + "\"detectDist\":10.0,\"zoomIndex\":32}}}";
    }
}
