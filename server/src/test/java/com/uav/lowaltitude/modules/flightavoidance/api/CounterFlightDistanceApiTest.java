package com.uav.lowaltitude.modules.flightavoidance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

/** 2026-10-27 用户确认：每台反制设备可以单独设置附近航班的安全距离，没设置的用规则管理里的默认值。 */
@SpringBootTest(properties = {"app.live-device.enabled=false", "app.mqtt.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CounterFlightDistanceApiTest {
    private static final double LON = 118.614, LAT = 37.464;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void ownDistancesReplaceTheDefaultsForThatDeviceOnly() throws Exception {
        String token = login(), counter = insertCounter(), other = insertCounter();
        // 一架 1500 米的航班停在反制设备正北 5 公里。
        insertReceiverWithAircraft(5.0, 1500);

        check(token, counter, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("BLOCK"));
        mvc.perform(get("/api/v1/flight-avoidance/devices/{id}/distance", counter).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.custom").value(false))
                .andExpect(jsonPath("$.data.force_land_block_km").value(10.0)).andExpect(jsonPath("$.data.can_manage").value(true));

        save(token, counter, "{\"custom\":true,\"expected_version\":null,\"force_land_block_km\":3,\"force_land_warn_km\":8,"
                + "\"force_land_max_altitude_m\":2000,\"drive_away_warn_km\":2,\"drive_away_max_altitude_m\":800}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.custom").value(true)).andExpect(jsonPath("$.data.version").value(1));

        check(token, counter, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("WARN"))
                .andExpect(jsonPath("$.data.block_km").value(3.0)).andExpect(jsonPath("$.data.warn_km").value(8.0));
        check(token, counter, "DRIVE_AWAY").andExpect(jsonPath("$.data.level").value("CLEAR"));
        // 另一台没单独设置的设备仍按默认值。
        check(token, other, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("BLOCK"));

        mvc.perform(get("/api/v1/flight-avoidance/traffic").header("Authorization", bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device_distances.length()").value(1))
                .andExpect(jsonPath("$.data.device_distances[0].device_id").value(counter))
                .andExpect(jsonPath("$.data.device_distances[0].force_land_warn_km").value(8.0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='counter_flight_distance_changed' AND object_id=?",
                Long.class, counter)).isEqualTo(1);

        // 改回默认值。
        save(token, counter, "{\"custom\":false,\"expected_version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custom").value(false));
        check(token, counter, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("BLOCK"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM counter_flight_distance", Long.class)).isZero();
    }

    @Test
    void rejectsStaleVersionsBadRangesAndDevicesThatAreNotCounters() throws Exception {
        String token = login(), counter = insertCounter();
        String custom = "{\"custom\":true,\"expected_version\":%s,\"force_land_block_km\":%s,\"force_land_warn_km\":8,"
                + "\"force_land_max_altitude_m\":2000,\"drive_away_warn_km\":2,\"drive_away_max_altitude_m\":800}";
        save(token, counter, custom.formatted("null", "9")).andExpect(status().isBadRequest());
        save(token, counter, custom.formatted("3", "3")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        save(token, counter, "{\"custom\":true,\"expected_version\":null}").andExpect(status().isBadRequest());
        save(token, UUID.randomUUID().toString(), custom.formatted("null", "3")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM counter_flight_distance", Long.class)).isZero();
    }

    private ResultActions check(String token, String deviceId, String mode) throws Exception {
        return mvc.perform(get("/api/v1/flight-avoidance/check").param("device_id", deviceId).param("countermeasure_mode", mode)
                .header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    private ResultActions save(String token, String deviceId, String body) throws Exception {
        return mvc.perform(put("/api/v1/flight-avoidance/devices/{id}/distance", deviceId).header("Authorization", bearer(token))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String insertCounter() {
        long now = System.currentTimeMillis();
        String sourceId = UUID.randomUUID().toString(), deviceId = UUID.randomUUID().toString(), no = "CM4-FD-" + deviceId.substring(0, 8);
        jdbc.update("""
                INSERT INTO ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,
                    source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'live',TRUE,'127.0.0.1/32',TRUE,0,?,?)
                """, sourceId, no, "航班距离夹具", DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, "2.0", now, now);
        jdbc.update("""
                INSERT INTO ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,
                    device_type_name,channel,longitude,latitude,coordinate_system,enabled,source_mode,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'countermeasure','反制','反制直连',?,?,'WGS-84',TRUE,'live',TRUE,0,?,?)
                """, deviceId, sourceId, no, no, "航班距离夹具", LON, LAT, now, now);
        jdbc.update("""
                INSERT INTO countermeasure_4ch_profile (device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at)
                VALUES (?,1,'ASCII_HEX_SPACED',5000,0,?)
                """, deviceId, now);
        return deviceId;
    }

    /** 2026-10-28 用户「改后台」：接收机 60 秒内有过报文就算收得到航班信息；航班位置仍只认最近 30 秒的。 */
    @Test
    void receiverCountsAsAliveForSixtySecondsWhileAircraftPositionsExpireAfterThirty() throws Exception {
        String token = login(), counter = insertCounter();
        long now = System.currentTimeMillis();
        // 天上没飞机：接收机只有每 30 秒一条状态报文，45 秒前的状态仍算收得到。
        String receiver = insertReceiver(now - 45_000);
        check(token, counter, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("CLEAR"));
        // 超过 60 秒没有任何报文才算收不到航班信息。
        jdbc.update("UPDATE adsb_receiver SET last_data_at=? WHERE device_id=?", now - 61_000, receiver);
        check(token, counter, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("NO_DATA"));
        // 接收机刚报过状态，但禁止反制范围里那架航班 40 秒没再报位置：不算在附近。
        jdbc.update("UPDATE adsb_receiver SET last_data_at=? WHERE device_id=?", now, receiver);
        insertAircraft(receiver, 5.0, 1500, now - 40_000);
        check(token, counter, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("CLEAR"));
        jdbc.update("UPDATE adsb_aircraft SET received_at=? WHERE receiver_id=?", now - 20_000, receiver);
        check(token, counter, "FORCE_LAND").andExpect(jsonPath("$.data.level").value("BLOCK"));
    }

    private void insertReceiverWithAircraft(double northKm, int altitudeM) {
        long now = System.currentTimeMillis();
        insertAircraft(insertReceiver(now), northKm, altitudeM, now);
    }

    private String insertReceiver(long lastDataAt) {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,simulated,version,created_at,updated_at)
                VALUES (?,?,?,'ADSB_PENDING','live',TRUE,FALSE,0,?,?)
                """, id, "adsb-" + id, "航班接收机夹具", now, now);
        jdbc.update("""
                INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,
                    channel,longitude,latitude,coordinate_system,enabled,source_mode,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'adsb_receiver','ADS-B 航班接收机','网络接入',?,?,'WGS-84',TRUE,'live',FALSE,0,?,?)
                """, id, id, "ADSB-FD", "ADSB-FD", "航班接收机夹具", LON, LAT, now, now);
        jdbc.update("INSERT INTO adsb_receiver(device_id,receive_range_km,last_data_at) VALUES(?,350,?)", id, lastDataAt);
        return id;
    }

    /** 一架航班停在反制设备正北 northKm 公里。 */
    private void insertAircraft(String receiverId, double northKm, int altitudeM, long receivedAt) {
        jdbc.update("""
                INSERT INTO adsb_aircraft(receiver_id,icao24,callsign,longitude,latitude,altitude_m,ground_speed_mps,track_deg,
                    observed_at,received_at,simulated) VALUES(?,'780A1B','CES2471',?,?,?,0,0,?,?,FALSE)
                """, receiverId, LON, LAT + northKm / 111.32, altitudeM, receivedAt, receivedAt);
    }

    private String login() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
