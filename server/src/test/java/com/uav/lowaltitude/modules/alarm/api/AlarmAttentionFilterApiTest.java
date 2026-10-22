package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/**
 * 按关注分组筛选（2026-10-07，告警页"待处置"统计与状态筛选）：筛选与每行返回的 attention_group 同一表达式，
 * total、分页与导出一致。待处置 = 已核实属实且分组不是历史。H2 与隔离 PostgreSQL 同跑，不写验收数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@Transactional
class AlarmAttentionFilterApiTest {
    static final String PENDING = "state=CONFIRMED&attention_group=CURRENT,AWAITING_CONFIRMATION";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FusionConfigService fusion;
    @SpyBean AppClock clock;
    String org, district, actor, token, role;
    Instant now;
    long lifetime;

    @BeforeEach void fixture() {
        setNow(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        lifetime = fusion.params(null).integer("identity", "terminate_after_ms");
        org = id(); district = district("筛选区域"); actor = id(); token = id(); role = "ROLE-AF-" + id().substring(0, 8);
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", org, org, "筛选机构");
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,'',FALSE,TRUE,0,0,0,FALSE)", role, role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES (?,'alarm:read','READ',FALSE,current_timestamp)", role);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", actor, "af-" + id().substring(0, 8), "筛选读者", role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)", actor, org, district);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES (?,?,?,'127.0.0.1',0)", token, actor, now.plusSeconds(3600).toEpochMilli());
    }

    @ParameterizedTest @ValueSource(strings = {"live", "replay", "mock"})
    void pendingDisposalIsConfirmedUntilHandlingEndsWithConsistentTotalPagesAndExport(String mode) throws Exception {
        Alarm current = alarm(mode, district, "LOW", 50, now.minusSeconds(1), "CONFIRMED");
        Alarm expired = alarm(mode, district, "CRITICAL", 40, now.minusMillis(lifetime + 1), "CONFIRMED");
        Alarm executing = alarm(mode, district, "LOW", 30, null, "CONFIRMED");
        authorization(executing, "EXECUTING");
        Alarm completed = alarm(mode, district, "HIGH", 20, now.minusSeconds(1), "CONFIRMED");
        authorization(completed, "COMPLETED");
        Alarm unverified = alarm(mode, district, "HIGH", 10, now.minusSeconds(1), "PENDING_VERIFICATION");
        Alarm falsePositive = alarm(mode, district, "HIGH", 5, now.minusSeconds(1), "FALSE_POSITIVE");
        Alarm withoutEvent = alarm(mode, district, "HIGH", 1, now.minusSeconds(1), null);

        JsonNode pending = page(PENDING);
        assertThat(pending.path("total").asLong()).isEqualTo(3);
        assertThat(ids(pending)).containsExactly(executing.alarm, current.alarm, expired.alarm);
        for (JsonNode row : pending.path("items")) {
            assertThat(row.path("state").asText()).isEqualTo("CONFIRMED");
            assertThat(row.path("attention_group").asText()).isNotEqualTo("HISTORY");
        }
        JsonNode second = page(PENDING + "&page=2&size=2");
        assertThat(second.path("total").asLong()).isEqualTo(3);
        assertThat(ids(second)).containsExactly(expired.alarm);
        assertThat(ids(page("state=CONFIRMED&attention_group=HISTORY"))).containsExactly(completed.alarm);
        assertThat(ids(page("attention_group=HISTORY"))).containsExactly(falsePositive.alarm, completed.alarm);
        assertThat(ids(page("attention_group=CURRENT")))
                .containsExactlyInAnyOrder(executing.alarm, current.alarm, unverified.alarm, withoutEvent.alarm);
        assertThat(page("").path("total").asLong()).isEqualTo(7);
        assertThat(exportNumbers(PENDING)).containsExactly(executing.number, current.number, expired.number);

        // 处置完成即离开待处置；观测过期只换分组、仍算待处置。
        authorization(current, "COMPLETED");
        assertThat(ids(page(PENDING))).containsExactly(executing.alarm, expired.alarm);
        setNow(now.plusMillis(lifetime));
        assertThat(ids(page(PENDING))).containsExactly(executing.alarm, expired.alarm);
        assertThat(page("state=CONFIRMED&attention_group=HISTORY").path("total").asLong()).isEqualTo(2);
    }

    @Test void filterKeepsDataScopeAndExportAuditRecordsTheGroups() throws Exception {
        Alarm mine = alarm("live", district, "HIGH", 10, now, "CONFIRMED");
        alarm("live", district("未授权区域"), "CRITICAL", 5, now, "CONFIRMED");
        assertThat(ids(page(PENDING))).containsExactly(mine.alarm);
        assertThat(exportNumbers(PENDING)).containsExactly(mine.number);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='alarms_exported' AND user_id=?"
                + " AND detail LIKE '%attention_group=CURRENT|AWAITING_CONFIRMATION%' AND detail LIKE '%rows=1%'", Integer.class, actor))
                .isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void currentVerificationExcludesCompletedPredictionsButPreservesHistoryAndObservedEvents(String mode) throws Exception {
        Alarm ordinary=alarm(mode,district,"HIGH",10,now,"PENDING_VERIFICATION");
        Alarm cleared=prediction(mode,district,"PENDING_VERIFICATION","CLEARED");
        prediction(mode,district,"PENDING_VERIFICATION","ENTERED");
        Alarm unknown=prediction(mode,district,"PENDING_VERIFICATION","UNKNOWN");
        prediction(mode,district("不可读范围"),"PENDING_VERIFICATION","UNKNOWN");
        String current="state=PENDING_VERIFICATION&current_pending=true&source_mode="+mode
                +"&occurred_from="+now.minusSeconds(60).toEpochMilli()+"&occurred_to="+now.plusSeconds(1).toEpochMilli();
        assertThat(ids(page(current))).containsExactlyInAnyOrder(ordinary.alarm,unknown.alarm);
        assertThat(exportNumbers(current)).containsExactlyInAnyOrder(ordinary.number,unknown.number);
        assertThat(page("state=PENDING_VERIFICATION").path("total").asLong()).isEqualTo(4);
        assertThat(page("state=PENDING_VERIFICATION&current_pending=false").path("total").asLong()).isEqualTo(4);
        assertThat(ids(page("state=PENDING_VERIFICATION&prediction_status=WITHDRAWN"))).containsExactly(cleared.alarm);
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,cleared.event)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='alarms_exported' AND user_id=? AND detail LIKE '%current_pending=true%'",Long.class,actor)).isEqualTo(1);
        grantDashboard();
        assertThat(dashboardClosure().path("pending_verification").asLong()).isEqualTo(2);
    }

    @Test void withdrawnConfirmedPredictionLeavesQueueOnlyAfterRemainingDeviceWorkEnds() throws Exception {
        Alarm ordinary=alarm("live",district,"HIGH",10,now,"CONFIRMED");
        Alarm cleared=prediction("replay",district,"CONFIRMED","CLEARED");
        Alarm executing=prediction("replay",district,"CONFIRMED","CLEARED");
        authorization(executing,"EXECUTING");
        Alarm stopping=prediction("live",district,"CONFIRMED","CLEARED");
        String stoppedAuthorization=authorization(stopping,"STOPPED");
        String stoppedDevice=jdbc.queryForObject("SELECT device_id FROM disposal_authorization WHERE authorization_id=?",String.class,stoppedAuthorization);
        String stop=id();
        jdbc.update("INSERT INTO disposal_emergency_stop(stop_id,event_id,requested_by,requested_at,note,request_origin) VALUES (?,?,NULL,?,'系统安全停止','SYSTEM_PREDICTION_STOP')",stop,stopping.event,now.toEpochMilli());
        jdbc.update("INSERT INTO disposal_emergency_stop_device(stop_id,device_id,authorization_id,device_name,channel,source_mode,simulated,stop_status,detail) VALUES (?,?,?,'等待全关设备','COUNTERMEASURE_4CH','live',FALSE,'UNSUPPORTED','等待明确停止结果')",stop,stoppedDevice,stoppedAuthorization);
        Alarm entered=prediction("live",district,"CONFIRMED","ENTERED");
        Alarm observed=alarm("replay",district,"HIGH",5,now,"CONFIRMED");
        String episode=jdbc.queryForObject("SELECT episode_id FROM prediction_event_link WHERE event_id=?",String.class,cleared.event);
        jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES (?,?,'OBSERVED',?)",observed.event,episode,now.toEpochMilli());
        String current=PENDING+"&current_pending=true";
        assertThat(ids(page(current))).containsExactlyInAnyOrder(ordinary.alarm,executing.alarm,stopping.alarm,entered.alarm,observed.alarm);
        assertThat(exportNumbers(current)).containsExactlyInAnyOrder(ordinary.number,executing.number,stopping.number,entered.number,observed.number);
        grantDashboard();
        assertThat(dashboardClosure().path("confirmed_blocked").asLong()).isEqualTo(5);
        jdbc.update("UPDATE disposal_authorization SET status='STOPPED' WHERE subject_id=?",executing.event);
        jdbc.update("UPDATE disposal_emergency_stop_device SET stop_status='NOT_REQUIRED' WHERE stop_id=?",stop);
        assertThat(ids(page(current))).containsExactlyInAnyOrder(ordinary.alarm,entered.alarm,observed.alarm);
        assertThat(dashboardClosure().path("confirmed_blocked").asLong()).isEqualTo(3);
        assertThat(page("state=CONFIRMED").path("total").asLong()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,cleared.event)).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest @ValueSource(strings={"current_pending=true","state=FALSE_POSITIVE&current_pending=true",
            "state=PENDING_VERIFICATION&current_pending=yes","state=CONFIRMED&current_pending=true&current_pending=false"})
    void currentPendingRejectsAmbiguousOrUnsupportedQueries(String query) throws Exception {
        for(String path:List.of("/api/v1/alarms?","/api/v1/alarms/export.csv?"))
            mvc.perform(get(path+query).header("Authorization","Bearer "+token))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"attention_group=OPEN", "attention_group=current", "attention_group=CURRENT,CURRENT",
            "attention_group=CURRENT,", "attention_group=,HISTORY", "attention_group=", "attention_group=CURRENT&attention_group=HISTORY"})
    void unknownDuplicateOrEmptyGroupsAreRejectedNotIgnored(String query) throws Exception {
        alarm("replay", district, "HIGH", 10, now, "CONFIRMED");
        for (String path : List.of("/api/v1/alarms?", "/api/v1/alarms/export.csv?")) {
            mvc.perform(get(path + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    Alarm alarm(String mode, String districtId, String severity, long ageSeconds, Instant observed, String state) {
        return alarm(mode,districtId,severity,ageSeconds,observed,state,"RULE_LEGALITY");
    }

    Alarm alarm(String mode, String districtId, String severity, long ageSeconds, Instant observed, String state,String type) {
        String target = id(), source = id(), alarm = id(), event = state == null ? null : id(), number = "AF-" + id().substring(0, 8);
        Timestamp received = ts(now.minusSeconds(ageSeconds));
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES (?,?,?,TRUE,?,?,?,0)", source, "S-" + source.substring(0, 8), "观测来源", mode, received, received);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,'UAV',?,?,?,?,?)", target, "T-" + target, mode, org, districtId, received, received);
        if (observed != null) jdbc.update("INSERT INTO target_latest_state(target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at) VALUES (?,CAST(? AS GEOMETRY),?,?,CAST('[]' AS JSON),?,?)", target, "SRID=4326;POINT(118.5 37.5)", ts(observed), ts(now), ts(now), ts(now));
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_no,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", alarm, target, source, number, number, type,severity, received, received, mode, org, districtId, received);
        if (event != null) jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,0)", event, alarm, state, org, districtId, received, received);
        return new Alarm(alarm, target, event, number, mode, districtId);
    }

    Alarm prediction(String mode,String districtId,String eventState,String status) {
        Alarm row=alarm(mode,districtId,"HIGH",5,now,eventState,"PREDICTED_AIRSPACE_ENTRY");
        String parameter=id(),snapshot=id(),episode=id();long at=now.toEpochMilli();
        jdbc.update("INSERT INTO prediction_parameter_version(version_id,parameters_json,created_at) VALUES (?,JSON '{}',?)",parameter,at);
        jdbc.update("INSERT INTO prediction_snapshot(prediction_id,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,source_mode,simulated) VALUES (?,?,?,?,?,'READY','READY','K03_TEST',?,JSON '{}',JSON '{}',JSON '{}',?,?,?,?)",
                snapshot,row.target,at,at,at+3000,parameter,org,districtId,mode,"replay".equals(mode));
        jdbc.update("INSERT INTO prediction_episode(episode_id,target_id,airspace_id,active_key,source_mode,owner_org_id,district_id,status,latest_prediction_id,first_prediction_id,last_observed_at,valid_until,current_evidence_valid,entered_evidence_valid,reason,started_at,closed_at,stability_json,warning_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,TRUE,TRUE,'TEST_LIFECYCLE',?,?,JSON '{}',JSON '{}')",
                episode,row.target,id(),"CLEARED".equals(status)?null:id(),mode,org,districtId,status,snapshot,snapshot,at,at+3000,at,"CLEARED".equals(status)?at:null);
        jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES (?,?,'PREDICTED',?)",row.event,episode,at);
        return row;
    }

    String authorization(Alarm row, String state) {
        String id = id(), policy = jdbc.queryForObject("SELECT policy_code FROM disposal_policy ORDER BY policy_code FETCH FIRST 1 ROW ONLY", String.class);
        jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,policy_version,owner_org_id,district_id,source_mode,created_at,updated_at) VALUES (?,?,'COUNTERMEASURE','UAV_EVENT',?,?,?,'COUNTERMEASURE_4CH','测试',?,?,?,?,?,?,?,?,?,?,?,?,?)", id, "AUTH-" + id.substring(0, 8), row.event, row.target, id(), actor, ts(now), actor, ts(now), ts(now.minusSeconds(60)), ts(now.plusSeconds(600)), state, policy, org, row.district, row.mode, ts(now), ts(now));
        return id;
    }

    String district(String name) {
        String id = id();
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", id, id, name);
        return id;
    }

    void setNow(Instant value) {
        now = value;
        doReturn(now).when(clock).now(); doReturn(now.toEpochMilli()).when(clock).nowMillis();
    }

    void grantDashboard() {
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES (?,'dashboard','READ',TRUE,current_timestamp)",role);
    }

    JsonNode dashboardClosure() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/dashboard/snapshot").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("closure");
    }

    JsonNode page(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/alarms" + (query.isEmpty() ? "" : "?" + query)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    List<String> exportNumbers(String query) throws Exception {
        String csv = mvc.perform(get("/api/v1/alarms/export.csv?" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return csv.lines().skip(1).map(line -> line.split(",")[0]).toList();
    }

    List<String> ids(JsonNode page) { return java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false).map(n -> n.path("alarm_id").asText()).toList(); }
    static String id() { return UUID.randomUUID().toString(); }
    static Timestamp ts(Instant value) { return Timestamp.from(value); }
    record Alarm(String alarm, String target, String event, String number, String mode, String district) { }
}
