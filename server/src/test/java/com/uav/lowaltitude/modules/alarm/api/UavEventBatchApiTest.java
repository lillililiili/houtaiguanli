package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Isolated fixtures; no scheduler, device dispatch, live notification or acceptance database. */
@SpringBootTest(properties = {"app.automation-rules.enabled=false", "app.dev-seed.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UavEventBatchApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    String role, user, token, org, district, source, otherOrg;
    List<String> events;

    @BeforeEach void setup() {
        role = "ROLE-BATCH-" + UUID.randomUUID().toString().substring(0, 8);
        user = UUID.randomUUID().toString(); token = UUID.randomUUID().toString();
        org = UUID.randomUUID().toString(); district = UUID.randomUUID().toString(); source = UUID.randomUUID().toString();
        otherOrg = UUID.randomUUID().toString();
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,'alarm:read','READ',false,current_timestamp),(?,'alarm:verify','READ',false,current_timestamp)", role, role);
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", user, user, "批量核实员", role);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3600000);
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", org, org, "测试机构");
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", otherOrg, otherOrg, "其他机构");
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", district, district, "测试区域");
        jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values(?,?,?,true,'mock',current_timestamp,current_timestamp,0)", source, source, "隔离测试来源");
        events = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        for (String id : events) {
            jdbc.update("insert into alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) values(?,?,?,'UAV','HIGH',current_timestamp,'mock',?,?,current_timestamp)", id, source, id, org, district);
            jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'PENDING_VERIFICATION',?,?,current_timestamp,current_timestamp,0)", id, id, org, district);
        }
    }

    @AfterEach void cleanup() {
        jdbc.update("delete from audit_log where user_id=?", user);
        jdbc.update("delete from idempotency_request where user_id=?", user);
        for (String id : events) { jdbc.update("delete from uav_event_verification where event_id=?", id); jdbc.update("delete from uav_event where event_id=?", id); jdbc.update("delete from alarm where alarm_id=?", id); }
        jdbc.update("delete from app_session where user_id=?", user); jdbc.update("delete from app_user_data_scope where user_id=?", user); jdbc.update("delete from app_user where user_id=?", user);
        jdbc.update("delete from app_role_permission where role_code=?", role); jdbc.update("delete from app_role where role_code=?", role);
        jdbc.update("delete from integration_source where source_id=?", source); jdbc.update("delete from app_org where org_id=?", org); jdbc.update("delete from app_org where org_id=?", otherOrg); jdbc.update("delete from app_district where district_id=?", district);
    }

    @Test void partialFailureDoesNotRollbackNeighborsAndReplayNeverWritesAgain() throws Exception {
        var items = List.of(verification(events.get(0), 0, "FALSE_POSITIVE", "partial-key-a"), verification(events.get(1), 0, "CONFIRMED", "partial-key-b"), verification(events.get(2), 0, "FALSE_POSITIVE", "partial-key-c"));
        mvc.perform(request("verify", Map.of("items", items))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(2)).andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.items[1].error.code").value("VERIFICATION_BASIS_MISSING"));
        mvc.perform(request("verify", Map.of("items", items))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].error.code").value("IDEMPOTENCY_REPLAY"));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id in (?,?,?)", Integer.class, events.toArray())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where user_id=? and result='FAILURE'", Integer.class, user)).isGreaterThan(0);
    }

    @Test void precheckOnlyOffersFalsePositiveWithoutEvidenceAndRetainsMissingItem() throws Exception {
        mvc.perform(request("precheck", Map.of("items", List.of(Map.of("event_id", events.get(0)), Map.of("event_id", UUID.randomUUID().toString())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].allowed_conclusions[0]").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.data.items[0].counter.can_direct").value(false))
                .andExpect(jsonPath("$.data.items[1].status").value("FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Integer.class, events.get(0))).isZero();
    }

    @Test void duplicateIdsAndUnknownFieldsFailBeforeAnyWrite() throws Exception {
        var item = verification(events.get(0), 0, "FALSE_POSITIVE", "duplicate-key");
        mvc.perform(request("verify", Map.of("items", List.of(item, item)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DUPLICATE_EVENT"));
        mvc.perform(request("verify", Map.of("items", List.of(item), "force", true))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, events.get(0))).isZero();
    }

    @Test void readPermissionCannotVerifyOrCounter() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='alarm:verify'", role);
        mvc.perform(request("verify", Map.of("items", List.of(verification(events.get(0), 0, "FALSE_POSITIVE", "denied-key")))))
                .andExpect(status().isForbidden());
        mvc.perform(request("counter", Map.of("items", List.of(counter(events.get(0), 0, "DIRECT")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].error.code").value("FORBIDDEN"));
    }

    @Test void counterVersionConflictIsPerEventAndDoesNotCreateAuthorizations() throws Exception {
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,'disposal:request','READ',false,current_timestamp)", role);
        mvc.perform(request("counter", Map.of("items", List.of(counter(events.get(0), 7, "REQUEST"), counter(events.get(1), 0, "REQUEST")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.items[1].status").value("FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id in (?,?)", Integer.class, events.get(0), events.get(1))).isZero();
    }

    @Test void assignedScopeCannotReadOrModifyUnassignedEvents() throws Exception {
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?", user);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)", user, otherOrg, district);
        mvc.perform(request("precheck", Map.of("items", List.of(Map.of("event_id", events.get(0))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].event").doesNotExist());
        mvc.perform(request("verify", Map.of("items", List.of(verification(events.get(0), 0, "FALSE_POSITIVE", "scoped-key")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.failed").value(1));
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, events.get(0))).isZero();
    }

    @Test void changingSourceModeDoesNotBypassTheSingleEventEvidenceRequirement() throws Exception {
        for (int i = 0; i < events.size(); i++) {
            String id = events.get(i), mode = List.of("mock", "replay", "live").get(i);
            jdbc.update("update alarm set source_mode=? where alarm_id=?", mode, id);
        }
        mvc.perform(request("verify", Map.of("items", events.stream().map(id -> verification(id, 0, "CONFIRMED", "source-" + id)).toList())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.failed").value(3))
                .andExpect(jsonPath("$.data.items[0].error.code").value("VERIFICATION_BASIS_MISSING"))
                .andExpect(jsonPath("$.data.items[1].error.code").value("VERIFICATION_BASIS_MISSING"))
                .andExpect(jsonPath("$.data.items[2].error.code").value("VERIFICATION_BASIS_MISSING"));
    }

    @Test void concurrentBatchesCommitOnlyOneVerification() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> mvc.perform(request("verify", Map.of("items", List.of(verification(events.get(0), 0, "FALSE_POSITIVE", "race-first"))))).andReturn());
            var second = executor.submit(() -> mvc.perform(request("verify", Map.of("items", List.of(verification(events.get(0), 0, "FALSE_POSITIVE", "race-second"))))).andReturn());
            var a = json.readTree(first.get(20, TimeUnit.SECONDS).getResponse().getContentAsString());
            var b = json.readTree(second.get(20, TimeUnit.SECONDS).getResponse().getContentAsString());
            assertThat(a.path("data").path("succeeded").asInt() + b.path("data").path("succeeded").asInt()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Integer.class, events.get(0))).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    private Map<String, Object> verification(String id, long version, String conclusion, String key) {
        return Map.of("event_id", id, "expected_version", version, "conclusion", conclusion, "note", "逐项核对", "idempotency_key", key);
    }
    private Map<String, Object> counter(String id, long version, String mode) {
        return Map.of("event_id", id, "expected_version", version, "idempotency_key", "counter-" + id, "mode", mode,
                "request", Map.of("action_type", "COUNTERMEASURE", "subject_kind", "UAV_EVENT", "subject_id", id, "device_id", UUID.randomUUID().toString(), "channel", "LINGYUN_B", "countermeasure_mode", "FORCE_LAND"));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String action, Object body) throws Exception {
        return post("/api/v1/uav-events/batch/" + action).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
}
