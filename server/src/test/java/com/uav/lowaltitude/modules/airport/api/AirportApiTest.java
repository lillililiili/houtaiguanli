package com.uav.lowaltitude.modules.airport.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 机场基础数据：鉴权先于解析、解析先于冲突；只增，没有修改与删除入口。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AirportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String suffix, org, district, icao, manager, reader, denied;

    @BeforeEach
    void seed() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = UUID.randomUUID().toString(); district = UUID.randomUUID().toString();
        icao = "ZS" + suffix.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-AP-" + suffix, "机场测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-AP-" + suffix, "机场测试区域");
        manager = session(role("APM-" + suffix, "airport:read", "airport:manage"));
        reader = session(role("APR-" + suffix, "airport:read"));
        denied = session(role("APD-" + suffix));
    }

    @Test
    void createOrderIsPermissionThenParsingThenConflict() throws Exception {
        // 无权限：即使 body 完全非法也只回 403，不泄露解析细节。
        mvc.perform(create(reader, "{\"bogus\":1}", "k-" + UUID.randomUUID()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(create(manager, body(icao) .replace("\"name\"", "\"unknown_field\""), "k-" + UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        mvc.perform(create(manager, body(icao).replace("118.788", "999"), "k-" + UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        JsonNode created = created(create(manager, body(icao), "k-" + UUID.randomUUID()));
        assertThat(created.path("icao_code").asText()).isEqualTo(icao);
        assertThat(created.path("name").asText()).isEqualTo("测试机场");
        assertThat(created.path("owner_org_name").asText()).isEqualTo("机场测试机构");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='airport_created' and result='SUCCESS' and object_id=?",
                Long.class, created.path("airport_id").asText())).isEqualTo(1L);

        // 同 ICAO 再建：409，不产生第二条机场。
        mvc.perform(create(manager, body(icao), "k-" + UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("AIRPORT_EXISTS"));
        assertThat(jdbc.queryForObject("select count(*) from airport where icao_code=?", Long.class, icao)).isEqualTo(1L);
    }

    @Test
    void subResourcesAreAppendOnlyAndVisibleInDetail() throws Exception {
        String airportId = created(create(manager, body(icao), "k-" + UUID.randomUUID())).path("airport_id").asText();
        created(child("/api/v1/airports/" + airportId + "/runways", manager,
                "{\"designator\":\"18/36\",\"heading_deg\":180,\"length_m\":2600,\"centerline\":\"SRID=4326;LINESTRING (118.788 37.575,118.788 37.595)\"}"));
        created(child("/api/v1/airports/" + airportId + "/procedure-routes", manager,
                "{\"kind\":\"APPROACH\",\"name\":\"18 号进近\",\"centerline\":\"SRID=4326;LINESTRING (118.788 37.560,118.788 37.575)\","
                        + "\"protect_width_m\":600,\"min_altitude_m\":0,\"max_altitude_m\":300,\"altitude_datum\":\"AMSL\"}"));
        created(child("/api/v1/airports/" + airportId + "/protected-targets", manager,
                "{\"name\":\"塔台\",\"kind\":\"TOWER\",\"longitude\":118.79,\"latitude\":37.586,\"radius_m\":800}"));
        created(child("/api/v1/airports/" + airportId + "/notification-targets", manager,
                "{\"name\":\"塔台值班席\",\"role\":\"塔台管制\",\"channel_kind\":\"PHONE\"}"));

        JsonNode detail = data("/api/v1/airports/" + airportId, reader);
        assertThat(detail.path("runways")).hasSize(1);
        assertThat(detail.path("procedure_routes").get(0).path("kind").asText()).isEqualTo("APPROACH");
        assertThat(detail.path("protected_targets").get(0).path("radius_m").asDouble()).isEqualTo(800.0);
        assertThat(detail.path("notification_targets").get(0).path("channel_kind").asText()).isEqualTo("PHONE");
        // 通报对象只有逻辑名与角色：响应里不能出现号码或凭据字段。
        assertThat(detail.path("notification_targets").toString()).doesNotContain("phone_number", "credential", "email");

        // 同名子资源再建：409。
        mvc.perform(child("/api/v1/airports/" + airportId + "/runways", manager,
                "{\"designator\":\"18/36\",\"heading_deg\":180}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUNWAY_EXISTS"));
        // 高度值缺基准：拒绝（AGL/AMSL 不互比，没有基准的高度无法参与判定）。
        mvc.perform(child("/api/v1/airports/" + airportId + "/procedure-routes", manager,
                "{\"kind\":\"DEPARTURE\",\"name\":\"36 号离场\",\"centerline\":\"SRID=4326;LINESTRING (118.788 37.595,118.788 37.610)\","
                        + "\"protect_width_m\":600,\"min_altitude_m\":50}")).andExpect(status().isBadRequest());
    }

    @Test
    void readingNeedsAirportReadAndOtherScopesGet404() throws Exception {
        String airportId = created(create(manager, body(icao), "k-" + UUID.randomUUID())).path("airport_id").asText();
        mvc.perform(get("/api/v1/airports").header("Authorization", "Bearer " + denied))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        JsonNode list = data("/api/v1/airports?size=100", reader);
        assertThat(list.path("total").asLong()).isPositive();

        String otherOrg = UUID.randomUUID().toString(), otherDistrict = UUID.randomUUID().toString();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", otherOrg, "ORG-AP2-" + suffix, "另一机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", otherDistrict, "DIST-AP2-" + suffix, "另一区域");
        String outsider = sessionIn(role("APO-" + suffix, "airport:read"), otherOrg, otherDistrict);
        mvc.perform(get("/api/v1/airports/" + airportId).header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("AIRPORT_NOT_FOUND"));
    }

    private String body(String icaoCode) {
        return "{\"icao_code\":\"" + icaoCode + "\",\"name\":\"测试机场\",\"longitude\":118.788,\"latitude\":37.585,"
                + "\"elevation_amsl_m\":6,\"owner_org_id\":\"" + org + "\",\"district_id\":\"" + district + "\",\"note\":\"演示数据\"}";
    }

    private JsonNode created(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private JsonNode data(String path, String session) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private static MockHttpServletRequestBuilder create(String session, String body, String key) {
        return post("/api/v1/airports").header("Authorization", "Bearer " + session).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder child(String path, String session, String body) {
        return post(path).header("Authorization", "Bearer " + session).header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String role(String suffix, String... permissions) {
        String role = "ROLE-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        return role;
    }

    private String session(String roleCode) { return sessionIn(roleCode, org, district); }

    private String sessionIn(String roleCode, String orgId, String districtId) {
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                user, "ap-" + user.substring(0, 8), "机场测试", roleCode);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)", user, orgId, districtId);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }
}
