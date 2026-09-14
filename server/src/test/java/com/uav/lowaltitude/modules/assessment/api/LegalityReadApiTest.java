package com.uav.lowaltitude.modules.assessment.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

/** 已保存研判只读契约：不在 GET 中生成或改写结果，也绝不输出内部输入快照。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LegalityReadApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String sessionId;
    private String planId;
    private String assessmentId;
    private String role;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        role = "ROLE-ASSESSMENT-" + suffix;
        String userId = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
        String orgId = UUID.randomUUID().toString();
        String districtId = UUID.randomUUID().toString();
        String routeId = UUID.randomUUID().toString();
        String routeVersionId = UUID.randomUUID().toString();
        String ruleVersionId = UUID.randomUUID().toString();
        planId = UUID.randomUUID().toString();
        assessmentId = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        for (String permission : new String[] { "flight:read", "assessment:read" }) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'READ',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "ORG-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", districtId, "DIST-" + suffix, "区域");
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", userId, "reader-" + suffix, "读取人", role);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", sessionId, userId, System.currentTimeMillis() + 3600000L);
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,current_timestamp,current_timestamp,0)", routeId, "R-" + suffix, "航线", orgId, districtId);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,GEOMETRY 'SRID=4326;LINESTRING (118 37,118.01 37.01)',100,current_timestamp,current_timestamp)", routeVersionId, routeId);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?, 'PENDING','mock',?,?,?,current_timestamp,current_timestamp,0)", planId, "P-" + suffix, routeVersionId, orgId, districtId);
        jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,source_snapshot,created_at) values (?,?,1,'ACTIVE',current_timestamp,'mock','{\"secret\":true}',current_timestamp)", ruleVersionId, "LOCAL-DEMO-V1-" + suffix);
        jdbc.update("insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,source_snapshot,created_at) values (?,?,?,?,?,'LEGAL','[{\"rule_code\":\"C01\",\"result_code\":\"PASS\"}]','[]','[\"evidence-1\"]','mock','{\"credential_ref\":\"secret\"}',?)", assessmentId, planId, routeVersionId, ruleVersionId, Instant.parse("2026-09-05T12:00:00Z"), Instant.parse("2026-09-05T12:00:00Z"));
    }

    @Test
    void readsSavedHistoryAndDetailWithoutLeakingSourceSnapshots() throws Exception {
        mvc.perform(get("/api/v1/flight-plans/" + planId + "/legality-assessments").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].assessment_id").value(assessmentId))
                .andExpect(jsonPath("$.data.items[0].conclusion_code").value("LEGAL"));
        mvc.perform(get("/api/v1/legality-assessments/" + assessmentId).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assessment_id").value(assessmentId))
                .andExpect(jsonPath("$.data.source_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.credential_ref").doesNotExist());
    }

    @Test
    void historyRequiresFlightThenAssessmentPermissionAndGetDoesNotWrite() throws Exception {
        Long before = jdbc.queryForObject("select count(*) from assessment_result", Long.class);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='flight:read'", role);
        mvc.perform(get("/api/v1/flight-plans/not-even-an-id/legality-assessments").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'flight:read','READ',false,current_timestamp)", role);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='assessment:read'", role);
        mvc.perform(get("/api/v1/flight-plans/" + planId + "/legality-assessments").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from assessment_result", Long.class)).isEqualTo(before);
    }

    @Test
    void malformedStoredChecksFailsClosedInsteadOfClaimingNoUnknowns() throws Exception {
        jdbc.update("update assessment_result set checks='not-json' where assessment_id=?", assessmentId);
        mvc.perform(get("/api/v1/legality-assessments/" + assessmentId).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @Test
    void historyUsesOneAssignedTuplePredicateForItemsTotalAndStablePagination() throws Exception {
        String userId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, sessionId);
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?", userId);
        String org = jdbc.queryForObject("select owner_org_id from flight_plan where plan_id=?", String.class, planId);
        String district = jdbc.queryForObject("select district_id from flight_plan where plan_id=?", String.class, planId);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)", userId, org, district);
        String secondPlan = clonePlanAndAssessment("a-plan", "a-assessment", org, district, "2026-09-05T13:00:00Z");
        String otherOrg = UUID.randomUUID().toString();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", otherOrg, "OTHER-" + otherOrg.substring(0, 6), "其它机构");
        clonePlanAndAssessment("z-plan", "z-assessment", otherOrg, district, "2026-09-05T14:00:00Z");

        mvc.perform(get("/api/v1/flight-plans/" + planId + "/legality-assessments?page=1&size=1").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/flight-plans/" + secondPlan + "/legality-assessments?page=1&size=1").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].assessment_id").value("a-assessment"));
        // 交叉组织计划即使 ID 可猜也必须统一为 404。
        mvc.perform(get("/api/v1/flight-plans/z-plan/legality-assessments").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("FLIGHT_PLAN_NOT_FOUND"));
    }

    @Test
    void detailHidesCrossTupleTargetTrackAndGetKeepsEveryPersistedTimestampUntouched() throws Exception {
        OffsetDateTime beforePlan = jdbc.queryForObject("select updated_at from flight_plan where plan_id=?", OffsetDateTime.class, planId);
        long beforeAssessment = jdbc.queryForObject("select count(*) from assessment_result", Long.class);
        String otherOrg = UUID.randomUUID().toString();
        String otherDistrict = UUID.randomUUID().toString();
        String otherTarget = UUID.randomUUID().toString();
        String otherSource = UUID.randomUUID().toString();
        String otherLink = UUID.randomUUID().toString();
        String otherTrack = UUID.randomUUID().toString();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", otherOrg, "ORG-X-" + otherOrg.substring(0, 6), "其它机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", otherDistrict, "DIST-X-" + otherDistrict.substring(0, 6), "其它区域");
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'mock',?,?,current_timestamp,current_timestamp,0)", otherTarget, "T-" + otherTarget.substring(0, 6), otherOrg, otherDistrict);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)", otherSource, "S-" + otherSource.substring(0, 6), "跨域来源");
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,?,?,?,current_timestamp)", otherLink, otherTarget, otherSource, "session-" + otherLink, "external-" + otherLink);
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,created_at) values (?,?,?,?,current_timestamp)", otherTrack, otherTarget, otherLink, "track-" + otherTrack);
        // target 与 track 都可能被历史研判误关联；两者均须按计划完整元组单独脱敏。
        jdbc.update("update assessment_result set target_id=?,track_id=? where assessment_id=?", otherTarget, otherTrack, assessmentId);
        mvc.perform(get("/api/v1/legality-assessments/" + assessmentId).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.target_id").doesNotExist())
                .andExpect(jsonPath("$.data.track_id").doesNotExist())
                .andExpect(jsonPath("$.data.source_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.credential_ref").doesNotExist());
        assertThat(jdbc.queryForObject("select updated_at from flight_plan where plan_id=?", OffsetDateTime.class, planId)).isEqualTo(beforePlan);
        assertThat(jdbc.queryForObject("select count(*) from assessment_result", Long.class)).isEqualTo(beforeAssessment);
    }

    @Test
    void badStoredJsonAndIllegalCheckEnumFailClosed() throws Exception {
        jdbc.update("update assessment_result set unknown_reasons='{}' where assessment_id=?", assessmentId);
        mvc.perform(get("/api/v1/legality-assessments/" + assessmentId).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        jdbc.update("update assessment_result set unknown_reasons='[]',checks='[{\"rule_code\":\"C01\",\"result_code\":\"MAYBE\"}]' where assessment_id=?", assessmentId);
        mvc.perform(get("/api/v1/legality-assessments/" + assessmentId).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @Test
    void sameAssessmentInstantUsesAssessmentIdTailKeyAcrossPages() throws Exception {
        String rule = jdbc.queryForObject("select rule_version_id from assessment_result where assessment_id=?", String.class, assessmentId);
        String routeVersion = jdbc.queryForObject("select route_version_id from assessment_result where assessment_id=?", String.class, assessmentId);
        jdbc.update("insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at) values ('00000000-0000-0000-0000-000000000001',?,?,?,?, 'LEGAL','[]','[]','[]','mock',?)", planId, routeVersion, rule, Instant.parse("2026-09-05T12:00:00Z"), Instant.parse("2026-09-05T12:00:00Z"));
        // 相同研判时刻以 assessment_id ASC 作尾键，翻页不能随机重复或漏项。
        mvc.perform(get("/api/v1/flight-plans/" + planId + "/legality-assessments?page=1&size=1").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].assessment_id").value("00000000-0000-0000-0000-000000000001"));
        mvc.perform(get("/api/v1/flight-plans/" + planId + "/legality-assessments?page=2&size=1").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].assessment_id").value(assessmentId));
    }

    @Test
    void assignedGrantsDoNotFormCartesianProductAndHideDetail() throws Exception {
        String user = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, sessionId);
        String org1 = jdbc.queryForObject("select owner_org_id from flight_plan where plan_id=?", String.class, planId);
        String district1 = jdbc.queryForObject("select district_id from flight_plan where plan_id=?", String.class, planId);
        String org2 = UUID.randomUUID().toString(), district2 = UUID.randomUUID().toString();
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?", user);
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org2, "ORG-2-" + org2.substring(0, 6), "机构二");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district2, "DIST-2-" + district2.substring(0, 6), "区域二");
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp),(?,?,?,current_timestamp)", user, org1, district1, user, org2, district2);
        clonePlanAndAssessment("cross-grant-plan", "cross-grant-assessment", org1, district2, "2026-09-05T13:00:00Z");
        mvc.perform(get("/api/v1/flight-plans/cross-grant-plan/legality-assessments").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("FLIGHT_PLAN_NOT_FOUND"));
        mvc.perform(get("/api/v1/legality-assessments/cross-grant-assessment").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEGALITY_ASSESSMENT_NOT_FOUND"));
    }

    private String clonePlanAndAssessment(String plan, String assessment, String org, String district, String assessedAt) {
        String route = UUID.randomUUID().toString(), version = UUID.randomUUID().toString(), rule = UUID.randomUUID().toString();
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,current_timestamp,current_timestamp,0)", route, "R-" + plan, "航线", org, district);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,GEOMETRY 'SRID=4326;LINESTRING (118 37,118.01 37.01)',100,current_timestamp,current_timestamp)", version, route);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?, 'PENDING','mock',?,?,?,current_timestamp,current_timestamp,0)", plan, "P-" + plan, version, org, district);
        jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at) values (?,?,1,'ACTIVE',current_timestamp,'mock',current_timestamp)", rule, "R-" + plan);
        jdbc.update("insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at) values (?,?,?,?,?,'LEGAL','[{\"rule_code\":\"C01\",\"result_code\":\"PASS\"}]','[]','[]','mock',?)", assessment, plan, version, rule, Instant.parse(assessedAt), Instant.parse(assessedAt));
        return plan;
    }
}
