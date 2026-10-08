package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.dev-seed.enabled=true", "app.handoff.channel=none", "app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FlightVerificationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.uav.lowaltitude.modules.flight.application.FlightScheduledCheckService scheduled;
    @Autowired com.uav.lowaltitude.modules.flight.infrastructure.FlightCheckScheduleRepository schedules;
    @org.springframework.boot.test.mock.mockito.SpyBean com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService checks;
    String session, planId;

    @BeforeEach void setup() throws Exception {
        session = json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        String template = jdbc.queryForObject("SELECT plan_id FROM flight_plan WHERE source_id IS NOT NULL ORDER BY plan_id FETCH FIRST 1 ROWS ONLY", String.class);
        planId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_id,source_mode,route_version_id,owner_org_id,district_id,start_at,end_at,created_at,updated_at,version)"
                + " SELECT ?,?,'COMPLETED',source_id,source_mode,route_version_id,owner_org_id,district_id,?,?,created_at,updated_at,0 FROM flight_plan WHERE plan_id=?",
                planId,"TEST-"+planId,Timestamp.from(Instant.now().minusSeconds(7200)),Timestamp.from(Instant.now().minusSeconds(3600)),template);
        String binding=UUID.randomUUID().toString(),setting=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO plan_source_binding(binding_id,source_id,external_org_code,org_id,enabled,created_at,updated_at,version) SELECT ?,source_id,?,owner_org_id,TRUE,0,0,0 FROM flight_plan WHERE plan_id=?",binding,"VERIFY-"+binding,planId);
        jdbc.update("UPDATE flight_plan SET source_binding_id=? WHERE plan_id=?",binding,planId);
        jdbc.update("INSERT INTO notification_setting(setting_id,purpose,routing_key,recipient_org_id,source_binding_id,channel_type,enabled,created_at,updated_at,version) SELECT ?,'PLAN_FEEDBACK',?,owner_org_id,?,'MOCK',TRUE,0,0,0 FROM flight_plan WHERE plan_id=?",setting,"PLAN_FEEDBACK:"+binding,binding,planId);
        // H2 covers unknown device locations; PostgreSQL subclasses retain the real spatial path.
        try (var connection = jdbc.getDataSource().getConnection()) {
            if ("H2".equals(connection.getMetaData().getDatabaseProductName()))
                jdbc.update("UPDATE ops_device SET longitude=NULL,latitude=NULL");
        }
    }

    @Test void automaticCheckKeepsTakeoffUnknownAndFeedbackDoesNotCreateAlarm() throws Exception {
        long alarms = jdbc.queryForObject("SELECT COUNT(*) FROM uav_event", Long.class);
        JsonNode created = automatic();
        assertThat(created.path("conclusion").asText()).isIn("AUTO_DEVICE_ABNORMAL", "CHECK_INCOMPLETE", "SUSPECTED_NOT_TAKEN_OFF");
        assertThat(created.path("evidence").asText()).startsWith("系统检查时间：");
        assertThat(created.path("takeoff_status").asText()).isEqualTo("UNKNOWN");
        assertThat(created.path("handled_by_name").asText()).isNotBlank();
        assertThat(created.path("handled_at").asLong()).isPositive();
        String verification = created.path("verification_id").asText();
        String sourceId = jdbc.queryForObject("SELECT source_id FROM flight_plan WHERE plan_id=?", String.class, planId);
        mvc.perform(post(base()+"/feedback").header("Authorization", "Bearer "+session)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("verification_id",verification,"recipient_id",sourceId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_status").value("PENDING_DELIVERY"))
                .andExpect(jsonPath("$.data.receipt_status").value("NOT_EXPECTED"))
                .andExpect(jsonPath("$.data.processing_result").doesNotExist());
        mvc.perform(get(base()).header("Authorization", "Bearer "+session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifications[0].verification_id").value(verification))
                .andExpect(jsonPath("$.data.feedback[0].verification_id").value(verification));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event", Long.class)).isEqualTo(alarms);
        assertThat(jdbc.queryForObject("SELECT status_code FROM flight_plan WHERE plan_id=?", String.class, planId)).isEqualTo("COMPLETED");
    }

    @Test void manualVerdictsAreDisabledAndAutomaticCheckRequiresRevisionAndDuePlan() throws Exception {
        mvc.perform(post(base()).header("Authorization", "Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"conclusion\":\"NOT_TAKEN_OFF\",\"evidence\":\" \",\"note\":\"确认未起飞\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MANUAL_VERIFICATION_DISABLED"));
        mvc.perform(post(base()+"/automatic").header("Authorization", "Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE flight_plan SET start_at=?,end_at=? WHERE plan_id=?",Timestamp.from(Instant.now().plusSeconds(3600)),Timestamp.from(Instant.now().plusSeconds(7200)),planId);
        mvc.perform(post(base()+"/automatic").header("Authorization", "Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_revision\":0}"))
                .andExpect(status().isConflict());
    }

    @Test void feedbackRequiresVerificationAndItsOwnRecipient() throws Exception {
        JsonNode created=automatic();
        assertThat(created.path("takeoff_status").asText()).isEqualTo("UNKNOWN");
        mvc.perform(post(base()+"/feedback").header("Authorization", "Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("verification_id",created.path("verification_id").asText(),"recipient_id","unrelated-risk-recipient"))))
                .andExpect(status().isConflict());
    }

    @Test void staleRevisionAndDuplicateFeedbackCannotCreateMoreRecords() throws Exception {
        JsonNode created=automatic();
        mvc.perform(post(base()+"/automatic").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_revision\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        String sourceId=jdbc.queryForObject("SELECT source_id FROM flight_plan WHERE plan_id=?",String.class,planId);
        String body=json.writeValueAsString(java.util.Map.of("verification_id",created.path("verification_id").asText(),"recipient_id",sourceId));
        mvc.perform(post(base()+"/feedback").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post(base()+"/feedback").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_verification WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_feedback WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
    }

    @Test void pendingPlanDoesNotReturnActualTrajectory() throws Exception {
        jdbc.update("UPDATE flight_plan SET status_code='APPROVED' WHERE plan_id=?",planId);
        mvc.perform(get("/api/v1/flight-plans/"+planId+"/trajectory").header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.availability").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.points").isEmpty());
    }

    private JsonNode automatic() throws Exception {
        return json.readTree(mvc.perform(post(base()+"/automatic").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_revision\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void unattendedChecksPersistResumeAndSkipUnchangedFacts() {
        long now=System.currentTimeMillis();
        jdbc.update("UPDATE flight_plan SET status_code='APPROVED',start_at=?,end_at=? WHERE plan_id=?",new Timestamp(now-1000),new Timestamp(now+3600000),planId);
        long notices=jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_feedback",Long.class);
        assertThat(schedules.due(now,1000)).contains(planId);
        scheduled.check(planId);
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM flight_plan_verification WHERE plan_id=?",String.class,planId)).isEqualTo("SYSTEM");
        assertThat(jdbc.queryForObject("SELECT handled_by FROM flight_plan_verification WHERE plan_id=?",String.class,planId)).isNull();
        assertThat(schedules.due(now,1000)).doesNotContain(planId);
        jdbc.update("UPDATE flight_device_check_schedule SET next_check_at=0 WHERE plan_id=?",planId);
        scheduled.check(planId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_verification WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
        jdbc.update("UPDATE flight_plan SET uav_sn=?,version=version+1 WHERE plan_id=?","SN-"+UUID.randomUUID(),planId);
        scheduled.check(planId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_verification WHERE plan_id=?",Long.class,planId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_feedback",Long.class)).isEqualTo(notices);
    }
    @Test void unattendedChecksExcludeEndedFutureAndCancelledWindows(){
        long now=System.currentTimeMillis();scheduled.check(planId);
        assertThat(schedules.state(planId)).isNull();
        jdbc.update("UPDATE flight_plan SET start_at=?,end_at=? WHERE plan_id=?",new Timestamp(now+60000),new Timestamp(now+120000),planId);
        scheduled.check(planId);assertThat(schedules.state(planId)).isNull();
        jdbc.update("UPDATE flight_plan SET status_code='CANCELLED',start_at=? WHERE plan_id=?",new Timestamp(now-60000),planId);
        scheduled.check(planId);assertThat(schedules.state(planId)).isNull();
    }
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentWorkersCreateOneVerification() throws Exception {
        long now=System.currentTimeMillis();
        jdbc.update("UPDATE flight_plan SET status_code='APPROVED',start_at=?,end_at=? WHERE plan_id=?",new Timestamp(now-1000),new Timestamp(now+3600000),planId);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var first=executor.submit(()->{start.await();scheduled.check(planId);return true;});
            var second=executor.submit(()->{start.await();scheduled.check(planId);return true;});
            start.countDown();first.get(20,java.util.concurrent.TimeUnit.SECONDS);second.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_verification WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
        }finally{executor.shutdownNow();}
    }
    @Test void ongoingFaultDoesNotReopenAfterHandlingButRecoveryThenNewFaultDoes(){
        long now=System.currentTimeMillis();
        String device=jdbc.queryForObject("SELECT ops_device_id FROM device_business_scope FETCH FIRST 1 ROWS ONLY",String.class);
        jdbc.update("UPDATE device_business_scope SET owner_org_id=(SELECT owner_org_id FROM flight_plan WHERE plan_id=?),district_id=(SELECT district_id FROM flight_plan WHERE plan_id=?) WHERE ops_device_id=?",planId,planId,device);
        jdbc.update("UPDATE flight_plan SET status_code='APPROVED',start_at=?,end_at=? WHERE plan_id=?",new Timestamp(now-1000),new Timestamp(now+3600000),planId);
        mockScheduledDevice(device,true,java.util.List.of());scheduled.check(planId);
        assertThat(schedules.state(planId)).as("schedule persisted").isNotNull();
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM flight_device_check_schedule WHERE plan_id=?",String.class,planId)).contains("隔离测试设备");
        String task=jdbc.queryForObject("SELECT task_id FROM ops_device_maintenance_task WHERE plan_id=?",String.class,planId);
        assertThat(jdbc.queryForObject("SELECT reported_by FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo("SYSTEM");
        completeTestTasks();
        dueAgain();scheduled.check(planId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
        mockScheduledDevice(device,false,java.util.List.of());dueAgain();scheduled.check(planId);
        mockScheduledDevice(device,true,java.util.List.of());dueAgain();scheduled.check(planId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task WHERE plan_id=?",Long.class,planId)).isEqualTo(2);
    }
    @Test void closingOneIncidentDoesNotCreateAnotherFaultEpisode(){
        long now=System.currentTimeMillis();String device=jdbc.queryForObject("SELECT ops_device_id FROM device_business_scope FETCH FIRST 1 ROWS ONLY",String.class);
        jdbc.update("UPDATE device_business_scope SET owner_org_id=(SELECT owner_org_id FROM flight_plan WHERE plan_id=?),district_id=(SELECT district_id FROM flight_plan WHERE plan_id=?) WHERE ops_device_id=?",planId,planId,device);
        jdbc.update("UPDATE flight_plan SET status_code='APPROVED',start_at=?,end_at=? WHERE plan_id=?",new Timestamp(now-1000),new Timestamp(now+3600000),planId);
        var first=new com.uav.lowaltitude.modules.device.application.DeviceService.Incident(UUID.randomUUID().toString(),"incident-a",device,"sensor","雷达","FAULT","HIGH","OPEN",now,"故障甲",null,null,true,null);
        var second=new com.uav.lowaltitude.modules.device.application.DeviceService.Incident(UUID.randomUUID().toString(),"incident-b",device,"sensor","雷达","FAULT","HIGH","OPEN",now,"故障乙",null,null,true,null);
        mockScheduledDevice(device,true,java.util.List.of(first,second));scheduled.check(planId);
        assertThat(schedules.state(planId)).as("schedule persisted").isNotNull();
        var episode=schedules.fault(planId,device).episode();
        completeTestTasks();
        mockScheduledDevice(device,true,java.util.List.of(second));dueAgain();scheduled.check(planId);
        assertThat(schedules.fault(planId,device).episode()).isEqualTo(episode);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
    }
    private void dueAgain(){jdbc.update("UPDATE flight_device_check_schedule SET next_check_at=0 WHERE plan_id=?",planId);}
    private void completeTestTasks(){
        String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        jdbc.update("UPDATE ops_device_maintenance_task SET status='HANDLED',workflow_state='COMPLETED',active_key=NULL,handled_by=?,handled_at=?,handled_by_name='隔离测试处理人',handling_note='隔离完成夹具' WHERE plan_id=?",actor,System.currentTimeMillis(),planId);
    }
    private void mockScheduledDevice(String device,boolean abnormal,java.util.List<com.uav.lowaltitude.modules.device.application.DeviceService.Incident> incidents){
        long now=System.currentTimeMillis();
        var row=new com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.DeviceRow(device,"隔离测试设备",true,java.math.BigDecimal.ONE,"ONLINE",abnormal?"BAD":"GOOD",now,now,abnormal,true,incidents);
        var result=new com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.Check(planId,abnormal?"AUTO_DEVICE_ABNORMAL":"SUSPECTED_NOT_TAKEN_OFF","隔离设备事实",now,java.math.BigDecimal.TEN,true,0,java.util.List.of(row),false);
        org.mockito.Mockito.doReturn(result).when(checks).scheduled(org.mockito.ArgumentMatchers.argThat(p->p!=null&&planId.equals(p.planId())));
    }
    @Test void executionFactsAreIdempotentVersionedAndModeIsolated() throws Exception {
        var target=jdbc.queryForMap("SELECT t.target_id,k.track_id,t.source_mode FROM target t JOIN track k ON k.target_id=t.target_id WHERE t.source_mode IN ('mock','replay') FETCH FIRST 1 ROWS ONLY");
        String mode=target.get("source_mode").toString();
        String source=jdbc.queryForObject("SELECT source_id FROM integration_source WHERE source_mode=? AND enabled=TRUE FETCH FIRST 1 ROWS ONLY",String.class,mode);
        var body=new java.util.LinkedHashMap<String,Object>();long now=System.currentTimeMillis();
        body.put("source_id",source);body.put("source_mode",mode);body.put("message_id",UUID.randomUUID().toString());body.put("version",1);
        body.put("target_id",target.get("target_id"));body.put("track_id",target.get("track_id"));body.put("execution_id",UUID.randomUUID().toString());
        body.put("valid_from",now-60000);body.put("valid_to",now+60000);body.put("phase","AIRBORNE");body.put("evidence_refs",java.util.List.of("isolated-test/"+UUID.randomUUID()));
        body.put("takeoff",java.util.Map.of("occurred_at",now-1000)); // An event without a position remains independent evidence, never a fabricated match.
        String path="/api/v1/local-interface-simulator/flight-execution-facts";
        var first=json.readTree(mvc.perform(post(path).header("Authorization","Bearer "+session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(false)).andReturn().getResponse().getContentAsString());
        mvc.perform(post(path).header("Authorization","Bearer "+session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(true));
        body.put("phase","UNKNOWN");
        mvc.perform(post(path).header("Authorization","Bearer "+session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isConflict());
        body.put("version",2);
        mvc.perform(post(path).header("Authorization","Bearer "+session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk());
        body.put("version",3);body.put("source_mode","live");
        mvc.perform(post(path).header("Authorization","Bearer "+session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_execution_fact WHERE message_id=? AND source_id=?",Long.class,body.get("message_id"),source)).isEqualTo(2);
        assertThat(first.path("data").path("fact_id").asText()).isNotBlank();
    }
    @Test void executionVersionStartsAsShadowAndLeavesActiveAndDemoUnchanged() throws Exception {
        var set=jdbc.queryForMap("SELECT * FROM rule_set WHERE active_version_id IS NOT NULL FETCH FIRST 1 ROWS ONLY");
        String candidate=UUID.randomUUID().toString();
        int next=jdbc.queryForObject("SELECT MAX(version_no)+1 FROM rule_set_version WHERE rule_set_id=?",Integer.class,set.get("rule_set_id"));
        jdbc.update("INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at) SELECT ?,rule_set_id,?,'PUBLISHED','DEMO',valid_from,'隔离测试候选',source_mode,created_at,published_at FROM rule_set_version WHERE rule_set_version_id=?",candidate,next,set.get("active_version_id"));
        jdbc.update("INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled) VALUES(?,'rule-flight-takeoff-v1',289,TRUE)",candidate);
        var body=java.util.Map.of("rule_set_version_id",candidate,"expected_version",set.get("version"),"note","隔离执行事实影子验证");
        mvc.perform(post("/api/v1/rule-sets/"+set.get("rule_set_code")+"/shadow").header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/rule-set-versions/"+candidate).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.is_shadow").value(true)).andExpect(jsonPath("$.data.param_status").value("DEMO"));
        assertThat(jdbc.queryForObject("SELECT active_version_id FROM rule_set WHERE rule_set_id=?",String.class,set.get("rule_set_id"))).isEqualTo(set.get("active_version_id"));
    }
    private String base(){return "/api/v1/flight-plans/"+planId+"/verifications";}
}
