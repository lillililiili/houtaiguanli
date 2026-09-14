package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 飞行计划"计划与实际对照"聚合：五段各自独立取权限，缺权限的段只给 availability，不带任何数量。
 * 不套测试事务：授权登记与研判都是提交后的事实，读路径必须看到真正落库的行。
 * 关掉演示种子：段内的数量断言只能被本用例自己造的行影响。
 */
@SpringBootTest(properties = "app.dev-seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlightActualsApiTest {
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String suffix, orgId, district, routeId, routeVersionId, planId, otherPlanId, sourceId;
    private String ruleSetId, ruleSetVersionId, runId;
    private String fullSession, flightOnlySession, userId;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        orgId = "org-9a-" + suffix; district = "dist-9a-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                orgId, "ORG-9A-" + suffix, "对照测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-9A-" + suffix, "对照测试区域");
        userId = UUID.randomUUID().toString();
        fullSession = user(userId, "flight:read", "assessment:read", "risk:read");
        flightOnlySession = user(UUID.randomUUID().toString(), "flight:read");

        sourceId = UUID.randomUUID().toString();
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version)"
                + " values (?,?,?,true,'mock','RADAR',?,?,0)", sourceId, "SRC-9A-" + suffix, "对照测试来源", ts(T0), ts(T0));
        routeId = UUID.randomUUID().toString(); routeVersionId = UUID.randomUUID().toString();
        route(routeId, "RT-9A-" + suffix, orgId, district);
        routeVersion(routeVersionId, routeId, 1, 50, 150, "AMSL");
        planId = UUID.randomUUID().toString();
        plan(planId, "PL-9A-" + suffix, routeVersionId, orgId, district);
        otherPlanId = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from flight_risk where owner_org_id=?", orgId);
        jdbc.update("delete from rule_evaluation where owner_org_id=?", orgId);
        if (runId != null) jdbc.update("delete from rule_run where run_id=?", runId);
        if (ruleSetId != null) {
            jdbc.update("update rule_set set active_version_id=null where rule_set_id=?", ruleSetId);
            jdbc.update("delete from rule_set_version where rule_set_id=?", ruleSetId);
            jdbc.update("delete from rule_set where rule_set_id=?", ruleSetId);
        }
        jdbc.update("delete from flight_plan where owner_org_id=?", orgId);
        jdbc.update("delete from route_version where route_id=?", routeId);
        jdbc.update("delete from route where route_id=?", routeId);
        jdbc.update("delete from integration_source where source_id=?", sourceId);
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'actuals-9a-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'actuals-9a-%')");
        jdbc.update("delete from app_user where account like 'actuals-9a-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-9A-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-9A-%'");
        jdbc.update("delete from app_district where district_id=?", district);
        jdbc.update("delete from app_org where org_id=?", orgId);
    }

    @Test
    void everySectionIsFilledWhenAllPermissionsAreGranted() throws Exception {
        String evaluationId = evaluation("FULL", "LEGAL", hits("120"));
        for (int i = 0; i < 6; i++) risk("R-" + i, "MEDIUM", T0.plusSeconds(60L * i));

        JsonNode data = getJson(fullSession, planId);
        assertThat(data.path("plan_id").asText()).isEqualTo(planId);

        assertThat(data.path("match").path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("match").path("plan_match_code").asText()).isEqualTo("FULL");
        assertThat(data.path("match").path("evaluation_id").asText()).isEqualTo(evaluationId);
        assertThat(data.path("match").path("hit_details_c01")).hasSize(1);
        assertThat(data.path("match").path("hit_details_c01").get(0).path("rule_code").asText()).isEqualTo("C01");
        // 参数状态跟着结论一起给：DEMO 版本得出的结论不能被当成已确认口径使用。
        assertThat(data.path("match").path("param_status").asText()).isEqualTo("DEMO");

        // 高度关系来自同一次研判的 C02-7 明细：目标高度与计划高度带已由引擎在同一基准上比过。
        assertThat(data.path("altitude_relation").path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("altitude_relation").path("relation").asText()).isEqualTo("WITHIN");
        assertThat(data.path("altitude_relation").path("datum").asText()).isEqualTo("AMSL");

        // 最近 5 条按 received_at 倒序：第 6 条（最早的一条）不在结果里。
        assertThat(data.path("latest_risks").path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("latest_risks").path("items")).hasSize(5);
        assertThat(data.path("latest_risks").path("items").get(0).path("received_at").asLong())
                .isEqualTo(T0.plusSeconds(300).toEpochMilli());
        assertThat(data.path("latest_risks").path("items").findValuesAsText("source_risk_id")).doesNotContain("R-0");

        assertThat(data.path("legality").path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("legality").path("legal_status").asText()).isEqualTo("LEGAL");
        assertThat(data.path("legality").path("evaluation_id").asText()).isEqualTo(evaluationId);
        assertThat(data.path("legality").path("param_status").asText()).isEqualTo("DEMO");
    }

    @Test
    void sectionsWithoutPermissionAreForbiddenAndCarryNoNumbers() throws Exception {
        evaluation("FULL", "LEGAL", hits("120"));
        risk("R-X", "HIGH", T0);

        JsonNode data = getJson(flightOnlySession, planId);
        // 缺 assessment:read 时研判段只剩 availability：连"有没有研判""匹配到几条"都不能泄露。
        assertThat(data.path("match").path("availability").asText()).isEqualTo("FORBIDDEN");
        assertThat(data.path("match").has("plan_match_code")).isFalse();
        assertThat(data.path("match").has("evaluation_id")).isFalse();
        assertThat(data.path("match").has("hit_details_c01")).isFalse();
        assertThat(data.path("match").has("param_status")).isFalse();
        assertThat(data.path("altitude_relation").path("availability").asText()).isEqualTo("FORBIDDEN");
        assertThat(data.path("altitude_relation").has("relation")).isFalse();
        assertThat(data.path("legality").path("availability").asText()).isEqualTo("FORBIDDEN");
        assertThat(data.path("legality").has("legal_status")).isFalse();
        assertThat(data.path("legality").has("param_status")).isFalse();
        assertThat(data.path("latest_risks").path("availability").asText()).isEqualTo("FORBIDDEN");
        assertThat(data.path("latest_risks").has("items")).isFalse();
        // 外部授权登记段已按 F8 裁定撤除：响应里不再出现 authorizations。
        assertThat(data.has("authorizations")).isFalse();
    }

    @Test
    void withoutAnyEvaluationTheMatchAndLegalitySectionsSayNoEvaluation() throws Exception {
        JsonNode data = getJson(fullSession, planId);
        assertThat(data.path("match").path("availability").asText()).isEqualTo("NO_EVALUATION");
        assertThat(data.path("match").has("plan_match_code")).isFalse();
        assertThat(data.path("legality").path("availability").asText()).isEqualTo("NO_EVALUATION");
        assertThat(data.path("altitude_relation").path("availability").asText()).isEqualTo("NO_EVALUATION");
        // 有权限但没有数据不是"无权限"：风险照常返回空列表。
        assertThat(data.path("latest_risks").path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("latest_risks").path("items")).isEmpty();
    }

    @Test
    void mixedAltitudeDatumsStayUndeterminedInsteadOfBeingConverted() throws Exception {
        // 计划高度带是 AMSL，目标只有 AGL 高度：引擎给不出同基准的比较，页面也不能自行换算。
        String undeterminedHits = "[{\"rule_code\":\"C01\",\"result_code\":\"PASS\",\"reason_code\":null,\"facts\":{\"plan_match_code\":\"PARTIAL\"},"
                + "\"params\":[],\"evidence\":[],\"message\":\"时间窗与走廊匹配\"},"
                + "{\"rule_code\":\"C02-7\",\"result_code\":\"UNDETERMINED\",\"reason_code\":\"ALTITUDE_DATUM_OR_RANGE_UNKNOWN\","
                + "\"facts\":{\"altitude_datum\":\"AMSL\",\"min_altitude_m\":50,\"max_altitude_m\":150},"
                + "\"params\":[],\"evidence\":[],\"message\":\"目标缺少 AMSL 基准高度，AGL 与 AMSL 不互比\"}]";
        evaluation("PARTIAL", "UNDETERMINED", undeterminedHits, "CONFIRMED");

        JsonNode actuals = getJson(fullSession, planId);
        assertThat(actuals.path("match").path("param_status").asText()).isEqualTo("CONFIRMED");
        JsonNode altitude = actuals.path("altitude_relation");
        assertThat(altitude.path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(altitude.path("relation").asText()).isEqualTo("UNDETERMINED");
        assertThat(altitude.path("datum").asText()).isEqualTo("AMSL");
        assertThat(altitude.path("unknown_reason").asText()).isEqualTo("ALTITUDE_DATUM_OR_RANGE_UNKNOWN");
        assertThat(altitude.has("target_altitude_m")).isFalse();
    }

    @Test
    void aPlanOutsideTheAssignedScopeIsNotFound() throws Exception {
        String otherOrg = "org-9a-o-" + suffix, otherDistrict = "dist-9a-o-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                otherOrg, "ORG-9AO-" + suffix, "越权机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                otherDistrict, "DIST-9AO-" + suffix, "越权区域");
        String otherRoute = UUID.randomUUID().toString(), otherVersion = UUID.randomUUID().toString();
        route(otherRoute, "RT-9AO-" + suffix, otherOrg, otherDistrict);
        routeVersion(otherVersion, otherRoute, 1, 50, 150, "AMSL");
        plan(otherPlanId, "PL-9AO-" + suffix, otherVersion, otherOrg, otherDistrict);
        try {
            mvc.perform(get("/api/v1/flight-plans/" + otherPlanId + "/actuals").header("Authorization", "Bearer " + fullSession))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FLIGHT_PLAN_NOT_FOUND"));
        } finally {
            jdbc.update("delete from flight_plan where plan_id=?", otherPlanId);
            jdbc.update("delete from route_version where route_id=?", otherRoute);
            jdbc.update("delete from route where route_id=?", otherRoute);
            jdbc.update("delete from app_district where district_id=?", otherDistrict);
            jdbc.update("delete from app_org where org_id=?", otherOrg);
        }
    }

    private JsonNode getJson(String token, String plan) throws Exception {
        String body = mvc.perform(get("/api/v1/flight-plans/" + plan + "/actuals").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private static String hits(String targetAltitude) {
        return "[{\"rule_code\":\"C01\",\"result_code\":\"PASS\",\"reason_code\":null,"
                + "\"facts\":{\"plan_match_code\":\"FULL\",\"candidate_count\":1},"
                + "\"params\":[{\"key\":\"time_window_min\",\"value\":\"10\",\"status\":\"DEMO\"}],\"evidence\":[],"
                + "\"message\":\"时间窗、走廊与身份均匹配计划\"},"
                + "{\"rule_code\":\"C02-7\",\"result_code\":\"PASS\",\"reason_code\":null,"
                + "\"facts\":{\"altitude_datum\":\"AMSL\",\"min_altitude_m\":50,\"max_altitude_m\":150,\"target_altitude_m\":" + targetAltitude + "},"
                + "\"params\":[],\"evidence\":[],\"message\":\"目标高度在计划高度带内\"}]";
    }

    private String evaluation(String planMatch, String legalStatus, String hitDetails) {
        return evaluation(planMatch, legalStatus, hitDetails, "DEMO");
    }

    private String evaluation(String planMatch, String legalStatus, String hitDetails, String paramStatus) {
        ruleSetId = UUID.randomUUID().toString();
        ruleSetVersionId = UUID.randomUUID().toString();
        runId = UUID.randomUUID().toString();
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,active_version_id,shadow_version_id,previous_active_version_id,version,created_at,updated_at)"
                + " values (?,?,?,null,null,null,0,?,?)", ruleSetId, "RS-9A-" + suffix, "对照测试规则集", ts(T0), ts(T0));
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,valid_to,description,source_mode,created_at,published_at)"
                + " values (?,?,1,'PUBLISHED',?,?,null,null,'mock',?,?)", ruleSetVersionId, ruleSetId, paramStatus, ts(T0), ts(T0), ts(T0));
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,replay_dataset_code,triggered_by,as_of,started_at,finished_at,"
                + "status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,error_summary,source_mode,created_at)"
                + " values (?,?,?,'ACTIVE','MANUAL',null,null,?,?,?,'DONE',1,1,0,0,null,'mock',?)",
                runId, ruleSetId, ruleSetVersionId, ts(T0), ts(T0), ts(T0), ts(T0));
        String evaluationId = UUID.randomUUID().toString();
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,track_id,plan_id,route_version_id,"
                + "observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,"
                + "evidence_references,input_snapshot,supersedes_evaluation_id,assessment_id,alarm_outcome,alarm_id,owner_org_id,district_id,source_mode,created_at)"
                + " values (?,?,?,'ACTIVE','PLAN',null,null,?,?,?,?,?,'FRESH',?,?,null,null,CAST('[]' AS JSON),CAST(? AS JSON),CAST('[]' AS JSON),"
                + "CAST('[]' AS JSON),CAST('{}' AS JSON),null,null,null,null,?,?,'mock',?)",
                evaluationId, runId, ruleSetVersionId, planId, routeVersionId, ts(T0), ts(T0), ts(T0), planMatch, legalStatus, hitDetails,
                orgId, district, ts(T0));
        return evaluationId;
    }

    private void risk(String sourceRiskId, String severity, Instant receivedAt) {
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,assessment_id,target_id,track_id,risk_type,"
                + "severity,state_code,reason_code,reason_text,occurred_at,received_at,observed_altitude_m,observed_altitude_datum,height_relation,"
                + "source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,null,null,null,'FOREIGN_OBJECT',?,'PENDING_VERIFICATION','BIRD_FLOCK','沿线鸟群',?,?,null,null,'UNKNOWN','mock',?,?,?,?,0)",
                UUID.randomUUID().toString(), sourceId, sourceRiskId, planId, routeVersionId, severity, ts(receivedAt), ts(receivedAt),
                orgId, district, ts(receivedAt), ts(receivedAt));
    }

    private void route(String id, String number, String owner, String districtId) {
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,true,'mock',?,?,?,?,0)", id, number, "对照测试航线", owner, districtId, ts(T0), ts(T0));
    }

    private void routeVersion(String id, String route, int versionNo, int min, int max, String datum) {
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,"
                + "altitude_datum,valid_from,valid_to,created_at)"
                + " values (?,?,?,GEOMETRY 'SRID=4326;LINESTRING (118.5 37.3,118.6 37.4)',100,?,?,?,?,null,?)",
                id, route, versionNo, min, max, datum, ts(T0), ts(T0));
    }

    private void plan(String id, String number, String routeVersion, String owner, String districtId) {
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,"
                + "owner_org_id,district_id,created_at,updated_at,version) values (?,?,'APPROVED',?,'mock','UAV-9A',?,?,?,?,?,?,?,0)",
                id, number, sourceId, ts(T0), ts(T0.plusSeconds(7200)), routeVersion, owner, districtId, ts(T0), ts(T0));
    }

    private String user(String id, String... permissions) {
        String tag = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-9A-" + tag, token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'READ',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", id, "actuals-9a-" + tag, "对照测试员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", id, orgId, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, id, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
