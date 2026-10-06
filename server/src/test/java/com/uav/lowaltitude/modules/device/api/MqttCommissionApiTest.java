package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.application.EoEdgeIngressService;
import com.uav.lowaltitude.modules.device.application.MqttCommissionCheck;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.application.MqttIngressService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.worker.OutboxWorker;

/** 雷达等 MQTT 设备可走完接入调测：平台核对自身 MQTT 会话、订阅和已接收上报，不向设备下发指令。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MqttCommissionApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker outboxWorker;
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttIngressService ingress;
    @Autowired EoEdgeIngressService eoIngress;
    @Autowired AppClock clock;

    private String org;
    private String district;
    private String token;
    private int packet;

    @BeforeEach
    void scope() throws Exception {
        org = jdbc.queryForObject("SELECT org_id FROM app_org WHERE enabled=TRUE ORDER BY org_id FETCH FIRST 1 ROW ONLY", String.class);
        district = jdbc.queryForObject("SELECT district_id FROM app_district WHERE enabled=TRUE ORDER BY district_id FETCH FIRST 1 ROW ONLY", String.class);
        token = login();
    }

    @AfterEach
    void clear() { AuthContext.clear(); }

    @Test
    void liveRadarIsCommissionedThroughThePlatformMqttLinkWithoutSendingCommands() throws Exception {
        Device radar = register("live", "radar");
        connected(radar);
        workParameters(radar, 1);
        senseData(radar);
        assertThat(data(get("/api/v1/commission-tasks/device-information/" + radar.id()), status().isOk())
                .path("task_supported").asBoolean()).isTrue();

        JsonNode task = create(radar.id(), null);
        assertThat(task.path("status").asText()).isEqualTo("CREATED");
        task = connect(task);
        assertThat(task.path("status").asText()).isEqualTo("CONNECTED");
        JsonNode snapshot = task.path("configuration");
        assertThat(snapshot.path("transport").asText()).isEqualTo("MQTT");
        assertThat(snapshot.path("host").asText()).isEqualTo("192.0.2.10");
        assertThat(snapshot.path("port").asInt()).isEqualTo(8883);
        assertThat(snapshot.path("tls").asBoolean()).isTrue();
        assertThat(snapshot.has("username") || snapshot.has("credential_ref") || snapshot.has("client_id")).isFalse();

        task = data(put("/api/v1/commission-tasks/{id}/configuration", id(task))
                .content("{\"version\":" + task.path("version").asLong() + "}"), status().isOk());
        assertThat(task.path("status").asText()).isEqualTo("READY");
        JsonNode report = run(task);
        assertThat(report.path("status").asText()).isEqualTo("PASSED");
        assertThat(report.path("simulated").asBoolean()).isFalse();
        assertThat(report.path("warning").asText()).isEqualTo(MqttCommissionCheck.WARNING);
        assertThat(report.path("criteria").path("thresholds").path("commands_sent").asBoolean(true)).isFalse();
        assertThat(codes(report)).containsExactly("SESSION", "SUBSCRIPTION", "WORK_PARAMETERS", "WORK_STATE", "SENSE", "RECEIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?", Long.class, radar.id())).isZero();
        assertThat(messages(report)).anyMatch(message -> message.contains("设备主题已订阅"));
    }

    @Test
    void linkProblemsEndAsUntestableOrFailedInsteadOfPassing() throws Exception {
        Device tdoa = register("live", "tdoa");
        JsonNode first = connect(create(tdoa.id(), null));
        assertThat(first.path("status").asText()).isEqualTo("UNTESTABLE");
        assertThat(messages(first)).anyMatch(message -> message.contains("平台未与 MQTT 服务器建立会话"));

        connected(tdoa);
        workParameters(tdoa, 1);
        JsonNode quiet = run(configure(connect(create(tdoa.id(), id(first)))));
        assertThat(quiet.path("status").asText()).isEqualTo("UNTESTABLE");
        assertThat(quiet.path("results").path("result_code").asText()).isEqualTo("MQTT_DATA_UNTESTABLE");

        jdbc.update("UPDATE mqtt_device_binding SET last_static_at=? WHERE ops_device_id=?", clock.nowMillis() - 60_000, tdoa.id());
        JsonNode stale = run(configure(connect(create(tdoa.id(), quiet.path("commission_id").asText()))));
        assertThat(stale.path("status").asText()).isEqualTo("FAILED");
        assertThat(stale.path("results").path("result_code").asText()).isEqualTo("MQTT_WORK_PARAMETERS_FAILED");
    }

    @Test
    void simulatorDeviceUsesThePlatformLinkInsteadOfTheSimulatorStatusAndStaysMarkedSimulated() throws Exception {
        Device jammer = register("replay", "countermeasure");
        connected(jammer);
        workParameters(jammer, 0);
        JsonNode task = connect(create(jammer.id(), null));
        assertThat(task.path("status").asText()).isEqualTo("CONNECTED");
        // 旧的逻辑调测参数被忽略，快照取平台侧 MQTT 连接
        task = data(put("/api/v1/commission-tasks/{id}/configuration", id(task)).content("{\"version\":" + task.path("version").asLong()
                + ",\"transport\":\"SIMULATOR\",\"host\":\"simulator\",\"port\":8766}"), status().isOk());
        assertThat(task.path("configuration").path("transport").asText()).isEqualTo("MQTT");
        assertThat(task.path("configuration").path("host").asText()).isEqualTo("127.0.0.1");
        JsonNode report = run(task);
        assertThat(report.path("status").asText()).isEqualTo("PASSED");
        assertThat(report.path("simulated").asBoolean()).isTrue();
        assertThat(codes(report)).containsExactly("SESSION", "SUBSCRIPTION", "WORK_PARAMETERS", "WORK_STATE", "RECEIVE");
        assertThat(messages(report)).noneMatch(message -> message.contains("8766") || message.contains("模拟器运行态"));
    }

    @Test
    void liveElectroOpticalDeviceIsCommissionedOnItsHeartbeat() throws Exception {
        Device eo = register("live", "oe");
        connected(eo);
        JsonNode task = configure(connect(create(eo.id(), null)));
        assertThat(task.path("configuration").path("host").asText()).isEqualTo("192.0.2.10");

        JsonNode missing = run(task);
        assertThat(missing.path("status").asText()).isEqualTo("FAILED");
        assertThat(missing.path("results").path("result_code").asText()).isEqualTo("MQTT_HEARTBEAT_FAILED");

        // 心跳时效只有数秒：紧挨着调测发送，workState=2（自主探测）是正常工作状态
        JsonNode next = configure(connect(create(eo.id(), id(missing))));
        heartbeat(eo, 2);
        JsonNode report = run(next);
        assertThat(report.path("status").asText()).isEqualTo("PASSED");
        assertThat(codes(report)).containsExactly("SESSION", "SUBSCRIPTION", "HEARTBEAT", "WORK_STATE", "RECEIVE");
        assertThat(report.path("criteria").path("thresholds").has("heartbeat_fresh_millis")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?", Long.class, eo.id())).isZero();
    }

    /** type 为协议 A 附录缩写；"oe" 表示协议 C 光电边端设备。 */
    private Device register(String sourceMode, String type) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        boolean live = "live".equals(sourceMode);
        boolean eo = "oe".equals(type);
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "MQTT 调测测试", "ROLE-ADMIN", 1, false, "ALL"));
        try {
            // live 连接须有平台分配的用户名和 env: 凭据引用；测试借用必然存在的 PATH 变量通过解析校验，不连接任何服务器
            BrokerInput broker = live
                    ? new BrokerInput("现场 MQTT", "192.0.2.10", 8883, true, "platform-test", "env:PATH", "192.0.2.0/24", "live", org, district, null)
                    : new BrokerInput("模拟器 MQTT", "127.0.0.1", 1883, false, null, null, "127.0.0.1/32", "replay", org, district, null);
            String brokerId = configuration.create(broker, UUID.randomUUID().toString()).brokerId();
            configuration.enable(brokerId, 0, true, UUID.randomUUID().toString());
            String external = type + "-" + suffix;
            Registration registration = eo
                    ? new Registration(EoEdgeEnvelope.PROTOCOL, brokerId, null, external, null, sourceMode, org, district,
                            "EO-" + suffix, "调测光电", null, null, null, "edge-" + suffix)
                    : new Registration(LingyunEnvelope.PROTOCOL, brokerId, "field-provider", external, type, sourceMode,
                            org, district, "MQ-" + suffix, "调测 " + type, null, null, null, null);
            String deviceId = configuration.register(registration, UUID.randomUUID().toString());
            return new Device(deviceId, brokerId, UUID.randomUUID().toString(), type, external, eo ? "edge-" + suffix : null);
        } finally {
            AuthContext.clear();
        }
    }

    private record Device(String id, String brokerId, String owner, String type, String external, String edge) { }

    /** 模拟平台会话已建立、设备主题已收到 SUBACK（测试不启动 MQTT 客户端）。 */
    private void connected(Device device) {
        long now = clock.nowMillis();
        jdbc.update("UPDATE mqtt_session_lease SET owner_id=?,lease_until=?,connection_state='CONNECTED',updated_at=? WHERE broker_id=?",
                device.owner(), now + 60_000, now, device.brokerId());
        jdbc.update("UPDATE mqtt_device_binding SET subscribed=TRUE WHERE ops_device_id=?", device.id());
        jdbc.update("UPDATE eo_device_binding SET subscribed=TRUE WHERE ops_device_id=?", device.id());
    }

    /** 协议 C 心跳；workState 0 空闲 / 1 工作 / 2 自主探测。 */
    private void heartbeat(Device device, int workState) {
        String body = "{\"event\":\"HeartBeat\",\"edgeId\":\"" + device.edge() + "\",\"timestamp\":" + clock.nowMillis()
                + ",\"metadata\":{\"deviceId\":\"" + device.external() + "\",\"codeStatus\":200,\"message\":\"\",\"taskId\":null,"
                + "\"workState\":" + workState + ",\"cameraStatus\":{\"hfov\":0.8,\"vfov\":0.4,\"panOrientAngle\":1.0,"
                + "\"tiltOrientAngle\":2.0,\"focalLen\":4.8,\"detectDist\":10.0,\"zoomIndex\":32}}}";
        eoIngress.receive(device.brokerId(), device.owner(), "iot-reporting/cmlc/edge/" + device.edge(),
                body.getBytes(StandardCharsets.UTF_8), ++packet, 1, false, false, clock.nowMillis());
    }

    /** 协议 A 工参（设备主动上报）；workState 0 未工作 / 1 工作中 / 2 设备异常。 */
    private void workParameters(Device device, int workState) {
        receive(device, false, "{\"providerCode\":\"field-provider\",\"deviceId\":\"" + device.external()
                + "\",\"deviceName\":\"调测 " + device.type() + "\",\"deviceType\":" + LingyunEnvelope.TYPES.get(device.type())
                + ",\"workState\":" + workState + ",\"ptTime\":" + clock.nowMillis() + "}");
    }

    private void senseData(Device device) {
        receive(device, true, "{\"deviceId\":\"" + device.external() + "\",\"ptTime\":" + clock.nowMillis()
                + ",\"msgCnt\":1,\"objects\":[]}");
    }

    private void receive(Device device, boolean sense, String body) {
        String topic = "bridge/field-provider/" + (sense ? "device_data/" : "device/") + device.type() + "/" + device.external();
        ingress.receive(device.brokerId(), device.owner(), topic, body.getBytes(StandardCharsets.UTF_8),
                ++packet, 1, false, false, clock.nowMillis());
    }

    private JsonNode create(String deviceId, String previous) throws Exception {
        return data(post("/api/v1/commission-tasks").content("{\"device_id\":\"" + deviceId + "\""
                + (previous == null ? "" : ",\"previous_task_id\":\"" + previous + "\"") + "}"), status().isOk());
    }

    private JsonNode connect(JsonNode task) throws Exception {
        data(post("/api/v1/commission-tasks/{id}/connect", id(task)).content(version(task)), status().isAccepted());
        processOutbox();
        return data(get("/api/v1/commission-tasks/{id}", id(task)), status().isOk());
    }

    private JsonNode configure(JsonNode task) throws Exception {
        return data(put("/api/v1/commission-tasks/{id}/configuration", id(task)).content(version(task)), status().isOk());
    }

    private JsonNode run(JsonNode task) throws Exception {
        data(post("/api/v1/commission-tasks/{id}/start", id(task)).content(version(task)), status().isAccepted());
        processOutbox();
        return data(get("/api/v1/commission-tasks/{id}/report", id(task)), status().isOk());
    }

    private void processOutbox() {
        jdbc.update("UPDATE outbox_event SET available_at=0 WHERE processed_at IS NULL AND topic IN ('commission.connect','commission.run')");
        outboxWorker.poll();
    }

    private List<String> codes(JsonNode report) {
        List<String> codes = new ArrayList<>();
        report.path("results").path("items").forEach(item -> codes.add(item.path("code").asText()));
        return codes;
    }

    private List<String> messages(JsonNode task) throws Exception {
        List<String> messages = new ArrayList<>();
        data(get("/api/v1/commission-tasks/{id}/events?limit=200", id(task)), status().isOk())
                .path("items").forEach(item -> messages.add(item.path("message").asText()));
        return messages;
    }

    private JsonNode data(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        String body = mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON))
                .andExpect(expected).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    private static String id(JsonNode task) { return task.path("commission_id").asText(); }

    private static String version(JsonNode task) { return "{\"version\":" + task.path("version").asLong() + "}"; }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("session_id").asText();
    }
}
