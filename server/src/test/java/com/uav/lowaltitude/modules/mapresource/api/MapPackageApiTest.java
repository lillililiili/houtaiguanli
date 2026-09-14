package com.uav.lowaltitude.modules.mapresource.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.config.MapPackageProperties;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MapPackageApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MapPackageProperties properties;

    @AfterEach
    void cleanup() throws IOException {
        jdbc.update("update map_runtime_config set active_package_id=null,previous_package_id=null,revision=0,updated_by=null,updated_at=null,version=0 where config_id='global'");
        jdbc.update("delete from audit_log where object_type='map_package'");
        jdbc.update("delete from map_package");
        Path root = Path.of(properties.getDataDir()).toAbsolutePath().normalize();
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void uploadActivateSwitchCityAndRollbackPublishesAtomicRuntimePointer() throws Exception {
        String token = login();
        JsonNode dongying = upload(token, "dongying.zip", packageZip("东营底图", "20260913", 118, 37, 119, 38),
                "370500", "东营市");
        assertThat(dongying.path("status").asText()).isEqualTo("VALIDATED");

        JsonNode first = data(mvc.perform(post("/api/v1/map-packages/{id}/activate", dongying.path("package_id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0,\"reason\":\"启用东营正式底图\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime.business_overlays_visible").value(true)));
        assertThat(first.path("runtime").path("version").asInt()).isEqualTo(1);

        JsonNode qingdao = upload(token, "qingdao.zip", packageZip("青岛底图", "20260914", 120, 35, 121, 37),
                "370200", "青岛市");
        JsonNode second = data(mvc.perform(post("/api/v1/map-packages/{id}/activate", qingdao.path("package_id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":1,\"reason\":\"切换青岛演示地图\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime.business_overlays_visible").value(false)));
        assertThat(second.path("runtime").path("previous_package_id").asText())
                .isEqualTo(dongying.path("package_id").asText());

        Path pointer = Path.of(properties.getDataDir()).toAbsolutePath().resolve("control/map-config.json");
        JsonNode config = json.readTree(Files.readAllBytes(pointer));
        assertThat(config.path("city_code").asText()).isEqualTo("370200");
        assertThat(config.path("clear_business_overlays").asBoolean()).isTrue();
        assertThat(config.path("manifest").asText()).contains(qingdao.path("package_id").asText());

        mvc.perform(post("/api/v1/map-packages/rollback")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":2,\"reason\":\"回滚验收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime.business_overlays_visible").value(true));
        assertThat(json.readTree(Files.readAllBytes(pointer)).path("city_code").asText()).isEqualTo("370500");
    }

    @Test
    void activeAndRollbackReservedPackagesCannotBeDeleted() throws Exception {
        String token = login();
        JsonNode row = upload(token, "active.zip", packageZip("东营底图", "v1", 118, 37, 119, 38),
                "370500", "东营市");
        mvc.perform(post("/api/v1/map-packages/{id}/activate", row.path("package_id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0,\"reason\":\"测试启用\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/map-packages/{id}", row.path("package_id").asText())
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":1,\"reason\":\"错误删除尝试\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MAP_PACKAGE_IN_USE"));
    }

    @Test
    void zipTraversalIsRejectedWithoutPublishingFiles() throws Exception {
        String token = login();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../outside.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        mvc.perform(multipart("/api/v1/map-packages")
                        .file(new MockMultipartFile("file", "unsafe.zip", "application/zip", bytes.toByteArray()))
                        .param("city_code", "370500").param("city_name", "东营市").param("reason", "安全测试")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MAP_PACKAGE_INVALID"));
        assertThat(jdbc.queryForObject("select count(*) from map_package", Integer.class)).isZero();
    }

    private JsonNode upload(String token, String filename, byte[] zip, String cityCode, String cityName) throws Exception {
        return data(mvc.perform(multipart("/api/v1/map-packages")
                        .file(new MockMultipartFile("file", filename, "application/zip", zip))
                        .param("city_code", cityCode).param("city_name", cityName).param("reason", "测试地图包")
                        .header("Authorization", bearer(token)).header("Idempotency-Key", key()))
                .andExpect(status().isCreated()));
    }

    private byte[] packageZip(String name, String version, double west, double south, double east, double north)
            throws Exception {
        byte[] pmtiles = pmtiles(west, south, east, north);
        byte[] manifest = ("{\"version\":1,\"coordinateSystem\":\"WGS84\",\"name\":\"" + name
                + "\",\"dataVersion\":\"" + version + "\",\"archive\":\"./map.pmtiles\","
                + "\"style\":\"./style.json\",\"bounds\":[" + west + "," + south + "," + east + "," + north
                + "],\"minZoom\":0,\"maxZoom\":15,\"displayMaxZoom\":18}").getBytes(StandardCharsets.UTF_8);
        byte[] style = "{\"version\":8,\"sources\":{\"protomaps\":{\"type\":\"vector\",\"url\":\"pmtiles://ignored\"}},\"layers\":[]}".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("map.pmtiles", pmtiles);
        files.put("manifest.json", manifest);
        files.put("style.json", style);
        StringBuilder checksums = new StringBuilder("{\"algorithm\":\"SHA-256\",\"files\":[");
        boolean first = true;
        for (var entry : files.entrySet()) {
            if (!first) checksums.append(',');
            first = false;
            checksums.append("{\"path\":\"").append(entry.getKey()).append("\",\"bytes\":")
                    .append(entry.getValue().length).append(",\"sha256\":\"").append(sha(entry.getValue())).append("\"}");
        }
        checksums.append("]}");
        files.put("checksums.json", checksums.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] pmtiles(double west, double south, double east, double north) {
        ByteBuffer header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN);
        header.put("PMTiles".getBytes(StandardCharsets.US_ASCII));
        header.put(7, (byte) 3);
        header.put(99, (byte) 1);
        header.put(100, (byte) 0);
        header.put(101, (byte) 15);
        header.putInt(102, (int) Math.round(west * 10_000_000));
        header.putInt(106, (int) Math.round(south * 10_000_000));
        header.putInt(110, (int) Math.round(east * 10_000_000));
        header.putInt(114, (int) Math.round(north * 10_000_000));
        return header.array();
    }

    private String login() throws Exception {
        return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk())).path("session_id").asText();
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
