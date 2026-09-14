package com.uav.lowaltitude.modules.airspace.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 版本差异：字段差在应用层比对；几何差只在 PostGIS 上算，H2 下如实标为暂不可用。 */
@SpringBootTest(properties = "app.dev-seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AirspaceDiffApiTest {
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final String SQUARE = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118.0,37.0],[118.1,37.0],[118.1,37.1],[118.0,37.1],[118.0,37.0]]]]}";
    private static final String SQUARE_EAST = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118.2,37.0],[118.35,37.0],[118.35,37.1],[118.2,37.1],[118.2,37.0]]]]}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String suffix, orgId, district, session, airspaceId, firstVersionId, secondVersionId;

    @BeforeEach
    void fixture() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        orgId = "org-9d-" + suffix; district = "dist-9d-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "ORG-9D-" + suffix, "差异测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-9D-" + suffix, "差异测试区域");
        session = user();
        MvcResult created = mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + session).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"airspace_no\":\"KY-9D-" + suffix + "\",\"name\":\"差异演示\",\"kind_code\":\"PROHIBITED\",\"boundary\":" + SQUARE
                        + ",\"min_altitude_m\":0,\"max_altitude_m\":120,\"altitude_datum\":\"AMSL\",\"valid_from\":" + T0.toEpochMilli()
                        + ",\"owner_org_id\":\"" + orgId + "\",\"district_id\":\"" + district + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        airspaceId = json.readTree(created.getResponse().getContentAsString()).path("data").path("airspace_id").asText();
        firstVersionId = json.readTree(created.getResponse().getContentAsString()).path("data").path("airspace_version_id").asText();
        MvcResult second = mvc.perform(post("/api/v1/airspaces/" + airspaceId + "/versions").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind_code\":\"RESTRICTED\",\"boundary\":" + SQUARE_EAST + ",\"min_altitude_m\":0,\"max_altitude_m\":200,"
                        + "\"altitude_datum\":\"AMSL\",\"valid_from\":" + T0.plusSeconds(3600).toEpochMilli()
                        + ",\"change_reason\":\"扩大范围并抬高上限\",\"expected_version\":0}"))
                .andExpect(status().isCreated()).andReturn();
        secondVersionId = json.readTree(second.getResponse().getContentAsString()).path("data").path("airspace_version_id").asText();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from airspace_version_origin where airspace_version_id in (select v.airspace_version_id from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.owner_org_id=?)", orgId);
        jdbc.update("delete from airspace_version where airspace_id in (select airspace_id from airspace where owner_org_id=?)", orgId);
        jdbc.update("delete from airspace where owner_org_id=?", orgId);
        jdbc.update("delete from audit_log where account like 'airspace-d-%'");
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 'airspace-d-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'airspace-d-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'airspace-d-%')");
        jdbc.update("delete from app_user where account like 'airspace-d-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-9D-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-9D-%'");
        jdbc.update("delete from app_district where district_id=?", district);
        jdbc.update("delete from app_org where org_id=?", orgId);
    }

    @Test
    void changedFieldsAreListedWithBothSides() throws Exception {
        mvc.perform(get("/api/v1/airspaces/{a}/versions/{f}/diff/{t}", airspaceId, firstVersionId, secondVersionId)
                        .header("Authorization", "Bearer " + session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from_version_no").value(1))
                .andExpect(jsonPath("$.data.to_version_no").value(2))
                .andExpect(jsonPath("$.data.fields[?(@.field=='kind_code')].from").value("PROHIBITED"))
                .andExpect(jsonPath("$.data.fields[?(@.field=='kind_code')].to").value("RESTRICTED"))
                .andExpect(jsonPath("$.data.fields[?(@.field=='max_altitude_m')].to").value("200"))
                // 第 1 版被接替关闭，valid_to 从空变成新版生效时刻，这本身也是一处差异。
                .andExpect(jsonPath("$.data.fields[?(@.field=='valid_to')]").exists());
    }

    @Test
    void geometryDiffIsUnavailableOnH2() throws Exception {
        // H2 没有 geography 面积能力：如实返回"暂不可用"，而不是给一个看似精确的错数。
        mvc.perform(get("/api/v1/airspaces/{a}/versions/{f}/diff/{t}", airspaceId, firstVersionId, secondVersionId)
                        .header("Authorization", "Bearer " + session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.geometry.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.geometry.area_delta_m2").doesNotExist());
    }

    @Test
    void versionFromAnotherAirspaceIsNotFound() throws Exception {
        MvcResult other = mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + session).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"airspace_no\":\"KY-9D-X-" + suffix + "\",\"name\":\"另一片空域\",\"kind_code\":\"PERMITTED\",\"boundary\":" + SQUARE
                        + ",\"valid_from\":" + T0.toEpochMilli() + ",\"owner_org_id\":\"" + orgId + "\",\"district_id\":\"" + district + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String otherVersionId = json.readTree(other.getResponse().getContentAsString()).path("data").path("airspace_version_id").asText();
        // 版本必须属于路径上的空域，否则可以拿别处的版本 ID 读到不该看到的字段。
        mvc.perform(get("/api/v1/airspaces/{a}/versions/{f}/diff/{t}", airspaceId, firstVersionId, otherVersionId)
                        .header("Authorization", "Bearer " + session))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private String user() {
        String role = "ROLE-9D-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'airspace:read','READ',false,current_timestamp),(?,'airspace:manage','OP',false,current_timestamp)", role, role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "airspace-d-" + UUID.randomUUID().toString().substring(0, 8), "差异查看员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, orgId, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, userId, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private static String key() { return "diff-" + UUID.randomUUID(); }
}
