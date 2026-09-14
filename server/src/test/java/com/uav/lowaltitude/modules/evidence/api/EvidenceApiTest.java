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
@Transactional
class EvidenceApiTest {
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
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/evidence-files"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void evidenceReadIsRequiredBeforeQueryIsInterpreted() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "target:read");
        mvc.perform(get("/api/v1/evidence-files?foo=1").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void unknownQueryIsRejectedAfterEvidenceRead() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read");
        mvc.perform(get("/api/v1/evidence-files?foo=1").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void ingestComputesSha256AndHidesUnlinkedFromReaders() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read");
        String readOnly = reader("ASSIGNED", org, district);
        grantAction(readOnly, "evidence:read");
        JsonNode created = ingestFile(ingest, "shot.jpg", org, district, null, null);
        assertThat(created.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(created.get("sha256").asText()).isEqualTo(sha(PAYLOAD));
        assertThat(created.get("size_bytes").asLong()).isEqualTo(PAYLOAD.length);
        assertThat(created.has("object_key")).isFalse();

        mvc.perform(get("/api/v1/evidence-files").header("Authorization", bearer(readOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/evidence-files").header("Authorization", bearer(ingest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void assignedReaderSeesLinkedOwnTargetNotOtherOrg() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read", "evidence:link");
        JsonNode created = ingestFile(ingest, "own.jpg", org, district, "TARGET", targetId);
        String evidenceId = created.get("evidence_id").asText();
        mvc.perform(get("/api/v1/evidence-files").header("Authorization", bearer(ingest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        String outsider = reader("ASSIGNED", otherOrg, otherDistrict);
        grantAction(outsider, "evidence:read", "evidence:ingest");
        mvc.perform(get("/api/v1/evidence-files").header("Authorization", bearer(outsider)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/evidence-files/" + evidenceId).header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadRequiresDownloadPermissionAndWritesAccessLog() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read", "evidence:link");
        String evidenceId = ingestFile(ingest, "clip.bin", org, district, "TARGET", targetId).get("evidence_id").asText();
        mvc.perform(get("/api/v1/evidence-files/" + evidenceId + "/content").header("Authorization", bearer(ingest)))
                .andExpect(status().isForbidden());

        grantAction(ingest, "evidence:download");
        byte[] body = mvc.perform(get("/api/v1/evidence-files/" + evidenceId + "/content")
                        .header("Authorization", bearer(ingest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(PAYLOAD);
        assertThat(jdbc.queryForObject(
                "select count(*) from evidence_access_log where evidence_id=? and action='DOWNLOAD' and result='GRANTED'",
                Long.class, evidenceId)).isEqualTo(1L);
    }

    @Test
    void holdBlocksSecondHoldAndVerifyDetectsMissingObject() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read", "evidence:link", "evidence:hold");
        JsonNode created = ingestFile(ingest, "hold.bin", org, district, "TARGET", targetId);
        String evidenceId = created.get("evidence_id").asText();
        JsonNode hold = json.readTree(mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/holds")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "hold-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"案件未结\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reason").value("案件未结"))
                .andReturn().getResponse().getContentAsString()).get("data");
        mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/holds")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "hold2-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"再次冻结\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HOLD_ACTIVE"));

        Path stored = Path.of(properties.getEvidenceDir()).toAbsolutePath().normalize()
                .resolve(jdbc.queryForObject("select object_key from evidence_file where evidence_id=?", String.class, evidenceId));
        Files.deleteIfExists(stored);
        mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/verify")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "verify-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MISSING"))
                .andExpect(jsonPath("$.data.matches").value(false));
        assertThat(hold.get("hold_id").asText()).isNotBlank();
    }

    @Test
    void ingestPersistsRetainUntilByKindAndHoldWinsCustody() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read", "evidence:hold");
        long captured = Instant.parse("2020-06-15T00:00:00Z").toEpochMilli();
        JsonNode report = ingestFile(ingest, "report.pdf", org, district, null, null, "COMMISSION_REPORT", captured);
        assertThat(report.get("retain_label").asText()).isEqualTo("90 天");
        assertThat(report.get("retain_note").asText()).isEqualTo("设备建设期记录，非案件证据");
        assertThat(report.get("custody").asText()).isEqualTo("DUE");
        assertThat(report.get("retain_until").asLong()).isEqualTo(
                Instant.parse("2020-06-15T00:00:00Z").atOffset(ZoneOffset.UTC).plusDays(90).toInstant().toEpochMilli());
        Timestamp storedUntil = jdbc.queryForObject(
                "select retain_until from evidence_file where evidence_id=?", Timestamp.class,
                report.get("evidence_id").asText());
        assertThat(storedUntil.toInstant()).isEqualTo(Instant.parse("2020-09-13T00:00:00Z"));

        JsonNode still = ingestFile(ingest, "shot.jpg", org, district, null, null);
        long stillCaptured = still.get("captured_at").asLong();
        assertThat(still.get("retain_label").asText()).isEqualTo("3 年");
        assertThat(still.get("custody").asText()).isEqualTo("KEPT");
        assertThat(still.get("retain_until").asLong()).isEqualTo(
                Instant.ofEpochMilli(stillCaptured).atOffset(ZoneOffset.UTC).plusYears(3).toInstant().toEpochMilli());

        String dueId = report.get("evidence_id").asText();
        mvc.perform(post("/api/v1/evidence-files/" + dueId + "/holds")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "hold-due-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"案件未结\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/evidence-files/" + dueId).header("Authorization", bearer(ingest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custody").value("HELD"))
                .andExpect(jsonPath("$.data.held").value(true));
    }

    @Test
    void nullRetainUntilIsDerivedOnReadWithoutWritingBack() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read");
        String evidenceId = UUID.randomUUID().toString();
        Instant captured = Instant.parse("2021-03-01T00:00:00Z");
        Timestamp at = Timestamp.from(captured);
        jdbc.update("""
                insert into evidence_file (evidence_id,evidence_no,kind_code,original_name,content_type,storage_backend,
                    object_key,size_bytes,sha256,captured_at,stored_at,retain_until,status,source_mode,owner_org_id,district_id,
                    created_at,updated_at,version)
                values (?,?,'EO_VIDEO','old.mp4','video/mp4','local',?,1,?,?,?,null,'AVAILABLE','mock',?,?,?,?,0)
                """, evidenceId, "EV-OLD-" + suffix, "old/" + evidenceId + "/old.mp4", "a".repeat(64),
                at, at, org, district, at, at);
        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/evidence-files/" + evidenceId)
                        .header("Authorization", bearer(ingest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retain_label").value("3 年"))
                .andExpect(jsonPath("$.data.custody").value("DUE"))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(detail.get("retain_until").asLong()).isEqualTo(Instant.parse("2024-03-01T00:00:00Z").toEpochMilli());
        assertThat(jdbc.queryForObject("select retain_until from evidence_file where evidence_id=?", Timestamp.class, evidenceId))
                .isNull();
    }

    @Test
    void destroyRemovesBytesKeepsMetadataAndEnforcesGates() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read", "evidence:download", "evidence:hold");
        long captured = Instant.parse("2020-06-15T00:00:00Z").toEpochMilli();
        String dueId = ingestFile(ingest, "due.pdf", org, district, null, null, "COMMISSION_REPORT", captured)
                .get("evidence_id").asText();
        String keptId = ingestFile(ingest, "kept.jpg", org, district, null, null).get("evidence_id").asText();

        mvc.perform(post("/api/v1/evidence-files/" + dueId + "/destroy")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "del-no-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"留存届满\"}"))
                .andExpect(status().isForbidden());

        grantAction(ingest, "evidence:destroy");
        mvc.perform(post("/api/v1/evidence-files/" + keptId + "/destroy")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "del-kept-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"留存届满\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DESTROY_NOT_DUE"));

        mvc.perform(post("/api/v1/evidence-files/" + dueId + "/holds")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "del-hold-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"案件未结\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/evidence-files/" + dueId + "/destroy")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "del-held-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"留存届满\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HOLD_ACTIVE"));
        JsonNode hold = json.readTree(mvc.perform(get("/api/v1/evidence-files/" + dueId)
                        .header("Authorization", bearer(ingest)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        String holdId = hold.get("holds").get(0).get("hold_id").asText();
        mvc.perform(post("/api/v1/evidence-files/" + dueId + "/holds/" + holdId + "/release")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "del-rel-" + suffix))
                .andExpect(status().isOk());

        Path stored = Path.of(properties.getEvidenceDir()).toAbsolutePath().normalize()
                .resolve(jdbc.queryForObject("select object_key from evidence_file where evidence_id=?", String.class, dueId));
        assertThat(stored).exists();
        JsonNode destroyed = json.readTree(mvc.perform(post("/api/v1/evidence-files/" + dueId + "/destroy")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "del-ok-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"留存届满清理\",\"approval_no\":\"DEL-2026-0118\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DESTROYED"))
                .andExpect(jsonPath("$.data.destroy_reason").value("留存届满清理"))
                .andExpect(jsonPath("$.data.destroy_approval").value("DEL-2026-0118"))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(destroyed.get("sha256").asText()).isEqualTo(sha(PAYLOAD));
        assertThat(stored).doesNotExist();
        mvc.perform(get("/api/v1/evidence-files/" + dueId + "/content").header("Authorization", bearer(ingest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_DESTROYED"));
        mvc.perform(post("/api/v1/evidence-files/" + dueId + "/destroy")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "del-again-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"再次销毁\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_DESTROYED"));
    }

    @Test
    void caseAndAuthorizationLinksRequireModuleReadAndStayInScope() throws Exception {
        String ingest = reader("ASSIGNED", org, district);
        grantAction(ingest, "evidence:ingest", "evidence:read", "evidence:link");
        JsonNode created = ingestFile(ingest, "case.jpg", org, district, "TARGET", targetId);
        String evidenceId = created.get("evidence_id").asText();
        String caseId = insertCase(org, district);
        String authId = insertAuthorization(org, district);

        mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/links")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "link-case-deny-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"CASE\",\"subject_id\":\"" + caseId + "\"}"))
                .andExpect(status().isNotFound());

        grantAction(ingest, "punishment:read", "disposal:read");
        mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/links")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "link-case-ok-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"CASE\",\"subject_id\":\"" + caseId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subject_kind").value("CASE"))
                .andExpect(jsonPath("$.data.subject_id").value(caseId));
        mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/links")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "link-case-dup-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"CASE\",\"subject_id\":\"" + caseId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LINK_EXISTS"));
        mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/links")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "link-auth-ok-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"AUTHORIZATION\",\"subject_id\":\"" + authId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subject_kind").value("AUTHORIZATION"));
        mvc.perform(post("/api/v1/evidence-files/" + evidenceId + "/links")
                        .header("Authorization", bearer(ingest)).header("Idempotency-Key", "link-bad-kind-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"HANDOFF\",\"subject_id\":\"" + caseId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/evidence-files").param("subject_kind", "CASE").param("subject_id", caseId)
                        .header("Authorization", bearer(ingest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        String outsider = reader("ASSIGNED", otherOrg, otherDistrict);
        grantAction(outsider, "evidence:read", "punishment:read");
        mvc.perform(get("/api/v1/evidence-files").param("subject_kind", "CASE").param("subject_id", caseId)
                        .header("Authorization", bearer(outsider)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    private JsonNode ingestFile(String token, String filename, String orgId, String districtId,
            String subjectKind, String subjectId) throws Exception {
        return ingestFile(token, filename, orgId, districtId, subjectKind, subjectId, "EO_STILL", null);
    }

    private JsonNode ingestFile(String token, String filename, String orgId, String districtId,
            String subjectKind, String subjectId, String kindCode, Long capturedAt) throws Exception {
        var request = multipart("/api/v1/evidence-files")
                .file(new MockMultipartFile("file", filename, "application/octet-stream", PAYLOAD))
                .param("kind_code", kindCode)
                .param("owner_org_id", orgId)
                .param("district_id", districtId)
                .header("Authorization", bearer(token))
                .header("Idempotency-Key", "ing-" + filename + "-" + suffix);
        if (subjectKind != null) {
            request.param("subject_kind", subjectKind).param("subject_id", subjectId);
        }
        if (capturedAt != null) {
            request.param("captured_at", String.valueOf(capturedAt));
        }
        String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String insertCase(String orgId, String districtId) {
        Timestamp at = Timestamp.from(Instant.now());
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        String alarmId = "ev-al-" + suffix, eventId = "ev-evt-" + suffix, handoffId = "ev-ho-" + suffix;
        String recipientId = "ev-rc-" + suffix, caseId = "ev-case-" + suffix;
        jdbc.update("""
                insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)
                values (?,?,?,true,'mock',?,?,0)
                """, "ev-src-" + suffix, "EV-SRC-" + suffix, "证据案件来源", at, at);
        jdbc.update("""
                insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at)
                values (?,?,?,?, 'UAV','HIGH',?,?,'mock',?,?,?)
                """, alarmId, targetId, "ev-src-" + suffix, "AL-" + suffix, at, at, orgId, districtId, at);
        jdbc.update("""
                insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,'CONFIRMED',?,?,?,?,1)
                """, eventId, alarmId, orgId, districtId, at, at);
        jdbc.update("""
                insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at)
                values (?,?,'UAV_PUNISHMENT',true,?,?)
                """, recipientId, "证据测试接收方", at, at);
        jdbc.update("""
                insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at)
                values (?,'UAV_EVENT',?,null,?,'UAV_PUNISHMENT',?,1,?,?,'mock',?,?)
                """, handoffId, eventId, eventId, recipientId, orgId, districtId, admin, at);
        jdbc.update("""
                insert into punishment_case (case_id,case_no,event_id,handoff_id,status,party_type,filed_by,filed_by_name,filed_at,owner_org_id,district_id,source_mode,version,created_at,updated_at)
                values (?,?,?,?,'FILED','UNKNOWN',?,?,?,?,?,'mock',0,?,?)
                """, caseId, "CASE-20260909-" + suffix.toUpperCase(), eventId, handoffId, admin, "超级管理员", at, orgId, districtId, at, at);
        return caseId;
    }

    private String insertAuthorization(String orgId, String districtId) {
        Timestamp at = Timestamp.from(Instant.now());
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        String id = "ev-auth-" + suffix;
        jdbc.update("""
                insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at)
                values (?,?,'DISPERSAL','TARGET',?,?,'MANUAL','证据关联用例',?,?,'REQUESTED','demo-v1',?,?,'mock',0,?,?)
                """, id, "AUTH-20260909-" + suffix.toUpperCase(), targetId, targetId, admin, at, orgId, districtId, at, at);
        return id;
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
