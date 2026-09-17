package com.uav.lowaltitude.modules.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.config.AppProperties;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvidencePreviewApiTest {
    private static final byte[] PAYLOAD = "evidence-bytes-v1".getBytes(StandardCharsets.UTF_8);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AppProperties properties;

    private String suffix, org, district, otherOrg, otherDistrict, targetId;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "ev-org-" + suffix;
        district = "ev-dist-" + suffix;
        otherOrg = "ev-other-org-" + suffix;
        otherDistrict = "ev-other-dist-" + suffix;
        catalog(org, district);
        catalog(otherOrg, otherDistrict);
        targetId = "ev-target-" + suffix;
        jdbc.update("""
                insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version)
                values (?,?,'UAV','mock',?,?,?,?,?,?,0)
                """, targetId, "T-" + suffix, org, district, java.sql.Timestamp.from(java.time.Instant.now()),
                java.sql.Timestamp.from(java.time.Instant.now()), java.sql.Timestamp.from(java.time.Instant.now()),
                java.sql.Timestamp.from(java.time.Instant.now()));
    }

    @Test
    void previewPermissionIsIndependentAndJsonRemainsRaw() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest");
        String id = file(token, "points.json", "application/json", "{\"points\":[1,2]}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/evidence-files/" + id + "/preview").header("Authorization", bearer(token))).andExpect(status().isForbidden());
        grantAction(token, "evidence:preview");
        var result = mvc.perform(get("/api/v1/evidence-files/" + id + "/preview").header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(result.getContentType()).startsWith("application/json");
        assertThat(result.getContentAsString()).isEqualTo("{\"points\":[1,2]}");
        assertThat(result.getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        mvc.perform(get("/api/v1/evidence-files/" + id + "/content").header("Authorization", bearer(token))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from evidence_access_log where evidence_id=? and action='PREVIEW' and result='GRANTED'", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from evidence_access_log where evidence_id=? and action='DOWNLOAD'", Long.class, id)).isZero();
    }

    @Test
    void thumbnailIsRealImageAndHasTheSameScopeAndPermission() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest", "evidence:preview");
        var img = new java.awt.image.BufferedImage(800, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(img, "png", out);
        String id = file(token, "photo.png", "image/png", out.toByteArray());
        var response = mvc.perform(get("/api/v1/evidence-files/" + id + "/thumbnail").header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn().getResponse();
        var thumbnail = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.getContentAsByteArray()));
        assertThat(thumbnail.getWidth()).isLessThanOrEqualTo(320);
        assertThat(thumbnail.getHeight()).isLessThanOrEqualTo(320);
        String outsider = reader("ASSIGNED", otherOrg, otherDistrict);
        grantAction(outsider, "evidence:preview", "evidence:ingest");
        mvc.perform(get("/api/v1/evidence-files/" + id + "/preview").header("Authorization", bearer(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/evidence-files/" + id + "/thumbnail").header("Authorization", bearer(outsider))).andExpect(status().isNotFound());
    }

    @Test
    void forgedImageAndChangedBytesAreDeniedAndDenialSurvives() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest", "evidence:preview");
        String forged = file(token, "fake.png", "image/png", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/evidence-files/" + forged + "/preview").header("Authorization", bearer(token))).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error.code").value("EVIDENCE_PREVIEW_UNSUPPORTED"));
        assertThat(jdbc.queryForObject("select count(*) from evidence_access_log where evidence_id=? and action='PREVIEW' and result='DENIED'", Long.class, forged)).isEqualTo(1);
        String changed = file(token, "note.txt", "text/plain", "original".getBytes(StandardCharsets.UTF_8));
        String object = jdbc.queryForObject("select object_key from evidence_file where evidence_id=?", String.class, changed);
        Files.write(Path.of(properties.getEvidenceDir()).resolve(object), "changed!".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/evidence-files/" + changed + "/preview").header("Authorization", bearer(token))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVIDENCE_CORRUPT"));
        assertThat(jdbc.queryForObject("select status from evidence_file where evidence_id=?", String.class, changed)).isEqualTo("AVAILABLE");
    }

    @Test
    void unavailableFilesRemainMetadataOnlyAndRevocationStopsNewRequests() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest", "evidence:preview");
        String id = file(token, "note.txt", "text/plain", "note".getBytes(StandardCharsets.UTF_8));
        for (String state : new String[]{"PENDING", "MISSING", "CORRUPT", "DESTROYED"}) {
            if ("DESTROYED".equals(state)) {
                jdbc.update("update evidence_file set status='DESTROYED',destroyed_at=current_timestamp,destroyed_by=(select user_id from app_session where session_id=?),destroy_reason='preview fixture' where evidence_id=?", token, id);
            } else jdbc.update("update evidence_file set status=? where evidence_id=?", state, id);
            mvc.perform(get("/api/v1/evidence-files/" + id + "/preview").header("Authorization", bearer(token))).andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("select count(*) from evidence_access_log where evidence_id=? and action='PREVIEW' and result='DENIED'", Long.class, id)).isEqualTo(4);
        jdbc.update("delete from app_role_permission where permission_code='evidence:preview' and role_code=(select u.role_code from app_user u join app_session s on s.user_id=u.user_id where s.session_id=?)", token);
        mvc.perform(get("/api/v1/evidence-files/" + id + "/preview").header("Authorization", bearer(token))).andExpect(status().isForbidden());
    }

    @Test
    void captureCoordinatesRoundTripAndIncompletePositionIsRejected() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest");
        var request = multipart("/api/v1/evidence-files").file(new MockMultipartFile("file", "position.txt", "text/plain", PAYLOAD))
                .param("kind_code", "EO_STILL").param("owner_org_id", org).param("district_id", district)
                .param("capture_longitude", "118.3").param("capture_latitude", "37.4")
                .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString());
        mvc.perform(request).andExpect(status().isCreated()).andExpect(jsonPath("$.data.capture_longitude").value(118.3))
                .andExpect(jsonPath("$.data.capture_latitude").value(37.4)).andExpect(jsonPath("$.data.capture_provenance").value("UPLOADER_DECLARED"));
        mvc.perform(multipart("/api/v1/evidence-files").file(new MockMultipartFile("file", "bad.txt", "text/plain", PAYLOAD))
                .param("kind_code", "EO_STILL").param("owner_org_id", org).param("district_id", district).param("capture_latitude", "37.4")
                .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isBadRequest());
    }

    @Test
    void sourceDeviceNameIsFrozenAtIngestAndMustShareScope() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest", "device:read");
        String device = UUID.randomUUID().toString();
        jdbc.update("insert into ops_device (device_id,device_no,name,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) values (?,?,?,'雷达','融合感知箱',true,'mock',true,0,0,0)", device, "PV-" + suffix, "采集设备原名");
        jdbc.update("insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,current_timestamp,current_timestamp)", device, org, district);
        var result = mvc.perform(multipart("/api/v1/evidence-files").file(new MockMultipartFile("file", "device.txt", "text/plain", PAYLOAD))
                .param("kind_code", "EO_STILL").param("owner_org_id", org).param("district_id", district).param("source_device_id", device)
                .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.source_device_name").value("采集设备原名")).andReturn().getResponse();
        String id = json.readTree(result.getContentAsString()).get("data").get("evidence_id").asText();
        jdbc.update("update ops_device set name='后来设备名称' where device_id=?", device);
        mvc.perform(get("/api/v1/evidence-files/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source_device_name").value("采集设备原名"));
        jdbc.update("update device_business_scope set owner_org_id=?,district_id=? where ops_device_id=?", otherOrg, otherDistrict, device);
        mvc.perform(multipart("/api/v1/evidence-files").file(new MockMultipartFile("file", "bad-device.txt", "text/plain", PAYLOAD))
                .param("kind_code", "EO_STILL").param("owner_org_id", org).param("district_id", district).param("source_device_id", device)
                .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    void webpIsPreviewableButThumbnailExplicitlyDeclinesWithoutDecoder() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest", "evidence:preview");
        byte[] bytes = java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        String id = file(token, "pixel.webp", "image/webp", bytes);
        mvc.perform(get("/api/v1/evidence-files/" + id + "/preview").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/evidence-files/" + id + "/thumbnail").header("Authorization", bearer(token)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void realWebmAndPdfBytesPreviewWithoutDownloadPermission() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest", "evidence:preview");
        for (String type : new String[]{"video/webm", "application/pdf"}) {
            String resource = type.equals("video/webm") ? "/evidence/preview-vp8.webm" : "/evidence/preview-document.pdf";
            byte[] bytes;
            try (var stream = getClass().getResourceAsStream(resource)) { bytes = stream.readAllBytes(); }
            String id = file(token, type.equals("video/webm") ? "clip.webm" : "document.pdf", type, bytes);
            var result = mvc.perform(get("/api/v1/evidence-files/" + id + "/preview").header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(result.getContentAsByteArray()).isEqualTo(bytes);
            assertThat(result.getContentType()).isEqualTo(type);
        }
        String fake = file(token, "fake.webm", "video/webm", "not a video".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/evidence-files/" + fake + "/preview").header("Authorization", bearer(token))).andExpect(status().isUnsupportedMediaType());
    }

    private String file(String token, String name, String type, byte[] bytes) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/v1/evidence-files").file(new MockMultipartFile("file", name, type, bytes))
                .param("kind_code", "EO_STILL").param("owner_org_id", org).param("district_id", district)
                .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data").get("evidence_id").asText();
    }

    private void catalog(String orgId, String districtId) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                orgId, orgId.toUpperCase(), orgId);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                districtId, districtId.toUpperCase(), districtId);
    }

    private String reader(String scope, String orgId, String districtId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String role = "ROLE-EV-" + id;
        String user = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)", user, "ev-" + id, "ev-tester", role, scope);
        if ("ASSIGNED".equals(scope)) {
            jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", user, orgId, districtId);
        }
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private void grantAction(String token, String... permissions) {
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) select u.role_code,?,'READ',false,current_timestamp from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?", permission, token);
        }
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
