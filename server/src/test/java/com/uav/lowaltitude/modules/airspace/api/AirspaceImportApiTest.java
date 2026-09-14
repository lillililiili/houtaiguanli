package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
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

/** GeoJSON 导入：暂存的问题清单、确认只建可接受项、重复决定被拒、放弃不建任何东西。 */
@SpringBootTest(properties = "app.dev-seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AirspaceImportApiTest {
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final String SQUARE = "[[[118.0,37.0],[118.1,37.0],[118.1,37.1],[118.0,37.1],[118.0,37.0]]]";
    private static final String SQUARE_EAST = "[[[118.4,37.0],[118.5,37.0],[118.5,37.1],[118.4,37.1],[118.4,37.0]]]";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String suffix, orgId, district, session;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        orgId = "org-9i-" + suffix; district = "dist-9i-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "ORG-9I-" + suffix, "导入测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-9I-" + suffix, "导入测试区域");
        session = user(true);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from airspace_import_item where batch_id in (select batch_id from airspace_import_batch where owner_org_id=?)", orgId);
        jdbc.update("delete from airspace_import_batch where owner_org_id=?", orgId);
        jdbc.update("delete from airspace_version_origin where airspace_version_id in (select v.airspace_version_id from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.owner_org_id=?)", orgId);
        jdbc.update("delete from airspace_version where airspace_id in (select airspace_id from airspace where owner_org_id=?)", orgId);
        jdbc.update("delete from airspace where owner_org_id=?", orgId);
        jdbc.update("delete from audit_log where account like 'airspace-i-%'");
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 'airspace-i-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'airspace-i-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'airspace-i-%')");
        jdbc.update("delete from app_user where account like 'airspace-i-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-9I-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-9I-%'");
        jdbc.update("delete from app_district where district_id=?", district);
        jdbc.update("delete from app_org where org_id=?", orgId);
    }

    @Test
    void stagedBatchListsIssuesAndAcceptsOnlyUsableFeatures() throws Exception {
        MvcResult result = stage(twoFeatures()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("STAGED"))
                .andExpect(jsonPath("$.data.feature_count").value(2))
                .andExpect(jsonPath("$.data.accepted_count").value(1))
                .andExpect(jsonPath("$.data.items[0].accepted").value(true))
                .andExpect(jsonPath("$.data.items[1].accepted").value(false))
                .andExpect(jsonPath("$.data.items[1].issues[0].reason_code").value("KIND_MISSING"))
                .andReturn();
        String batchId = batchId(result);
        // 暂存阶段不产生任何空域：操作者还没确认。
        assertThat(count("airspace where owner_org_id=?", orgId)).isZero();
        assertThat(count("airspace_import_item where batch_id=?", batchId)).isEqualTo(2);
        mvc.perform(get("/api/v1/airspaces/import-batches/" + batchId).header("Authorization", "Bearer " + session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void confirmCreatesOnlyAcceptedItemsAndCannotBeRepeated() throws Exception {
        String batchId = batchId(stage(twoFeatures()).andExpect(status().isCreated()).andReturn());
        mvc.perform(decide(batchId, "confirm", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.created_airspaces").value(1))
                .andExpect(jsonPath("$.data.created_versions").value(1));
        assertThat(count("airspace where owner_org_id=?", orgId)).isEqualTo(1);
        assertThat(count("airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.owner_org_id=?", orgId)).isEqualTo(1);
        // 被拒绝的要素不产生空域，但它的记录仍在批次里，供操作者回看原因。
        assertThat(count("airspace_import_item where batch_id=? and accepted=false and result_airspace_version_id is null", batchId)).isEqualTo(1);
        assertThat(count("airspace_version_origin o join airspace_import_item i on i.result_airspace_version_id=o.airspace_version_id where o.origin_kind='GEOJSON_IMPORT' and i.batch_id=?", batchId)).isEqualTo(1);
        assertThat(count("audit_log where action='airspace_import_confirmed' and object_id=? and result='SUCCESS'", batchId)).isEqualTo(1);

        mvc.perform(decide(batchId, "confirm", 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IMPORT_ALREADY_DECIDED"));
        assertThat(count("airspace where owner_org_id=?", orgId)).isEqualTo(1);
    }

    @Test
    void confirmingExistingAirspaceNumberAppendsSucceedingVersion() throws Exception {
        String batchId = batchId(stage(oneFeature("KY-9I-DUP-" + suffix, T0)).andExpect(status().isCreated()).andReturn());
        mvc.perform(decide(batchId, "confirm", 0)).andExpect(status().isOk()).andExpect(jsonPath("$.data.created_airspaces").value(1));
        String airspaceId = jdbc.queryForObject("select airspace_id from airspace where airspace_no=?", String.class, "KY-9I-DUP-" + suffix);

        // 同编号再导一次、生效时间更晚：应当接替出第 2 版，而不是新建第二片空域。
        String second = batchId(stage(oneFeature("KY-9I-DUP-" + suffix, T0.plusSeconds(3600))).andExpect(status().isCreated()).andReturn());
        mvc.perform(decide(second, "confirm", 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created_airspaces").value(0))
                .andExpect(jsonPath("$.data.created_versions").value(1));
        assertThat(count("airspace where airspace_no=?", "KY-9I-DUP-" + suffix)).isEqualTo(1);
        assertThat(count("airspace_version where airspace_id=?", airspaceId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_id=? and version_no=1", java.sql.Timestamp.class, airspaceId).toInstant())
                .isEqualTo(T0.plusSeconds(3600));
    }

    @Test
    void discardKeepsBatchButCreatesNothing() throws Exception {
        String batchId = batchId(stage(twoFeatures()).andExpect(status().isCreated()).andReturn());
        mvc.perform(decide(batchId, "discard", 0)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISCARDED"));
        assertThat(count("airspace where owner_org_id=?", orgId)).isZero();
        assertThat(count("airspace_import_batch where batch_id=? and status='DISCARDED' and decided_by is not null", batchId)).isEqualTo(1);
        mvc.perform(decide(batchId, "confirm", 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IMPORT_ALREADY_DECIDED"));
    }

    @Test
    void oversizedCollectionAndMalformedGeoJsonAreRejected() throws Exception {
        StringBuilder features = new StringBuilder();
        for (int i = 0; i <= 200; i++) {
            if (i > 0) features.append(',');
            features.append("{\"type\":\"Feature\",\"properties\":{\"kind_code\":\"PROHIBITED\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":").append(SQUARE).append("}}");
        }
        mvc.perform(stageRequest("{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IMPORT_TOO_LARGE"));
        mvc.perform(stageRequest("{\"type\":\"Polygon\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GEOJSON"));
        assertThat(count("airspace_import_batch where owner_org_id=?", orgId)).isZero();
    }

    @Test
    void missingManagePermissionIsForbidden() throws Exception {
        String readOnly = user(false);
        mvc.perform(post("/api/v1/airspaces/import-batches").header("Authorization", "Bearer " + readOnly)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isForbidden());
        assertThat(count("airspace_import_batch where owner_org_id=?", orgId)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions stage(String geoJson) throws Exception {
        return mvc.perform(stageRequest(geoJson));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder stageRequest(String geoJson) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("geojson", geoJson, "owner_org_id", orgId, "district_id", district,
                "defaults", java.util.Map.of("valid_from", T0.toEpochMilli())));
        return post("/api/v1/airspaces/import-batches").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decide(String batchId, String action, long expectedVersion) {
        return post("/api/v1/airspaces/import-batches/" + batchId + "/" + action).header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version\":" + expectedVersion + "}");
    }

    /** 一条可接受、一条缺种类被拒。 */
    private String twoFeatures() {
        return "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"name\":\"导入甲区\",\"airspace_no\":\"KY-9I-A-" + suffix + "\",\"kind_code\":\"RESTRICTED\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}},"
                + "{\"type\":\"Feature\",\"properties\":{\"name\":\"导入乙区\",\"airspace_no\":\"KY-9I-B-" + suffix + "\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE_EAST + "}}]}";
    }

    private String oneFeature(String airspaceNo, Instant validFrom) {
        return "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"name\":\"重复编号区\",\"airspace_no\":\"" + airspaceNo + "\",\"kind_code\":\"PROHIBITED\","
                + "\"valid_from\":" + validFrom.toEpochMilli() + "},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}}]}";
    }

    private String batchId(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("batch_id").asText();
    }

    private String user(boolean manage) {
        String role = "ROLE-9I-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'airspace:read','READ',false,current_timestamp)", role);
        if (manage) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'airspace:manage','OP',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "airspace-i-" + UUID.randomUUID().toString().substring(0, 8), "导入操作员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, orgId, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, userId, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private long count(String fromWhere, Object... args) {
        return jdbc.queryForObject("select count(*) from " + fromWhere, Long.class, args);
    }

    private static String key() { return "import-" + UUID.randomUUID(); }
}
