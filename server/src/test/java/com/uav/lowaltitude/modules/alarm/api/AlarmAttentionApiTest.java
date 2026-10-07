package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.platform.time.AppClock;

/** Same contract runs on H2 and an isolated PostgreSQL/PostGIS schema. Never writes acceptance data. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@Transactional
class AlarmAttentionApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FusionConfigService fusion;
    @SpyBean AppClock clock;
    String org, district, actor, token, role;
    Instant now;
    long lifetime;

    @BeforeEach void fixture() {
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        doReturn(now).when(clock).now(); doReturn(now.toEpochMilli()).when(clock).nowMillis();
        lifetime = fusion.params(null).integer("identity", "terminate_after_ms");
        org = id(); district = id(); actor = id(); token = id(); role = "ROLE-AT-" + id().substring(0, 8);
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", org, org, "排序机构");
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", district, district, "排序区域");
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,'',FALSE,TRUE,0,0,0,FALSE)", role, role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES (?,'alarm:read','READ',FALSE,current_timestamp)", role);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", actor, "at-" + id().substring(0, 8), "排序读者", role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)", actor, org, district);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES (?,?,?,'127.0.0.1',0)", token, actor, now.plusSeconds(3600).toEpochMilli());
    }

    @ParameterizedTest @ValueSource(strings = {"live", "replay", "mock"})
    void currentBeforeExpiredRegardlessOfSeverityWithStablePaginationAndExport(String mode) throws Exception {
        Alarm expired = alarm(mode, "CRITICAL", 86400, now.minusMillis(lifetime + 1), "PENDING_VERIFICATION");
        Alarm older = alarm(mode, "HIGH", 900, now.minusSeconds(1), "PENDING_VERIFICATION");
        Alarm newer = alarm(mode, "HIGH", 600, now.minusSeconds(1), null);
        Alarm low = alarm(mode, "LOW", 1, now.minusSeconds(1), "CONFIRMED");
        Alarm excluded = alarm(mode, "CRITICAL", 0, now.minusSeconds(1), "FALSE_POSITIVE");
        List<String> expected = List.of(newer.alarm, older.alarm, low.alarm, expired.alarm, excluded.alarm);
        assertThat(ids(page(""))).containsExactlyElementsOf(expected);
        assertThat(ids(page("page=2&size=2"))).containsExactly(low.alarm, expired.alarm);
        JsonNode row = detail(expired);
        assertThat(row.path("state").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(row.path("observation_status").asText()).isEqualTo("EXPIRED");
        assertThat(row.path("attention_group").asText()).isEqualTo("AWAITING_CONFIRMATION");
        String csv = mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines().skip(1).map(line -> line.split(",")[0]).toList())
                .containsExactly(newer.number, older.number, low.number, expired.number, excluded.number);
        assertThat(csv).contains("观测已过期");
        assertThat(ids(page("sort=received_at&order=asc"))).containsExactly(expired.alarm, older.alarm, newer.alarm, low.alarm, excluded.alarm);
    }

    @Test void expiryBoundaryAndNewObservationChangeGroupWithoutChangingVerification() throws Exception {
        Alarm row = alarm("replay", "HIGH", 600, now.minusMillis(lifetime - 1), "PENDING_VERIFICATION");
        assertThat(detail(row).path("observation_status").asText()).isEqualTo("CURRENT");
        now = now.plusMillis(1); doReturn(now).when(clock).now(); doReturn(now.toEpochMilli()).when(clock).nowMillis();
        assertThat(detail(row).path("observation_status").asText()).isEqualTo("EXPIRED");
        jdbc.update("UPDATE target_latest_state SET observed_at=?,received_at=? WHERE target_id=?", ts(now), ts(now), row.target);
        assertThat(detail(row).path("attention_group").asText()).isEqualTo("CURRENT");
        assertThat(detail(row).path("state").asText()).isEqualTo("PENDING_VERIFICATION");
    }

    @Test void missingFutureUntrustedAndCrossModeObservationsNeverCountAsCurrent() throws Exception {
        Alarm missing = alarm("live", "HIGH", 30, null, "PENDING_VERIFICATION");
        Alarm future = alarm("live", "HIGH", 20, now.plusSeconds(1), "PENDING_VERIFICATION");
        Alarm untrusted = alarm("live", "HIGH", 10, now, "PENDING_VERIFICATION");
        jdbc.update("UPDATE target_latest_state SET unknown_fields=CAST(? AS JSON) WHERE target_id=?", "[{\"field\":\"observed_at\",\"reason_code\":\"TIME_UNTRUSTED\"}]", untrusted.target);
        Alarm cross = alarm("mock", "HIGH", 5, now, "PENDING_VERIFICATION");
        jdbc.update("UPDATE target SET source_mode='live' WHERE target_id=?", cross.target);
        for (Alarm row : List.of(missing, future, untrusted, cross)) {
            assertThat(detail(row).path("attention_group").asText()).isEqualTo("AWAITING_CONFIRMATION");
            assertThat(detail(row).path("observation_status").asText()).isEqualTo("UNKNOWN");
        }
    }

    @Test void executionAndUnresolvedEmergencyStopStayCurrentEvenWithoutObservation() throws Exception {
        Alarm executing = alarm("replay", "LOW", 1000, now.minusMillis(lifetime + 1), "CONFIRMED");
        authorization(executing, "EXECUTING");
        Alarm stopped = alarm("replay", "HIGH", 900, null, "CONFIRMED");
        String authorization = authorization(stopped, "STOPPED"), stop = id();
        jdbc.update("INSERT INTO disposal_emergency_stop(stop_id,event_id,requested_by,requested_at,note) VALUES (?,?,?,?,?)", stop, stopped.event, actor, now.toEpochMilli(), "核查中");
        jdbc.update("INSERT INTO disposal_emergency_stop_device(stop_id,device_id,authorization_id,device_name,channel,source_mode,simulated,stop_status,detail) VALUES (?,?,?,'核查设备','COUNTERMEASURE_4CH','replay',TRUE,'UNSUPPORTED','待核查')", stop, id(), authorization);
        Alarm completed = alarm("replay", "CRITICAL", 1, now, "CONFIRMED"); authorization(completed, "COMPLETED");
        assertThat(ids(page(""))).containsExactly(stopped.alarm, executing.alarm, completed.alarm);
        assertThat(detail(executing).path("attention_group").asText()).isEqualTo("CURRENT");
        assertThat(detail(stopped).path("attention_group").asText()).isEqualTo("CURRENT");
        assertThat(detail(completed).path("attention_group").asText()).isEqualTo("HISTORY");
        jdbc.update("UPDATE disposal_emergency_stop_device SET confirmed_by=?,confirmed_at=?,confirmation_note='已停机' WHERE stop_id=?", actor, now.toEpochMilli(), stop);
        assertThat(detail(stopped).path("attention_group").asText()).isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test void identicalTimesHaveStablePagesAndForeignScopeCannotProvideCurrentObservation() throws Exception {
        Alarm first = alarm("live", "HIGH", 30, now, "PENDING_VERIFICATION");
        Alarm second = alarm("live", "HIGH", 30, now, "PENDING_VERIFICATION");
        List<String> expected = java.util.stream.Stream.of(first.alarm, second.alarm).sorted().toList();
        assertThat(ids(page("size=1&page=1"))).containsExactly(expected.get(0));
        assertThat(ids(page("size=1&page=2"))).containsExactly(expected.get(1));
        String other = id();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", other, other, "其他机构");
        jdbc.update("UPDATE target SET owner_org_id=? WHERE target_id=?", other, first.target);
        assertThat(detail(first).path("observation_status").asText()).isEqualTo("UNKNOWN");
        assertThat(ids(page(""))).containsExactly(second.alarm, first.alarm);
    }

    @Test void previousCompletionDoesNotHideALaterUnresolvedAttempt() throws Exception {
        Alarm row = alarm("replay", "HIGH", 900, null, "CONFIRMED");
        authorization(row, "COMPLETED");
        assertThat(detail(row).path("attention_group").asText()).isEqualTo("HISTORY");
        now = now.plusSeconds(1);
        authorization(row, "FAILED");
        assertThat(detail(row).path("attention_group").asText()).isEqualTo("AWAITING_CONFIRMATION");
    }

    Alarm alarm(String mode, String severity, long ageSeconds, Instant observed, String state) {
        String target = id(), source = id(), alarm = id(), event = state == null ? null : id(), number = "AT-" + id().substring(0, 8);
        Timestamp received = ts(now.minusSeconds(ageSeconds));
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES (?,?,?,TRUE,?,?,?,0)", source, "S-" + source.substring(0, 8), "观测来源", mode, received, received);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,'UAV',?,?,?,?,?)", target, "T-" + target, mode, org, district, received, received);
        if (observed != null) jdbc.update("INSERT INTO target_latest_state(target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at) VALUES (?,CAST(? AS GEOMETRY),?,?,CAST('[]' AS JSON),?,?)", target, "SRID=4326;POINT(118.5 37.5)", ts(observed), ts(now), ts(now), ts(now));
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_no,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES (?,?,?,?,?,'RULE_LEGALITY',?,?,?,?,?,?,?)", alarm, target, source, number, number, severity, received, received, mode, org, district, received);
        if (event != null) jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,0)", event, alarm, state, org, district, received, received);
        return new Alarm(alarm, target, event, number);
    }

    String authorization(Alarm row, String state) {
        String id = id(), policy = jdbc.queryForObject("SELECT policy_code FROM disposal_policy ORDER BY policy_code FETCH FIRST 1 ROW ONLY", String.class);
        jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,policy_version,owner_org_id,district_id,source_mode,created_at,updated_at) VALUES (?,?,'COUNTERMEASURE','UAV_EVENT',?,?,?,'COUNTERMEASURE_4CH','测试',?,?,?,?,?,?,?,?,?,?,'replay',?,?)", id, "AUTH-" + id.substring(0, 8), row.event, row.target, id(), actor, ts(now), actor, ts(now), ts(now.minusSeconds(60)), ts(now.plusSeconds(600)), state, policy, org, district, ts(now), ts(now));
        return id;
    }
    JsonNode page(String query) throws Exception { return read("/alarms" + (query.isEmpty() ? "" : "?" + query)); }
    JsonNode detail(Alarm row) throws Exception { return read("/alarms/" + row.alarm); }
    JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    List<String> ids(JsonNode page) { return java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false).map(n -> n.path("alarm_id").asText()).toList(); }
    static String id() { return UUID.randomUUID().toString(); }
    static Timestamp ts(Instant value) { return Timestamp.from(value); }
    record Alarm(String alarm, String target, String event, String number) { }
}
