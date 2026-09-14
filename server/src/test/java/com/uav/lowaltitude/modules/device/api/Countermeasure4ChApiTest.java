package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.application.Countermeasure4ChControlService;
import com.uav.lowaltitude.platform.worker.OutboxWorker;

@SpringBootTest(properties = {
        "app.network.allow-loopback-when-listed=true",
        "app.live-device.enabled=false",
        "app.mqtt.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Countermeasure4ChApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker outboxWorker;

    @Test
    void setMaskSucceedsOnLoopbackSimulatorAndRejectsRadarAndEmergencyStop() throws Exception {
        String token = login();
        String radarId = jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='DEV-MOCK-001'", String.class);
        mvc.perform(post("/api/v1/devices/{id}/commands/countermeasure-4ch", radarId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_id\":\"AUTH-4CH\",\"action\":\"SET_MASK\",\"mask\":15,\"reason\":\"雷达不能走四通道\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTROL_NOT_ENABLED"));
        mvc.perform(post("/api/v1/devices/{id}/commands/emergency-stop", radarId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTROL_NOT_ENABLED"));

        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> seen = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    byte[] request = readUntilNewline(socket);
                    String text = new String(request, StandardCharsets.US_ASCII);
                    socket.getOutputStream().write("22 01 13 00 00 00 0F 45\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return text;
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            String deviceId = insertFourCh(server.getLocalPort());
            String idem = key();
            String body = "{\"authorization_id\":\"AUTH-4CH\",\"action\":\"SET_MASK\",\"mask\":15,\"reason\":\"本机模拟迫降全开\"}";
            JsonNode first = mapper.readTree(mvc.perform(post("/api/v1/devices/{id}/commands/countermeasure-4ch", deviceId)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", idem)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.command_type").value(Countermeasure4ChControlService.TYPE))
                    .andReturn().getResponse().getContentAsString()).path("data");
            JsonNode replay = mapper.readTree(mvc.perform(post("/api/v1/devices/{id}/commands/countermeasure-4ch", deviceId)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", idem)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString()).path("data");
            assertThat(replay.path("command_id").asText()).isEqualTo(first.path("command_id").asText());
            mvc.perform(post("/api/v1/devices/{id}/commands/countermeasure-4ch", deviceId)
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            outboxWorker.poll();
            assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?",
                    String.class, first.path("command_id").asText())).isEqualTo("SUCCEEDED");
            assertThat(seen.get(5, TimeUnit.SECONDS)).contains("55 01 13 00 00 00 0F 78");
        }
    }

    private String insertFourCh(int port) {
        long now = System.currentTimeMillis();
        String sourceId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String no = "CM4-T-" + deviceId.substring(0, 8);
        jdbc.update("""
                INSERT INTO ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,
                    source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'live',TRUE,'127.0.0.1/32',TRUE,0,?,?)
                """, sourceId, no, "四通道 API 夹具", DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, "2.0", now, now);
        jdbc.update("""
                INSERT INTO ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,
                    device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'countermeasure','反制','反制直连',TRUE,'live',TRUE,0,?,?)
                """, deviceId, sourceId, no, no, "四通道 API 夹具", now, now);
        jdbc.update("""
                INSERT INTO device_connection_profile (device_id,transport,host,port,timeout_millis,retry_count,version,updated_at)
                VALUES (?,'TCP','127.0.0.1',?,1000,1,0,?)
                """, deviceId, port, now);
        jdbc.update("""
                INSERT INTO countermeasure_4ch_profile (device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at)
                VALUES (?,1,'ASCII_HEX_SPACED',5000,0,?)
                """, deviceId, now);
        jdbc.update("""
                INSERT INTO ops_device_state (device_id,connectivity,has_alarm,health_code,observed_at,received_at,simulated,version)
                VALUES (?,'ONLINE',FALSE,'GOOD',?,?,TRUE,0)
                """, deviceId, now, now);
        return deviceId;
    }

    private String login() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
    }

    private static byte[] readUntilNewline(Socket socket) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = socket.getInputStream().read()) >= 0) {
            out.write(value);
            if (value == '\n') break;
        }
        return out.toByteArray();
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private static String key() { return UUID.randomUUID().toString(); }
}
