package com.uav.lowaltitude.modules.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceChainChecksum;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceChainChecksum.Member;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EvidenceChainApiTest {
    private static final byte[] PAYLOAD = "evidence-chain-bytes".getBytes(StandardCharsets.UTF_8);
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String suffix, org, district, otherOrg, otherDistrict;
    private String targetId, historicalId, eventId, alarmId, trackId, assessmentId, evidenceId;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "ch-org-" + suffix;
        district = "ch-dist-" + suffix;
        otherOrg = "ch-other-org-" + suffix;
        otherDistrict = "ch-other-dist-" + suffix;
        catalog(org, district);
        catalog(otherOrg, otherDistrict);
        targetId = "ch-tgt-" + suffix;
        historicalId = "ch-hist-" + suffix;
        eventId = "ch-evt-" + suffix;
        alarmId = "ch-alm-" + suffix;
        trackId = "ch-trk-" + suffix;
        assessmentId = "ch-asm-" + suffix;
        target(targetId, "T-" + suffix, org, district);
        target(historicalId, "TH-" + suffix, org, district);
        String source = "ch-src-" + suffix;
        jdbc.update("""
                insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)
                values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)
                """, source, "SRC-" + suffix, "来源");
        jdbc.update("""
                insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at)
                values (?,?,?,?,'UAV','HIGH',?,?, 'mock',?,?,current_timestamp)
                """, alarmId, targetId, source, "AL-" + suffix, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0), org, district);
        jdbc.update("""
                insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,'CONFIRMED',?,?,?, ?,0)
                """, eventId, alarmId, org, district, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        jdbc.update("""
                insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at,layer,config_version)
                values (?,?,NULL,?,?,?,'FUSED','demo-v1')
                """, trackId, targetId, "fused-" + suffix, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        jdbc.update("""
                insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,created_at)
                values (?,?,0,?,?,CAST('SRID=4326;POINT(118.6 37.4)' AS GEOMETRY),?)
                """, "ch-pt-" + suffix, trackId, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        String route = "ch-rt-" + suffix, rv = "ch-rv-" + suffix, plan = "ch-pl-" + suffix, rule = "ch-rl-" + suffix;
        jdbc.update("""
                insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,?,true,'mock',?,?,current_timestamp,current_timestamp,0)
                """, route, "R-" + suffix, "航线", org, district);
        jdbc.update("""
                insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at)
                values (?,?,1,GEOMETRY 'SRID=4326;LINESTRING (118 37,118.01 37.01)',100,current_timestamp,current_timestamp)
                """, rv, route);
        jdbc.update("""
                insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,created_at,updated_at,version)
                values (?,?, 'PENDING','mock',?,?,?,current_timestamp,current_timestamp,0)
                """, plan, "P-" + suffix, rv, org, district);
        jdbc.update("""
                insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at)
                values (?,?,1,'ACTIVE',current_timestamp,'mock',current_timestamp)
                """, rule, "RULE-" + suffix);
        jdbc.update("""
                insert into assessment_result (assessment_id,plan_id,target_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at)
                values (?,?,?,?,?,?,'ILLEGAL',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),'mock',?)
                """, assessmentId, plan, historicalId, rv, rule, java.sql.Timestamp.from(T0.minusSeconds(60)),
                java.sql.Timestamp.from(T0.minusSeconds(60)));
        String lineageId = "ch-lin-" + suffix;
        jdbc.update("""
                insert into target_lineage (lineage_id,op,occurred_at,survivor_target_id,origin_target_id,member_target_ids,source_target_ids,basis,algo_version,config_version,operator_kind,snapshots,created_at)
                values (?,'MERGE',?,?,null,CAST(? AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),'test','demo-v1','SYSTEM',CAST(? AS JSON),?)
                """, lineageId, java.sql.Timestamp.from(T0), targetId, "[\"" + historicalId + "\"]",
                "{\"" + historicalId + "\":{\"target_no\":\"TH-" + suffix + "\",\"object_type_code\":\"UAV\",\"version\":0}}",
                java.sql.Timestamp.from(T0));
        jdbc.update("""
                insert into target_current_alias (historical_target_id,current_target_id,lineage_id,updated_at)
                values (?,?,?,?)
                """, historicalId, targetId, lineageId, java.sql.Timestamp.from(T0));
        jdbc.update("""
                insert into audit_log (audit_id,account,action,object_type,object_id,detail,occurred_at,ip,module_code,role_code,result,user_agent)
                values (?,'tester','uav_verified','uav_event',?,'',?,'','alarms','ROLE-X','SUCCESS','')
                """, "ch-aud-" + suffix, eventId, T0.toEpochMilli());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/evidence-chains/EVENT/" + eventId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void evidenceReadIsRequiredBeforeQueryIsInterpreted() throws Exception {
        String token = reader("ASSIGNED", org, district);
        mvc.perform(get("/api/v1/evidence-chains/EVENT/" + eventId + "?foo=1").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void unknownQueryAndKindAreRejectedAfterEvidenceRead() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read");
        mvc.perform(get("/api/v1/evidence-chains/EVENT/" + eventId + "?foo=1").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/evidence-chains/PLAN/" + eventId).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/evidence-chains/AUTHORIZATION/" + eventId).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void caseChainIncludesCaseLinkedFileAndNeedsPunishmentRead() throws Exception {
        String token = fullReader();
        String caseId = insertCase();
        ingest(token, "case-shot.jpg", "EO_STILL", "CASE", caseId);
        String noPunish = reader("ASSIGNED", org, district);
        grantAction(noPunish, "evidence:read");
        mvc.perform(get("/api/v1/evidence-chains/CASE/" + caseId).header("Authorization", bearer(noPunish)))
                .andExpect(status().isNotFound());
        JsonNode data = chain(token, "CASE", caseId);
        assertThat(data.get("subject_kind").asText()).isEqualTo("CASE");
        assertThat(data.get("subject_id").asText()).isEqualTo(caseId);
        assertThat(data.get("coverage").get("IMAGE").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.get("coverage").get("ALARM").get("status").asText()).isEqualTo("PRESENT");
    }

    @Test
    void outsiderDoesNotSeeEventChain() throws Exception {
        String token = reader("ASSIGNED", otherOrg, otherDistrict);
        grantAction(token, "evidence:read");
        mvc.perform(get("/api/v1/evidence-chains/EVENT/" + eventId).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void eventChainIncludesDomainAuthorizationOnlyWhenDisposalIsReadable() throws Exception {
        String token = fullReader();
        String authorization = UUID.randomUUID().toString();
        jdbc.update("""
                insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,
                target_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,
                source_mode,version,created_at,updated_at)
                select ?,?,'DISPERSAL','UAV_EVENT',?,?,'MANUAL','证据链回归',user_id,current_timestamp,'REQUESTED',
                'demo-v1',?,?,'mock',0,current_timestamp,current_timestamp from app_session where session_id=?
                """, authorization, "AUTH-TEST-" + suffix, eventId, targetId, org, district, token);
        grantAction(token, "disposal:read");
        JsonNode data = chain(token, "EVENT", eventId);
        assertThat(data.get("coverage").get("AUTHORIZATION").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.toString()).contains(authorization);
        String restricted = fullReader();
        JsonNode hidden = chain(restricted, "EVENT", eventId);
        assertThat(hidden.toString()).doesNotContain(authorization);
    }

    @Test
    void eventChainAggregatesPresentTypesAndFlagsAbsentAuthorization() throws Exception {
        String token = fullReader();
        evidenceId = ingest(token, "shot.jpg", "EO_STILL", "EVENT", eventId);
        JsonNode data = chain(token, "EVENT", eventId);
        assertThat(data.get("subject_kind").asText()).isEqualTo("EVENT");
        assertThat(data.get("subject_id").asText()).isEqualTo(eventId);
        assertThat(data.get("current_target_id").asText()).isEqualTo(targetId);
        assertThat(data.get("historical_target_ids").toString()).contains(historicalId);
        assertThat(data.get("coverage").get("TRACK").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.get("coverage").get("IMAGE").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.get("coverage").get("VIDEO").get("status").asText()).isEqualTo("ABSENT");
        assertThat(data.get("coverage").get("ALARM").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.get("coverage").get("JUDGMENT").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.get("coverage").get("AUTHORIZATION").get("status").asText()).isEqualTo("ABSENT");
        assertThat(data.get("coverage").get("OPERATION").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.get("integrity").get("algorithm").asText()).isEqualTo("SHA-256");
        assertThat(data.get("lineage").get("availability").asText()).isEqualTo("PRESENT");
        boolean sawPreMerge = false;
        for (JsonNode row : data.get("lineage").get("pre_merge_judgments")) {
            if (historicalId.equals(row.get("member_target_id").asText())) {
                sawPreMerge = true;
                assertThat(row.get("judgment_availability").asText()).isEqualTo("PRESENT");
                assertThat(row.get("assessment_id").asText()).isEqualTo(assessmentId);
                assertThat(row.get("conclusion_code").asText()).isEqualTo("ILLEGAL");
            }
        }
        assertThat(sawPreMerge).isTrue();
        JsonNode again = chain(token, "EVENT", eventId);
        assertThat(again.get("integrity").get("checksum").asText())
                .isEqualTo(data.get("integrity").get("checksum").asText());
        assertThat(data.get("integrity").get("checksum").asText()).isEqualTo(expectedChecksum(data));
    }

    @Test
    void corruptFileChangesChecksumAndBrokenCount() throws Exception {
        String token = fullReader();
        evidenceId = ingest(token, "bad.jpg", "EO_STILL", "EVENT", eventId);
        String before = chain(token, "EVENT", eventId).get("integrity").get("checksum").asText();
        jdbc.update("update evidence_file set status='CORRUPT' where evidence_id=?", evidenceId);
        JsonNode after = chain(token, "EVENT", eventId);
        assertThat(after.get("coverage").get("IMAGE").get("broken_count").asInt()).isEqualTo(1);
        assertThat(after.get("integrity").get("checksum").asText()).isNotEqualTo(before);
        boolean sawUnavailable = false;
        for (JsonNode record : after.get("records")) {
            if (evidenceId.equals(record.get("record_id").asText())) {
                sawUnavailable = true;
                assertThat(record.get("availability").asText()).isEqualTo("UNAVAILABLE");
            }
        }
        assertThat(sawUnavailable).isTrue();
    }

    @Test
    void historicalTargetStillResolvesPreMergeJudgment() throws Exception {
        String token = fullReader();
        JsonNode data = chain(token, "TARGET", historicalId);
        assertThat(data.get("current_target_id").asText()).isEqualTo(targetId);
        assertThat(data.get("lineage").get("pre_merge_judgments").size()).isGreaterThan(0);
        assertThat(data.get("coverage").get("JUDGMENT").get("status").asText()).isEqualTo("PRESENT");
    }

    @Test
    void missingAlarmReadForbidsAlarmBucketNotWholeChain() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "target:read", "evidence:ingest", "evidence:link");
        JsonNode data = chain(token, "EVENT", eventId);
        assertThat(data.get("coverage").get("ALARM").get("status").asText()).isEqualTo("FORBIDDEN");
        assertThat(data.get("coverage").get("TRACK").get("status").asText()).isEqualTo("PRESENT");
        assertThat(data.get("lineage").get("availability").asText()).isEqualTo("FORBIDDEN");
        assertThat(data.has("historical_target_ids")).isFalse();
        for (JsonNode record : data.get("records")) {
            assertThat(record.get("record_type").asText()).isNotEqualTo("ALARM");
        }
    }

    private String fullReader() {
        String token = reader("ASSIGNED", org, district);
        grantAction(token, "evidence:read", "evidence:ingest", "evidence:link", "target:read", "alarm:read",
                "assessment:read", "fusion:read", "handoff:read", "punishment:read");
        grantMenu(token, "audit");
        return token;
    }

    private JsonNode chain(String token, String kind, String id) throws Exception {
        String body = mvc.perform(get("/api/v1/evidence-chains/" + kind + "/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String ingest(String token, String filename, String kind, String subjectKind, String subjectId) throws Exception {
        String body = mvc.perform(multipart("/api/v1/evidence-files")
                        .file(new MockMultipartFile("file", filename, "application/octet-stream", PAYLOAD))
                        .param("kind_code", kind)
                        .param("owner_org_id", org)
                        .param("district_id", district)
                        .param("subject_kind", subjectKind)
                        .param("subject_id", subjectId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "ing-" + filename + "-" + suffix))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("evidence_id").asText();
    }

    private String expectedChecksum(JsonNode data) throws Exception {
        java.util.ArrayList<Member> members = new java.util.ArrayList<>();
        for (JsonNode record : data.get("records")) {
            members.add(new Member(record.get("record_type").asText(), record.get("record_id").asText(),
                    record.get("fingerprint").asText()));
        }
        return EvidenceChainChecksum.digest(members);
    }

    private String insertCase() {
        java.sql.Timestamp at = java.sql.Timestamp.from(T0);
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        String handoffId = "ch-ho-" + suffix, recipientId = "ch-rc-" + suffix, caseId = "ch-case-" + suffix;
        jdbc.update("""
                insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at)
                values (?,?,'UAV_PUNISHMENT',true,?,?)
                """, recipientId, "链测试接收方", at, at);
        jdbc.update("""
                insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at)
                values (?,'UAV_EVENT',?,null,?,'UAV_PUNISHMENT',?,1,?,?,'mock',?,?)
                """, handoffId, eventId, eventId, recipientId, org, district, admin, at);
        jdbc.update("""
                insert into punishment_case (case_id,case_no,event_id,handoff_id,status,party_type,filed_by,filed_by_name,filed_at,owner_org_id,district_id,source_mode,version,created_at,updated_at)
                values (?,?,?,?,'FILED','UNKNOWN',?,?,?,?,?,'mock',0,?,?)
                """, caseId, "CASE-20260909-CH" + suffix.toUpperCase(), eventId, handoffId, admin, "超级管理员", at, org, district, at, at);
        return caseId;
    }

    private void catalog(String orgId, String districtId) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                orgId, orgId.toUpperCase(), orgId);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                districtId, districtId.toUpperCase(), districtId);
    }

    private void target(String id, String no, String orgId, String districtId) {
        jdbc.update("""
                insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version)
                values (?,?,'UAV','mock',?,?,?,?,?,?,0)
                """, id, no, orgId, districtId, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0),
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
    }

    private String reader(String scope, String orgId, String districtId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String role = "ROLE-CH-" + id;
        String user = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)", user, "ch-" + id, "chain-tester", role, scope);
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

    private void grantMenu(String token, String permission) {
        grantAction(token, permission);
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
