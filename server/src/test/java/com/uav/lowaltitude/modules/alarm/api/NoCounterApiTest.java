package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.modules.alarm.application.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository;
import com.uav.lowaltitude.modules.disposal.api.CounterEvidenceFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@org.springframework.boot.test.context.SpringBootTest(properties={"app.advisory.auto-sms.enabled=true","app.advisory.auto-voice.enabled=true"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class NoCounterApiTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    protected String eventId,session,userId,role;
    @Autowired NoCounterRepository decisions;
    @Autowired AutoSmsService automaticSms;
    @Autowired AutoVoiceService automaticVoice;
    @Autowired AutoSmsPolicy smsPolicy;
    @Autowired AutoVoicePolicy voicePolicy;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @BeforeEach void evidence() {
        var fixture=new UavAdvisoryApiTest();fixture.mvc=mvc;fixture.jdbc=jdbc;fixture.json=json;fixture.fixture();
        eventId=fixture.eventId;session=fixture.session;userId=fixture.userId;role=fixture.role;
        CounterEvidenceFixture.seed(jdbc,eventId);
    }
    @Test void confirmedIllegalAllowsIndependentAuditedDecisionAndRetainsFacts() throws Exception {
        noCounter().andExpect(status().isOk()).andExpect(jsonPath("$.data.can_decide").value(true)).andExpect(jsonPath("$.data.basis.legal_status").value("ILLEGAL"));
        String request=body(0),key=key();
        String first=decide(request,key).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision_active").value(true)).andExpect(jsonPath("$.data.event_version").value(1)).andReturn().getResponse().getContentAsString();
        var replay=json.readTree(decide(request,key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("data").path("decision")).isEqualTo(json.readTree(first).path("data").path("decision"));
        assertThat(jdbc.queryForObject("select count(*) from uav_no_counter_decision where event_id=?",Integer.class,eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?",String.class,eventId)).isEqualTo("CONFIRMED");
        read().andExpect(jsonPath("$.data.no_counter.decision_active").value(true)).andExpect(jsonPath("$.data.can_request_counter").value(false)).andExpect(jsonPath("$.data.auto_handoff.status").value("NOT_REQUIRED"));
        decide(body(1),key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where object_id=? and action='uav_no_counter_decided'",Integer.class,eventId)).isEqualTo(1);
    }
    @Test void currentUnknownOrMissingOrStaleOrOtherSourceBlocks() throws Exception {
        append("ILLEGAL","[]","[\"MISSING\"]","mock",Instant.now());
        decide(body(0),key()).andExpect(status().isConflict());
        append("ILLEGAL","[]","[]","live",Instant.now());
        noCounter().andExpect(jsonPath("$.data.can_decide").value(false));
        append("ILLEGAL","[]","[]","mock",Instant.now().minusSeconds(864000));
        decide(body(0),key()).andExpect(status().isConflict());
        jdbc.update("delete from target_latest_state where target_id=(select target_id from alarm where alarm_id=(select alarm_id from uav_event where event_id=?))",eventId);
        noCounter().andExpect(jsonPath("$.data.can_decide").value(false));
    }
    @Test void legalAndAbnormalReliableJudgmentsCanBeManuallyAccepted() throws Exception {
        append("LEGAL","[]","[]","mock",Instant.now());
        noCounter().andExpect(jsonPath("$.data.can_decide").value(true));
        append("ABNORMAL","[]","[]","mock",Instant.now());
        decide(body(0),key()).andExpect(status().isOk());
    }
    @Test void permissionAndScopeAreCheckedBeforeReplay() throws Exception {
        String body=body(0),key=key();decide(body,key).andExpect(status().isOk());
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='alarm:verify'",role);
        decide(body,key).andExpect(status().isForbidden());
        jdbc.update("update app_user_data_scope set org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where user_id=?",userId);
        noCounter().andExpect(status().isNotFound());
    }
    @Test void onlyReadAndVerifyPermissionsAreNeeded() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code not in ('alarm:read','alarm:verify')",role);
        decide(body(0),key()).andExpect(status().isOk());
    }
    @Test void versionAndEvaluationConflictRequireFreshConfirmation() throws Exception {
        decide(body(99),key()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        decide("{\"expected_version\":0,\"expected_evaluation_id\":\"old\"}",key()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVALUATION_CONFLICT"));
        decide("{\"expected_version\":0,\"expected_evaluation_id\":\"old\",\"actor_id\":\"spoof\"}",key()).andExpect(status().isBadRequest());
    }
    @Test void concurrentDecisionCommitsExactlyOnce() throws Exception {
        String request=body(0),key=key();var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {Callable<String> call=()->{barrier.await();return decide(request,key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();};var a=pool.submit(call);var b=pool.submit(call);assertThat(json.readTree(a.get(30,TimeUnit.SECONDS)).path("data").path("decision")).isEqualTo(json.readTree(b.get(30,TimeUnit.SECONDS)).path("data").path("decision"));}finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from uav_no_counter_decision where event_id=?",Integer.class,eventId)).isEqualTo(1);
    }
    @Test void repeatedSameRiskDoesNotReopenButNewRiskDoesAndHistoryRemains() throws Exception {
        String original=body(0),originalKey=key();
        String decisionId=json.readTree(decide(original,originalKey).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("decision").path("decision_id").asText();
        Thread.sleep(5);CounterEvidenceFixture.seed(jdbc,eventId);
        noCounter().andExpect(jsonPath("$.data.decision_active").value(true)).andExpect(jsonPath("$.data.review_required").value(false));
        append("ILLEGAL","[\"NEW_HEIGHT_VIOLATION\"]","[]","mock",Instant.now());
        noCounter().andExpect(jsonPath("$.data.decision_active").value(false)).andExpect(jsonPath("$.data.review_required").value(true)).andExpect(jsonPath("$.data.decision.decision_id").isNotEmpty());
        decide(original,originalKey).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision_active").value(false)).andExpect(jsonPath("$.data.review_required").value(true)).andExpect(jsonPath("$.data.decision.decision_id").value(decisionId));
        String secondId=json.readTree(decide(body(1),key()).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision_active").value(true)).andReturn().getResponse().getContentAsString()).path("data").path("decision").path("decision_id").asText();
        decide(original,originalKey).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision_active").value(true)).andExpect(jsonPath("$.data.decision.decision_id").value(secondId)).andExpect(jsonPath("$.data.replayed_decision_id").value(decisionId));
        assertThat(jdbc.queryForObject("select count(*) from uav_no_counter_decision where event_id=?",Integer.class,eventId)).isEqualTo(2);
    }
    @Test void unknownAfterDecisionDoesNotReopenOrFabricateNoRisk() throws Exception {
        decide(body(0),key()).andExpect(status().isOk());Thread.sleep(5);CounterEvidenceFixture.seed(jdbc,eventId);
        append("ILLEGAL","[\"NEW_RISK\"]","[\"MISSING\"]","mock",Instant.now());
        noCounter().andExpect(jsonPath("$.data.decision_active").value(true)).andExpect(jsonPath("$.data.can_decide").value(false));
    }
    @Test void lateCommittedEvaluationWithEarlierTimestampReopensWithoutUuidOrdering()throws Exception {
        var at=Instant.ofEpochMilli(decisions.latest(eventId).basis().evaluatedAt());
        var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        var future=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->{
            NoCounterEvidenceFixture.append(jdbc,eventId,"ILLEGAL","[\"LATE_NEW_RISK\"]","[]","mock",at,at);
            inserted.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Decision timed out");}catch(InterruptedException e){throw new IllegalStateException(e);}return true;
        }));
        try {assertThat(inserted.await(10,TimeUnit.SECONDS)).isTrue();decide(body(0),key()).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision_active").value(true));release.countDown();assertThat(future.get(20,TimeUnit.SECONDS)).isTrue();}
        finally{release.countDown();pool.shutdownNow();}
        noCounter().andExpect(jsonPath("$.data.review_required").value(true)).andExpect(jsonPath("$.data.decision_active").value(false));
    }
    @Test void readDoesNotWriteAndWorkersDoNotRestartHandledEpisode() throws Exception {
        decide(body(0),key()).andExpect(status().isOk());
        int before=jdbc.queryForObject("select count(*) from uav_no_counter_decision where event_id=?",Integer.class,eventId);
        for(int i=0;i<3;i++){noCounter().andExpect(status().isOk());read().andExpect(status().isOk());}
        assertThat(smsPolicy.enabled()).isTrue();assertThat(voicePolicy.enabled()).isTrue();
        automaticSms.process(eventId);automaticVoice.process(eventId);
        assertThat(jdbc.queryForObject("select count(*) from uav_no_counter_decision where event_id=?",Integer.class,eventId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("select attempt_count from uav_auto_sms_task where event_id=?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("select attempt_count from uav_auto_voice_task where event_id=?",Integer.class,eventId)).isZero();
    }
    @Test void requestedCounterAndUnresolvedEmergencyStopBlockDecision() throws Exception {
        var result=mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization","Bearer "+session).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""+eventId+"\",\"action_type\":\"COUNTERMEASURE\",\"channel\":\"MANUAL\",\"reason\":\"隔离测试反制申请\"}")).andExpect(status().isCreated());
        noCounter().andExpect(jsonPath("$.data.can_decide").value(false));
        decide(body(0),key()).andExpect(status().isConflict());
        String authorization=json.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path("authorization_id").asText();
        for(String state:List.of("APPROVED","EXECUTING")) {
            jdbc.update("update disposal_authorization set status=? where authorization_id=?",state,authorization);
            noCounter().andExpect(jsonPath("$.data.can_decide").value(false));
        }
        jdbc.update("update disposal_authorization set status='STOPPED' where authorization_id=?",authorization);
        String stop=key();
        jdbc.update("insert into disposal_emergency_stop(stop_id,event_id,requested_by,requested_at,reason_pending,note) values(?,?,?,?,true,'隔离停机核查')",stop,eventId,userId,System.currentTimeMillis());
        jdbc.update("insert into disposal_emergency_stop_device(stop_id,device_id,authorization_id,device_name,channel,source_mode,simulated,stop_status,detail) values(?,?,?,'隔离设备','MANUAL','mock',true,'UNSUPPORTED','未核实实际停机')",stop,key(),authorization);
        noCounter().andExpect(jsonPath("$.data.can_decide").value(false)).andExpect(jsonPath("$.data.block_reason").value("设备急停后的实际停机尚未核查，不能结束本次处置"));
    }
    protected ResultActions noCounter()throws Exception{return mvc.perform(get("/api/v1/uav-events/"+eventId+"/no-counter-decision").header("Authorization","Bearer "+session));}
    protected ResultActions decide(String body,String key)throws Exception{return mvc.perform(post("/api/v1/uav-events/"+eventId+"/no-counter-decision").header("Authorization","Bearer "+session).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
    protected String latestId(){return decisions.latest(eventId).basis().evaluationId();}
    protected String body(long version){return "{\"expected_version\":"+version+",\"expected_evaluation_id\":\""+latestId()+"\"}";}
    protected String key(){return UUID.randomUUID().toString();}
    protected String append(String legal,String reasons,String unknown,String mode,Instant observed)throws Exception {
        Thread.sleep(2);
        return NoCounterEvidenceFixture.append(jdbc,eventId,legal,reasons,unknown,mode,Instant.now(),observed);
    }
    protected ResultActions read()throws Exception{return mvc.perform(get("/api/v1/uav-events/"+eventId+"/advisory").header("Authorization","Bearer "+session));}
}
