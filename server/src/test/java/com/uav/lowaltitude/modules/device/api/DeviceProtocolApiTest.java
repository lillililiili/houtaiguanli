package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackBatch;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackItem;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.Rtk;
import com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeviceProtocolApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProtocolDataRepository protocolData;

    @Test
    void protocolCatalogLiveSourceTypedDeviceStatusAndTargetsAreConnected() throws Exception {
        String token = login();
        mvc.perform(get("/api/v1/device-protocols").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].protocol_code").value("LINGYUN_MQTT_V8_6"))
                .andExpect(jsonPath("$.data[0].control_enabled").value(true))
                .andExpect(jsonPath("$.data[1].protocol_code").value("EO_EDGE_MQTT_20250826"))
                .andExpect(jsonPath("$.data[3].capabilities[0]").value("SAFE_STATUS_QUERY"));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceBody = """
                {"source_code":"RAD-%s","name":"雷达协议回归来源","protocol_code":"RADAR_TCP_V3_0_0",
                 "protocol_version":"3.0.0","allowed_cidrs":"192.0.2.0/24"}
                """.formatted(suffix);
        JsonNode source = mapper.readTree(mvc.perform(post("/api/v1/integration-sources")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(sourceBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
                .andReturn().getResponse().getContentAsString()).path("data");

        String deviceNo = "RAD-LIVE-" + suffix;
        String deviceBody = """
                {"source_id":"%s","external_device_id":"T02-%s","device_no":"%s","name":"T02 回归雷达",
                 "device_type_code":"radar","device_type_name":"雷达","channel":"雷达直连","model":"T02",
                 "connection":{"transport":"TCP","host":"192.0.2.88","port":5001,"timeout_millis":1000},
                 "protocol_configuration":{"login_role":"DATA",
                    "rtk_enabled":true,"coordinate_transform_enabled":false}}
                """.formatted(source.path("source_id").asText(), suffix, deviceNo);
        JsonNode device = mapper.readTree(mvc.perform(post("/api/v1/devices").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(deviceBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol_code").value("RADAR_TCP_V3_0_0"))
                .andExpect(jsonPath("$.data.protocol_configuration.login_role").value("DATA"))
                .andReturn().getResponse().getContentAsString()).path("data");
        String deviceId = device.path("device").path("device_id").asText();

        mvc.perform(patch("/api/v1/integration-sources/{id}/enabled", source.path("source_id").asText())
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"version\":" + source.path("version").asLong()
                                + ",\"reason\":\"协议回归显式启用\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        assertThat(jdbc.queryForObject("SELECT source_type FROM integration_source WHERE source_code=?",
                String.class, source.path("source_code").asText())).isEqualTo("RADAR");
        assertThat(jdbc.queryForObject("SELECT enabled FROM integration_source WHERE source_code=?",
                Boolean.class, source.path("source_code").asText())).isTrue();

        mvc.perform(get("/api/v1/devices/{id}/protocol-status", deviceId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.connection_state").value("DISCONNECTED"));

        JsonNode task = mapper.readTree(mvc.perform(post("/api/v1/commission-tasks")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device_id\":\"" + deviceId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol_code").value("RADAR_TCP_V3_0_0"))
                .andReturn().getResponse().getContentAsString()).path("data");
        jdbc.update("UPDATE ops_integration_source SET protocol_code='COUNTERMEASURE_TCP_4CH_V2_0',protocol_version='2.0',allowed_cidrs='198.51.100.0/24' WHERE source_id=?",
                source.path("source_id").asText());
        mvc.perform(get("/api/v1/commission-tasks/{id}", task.path("commission_id").asText())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol_code").value("RADAR_TCP_V3_0_0"))
                .andExpect(jsonPath("$.data.protocol_version").value("3.0.0"));
        assertThat(jdbc.queryForObject("SELECT allowed_cidrs_snapshot FROM commission_task WHERE commission_id=?",
                String.class, task.path("commission_id").asText())).isEqualTo("192.0.2.0/24");
        assertThat(jdbc.queryForObject("SELECT protocol_configuration_json FROM commission_task WHERE commission_id=?",
                String.class, task.path("commission_id").asText())).contains("\"rtk_enabled\":true");

        jdbc.update("""
                INSERT INTO ops_device_state (device_id,connectivity,health_code,received_at,last_heartbeat_at,simulated,version)
                VALUES (?,'ONLINE','GOOD',1,1,FALSE,0)
                """, deviceId);
        mvc.perform(post("/api/v1/devices/{id}/commands/reboot", deviceId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "live-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验证协议能力阻断\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEVICE_NOT_OPERABLE"));

        TrackItem item = new TrackItem("4294967295", new BigDecimal("1.25"), new BigDecimal("-2.50"),
                new BigDecimal("3.75"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("12.34"), new BigDecimal("0.50"), new BigDecimal("0.500001"),
                3, "UAV", false);
        protocolData.saveTrackBatch(deviceId, deviceNo, new TrackBatch(99L, "7", System.currentTimeMillis(),
                BigDecimal.ZERO, BigDecimal.ONE, 1, 0, List.of(item)), System.currentTimeMillis());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=?", Long.class,
                "live-radar:" + source.path("source_code").asText())).isZero();
        String targets = mvc.perform(get("/api/v1/sensing/targets?device_id=" + deviceId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].external_track_id").value("4294967295"))
                .andExpect(jsonPath("$.data.items[0].longitude_deg").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String targetId = mapper.readTree(targets).path("data").path("items").get(0).path("target_id").asText();
        mvc.perform(get("/api/v1/sensing/targets/{id}/track", targetId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.points.length()").value(1))
                .andExpect(jsonPath("$.data.points[0].derived").value(false));
    }

    /* 本用例断言服务层事务在回环地址被拒后真实回滚；若沿用类级测试事务，未提交的设备行仍在同一事务内可见，
       无法验证回滚，因此这里不参与测试事务（数据与上游用例一样留在共享 H2 中，编号带随机后缀）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void onboardCreatesEnabledLiveDeviceAndRejectsLoopback() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String deviceNo = "RAD-OB-" + suffix;
        try {
            String token = login();
            JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/devices/onboard")
                            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"protocol_code":"RADAR_TCP_V3_0_0","device_no":"%s","name":"接入雷达",
                                     "host":"192.0.2.88","port":5001,"allowed_cidrs":"192.0.2.0/24"}
                                    """.formatted(deviceNo)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.device.source_mode").value("live"))
                    .andExpect(jsonPath("$.data.device.device_type_name").value("雷达"))
                    .andExpect(jsonPath("$.data.protocol_code").value("RADAR_TCP_V3_0_0"))
                    .andExpect(jsonPath("$.data.allowed_cidrs").value("192.0.2.0/24"))
                    .andReturn().getResponse().getContentAsString()).path("data");
            assertThat(jdbc.queryForObject("SELECT enabled FROM ops_integration_source WHERE source_code=?", Boolean.class, deviceNo)).isTrue();
            assertThat(jdbc.queryForObject("SELECT source_type FROM integration_source WHERE source_code=?", String.class, deviceNo)).isEqualTo("RADAR");
            assertThat(jdbc.queryForObject("SELECT enabled FROM integration_source WHERE source_code=?", Boolean.class, deviceNo)).isTrue();
            assertThat(created.path("device").path("channel").asText()).isEqualTo("雷达直连");

            mvc.perform(post("/api/v1/devices/onboard").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"protocol_code":"COUNTERMEASURE_TCP_4CH_V2_0","device_no":"CM-OB-%s","name":"接入反制",
                                     "host":"127.0.0.1","port":10006,"allowed_cidrs":"127.0.0.0/8"}
                                    """.formatted(suffix)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("NETWORK_TARGET_FORBIDDEN"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_no=?", Long.class, "CM-OB-" + suffix)).isZero();
        } finally {
            // 不参与测试事务，必须自行清理，否则共享 H2 中的设备总数会影响 DeviceOperationsApiTest 等用例。
            cleanupOnboarded(deviceNo);
        }
    }

    private void cleanupOnboarded(String deviceNo) {
        for (String table : List.of("device_event_log", "radar_v3_profile", "countermeasure_4ch_profile",
                "device_connection_profile", "ops_device_state"))
            jdbc.update("DELETE FROM " + table + " WHERE device_id IN (SELECT device_id FROM ops_device WHERE device_no=?)", deviceNo);
        jdbc.update("DELETE FROM ops_device WHERE device_no=?", deviceNo);
        jdbc.update("DELETE FROM ops_integration_source WHERE source_code=?", deviceNo);
        jdbc.update("DELETE FROM integration_source WHERE source_code=?", deviceNo);
    }

    @Test
    void sourceEnableRejectsLoopbackEvenWhenListed() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode source = mapper.readTree(mvc.perform(post("/api/v1/integration-sources")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source_code\":\"CM-" + suffix + "\",\"name\":\"反制回归来源\","
                                + "\"protocol_code\":\"COUNTERMEASURE_TCP_4CH_V2_0\",\"allowed_cidrs\":\"127.0.0.0/8\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String body = """
                {"source_id":"%s","external_device_id":"CM-%s","device_no":"CM-%s","name":"四通道控制器",
                 "device_type_code":"countermeasure","device_type_name":"反制","channel":"反制直连",
                 "connection":{"transport":"TCP","host":"127.0.0.1","port":10006},
                 "protocol_configuration":{"device_address":1,"wire_encoding":"AUTO","poll_interval_millis":5000}}
                """.formatted(source.path("source_id").asText(), suffix, suffix);
        mvc.perform(post("/api/v1/devices").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/integration-sources/{id}/enabled", source.path("source_id").asText())
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"version\":0,\"reason\":\"验证网络边界\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NETWORK_TARGET_FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_source WHERE source_code=? AND source_type='RADAR'",
                Long.class, "CM-" + suffix)).isZero();
    }

    @Test
    void radarPersistenceDeduplicatesTrackExpiresAfterThreeSecondsAndAveragesTwentyRtkFrames() {
        String deviceId = jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='DEV-MOCK-001'", String.class);
        String sourceId = jdbc.queryForObject("SELECT source_id FROM ops_device WHERE device_id=?", String.class, deviceId);
        assertThat(protocolData.insertInbox(sourceId, deviceId, "golden:duplicate", new byte[] { 1, 2, 3 }, 1)).isTrue();
        assertThat(protocolData.insertInbox(sourceId, deviceId, "golden:duplicate", new byte[] { 1, 2, 3 }, 2)).isFalse();
        assertThat(jdbc.queryForObject("SELECT payload_sha256 FROM inbox_message WHERE source_msg_id='golden:duplicate'", String.class))
                .hasSize(64);
        TrackItem item = new TrackItem("88", BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, 0, "PENDING_IDENTIFICATION", false);
        long received = System.currentTimeMillis() - 4000;
        TrackBatch batch = new TrackBatch(123L, "998", received, BigDecimal.ZERO, BigDecimal.ONE, 0, 0, List.of(item));
        protocolData.saveTrackBatch(deviceId, "DEV-MOCK-001", batch, received);
        protocolData.saveTrackBatch(deviceId, "DEV-MOCK-001", batch, received);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_track_point p JOIN ops_track t ON t.track_id=p.track_id WHERE t.device_id=? AND t.external_track_id='88'",
                Long.class, deviceId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source LIKE 'live-radar:%' AND source_msg_id LIKE '123:%'",
                Long.class)).isZero();
        protocolData.expireTracks(System.currentTimeMillis() - 3000, System.currentTimeMillis());
        assertThat(jdbc.queryForObject("SELECT active FROM ops_track WHERE device_id=? AND external_track_id='88'", Boolean.class, deviceId)).isFalse();

        for (int i = 0; i < 20; i++) protocolData.saveRtk(deviceId, "rtk-" + i,
                new Rtk(new BigDecimal("37.123456789"), new BigDecimal("118.987654321"),
                        new BigDecimal("12.500000000"), 18, 999999), received + i);
        assertThat(jdbc.queryForObject("SELECT sample_count FROM radar_site_reference WHERE device_id=?", Integer.class, deviceId)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT verified FROM radar_site_reference WHERE device_id=?", Boolean.class, deviceId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='radar_site_reference' AND column_name LIKE '%altitude%'",
                Integer.class)).isZero();
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("session_id").asText();
    }
    private static String bearer(String token) { return "Bearer " + token; }
}
