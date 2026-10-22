package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/** 方案 A（2026-10-14）：模拟器自己登记的模拟设备退役或停用后可恢复为同一条记录；真实设备不能恢复；忙设备说清原因。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SimulatedDeviceRestoreApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MqttConfigurationService configuration;

    private String org;
    private String district;
    private String token;

    @BeforeEach
    void scope() throws Exception {
        org = jdbc.queryForObject("SELECT org_id FROM app_org WHERE enabled=TRUE ORDER BY org_id FETCH FIRST 1 ROW ONLY", String.class);
        district = jdbc.queryForObject("SELECT district_id FROM app_district WHERE enabled=TRUE ORDER BY district_id FETCH FIRST 1 ROW ONLY", String.class);
        token = login();
    }

    @AfterEach
    void clear() { AuthContext.clear(); }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"radar,replay","oe,replay","radar,live","oe,live"})
    void mqttAndEoHttpRegistrationAndUpdateRequireExplicitWgs84(String type,String sourceMode) throws Exception {
        Device original=onboard(sourceMode,type);
        var before=jdbc.queryForMap("SELECT longitude,latitude,coordinate_system,version FROM ops_device WHERE device_id=?",original.id());
        for(String declaration:new String[]{"missing",""," ","GCJ02","BD-09","CGCS2000"}) {
            Map<String,Object> body=new LinkedHashMap<>(original.registration());
            body.put("longitude",108.56);body.put("latitude",31.29);body.put("version",version(original));
            if(!"missing".equals(declaration))body.put("coordinate_system",declaration);
            JsonNode rejected=error(put("/api/v1/devices/"+original.id()).header("Idempotency-Key",UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)),status().isBadRequest());
            assertThat(rejected.path("code").asText()).isEqualTo("MQTT_CONFIG_INVALID");
            assertThat(jdbc.queryForMap("SELECT longitude,latitude,coordinate_system,version FROM ops_device WHERE device_id=?",original.id())).isEqualTo(before);
            rejected=error(post("/api/v1/devices/onboard").header("Idempotency-Key",UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)),status().isBadRequest());
            assertThat(rejected.path("code").asText()).isEqualTo("MQTT_CONFIG_INVALID");
        }
        for(String declaration:new String[]{"WGS84","WGS-84"}) {
            Map<String,Object> body=new LinkedHashMap<>(original.registration());
            body.put("longitude",-72.31);body.put("latitude",19.86);body.put("coordinate_system",declaration);body.put("version",version(original));
            data(put("/api/v1/devices/"+original.id()).header("Idempotency-Key",UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)),status().isOk());
            assertThat(jdbc.queryForObject("SELECT coordinate_system FROM ops_device WHERE device_id=?",String.class,original.id())).isEqualTo("WGS-84");
            String external="wgs-"+UUID.randomUUID().toString().substring(0,16);
            body.put("device_no",external);body.put("external_device_id",external);
            if("oe".equals(type))body.put("edge_id","edge-"+external);
            JsonNode created=data(post("/api/v1/devices/onboard").header("Idempotency-Key",UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)),status().isOk());
            assertThat(created.path("coordinate_system").asText()).isEqualTo("WGS-84");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"radar", "oe"})
    void retiredSimulatorDeviceComesBackAsTheSameRecord(String type) throws Exception {
        Device device = onboard("replay", type);
        retire(device);
        assertThat(data(get("/api/v1/devices?keyword=" + device.deviceNo()), status().isOk()).path("total").asInt()).isZero();

        // 模拟器每次都用同一个登记幂等键：以前这里说“该操作已提交”，现在如实说编号已退役、模拟设备可恢复。
        JsonNode again = error(post("/api/v1/devices/onboard").header("Idempotency-Key", device.onboardKey())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(device.registration())), status().isConflict());
        assertThat(again.path("code").asText()).isEqualTo("DEVICE_RETIRED");
        assertThat(again.path("message").asText()).contains(device.deviceNo()).contains("已退役").contains("可以恢复");

        JsonNode restored = data(restore(device, device.identity()), status().isOk());
        assertThat(restored.path("device").path("device_id").asText()).isEqualTo(device.id());
        assertThat(restored.path("device").path("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_no=?", Integer.class, device.deviceNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM ops_device WHERE device_id=?", Long.class, device.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT s.enabled FROM ops_device d JOIN ops_integration_source s ON s.source_id=d.source_id WHERE d.device_id=?",
                Boolean.class, device.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_event_log WHERE device_id=? AND event_type='CATALOG_DELETED'",
                Integer.class, device.id())).as("退役记录保留").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message FROM device_event_log WHERE device_id=? AND event_type='CATALOG_RESTORED'",
                String.class, device.id())).contains("原为已退役");
        assertThat(restoreAudits(device)).isEqualTo(1);
        assertThat(data(get("/api/v1/devices?keyword=" + device.deviceNo()), status().isOk()).path("total").asInt()).isEqualTo(1);

        // 已经在用时再恢复什么也不改。
        assertThat(data(restore(device, device.identity()), status().isOk()).path("device").path("device_id").asText()).isEqualTo(device.id());
        assertThat(restoreAudits(device)).isEqualTo(1);
    }

    @Test
    void switchedOffSimulatorDeviceIsSwitchedBackOn() throws Exception {
        Device device = onboard("replay", "oe");
        setEnabled(device, false);
        JsonNode restored = data(restore(device, device.identity()), status().isOk());
        assertThat(restored.path("device").path("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT message FROM device_event_log WHERE device_id=? AND event_type='CATALOG_RESTORED'",
                String.class, device.id())).contains("原为已停用");
        assertThat(restoreAudits(device)).isEqualTo(1);
    }

    @Test
    void realDevicesStayRetiredAndOnlyTheSameIdentityIsRestored() throws Exception {
        Device live = onboard("live", "radar");
        retire(live);
        JsonNode refused = error(restore(live, live.identity()), status().isConflict());
        assertThat(refused.path("code").asText()).isEqualTo("DEVICE_NOT_RESTORABLE");
        assertThat(refused.path("message").asText()).contains(live.deviceNo()).contains("真实设备");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM ops_device WHERE device_id=?", Long.class, live.id())).isNotNull();
        JsonNode liveAgain = error(post("/api/v1/devices/onboard").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(live.registration())), status().isConflict());
        assertThat(liveAgain.path("code").asText()).isEqualTo("DEVICE_RETIRED");
        assertThat(liveAgain.path("message").asText()).contains("新的设备编号").doesNotContain("可以恢复");

        Device simulated = onboard("replay", "radar");
        retire(simulated);
        Map<String, Object> otherExternal = new LinkedHashMap<>(simulated.identity());
        otherExternal.put("external_device_id", "radar-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(error(restore(simulated, otherExternal), status().isConflict()).path("message").asText()).contains("外部设备编号");
        Map<String, Object> otherBroker = new LinkedHashMap<>(simulated.identity());
        otherBroker.put("broker_id", onboard("replay", "radar").brokerId());
        assertThat(error(restore(simulated, otherBroker), status().isConflict()).path("message").asText()).contains("MQTT 连接");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM ops_device WHERE device_id=?", Long.class, simulated.id())).isNotNull();
        assertThat(restoreAudits(simulated)).isZero();

        // 没有这个编号的设备：没有可恢复的，成功返回、不带 data，模拟器接着新登记。
        // 模拟器每次新登记前都先问一次，这不算失败，不留失败审计（上面被拒的恢复照常留）。
        Map<String, Object> unknown = new LinkedHashMap<>(simulated.identity());
        unknown.put("device_no", "map-sim-radar-" + UUID.randomUUID().toString().substring(0, 8));
        int failures = restoreFailureAudits();
        assertThat(failures).isPositive();
        JsonNode nothing = response(restore(simulated, unknown), status().isOk());
        assertThat(nothing.path("ok").asBoolean()).isTrue();
        assertThat(nothing.has("data")).isFalse();
        assertThat(restoreFailureAudits()).isEqualTo(failures);
    }

    @Test
    void busyDeviceSaysWhatIsUnfinishedBeforeAndWhenDeleting() throws Exception {
        Device device = onboard("replay", "oe");
        assertThat(data(get("/api/v1/devices/" + device.id() + "/catalog-blockers"), status().isOk()).path("reasons")).isEmpty();
        jdbc.update("INSERT INTO eo_tracking_task(task_id,ops_device_id,status,notes,bootstrap_json,created_at) VALUES(?,?,'ENDING','结束中的跟踪','{}',?)",
                UUID.randomUUID().toString(), device.id(), System.currentTimeMillis());
        JsonNode blockers = data(get("/api/v1/devices/" + device.id() + "/catalog-blockers"), status().isOk());
        assertThat(blockers.path("device_no").asText()).isEqualTo(device.deviceNo());
        assertThat(blockers.path("reasons").get(0).asText()).isEqualTo("光电跟踪任务还没结束");
        assertThat(blockers.path("reasons")).hasSize(1);

        setEnabled(device, false);
        JsonNode busy = error(delete("/api/v1/devices/" + device.id()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version(device) + ",\"reason\":\"退役忙设备\"}"), status().isConflict());
        assertThat(busy.path("code").asText()).isEqualTo("DEVICE_BUSY");
        assertThat(busy.path("message").asText()).contains(device.deviceNo()).contains("光电跟踪任务还没结束");
    }

    private Device onboard(String sourceMode, String type) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        boolean eo = "oe".equals(type);
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "恢复模拟设备测试", "ROLE-ADMIN", 1, false, "ALL"));
        String brokerId;
        try {
            // live 连接须有平台分配的用户名和 env: 凭据引用；借用必然存在的 PATH 变量通过解析校验，不连接任何服务器。
            BrokerInput broker = "live".equals(sourceMode)
                    ? new BrokerInput("现场 MQTT " + suffix, "192.0.2.10", 8883, true, "platform-test", "env:PATH", "192.0.2.0/24", "live", org, district, null)
                    : new BrokerInput("模拟器 MQTT " + suffix, "127.0.0.1", 1883, false, null, null, "127.0.0.1/32", "replay", org, district, null);
            brokerId = configuration.create(broker, UUID.randomUUID().toString()).brokerId();
            configuration.enable(brokerId, 0, true, UUID.randomUUID().toString());
        } finally {
            AuthContext.clear();
        }
        String external = "map-sim-" + type + "-" + suffix;
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("protocol_code", eo ? EoEdgeEnvelope.PROTOCOL : LingyunEnvelope.PROTOCOL);
        identity.put("broker_id", brokerId);
        identity.put("external_device_id", external);
        identity.put("source_mode", sourceMode);
        if (eo) identity.put("edge_id", external + "-edge");
        else {
            identity.put("provider_code", "map-sim");
            identity.put("device_type_abbr", type);
        }
        identity.put("device_no", external);
        Map<String, Object> registration = new LinkedHashMap<>(identity);
        registration.put("owner_org_id", org);
        registration.put("district_id", district);
        registration.put("name", "模拟 " + type + " " + suffix);
        registration.put("vendor", "地图场景模拟器");
        registration.put("model", "MQTT-SIM");
        String key = "sim-onboard-" + external;
        JsonNode created = data(post("/api/v1/devices/onboard").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(registration)), status().isOk());
        return new Device(created.path("device").path("device_id").asText(), brokerId, external, key, identity, registration);
    }

    private void retire(Device device) throws Exception {
        setEnabled(device, false);
        data(delete("/api/v1/devices/" + device.id()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version(device) + ",\"reason\":\"模拟场景台账同步\"}"), status().isOk());
    }

    private void setEnabled(Device device, boolean enabled) throws Exception {
        data(patch("/api/v1/devices/" + device.id() + "/enabled").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + ",\"version\":" + version(device) + ",\"reason\":\"模拟场景切换\"}"), status().isOk());
    }

    private MockHttpServletRequestBuilder restore(Device device, Map<String, Object> identity) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(identity);
        body.put("reason", "模拟器启动：恢复本场景的模拟设备");
        return post("/api/v1/devices/restore-simulated").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private long version(Device device) {
        return jdbc.queryForObject("SELECT version FROM ops_device WHERE device_id=?", Long.class, device.id());
    }

    private int restoreAudits(Device device) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='device_restore' AND object_id=?", Integer.class, device.id());
    }

    private int restoreFailureAudits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE result='FAILURE' AND object_id='/api/v1/devices/restore-simulated'",
                Integer.class);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        return response(request, expected).path("data");
    }

    private JsonNode error(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        return response(request, expected).path("error");
    }

    private JsonNode response(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", "Bearer " + token)).andExpect(expected)
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("session_id").asText();
    }

    private record Device(String id, String brokerId, String deviceNo, String onboardKey,
                          Map<String, Object> identity, Map<String, Object> registration) { }
}
