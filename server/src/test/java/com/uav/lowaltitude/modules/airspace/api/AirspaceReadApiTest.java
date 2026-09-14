package com.uav.lowaltitude.modules.airspace.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AirspaceReadApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AirspaceReadRepository repository;

    private String sessionId;
    private String userId;
    private String role;

    @BeforeEach
    void grantAirspaceRead() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        role = "ROLE-AIRSPACE-" + suffix;
        userId = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)
                values (?,?,'',false,true,0,0,0,false)
                """, role, "Airspace reader " + suffix);
        jdbc.update("""
                insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)
                values (?,'airspace:read','READ',false,current_timestamp)
                """, role);
        jdbc.update("""
                insert into app_user
                    (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,
                     permission_version,created_at,updated_at,version)
                values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)
                """, userId, "airspace-reader-" + suffix, "Airspace reader", role);
        jdbc.update("""
                insert into app_session (session_id,user_id,expire_at,ip,permission_version)
                values (?,?,?,'127.0.0.1',0)
                """, sessionId, userId, System.currentTimeMillis() + 3_600_000L);
    }

    @Test
    void listsAccessibleAirspacesWithTheStandardPageEnvelope() throws Exception {
        mvc.perform(get("/api/v1/airspaces").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void conflictReadRequiresFlightPermissionBeforeItExaminesThePlanId() throws Exception {
        mvc.perform(get("/api/v1/flight-plans/not-a-valid-plan-id/airspace-conflicts")
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void conflictReadChecksAirspacePermissionBeforeItExaminesThePlanId() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String flightOnlyRole = "ROLE-FLIGHT-ONLY-" + suffix;
        String flightOnlyUser = UUID.randomUUID().toString();
        String flightOnlySession = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", flightOnlyRole, flightOnlyRole);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'flight:read','READ',false,current_timestamp)", flightOnlyRole);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", flightOnlyUser, "flight-only-" + suffix, "仅飞行读取", flightOnlyRole);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", flightOnlySession, flightOnlyUser, System.currentTimeMillis() + 3_600_000L);

        // 即使已拥有 flight:read，仍必须在查找计划 ID 前拒绝缺失的 airspace:read。
        mvc.perform(get("/api/v1/flight-plans/not-a-valid-plan-id/airspace-conflicts")
                        .header("Authorization", "Bearer " + flightOnlySession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void ordinaryReadEndpointsRequireAuthenticationAndPermission() throws Exception {
        mvc.perform(get("/api/v1/airspaces"))
                .andExpect(status().isUnauthorized());
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='airspace:read'", role);
        mvc.perform(get("/api/v1/airspaces").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void assignedGrantsCannotBeCrossJoinedAndHideAirspaceDetailsAndVersions() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orgOne = UUID.randomUUID().toString(), districtOne = UUID.randomUUID().toString();
        String orgTwo = UUID.randomUUID().toString(), districtTwo = UUID.randomUUID().toString();
        insertScope(orgOne, districtOne, "ONE-" + suffix);
        insertScope(orgTwo, districtTwo, "TWO-" + suffix);
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?", userId);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp),(?,?,?,current_timestamp)", userId, orgOne, districtOne, userId, orgTwo, districtTwo);
        String crossAirspace = "cross-" + suffix;
        String crossVersion = "cross-version-" + suffix;
        insertAirspace(crossAirspace, crossVersion, "CROSS-" + suffix, "cross-grant-" + suffix, orgOne, districtTwo, OffsetDateTime.parse("2026-09-05T00:00:00Z"));

        // 两条授权只代表两个完整元组，绝不能把 orgOne 与 districtTwo 拼成第三个可读范围。
        mvc.perform(get("/api/v1/airspaces").param("keyword", "cross-grant-" + suffix).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/airspaces/" + crossAirspace).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("AIRSPACE_NOT_FOUND"));
        mvc.perform(get("/api/v1/airspaces/" + crossAirspace + "/versions").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("AIRSPACE_NOT_FOUND"));
        mvc.perform(get("/api/v1/airspace-versions/" + crossVersion).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("AIRSPACE_VERSION_NOT_FOUND"));
    }

    @Test
    void sameUpdatedAtUsesAirspaceIdTailKeyForPagingAndGetDoesNotWrite() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString();
        insertScope(org, district, "PAGE-" + suffix);
        OffsetDateTime sameUpdatedAt = OffsetDateTime.parse("2026-09-05T12:00:00Z");
        String first = "stable-001-" + suffix, second = "stable-002-" + suffix;
        String firstVersion = "stable-v1-" + suffix;
        insertAirspace(first, firstVersion, "STABLE-1-" + suffix, "stable-pagination-" + suffix, org, district, sameUpdatedAt);
        insertAirspace(second, "stable-v2-" + suffix, "STABLE-2-" + suffix, "stable-pagination-" + suffix, org, district, sameUpdatedAt);
        long beforeAirspaces = jdbc.queryForObject("select count(*) from airspace", Long.class);
        long beforeVersions = jdbc.queryForObject("select count(*) from airspace_version", Long.class);
        OffsetDateTime beforeUpdatedAt = jdbc.queryForObject("select updated_at from airspace where airspace_id=?", OffsetDateTime.class, first);

        // updated_at 相同仍以 airspace_id ASC 收尾；分页与 total 必须复用同一可见性谓词。
        mvc.perform(get("/api/v1/airspaces?page=1&size=1").param("keyword", "stable-pagination-" + suffix).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].airspace_id").value(first));
        mvc.perform(get("/api/v1/airspaces?page=2&size=1").param("keyword", "stable-pagination-" + suffix).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].airspace_id").value(second));
        mvc.perform(get("/api/v1/airspaces/" + first).header("Authorization", "Bearer " + sessionId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/airspaces/" + first + "/versions").header("Authorization", "Bearer " + sessionId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/airspace-versions/" + firstVersion).header("Authorization", "Bearer " + sessionId)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from airspace", Long.class)).isEqualTo(beforeAirspaces);
        assertThat(jdbc.queryForObject("select count(*) from airspace_version", Long.class)).isEqualTo(beforeVersions);
        assertThat(jdbc.queryForObject("select updated_at from airspace where airspace_id=?", OffsetDateTime.class, first)).isEqualTo(beforeUpdatedAt);
    }

    @Test
    void listsOnlyVersionsEffectiveAtTheRequestedInstantAndRejectsUnknownOrRepeatedParameters() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String organizationId = UUID.randomUUID().toString();
        String districtId = UUID.randomUUID().toString();
        String airspaceId = UUID.randomUUID().toString();
        String airspaceVersionId = UUID.randomUUID().toString();
        long validFrom = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli();
        long validTo = Instant.parse("2026-09-05T01:00:00Z").toEpochMilli();
        jdbc.update("""
                insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version)
                values (?,?,?,true,0,0,0)
                """, organizationId, "ORG-" + suffix, "Airspace org " + suffix);
        jdbc.update("""
                insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version)
                values (?,?,?,true,0,0,0)
                """, districtId, "DIST-" + suffix, "Airspace district " + suffix);
        jdbc.update("""
                insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,
                                      created_at,updated_at,version)
                values (?,?,?,'mock',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, airspaceId, "ASP-" + suffix, "Visible airspace " + suffix, organizationId, districtId);
        jdbc.update("""
                insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,
                                               valid_from,valid_to,created_at)
                values (?,?,1,'PROHIBITED',?,?,CURRENT_TIMESTAMP)
                """, airspaceVersionId, airspaceId,
                Instant.ofEpochMilli(validFrom), Instant.ofEpochMilli(validTo));

        mvc.perform(get("/api/v1/airspaces?valid_at=" + validFrom)
                        .param("owner_org_id", organizationId).param("district_id", districtId)
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].airspace_id").value(airspaceId));
        // 有效期为左闭右开，终点不能再把失效版本当成可用事实。
        mvc.perform(get("/api/v1/airspaces?valid_at=" + validTo)
                        .param("owner_org_id", organizationId).param("district_id", districtId)
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/airspaces").param("keyword", "Visible airspace")
                        .param("owner_org_id", organizationId).param("district_id", districtId)
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].airspace_id").value(airspaceId));
        mvc.perform(get("/api/v1/airspaces/" + airspaceId).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.airspace_id").value(airspaceId))
                .andExpect(jsonPath("$.data.name").value("Visible airspace " + suffix));
        mvc.perform(get("/api/v1/airspaces/" + airspaceId + "/versions")
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].airspace_version_id").value(airspaceVersionId));
        mvc.perform(get("/api/v1/airspaces/" + airspaceId + "/versions?kind_code=PROHIBITED")
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/airspaces/" + airspaceId + "/versions?page=1&page=2")
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/v1/airspace-versions/" + airspaceVersionId)
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind_code").value("PROHIBITED"))
                .andExpect(jsonPath("$.data.boundary").doesNotExist());
        mvc.perform(get("/api/v1/airspaces?page=1&page=2").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PAGE"));
        mvc.perform(get("/api/v1/airspaces?unrecognized=value").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void kindAndEffectiveTimeMustMatchTheSameAirspaceVersion() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString();
        String district = UUID.randomUUID().toString();
        String airspace = UUID.randomUUID().toString();
        Instant switchAt = Instant.parse("2026-09-05T01:00:00Z");
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-F-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-F-" + suffix, "区域");
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'mock',?,?,current_timestamp,current_timestamp,0)", airspace, "A-F-" + suffix, "跨版本筛选空域", org, district);
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,valid_from,valid_to,created_at) values (?,?,1,'PROHIBITED',?,?,current_timestamp)", "old-" + suffix, airspace, switchAt.minusSeconds(3600), switchAt);
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,valid_from,created_at) values (?,?,2,'RESTRICTED',?,current_timestamp)", "current-" + suffix, airspace, switchAt);

        // 类型与时点必须落在同一版本；旧禁飞版本不能和当前限飞版本拼出一个虚假的当前禁飞空域。
        mvc.perform(get("/api/v1/airspaces")
                        .param("kind_code", "PROHIBITED")
                        .param("owner_org_id", org).param("district_id", district)
                        .param("valid_at", String.valueOf(switchAt.toEpochMilli()))
                        .header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void detailReturnsExactlyOneCurrentVersionOrNoCurrentVersion() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString(), airspace = UUID.randomUUID().toString();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-C-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-C-" + suffix, "区域");
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'mock',?,?,current_timestamp,current_timestamp,0)", airspace, "A-C-" + suffix, "当前空域", org, district);
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,valid_from,created_at) values (?,?,1,'PROHIBITED',current_timestamp,current_timestamp)", UUID.randomUUID().toString(), airspace);
        mvc.perform(get("/api/v1/airspaces/" + airspace).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current_version.version_no").value(1));
    }

    @Test
    void adjacentHalfOpenVersionsAreNotAmbiguousButTrueOverlapIs() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString(), airspace = UUID.randomUUID().toString();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-A-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-A-" + suffix, "区域");
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'mock',?,?,current_timestamp,current_timestamp,0)", airspace, "A-A-" + suffix, "空域", org, district);
        OffsetDateTime at = OffsetDateTime.parse("2026-09-05T00:00:00Z");
        insertVersion(airspace, "v1-" + suffix, 1, at, at.plusHours(1));
        insertVersion(airspace, "v2-" + suffix, 2, at.plusHours(1), at.plusHours(2));
        PlanRow plan = new PlanRow("plan", "P", "PENDING", null, null, "mock", null, at, at.plusHours(2), org, district, "route-version", "route", "R", "route", 1, null, at, at, 0, null, null, null);
        assertThat(repository.hasAmbiguousEffectiveVersion(plan, new AccessDecision("reader", ScopeMode.ALL))).isFalse();
        insertVersion(airspace, "v3-" + suffix, 3, at.plusMinutes(30), at.plusHours(1).plusMinutes(30));
        assertThat(repository.hasAmbiguousEffectiveVersion(plan, new AccessDecision("reader", ScopeMode.ALL))).isTrue();
    }

    private void insertVersion(String airspace, String version, int number, OffsetDateTime from, OffsetDateTime to) {
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,valid_from,valid_to,created_at) values (?,?,?,'PROHIBITED',?,?,current_timestamp)", version, airspace, number, from, to);
    }

    private void insertScope(String org, String district, String suffix) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-" + suffix, "机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-" + suffix, "区域");
    }

    private void insertAirspace(String airspace, String version, String number, String name, String org, String district, OffsetDateTime updatedAt) {
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'mock',?,?,?, ?,0)", airspace, number, name, org, district, updatedAt, updatedAt);
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,valid_from,created_at) values (?,?,1,'PROHIBITED',?,?)", version, airspace, updatedAt, updatedAt);
    }
}
