package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FlightReadApiTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private String role;
    private String userId;
    private String sessionId;
    private String orgA;
    private String districtA;
    private String orgB;
    private String districtB;
    private String routeA;
    private String routeB;
    private String routeVersionOne;
    private String routeVersionTwo;
    private String routeVersionOther;
    private String planA;
    private String planNoTime;
    private String planOtherScope;
    private String planIncomplete;

    @BeforeEach
    void seedFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        role = "ROLE-FLIGHT-" + suffix;
        userId = id();
        sessionId = id();
        orgA = id();
        districtA = id();
        orgB = id();
        districtB = id();
        routeA = id();
        routeB = id();
        routeVersionOne = id();
        routeVersionTwo = id();
        routeVersionOther = id();
        planA = id();
        planNoTime = id();
        planOtherScope = id();
        planIncomplete = id();

        jdbc.update("""
                insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)
                values (?,?,'',false,true,0,0,0,false)
                """, role, "Flight reader " + suffix);
        jdbc.update("""
                insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)
                values (?,'flight:read','READ',false,current_timestamp)
                """, role);
        jdbc.update("""
                insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)
                values (?,'route:read','READ',false,current_timestamp)
                """, role);
        organization(orgA, "ORG-A-" + suffix);
        district(districtA, "DIST-A-" + suffix);
        organization(orgB, "ORG-B-" + suffix);
        district(districtB, "DIST-B-" + suffix);
        jdbc.update("""
                insert into app_user
                    (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,
                     permission_version,created_at,updated_at,version)
                values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)
                """, userId, "flight-reader-" + suffix, "Flight reader", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)",
                userId, orgA, districtA);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                sessionId, userId, System.currentTimeMillis() + 3_600_000L);

        route(routeA, "ROUTE-A-" + suffix, "航线 A", orgA, districtA, T0.plusSeconds(2));
        route(routeB, "ROUTE-B-" + suffix, "航线 B", orgB, districtB, T0.plusSeconds(1));
        routeVersion(routeVersionOne, routeA, 1, T0, T0.plusDays(2));
        routeVersion(routeVersionTwo, routeA, 2, T0.plusDays(2), null);
        routeVersion(routeVersionOther, routeB, 1, T0, null);
        plan(planA, "PLAN-A-" + suffix, routeVersionOne, orgA, districtA, T0.plusHours(1), T0.plusHours(2));
        plan(planNoTime, "PLAN-B-" + suffix, routeVersionTwo, orgA, districtA, null, null);
        plan(planOtherScope, "PLAN-C-" + suffix, routeVersionOther, orgB, districtB, T0.plusHours(3), T0.plusHours(4));
        jdbc.update("""
                insert into flight_plan
                    (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,'PENDING','mock',?,?,?, ?,?,0)
                """, planIncomplete, "PLAN-INCOMPLETE-" + suffix, routeVersionOne, null, null, T0, T0);
    }

    @Test
    void readsPlansWithExactRouteVersionStableOrderingAndTimeWindowFiltering() throws Exception {
        JsonNode first = getJson("/api/v1/flight-plans?size=1").path("data");
        assertThat(first.path("total").asLong()).isEqualTo(2);
        assertThat(first.path("items").get(0).path("plan_id").asText()).isEqualTo(planA);
        assertThat(first.path("items").get(0).path("route").path("route_version_id").asText())
                .isEqualTo(routeVersionOne);
        assertThat(first.path("items").get(0).path("route").path("version_no").asInt()).isEqualTo(1);
        assertThat(first.path("items").get(0).toString()).doesNotContain("credential_ref", "source_snapshot");

        JsonNode noTime = getJson("/api/v1/flight-plans?window_from=" + T0.toInstant().toEpochMilli()
                + "&window_to=" + T0.plusDays(1).toInstant().toEpochMilli()).path("data");
        assertThat(noTime.path("total").asLong()).isEqualTo(1);
        assertThat(noTime.path("items").get(0).path("plan_id").asText()).isEqualTo(planA);
        assertThat(getJson("/api/v1/flight-plans?route_id=" + routeA).path("data").path("total").asLong())
                .isEqualTo(2);
        assertThat(getJson("/api/v1/flight-plans?page=3&size=1").path("data").path("items")).isEmpty();
    }

    @Test
    void readsRouteAndVersionOnlyWithinTheSameAssignedTuple() throws Exception {
        JsonNode routes = getJson("/api/v1/routes?size=100").path("data");
        assertThat(routes.path("total").asLong()).isEqualTo(1);
        assertThat(routes.path("items").get(0).path("route_id").asText()).isEqualTo(routeA);
        JsonNode versions = getJson("/api/v1/routes/" + routeA + "/versions?size=100").path("data");
        assertThat(versions.path("items").findValuesAsText("route_version_id"))
                .containsExactly(routeVersionTwo, routeVersionOne);
        JsonNode version = getJson("/api/v1/route-versions/" + routeVersionOne).path("data");
        assertThat(version.path("centerline").path("type").asText()).isEqualTo("LineString");
        assertThat(version.path("centerline").path("coordinate_system").asText()).isEqualTo("WGS84");

        assertError("/api/v1/flight-plans/" + planOtherScope, 404, "FLIGHT_PLAN_NOT_FOUND");
        assertError("/api/v1/routes/" + routeB, 404, "ROUTE_NOT_FOUND");
    }

    @Test
    void validatesRequestValuesAndChecksPermissionBeforePathsOrQueryValues() throws Exception {
        assertError("/api/v1/flight-plans?window_from=1", 400, "INVALID_TIME_RANGE");
        assertError("/api/v1/flight-plans?window_from=2&window_to=1", 400, "INVALID_TIME_RANGE");
        assertError("/api/v1/flight-plans?page=0", 400, "INVALID_PAGE");
        assertError("/api/v1/routes?enabled=true&enabled=false", 400, "VALIDATION_ERROR");

        jdbc.update("delete from app_role_permission where role_code=? and permission_code='flight:read'", role);
        assertError("/api/v1/flight-plans?page=0", 403, "FORBIDDEN");
        assertError("/api/v1/flight-plans/" + "x".repeat(37), 403, "FORBIDDEN");

        jdbc.update("delete from app_role_permission where role_code=? and permission_code='route:read'", role);
        assertError("/api/v1/routes?enabled=not-a-boolean", 403, "FORBIDDEN");
        assertError("/api/v1/route-versions/" + "x".repeat(37), 403, "FORBIDDEN");
    }

    @Test
    void allScopeStillExcludesRowsWithPartialOwnership() throws Exception {
        jdbc.update("update app_user set scope_mode='ALL' where user_id=?", userId);
        JsonNode plans = getJson("/api/v1/flight-plans?size=100").path("data");
        assertThat(plans.path("items").findValuesAsText("plan_id")).doesNotContain(planIncomplete);
        assertError("/api/v1/flight-plans/" + planIncomplete, 404, "FLIGHT_PLAN_NOT_FOUND");
    }

    @Test
    void assignedScopeNeverCombinesOrganizationAndDistrictFromDifferentGrants() throws Exception {
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)",
                userId, orgB, districtB);
        String crossedRoute = id();
        String crossedVersion = id();
        String crossedPlan = id();
        route(crossedRoute, "ROUTE-CROSS-" + UUID.randomUUID(), "交叉航线", orgA, districtB, T0.plusSeconds(3));
        routeVersion(crossedVersion, crossedRoute, 1, T0, null);
        plan(crossedPlan, "PLAN-CROSS-" + UUID.randomUUID(), crossedVersion, orgA, districtB,
                T0.plusHours(5), T0.plusHours(6));

        JsonNode plans = getJson("/api/v1/flight-plans?size=100").path("data");
        assertThat(plans.path("items").findValuesAsText("plan_id")).doesNotContain(crossedPlan);
        assertThat(plans.path("total").asLong()).isEqualTo(3);
        JsonNode routes = getJson("/api/v1/routes?size=100").path("data");
        assertThat(routes.path("items").findValuesAsText("route_id")).doesNotContain(crossedRoute);
        assertThat(routes.path("total").asLong()).isEqualTo(2);
        assertError("/api/v1/flight-plans/" + crossedPlan, 404, "FLIGHT_PLAN_NOT_FOUND");
        assertError("/api/v1/routes/" + crossedRoute, 404, "ROUTE_NOT_FOUND");
        assertError("/api/v1/routes/" + crossedRoute + "/versions", 404, "ROUTE_NOT_FOUND");
        assertError("/api/v1/route-versions/" + crossedVersion, 404, "ROUTE_VERSION_NOT_FOUND");
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void assertError(String path, int expectedStatus, String code) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private void route(String id, String number, String name, String ownerOrgId, String districtId, OffsetDateTime updatedAt) {
        jdbc.update("""
                insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,?,true,'mock',?,?,?, ?,0)
                """, id, number, name, ownerOrgId, districtId, T0, updatedAt);
    }

    private void routeVersion(String id, String routeId, int versionNo, OffsetDateTime validFrom, OffsetDateTime validTo) {
        jdbc.update("""
                insert into route_version
                    (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,
                     altitude_datum,valid_from,valid_to,created_at)
                values (?,?,?,GEOMETRY 'SRID=4326;LINESTRING (120 30,121 31)',100,10,100,'AMSL',?,?,?)
                """, id, routeId, versionNo, validFrom, validTo, T0);
    }

    private void plan(String id, String number, String routeVersionId, String ownerOrgId, String districtId,
            OffsetDateTime startAt, OffsetDateTime endAt) {
        jdbc.update("""
                insert into flight_plan
                    (plan_id,plan_no,status_code,source_mode,uav_sn,start_at,end_at,route_version_id,
                     owner_org_id,district_id,created_at,updated_at,version)
                values (?,?, 'PENDING','mock','UAV-SN',?,?,?, ?,?,?,?,0)
                """, id, number, startAt, endAt, routeVersionId, ownerOrgId, districtId, T0, T0);
    }

    private void organization(String id, String code) {
        jdbc.update("""
                insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version)
                values (?,?,?,true,0,0,0)
                """, id, code, code);
    }

    private void district(String id, String code) {
        jdbc.update("""
                insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version)
                values (?,?,?,true,0,0,0)
                """, id, code, code);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
