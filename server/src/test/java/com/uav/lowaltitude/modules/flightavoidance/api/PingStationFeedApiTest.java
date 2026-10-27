package com.uav.lowaltitude.modules.flightavoidance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.uav.lowaltitude.modules.flightavoidance.infrastructure.PingStationUdpListener;

/**
 * 2026-10-27 用户确认：客户的航班接收机是 pingStation3，通过 UDP 推送 JSON；后台登记识别码，数据对上就收，启用后参与检查。
 * 本用例不开事务：UDP 监听线程在别的事务里写库，用例自己收尾。
 */
@SpringBootTest(properties = {"app.live-device.enabled=false", "app.mqtt.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PingStationFeedApiTest {
    private static final String GUID = "754104714b10e9a6";
    private static final double LON = 118.614, LAT = 37.464;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PingStationUdpListener listener;

    @Test
    void udpTrafficFromARegisteredStationFeedsTheFlightCheckOnceEnabled() throws Exception {
        String token = login();
        String id = createReceiver(token, GUID);
        mvc.perform(get("/api/v1/adsb-receivers/{id}", id).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.station_guid").value(GUID)).andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.last_data_at").doesNotExist());

        // 真实的 UDP 报文：一架航班在接收机正北 5 公里、1500 米；时间戳按 ICD 的 ISO-8601。
        assertThat(listener.port()).isGreaterThan(0);
        long observed = System.currentTimeMillis() - 1000;
        send(traffic(GUID, "780A1B", "CES2471 ", LAT + 5 / 111.32, LON, 1_500_000, observed));
        waitUntil(() -> jdbc.queryForObject("SELECT COUNT(*) FROM adsb_aircraft WHERE receiver_id=?", Long.class, id) == 1);
        var row = jdbc.queryForMap("SELECT * FROM adsb_aircraft WHERE receiver_id=?", id);
        assertThat(row.get("callsign")).isEqualTo("CES2471");
        assertThat(row.get("airline")).isEqualTo("东方航空");
        assertThat(row.get("altitude_m")).isEqualTo(1500);
        assertThat(((Number) row.get("track_deg")).doubleValue()).isEqualTo(263.3);
        assertThat(((Number) row.get("ground_speed_mps")).doubleValue()).isEqualTo(211.43);
        assertThat(((Number) row.get("observed_at")).longValue()).isEqualTo(observed / 1000 * 1000);
        assertThat(row.get("simulated")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT last_data_at FROM adsb_receiver WHERE device_id=?", Long.class, id)).isNotNull();

        // 没启用：地图和检查都还不看它。
        mvc.perform(get("/api/v1/flight-avoidance/traffic").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivers.length()").value(0)).andExpect(jsonPath("$.data.aircraft.length()").value(0));

        // 启用后立刻按它检查：5 公里内的航班 → 禁止反制。
        mvc.perform(patch("/api/v1/devices/{id}/enabled", id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"enabled\":true,\"reason\":\"数据已正常\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/flight-avoidance/traffic").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivers[0].online").value(true))
                .andExpect(jsonPath("$.data.aircraft[0].callsign").value("CES2471"));
        String counter = insertCounter();
        mvc.perform(get("/api/v1/flight-avoidance/check").param("device_id", counter).param("countermeasure_mode", "FORCE_LAND")
                .header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.level").value("BLOCK"));

        // 状态报文只更新「最近收到数据」，不加航班。
        jdbc.update("UPDATE adsb_receiver SET last_data_at=1 WHERE device_id=?", id);
        send("{\"status\":{\"pingStationGuid\":\"" + GUID + "\",\"gpsStatus\":3,\"receiverStatus\":0}}");
        waitUntil(() -> jdbc.queryForObject("SELECT last_data_at FROM adsb_receiver WHERE device_id=?", Long.class, id) > 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adsb_aircraft WHERE receiver_id=?", Long.class, id)).isEqualTo(1);

        // 没登记的识别码：不收，记到未匹配站点里给登记页提示；超出接收范围的航班也不收。
        send(traffic("00000000deadbeef", "780B44", "CDG4703", LAT, LON, 9_000_000, observed));
        send(traffic(GUID, "7808F2", "CHH7671", LAT + 400 / 111.32, LON, 9_000_000, observed));
        waitUntil(() -> !listener.unmatchedStations().isEmpty());
        mvc.perform(get("/api/v1/adsb-receivers/unmatched-stations").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.udp_port").value(listener.port()))
                .andExpect(jsonPath("$.data.stations[0].station_guid").value("00000000deadbeef"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM adsb_aircraft WHERE receiver_id=?", Long.class, id)).isEqualTo(1);
        cleanup(id, counter);
    }

    @Test
    void stationGuidMustBeUniqueAndIsRequiredBeforeEnabling() throws Exception {
        String token = login();
        String first = createReceiver(token, "a1b2c3d4e5f60001"), second = createReceiver(token, null);
        ObjectNode input = receiverInput(token, "a1B2C3D4E5F60001");
        input.put("version", 0);
        input.put("device_no", jdbc.queryForObject("SELECT device_no FROM ops_device WHERE device_id=?", String.class, second));
        mvc.perform(put("/api/v1/adsb-receivers/{id}", second).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(input.toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATION_GUID_CONFLICT"));
        mvc.perform(patch("/api/v1/devices/{id}/enabled", second).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"enabled\":true,\"reason\":\"没填识别码\"}")).andExpect(status().isBadRequest());
        input.put("station_guid", "not-hex");
        mvc.perform(put("/api/v1/adsb-receivers/{id}", second).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(input.toString())).andExpect(status().isBadRequest());
        // 直接调解析：不是 pingStation 的报文不收。
        assertThat(listener.handle("{\"hello\":1}", "127.0.0.1")).isEqualTo(-2);
        cleanup(first, second);
    }

    private String traffic(String guid, String icao, String callsign, double lat, double lon, long altitudeMm, long observedAt) {
        return "{\"aircraft\":[{\"icaoAddress\":\"" + icao + "\",\"trafficSource\":0,\"latDD\":" + lat + ",\"lonDD\":" + lon
                + ",\"altitudeMM\":" + altitudeMm + ",\"headingDE2\":26330,\"horVelocityCMS\":21143,\"verVelocityCMS\":0,\"squawk\":1305,"
                + "\"altitudeType\":0,\"callsign\":\"" + callsign + "\",\"emitterType\":5,\"sequenceNumber\":81223,\"pingStationGuid\":\""
                + guid + "\",\"utcSync\":1,\"timeStamp\":\"" + java.time.Instant.ofEpochMilli(observedAt / 1000 * 1000) + "\"}]}";
    }

    private void send(String payload) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), listener.port()));
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as("UDP 报文应在 5 秒内处理完").isTrue();
    }

    private ObjectNode receiverInput(String token, String guid) throws Exception {
        var scopes = mapper.readTree(mvc.perform(get("/api/v1/mqtt-brokers/scopes").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").get(0);
        ObjectNode input = mapper.createObjectNode();
        input.put("device_no", "ADSB-" + UUID.randomUUID().toString().substring(0, 8)).put("name", "东营航班接收机")
                .put("vendor", "uAvionix").put("model", "pingStation3").put("owner_org_id", scopes.path("org_id").asText())
                .put("district_id", scopes.path("district_id").asText()).put("longitude", LON).put("latitude", LAT)
                .put("receive_range_km", 350);
        if (guid != null) input.put("station_guid", guid);
        return input;
    }

    private String createReceiver(String token, String guid) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/adsb-receivers").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(receiverInput(token, guid).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("device_id").asText();
    }

    private String insertCounter() {
        long now = System.currentTimeMillis();
        String sourceId = UUID.randomUUID().toString(), deviceId = UUID.randomUUID().toString(), no = "CM4-PS-" + deviceId.substring(0, 8);
        jdbc.update("""
                INSERT INTO ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,
                    source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'live',TRUE,'127.0.0.1/32',TRUE,0,?,?)
                """, sourceId, no, "航班夹具", com.uav.lowaltitude.integration.device.DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, "2.0", now, now);
        jdbc.update("""
                INSERT INTO ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,
                    device_type_name,channel,longitude,latitude,coordinate_system,enabled,source_mode,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'countermeasure','反制','反制直连',?,?,'WGS-84',TRUE,'live',TRUE,0,?,?)
                """, deviceId, sourceId, no, no, "航班夹具", LON, LAT, now, now);
        jdbc.update("INSERT INTO countermeasure_4ch_profile (device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at) VALUES (?,1,'ASCII_HEX_SPACED',5000,0,?)",
                deviceId, now);
        return deviceId;
    }

    private void cleanup(String... deviceIds) {
        for (String id : deviceIds) {
            jdbc.update("DELETE FROM counter_flight_check WHERE device_id=?", id);
            jdbc.update("DELETE FROM countermeasure_4ch_profile WHERE device_id=?", id);
            jdbc.update("DELETE FROM adsb_aircraft WHERE receiver_id=?", id);
            jdbc.update("DELETE FROM adsb_receiver WHERE device_id=?", id);
            jdbc.update("DELETE FROM device_event_log WHERE device_id=?", id);
            jdbc.update("DELETE FROM device_business_scope WHERE ops_device_id=?", id);
            String source = jdbc.queryForList("SELECT source_id FROM ops_device WHERE device_id=?", String.class, id).stream().findFirst().orElse(null);
            jdbc.update("DELETE FROM ops_device WHERE device_id=?", id);
            if (source != null) jdbc.update("DELETE FROM ops_integration_source WHERE source_id=?", source);
        }
    }

    private String login() throws Exception {
        return "Bearer " + mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
}
