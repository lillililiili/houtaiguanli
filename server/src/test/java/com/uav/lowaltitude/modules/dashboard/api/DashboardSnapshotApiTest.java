package com.uav.lowaltitude.modules.dashboard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardSnapshotApiTest {
    private static final String SOURCE = "seed-stage3-source";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    private String suffix;
    private String org;
    private String district;
    private String otherOrg;
    private String otherDistrict;
    private String target;
    private String hiddenTarget;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = "dash-org-" + suffix;
        district = "dash-dist-" + suffix;
        otherOrg = "dash-other-org-" + suffix;
        otherDistrict = "dash-other-dist-" + suffix;
        catalog(org, district);
        catalog(otherOrg, otherDistrict);
        target = "dash-target-" + suffix;
        hiddenTarget = "dash-hidden-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                target, "T-" + suffix, org, district, ts(now()), ts(now()), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,altitude_amsl_m,speed_mps,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,GEOMETRY 'SRID=4326;POINT (118.5 37.4)',120,12.5,?,?,? FORMAT JSON,?,?,0)
                """, target, ts(now()), ts(now()), "[]", ts(now()), ts(now()));
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                hiddenTarget, "T-H-" + suffix, otherOrg, otherDistrict, ts(now()), ts(now()), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,GEOMETRY 'SRID=4326;POINT (119.1 37.8)',?,?,'[]' FORMAT JSON,?,?,0)
                """, hiddenTarget, ts(now()), ts(now()), ts(now()), ts(now()));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/dashboard/snapshot"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void dashboardReadIsRequiredBeforeAnyParameterIsInterpreted() throws Exception {
        String denied = reader("ASSIGNED", org, district);
        grantAction(denied, "target:read", "alarm:read");
        mvc.perform(get("/api/v1/dashboard/snapshot?foo=1&foo=again").header("Authorization", bearer(denied)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void dashboardOnlyReaderGetsNullCountsAndForbiddenAvailability() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability.targets").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.alarms").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.assessments").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.handoffs").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.devices").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.flights").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.airspaces").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.availability.stats").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.kpis.sensed_today").value(nullValue()))
                .andExpect(jsonPath("$.data.kpis.alarms_today").value(nullValue()))
                .andExpect(jsonPath("$.data.kpis.pending_assessment").value(nullValue()))
                .andExpect(jsonPath("$.data.kpis.pending_handoffs").value(nullValue()))
                .andExpect(jsonPath("$.data.trend").value(nullValue()))
                .andExpect(jsonPath("$.data.devices").value(nullValue()))
                .andExpect(jsonPath("$.data.flights").value(nullValue()))
                .andExpect(jsonPath("$.data.closure.evidence.status").value("NOT_BUILT"))
                .andExpect(jsonPath("$.data.map.targets").isEmpty())
                .andExpect(jsonPath("$.data.map.alarms").isEmpty());
    }

    @Test
    void assignedReaderSeesOwnLocatedTargetAndRejectsUnknownQuery() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantAction(token, "target:read", "alarm:read");
        String noLocation = "dash-noloc-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                noLocation, "T-N-" + suffix, org, district, ts(now()), ts(now()), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state (target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,null,?,?,'[]' FORMAT JSON,?,?,0)
                """, noLocation, ts(now()), ts(now()), ts(now()), ts(now()));
        String alarmId = "dash-alarm-" + suffix;
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarmId, target, SOURCE, "SRC-" + alarmId, ts(now()), ts(now()), org, district, ts(now()));
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,?,?,0)",
                "dash-event-" + suffix, alarmId, "PENDING_VERIFICATION", org, district, ts(now()), ts(now()));
        String orphanAlarm = "dash-orphan-" + suffix;
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,?,'UAV_INTRUSION','LOW',?,?,'mock',?,?,?)",
                orphanAlarm, noLocation, SOURCE, "SRC-" + orphanAlarm, ts(now()), ts(now()), org, district, ts(now()));

        JsonNode data = json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability.targets").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.availability.alarms").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.kpis.sensed_today").value(2))
                .andExpect(jsonPath("$.data.kpis.alarms_today").value(2))
                .andReturn().getResponse().getContentAsString()).get("data");

        assertThat(ids(data.get("map").get("targets"))).contains(target).doesNotContain(hiddenTarget, noLocation);
        assertThat(ids(data.get("map").get("alarms"))).contains(alarmId).doesNotContain(orphanAlarm);

        mvc.perform(get("/api/v1/dashboard/snapshot?extra=1").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void mapTargetsIncludeLocatedTargetsOutsideTodayWindow() throws Exception {
        String token = reader("ASSIGNED", org, district);
        grantModule(token, "dashboard");
        grantAction(token, "target:read");
        String stale = "dash-stale-" + suffix;
        long threeDaysAgo = now() - 3L * 24 * 60 * 60 * 1000;
        jdbc.update("insert into target (target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version) values (?,?,'UAV','mock',?,?,?,?,?,?,0)",
                stale, "T-S-" + suffix, org, district, ts(threeDaysAgo), ts(threeDaysAgo), ts(now()), ts(now()));
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,altitude_amsl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,GEOMETRY 'SRID=4326;POINT (118.61 37.41)',80,?,?,'[]' FORMAT JSON,?,?,0)
                """, stale, ts(threeDaysAgo), ts(threeDaysAgo), ts(now()), ts(now()));

        JsonNode data = json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(data.path("kpis").path("sensed_today").asInt()).isEqualTo(1);
        assertThat(ids(data.get("map").get("targets"))).contains(target, stale);
    }

    private static Set<String> ids(JsonNode items) {
        Set<String> out = new LinkedHashSet<>();
        items.forEach(item -> {
            if (item.has("target_id")) out.add(item.get("target_id").asText());
            if (item.has("alarm_id")) out.add(item.get("alarm_id").asText());
        });
        return out;
    }

    private void catalog(String orgId, String districtId) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, orgId.toUpperCase(), orgId);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", districtId, districtId.toUpperCase(), districtId);
    }

    private String reader(String scope, String orgId, String districtId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String role = "ROLE-DASH-" + id;
        String user = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)", user, "dash-" + id, "dash-tester", role, scope);
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

    private void grantModule(String token, String module) {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) select u.role_code,?,'READ',true,current_timestamp from app_session s join app_user u on u.user_id=s.user_id where s.session_id=?", module, token);
    }

    private static Timestamp ts(long millis) {
        return Timestamp.from(Instant.ofEpochMilli(millis));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
