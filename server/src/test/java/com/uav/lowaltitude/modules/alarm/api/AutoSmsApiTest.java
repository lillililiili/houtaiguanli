package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.fasterxml.jackson.databind.*;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsService;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsJob;
import com.uav.lowaltitude.integration.mock.LocalAdvisorySmsAdapter;

@SpringBootTest(properties={"app.advisory.auto-sms.enabled=true","app.advisory.auto-sms.initial-delay-millis=3600000","app.outbox.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutoSmsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AutoSmsService automatic;
    @Autowired AutoSmsJob job;
    @SpyBean LocalAdvisorySmsAdapter sms;
    @SpyBean com.uav.lowaltitude.platform.audit.AuditService audit;
    @SpyBean com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
    protected String eventId,session,userId,role,targetId,pilotPlanId;
    private static final String ORG="seed-stage3-org", DISTRICT="seed-stage3-district";
    @BeforeEach void fixture() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        role="ROLE-ADV-"+suffix;userId=UUID.randomUUID().toString();session=UUID.randomUUID().toString();
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,?,'',false,true,0,0,0,false)",role,role);
        for(String p:List.of("alarm:read","alarm:verify","handoff:create","disposal:request","disposal:read","disposal:execute","disposal:approve"))
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,?,?,false,current_timestamp)",role,p,p.endsWith(":read")?"READ":"OP");
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",userId,"adv-"+suffix,"核查员",role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)",userId,ORG,DISTRICT);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)",session,userId,System.currentTimeMillis()+3600000);
        String alarm=UUID.randomUUID().toString(),source=UUID.randomUUID().toString();eventId=UUID.randomUUID().toString();
        Timestamp at=Timestamp.from(Instant.now());
        jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values(?,?,?,true,'mock',?,?,0)",source,"ADV-"+suffix,"劝离测试源",at,at);
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values(?,null,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",alarm,source,"ADV-"+suffix,at,at,ORG,DISTRICT,at);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'CONFIRMED',?,?,?,?,0)",eventId,alarm,ORG,DISTRICT,at,at);
        targetId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'UAV','mock',?,?,?, ?,0)",targetId,"AUTO-"+suffix,ORG,DISTRICT,at,at);
        jdbc.update("INSERT INTO target_latest_state(target_id,observed_at,received_at,created_at,updated_at,unknown_fields) VALUES(?,?,?,?,?,CAST('[]' AS JSON))",targetId,at,at,at,at);
        jdbc.update("UPDATE alarm SET target_id=? WHERE alarm_id=?",targetId,alarm);
        jdbc.update("UPDATE uav_event SET version=1 WHERE event_id=?",eventId);
        jdbc.update("INSERT INTO uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) VALUES(?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','测试人工确认现场违规',?,?)",UUID.randomUUID().toString(),eventId,userId,at);
        pilotPlanId=DirectoryAdvisoryFixture.create(jdbc,ORG,DISTRICT);
        DirectoryAdvisoryFixture.evaluation(jdbc,eventId,targetId,pilotPlanId,ORG,DISTRICT,Instant.now().minusSeconds(30));
    }

    @Test void manualConfirmationWithoutPrecisePlanDoesNotInventPilot()throws Exception {
        String withoutEvaluation=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) SELECT ?,?,'UAV','mock',owner_org_id,district_id,created_at,updated_at,0 FROM target WHERE target_id=?",withoutEvaluation,"NO-PLAN-"+withoutEvaluation,targetId);
        jdbc.update("INSERT INTO target_latest_state(target_id,observed_at,received_at,created_at,updated_at,unknown_fields) SELECT ?,observed_at,received_at,created_at,updated_at,unknown_fields FROM target_latest_state WHERE target_id=?",withoutEvaluation,targetId);
        jdbc.update("UPDATE alarm SET target_id=? WHERE alarm_id=(SELECT alarm_id FROM uav_event WHERE event_id=?)",withoutEvaluation,eventId);
        targetId=withoutEvaluation;
        automatic.process(eventId);assertBlocked();
        verify(sms,never()).simulate(anyString(),anyString(),anyString(),anyString());
    }
    @Test void missingPilotDirectoryNeverSendsToAPlaceholderOrUnitContact() throws Exception {
        jdbc.update("update flight_plan set pilot_contact_id=NULL where plan_id in(select plan_id from rule_evaluation where target_id=?)",targetId);
        automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("BLOCKED"));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=?",Integer.class,eventId)).isZero();
        verify(sms,never()).simulate(anyString(),anyString(),anyString(),anyString());
    }

    @Test void configurationRevokedBetweenEligibilityAndClaimNeverSends()throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==2)jdbc.update("UPDATE notification_setting SET enabled=FALSE WHERE setting_id='advisory-sms'");return call.callRealMethod();})
                .when(directory).forPilotEvent(eq("ADVISORY_SMS"),eq(eventId),any());
        automatic.process(eventId);
        assertThat(jdbc.queryForObject("SELECT status FROM uav_auto_sms_task WHERE event_id=?",String.class,eventId)).isEqualTo("BLOCKED");
        verify(sms,never()).simulate(anyString(),anyString(),anyString(),anyString());
    }
    @AfterEach void clearSpy(){reset(directory);reset(sms);reset(audit);}
    @Test void voiceIsDisabledByDefaultAndCannotBeRetried()throws Exception {
        read().andExpect(status().isOk()).andExpect(jsonPath("$.data.voice_mode").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.auto_voice.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.auto_voice.can_retry").value(false));
        mvc.perform(post("/api/v1/uav-events/"+eventId+"/advisory/auto-voice/retry")
                .header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":1,\"note\":\"核查语音通道状态\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("AUTO_VOICE_RETRY_BLOCKED"));
    }
    @Test void readingNeverSchedulesOrSends()throws Exception {
        read().andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_sms.status").value("WAITING"));
        read().andExpect(status().isOk());
        assertThat(count("uav_auto_sms_task")).isZero();assertThat(count("uav_event_advisory")).isZero();
        verify(sms,never()).simulate(anyString(),anyString(),anyString(),anyString());
    }
    @Test void backgroundJobRunsWithoutBrowserOrSessionAndOnlySendsOnce()throws Exception {
        // 没有调用页面或发送API；会话全部退出后直接执行定时任务入口。
        jdbc.update("delete from app_session where user_id=?",userId);
        assertThat(com.uav.lowaltitude.platform.security.AuthContext.get()).isNull();
        job.poll();job.poll();
        assertThat(count("uav_event_advisory")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT trigger_mode FROM uav_event_advisory WHERE event_id=?",String.class,eventId)).isEqualTo("AUTO");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM uav_event_advisory WHERE event_id=?",String.class,eventId)).isNull();
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,eventId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND account='AUTO_SMS' AND action='auto_sms_delivered'",Integer.class,eventId)).isEqualTo(1);
    }
    @Test void preciselyLinkedRulePublishesEvidenceTimes()throws Exception {
        automatic.process(eventId);
        read().andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_sms.status").value("SIMULATED_DELIVERED"))
                .andExpect(jsonPath("$.data.auto_sms.trigger_source").value("RULE_ILLEGAL"))
                .andExpect(jsonPath("$.data.auto_sms.data_updated_at").isNumber())
                .andExpect(jsonPath("$.data.records[0].actor_name").value("自动短信服务"))
                .andExpect(jsonPath("$.data.records[0].policy_code").value("LOCAL_AUTO_SMS_DEMO_V1"));
    }
    @Test void freshLinkedIllegalRuleCanNotifyWithoutHumanConfirmation()throws Exception {
        jdbc.update("update uav_event set state_code='PENDING_VERIFICATION' where event_id=?",eventId);
        evaluation("ILLEGAL","FRESH","[]",true,Instant.now().minusSeconds(2));
        automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("SIMULATED_DELIVERED"))
                .andExpect(jsonPath("$.data.auto_sms.trigger_source").value("RULE_ILLEGAL"))
                .andExpect(jsonPath("$.data.auto_sms.evaluated_at").isNumber())
                .andExpect(jsonPath("$.data.can_request_counter").value(false));
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?",String.class,eventId)).isEqualTo("PENDING_VERIFICATION");
    }
    @Test void latestLegalResultOverridesOldIllegalEvenAfterManualConfirmation()throws Exception {
        evaluation("ILLEGAL","FRESH","[]",true,Instant.now().minusSeconds(10));
        evaluation("LEGAL","FRESH","[]",false,Instant.now().minusSeconds(1));
        automatic.process(eventId);assertBlocked();
    }
    @Test void unknownStaleAndUnlinkedRuleNeverFallBackToManualConfirmation()throws Exception {
        evaluation("ILLEGAL","FRESH","[\"MISSING_LOCATION\"]",true,Instant.now().minusSeconds(4));
        automatic.process(eventId);assertBlocked();
        evaluation("ILLEGAL","STALE","[]",true,Instant.now().minusSeconds(3));automatic.process(eventId);assertBlocked();
        evaluation("ILLEGAL","FRESH","[]",false,Instant.now().minusSeconds(2));automatic.process(eventId);assertBlocked();
    }
    @Test void staleTargetOldEventAndFalsePositiveDoNotSend()throws Exception {
        jdbc.update("update target_latest_state set observed_at=? where target_id=?",Timestamp.from(Instant.now().minusSeconds(121)),targetId);
        automatic.process(eventId);assertBlocked();
        jdbc.update("update target_latest_state set observed_at=current_timestamp where target_id=?",targetId);
        jdbc.update("update alarm set received_at=? where alarm_id=(select alarm_id from uav_event where event_id=?)",Timestamp.from(Instant.now().minusSeconds(301)),eventId);
        automatic.process(eventId);assertBlocked();
        jdbc.update("update alarm set received_at=current_timestamp where alarm_id=(select alarm_id from uav_event where event_id=?)",eventId);
        jdbc.update("update uav_event set state_code='FALSE_POSITIVE' where event_id=?",eventId);automatic.process(eventId);assertBlocked();
    }
    @Test void historicalObservationDoesNotOverrideCurrentNotificationFacts()throws Exception {
        observation("DEPARTED");automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("SIMULATED_DELIVERED"));
        assertThat(count("uav_event_advisory")).isEqualTo(2);
        fixture();
        jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated) values(?,?,0,'CONTACT_RECORDED',0,?,'飞手','现场电话核对','已劝离',false,false)",UUID.randomUUID().toString(),eventId,userId);
        automatic.process(eventId);assertThat(count("uav_event_advisory")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from uav_auto_sms_task where event_id=?",String.class,eventId)).isEqualTo("BLOCKED");
    }
    @Test void liveNeverPretendsSimulationSucceeded()throws Exception {
        jdbc.update("update alarm set source_mode='live' where alarm_id=(select alarm_id from uav_event where event_id=?)",eventId);
        automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("UNAVAILABLE")).andExpect(jsonPath("$.data.auto_sms.can_retry").value(false));
        assertThat(count("uav_event_advisory")).isZero();
    }
    @Test void failureIsDurableRetryOnlyQueuesAndUsesSameProviderKey()throws Exception {
        doThrow(new IllegalStateException("fake transport failed")).when(sms).simulate(anyString(),anyString(),anyString(),anyString());
        automatic.process(eventId);automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("FAILED")).andExpect(jsonPath("$.data.auto_sms.can_retry").value(true));
        assertThat(count("uav_event_advisory")).isZero();
        String key=UUID.randomUUID().toString();String first=retry(key,1).andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_sms.status").value("WAITING")).andReturn().getResponse().getContentAsString();
        assertThat(retry(key,1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        retry(UUID.randomUUID().toString(),1).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(count("uav_event_advisory")).isZero();
        reset(sms);automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("SIMULATED_DELIVERED")).andExpect(jsonPath("$.data.auto_sms.attempt_count").value(2));
        verify(sms).simulate(eq("mock"),anyString(),anyString(),eq("auto-advisory:"+eventId));
    }
    @Test void retryRequiresActionsScopeAndValidVersion()throws Exception {
        doThrow(new IllegalStateException()).when(sms).simulate(anyString(),anyString(),anyString(),anyString());automatic.process(eventId);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='handoff:create'",role);
        retry(UUID.randomUUID().toString(),1).andExpect(status().isForbidden());
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,'handoff:create','OP',false,current_timestamp)",role);
        jdbc.update("update app_user_data_scope set org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where user_id=?",userId);
        retry(UUID.randomUUID().toString(),1).andExpect(status().isNotFound());
        read().andExpect(status().isNotFound());
    }
    @Test void concurrentWorkersCommitExactlyOneAutomaticRecord()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CyclicBarrier(2);
        try {
            java.util.concurrent.Callable<Void> call=()->{start.await(10,java.util.concurrent.TimeUnit.SECONDS);automatic.process(eventId);return null;};
            var first=pool.submit(call);var second=pool.submit(call);first.get(20,java.util.concurrent.TimeUnit.SECONDS);second.get(20,java.util.concurrent.TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(count("uav_event_advisory")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select attempt_count from uav_auto_sms_task where event_id=?",Integer.class,eventId)).isEqualTo(1);
    }
    @Test void sendingCannotBeRetriedAndExpiredClaimIsReconciledWithoutAutomaticResend()throws Exception {
        jdbc.update("insert into uav_auto_sms_task(event_id,status,policy_code,reason,updated_at,provider_key,claim_token,lease_until) values(?,'SENDING','LOCAL_AUTO_SMS_DEMO_V1','发送中',?,?,?,?)",eventId,System.currentTimeMillis(),"auto-advisory:"+eventId,UUID.randomUUID().toString(),System.currentTimeMillis()+60000);
        retry(UUID.randomUUID().toString(),1).andExpect(status().isConflict());
        jdbc.update("update uav_auto_sms_task set lease_until=0 where event_id=?",eventId);automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("FAILED"));assertThat(count("uav_event_advisory")).isZero();
    }
    @Test void getShowsCurrentBlockWithoutMutatingQueuedTask()throws Exception {
        jdbc.update("insert into uav_auto_sms_task(event_id,status,policy_code,reason,updated_at,provider_key) values(?,'WAITING','LOCAL_AUTO_SMS_DEMO_V1','等待后台发送',?,?)",eventId,System.currentTimeMillis(),"auto-advisory:"+eventId);
        jdbc.update("update target_latest_state set observed_at=? where target_id=?",Timestamp.from(Instant.now().minusSeconds(121)),targetId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("BLOCKED"));
        assertThat(jdbc.queryForObject("select status from uav_auto_sms_task where event_id=?",String.class,eventId)).isEqualTo("WAITING");
    }
    @Test void historicalObservationCannotSubstituteForReliableSystemEvidence()throws Exception {
        observation("STILL_INSIDE");jdbc.update("update uav_event_advisory set danger='HIGH' where event_id=?",eventId);
        automatic.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("SIMULATED_DELIVERED")).andExpect(jsonPath("$.data.can_request_counter").value(false));
    }
    @Test void futureTimestampAndUnknownObjectAreNotUsableCurrentFacts()throws Exception {
        jdbc.update("update target_latest_state set observed_at=? where target_id=?",Timestamp.from(Instant.now().plusSeconds(60)),targetId);automatic.process(eventId);assertBlocked();
        jdbc.update("update target_latest_state set observed_at=current_timestamp where target_id=?",targetId);
        jdbc.update("update target set object_type_code='UNKNOWN' where target_id=?",targetId);automatic.process(eventId);assertBlocked();
    }
    @Test void differentEventsCannotReuseOneConcurrentRetryKey()throws Exception {
        doThrow(new IllegalStateException()).when(sms).simulate(anyString(),anyString(),anyString(),anyString());
        automatic.process(eventId);String firstEvent=eventId,firstSession=session;
        fixture();automatic.process(eventId);String secondEvent=eventId;
        String key=UUID.randomUUID().toString();var barrier=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call->{Object result=call.callRealMethod();barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return result;})
                .when((com.uav.lowaltitude.platform.audit.AuditService)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit)).record(anyString(),anyString(),anyString(),eq("alarm"),eq("auto_sms_retry_requested"),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<String,java.util.concurrent.Callable<org.springframework.mock.web.MockHttpServletResponse>> request=id->()->mvc.perform(post("/api/v1/uav-events/"+id+"/advisory/auto-sms/retry")
                    .header("Authorization","Bearer "+firstSession).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expected_version\":1,\"note\":\"retry after verifying channel failure\"}")).andReturn().getResponse();
            var a=pool.submit(request.apply(firstEvent));var b=pool.submit(request.apply(secondEvent));
            var first=a.get(20,java.util.concurrent.TimeUnit.SECONDS);var second=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(first.getStatus(),second.getStatus())).containsExactlyInAnyOrder(200,409);
            var rejected=first.getStatus()==409?first:second;
            assertThat(json.readTree(rejected.getContentAsString()).path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertThat(jdbc.queryForList("SELECT status FROM uav_auto_sms_task WHERE event_id IN (?,?)",String.class,firstEvent,secondEvent)).containsExactlyInAnyOrder("WAITING","FAILED");
            assertThat(jdbc.queryForList("SELECT version FROM uav_event WHERE event_id IN (?,?)",Long.class,firstEvent,secondEvent)).containsExactlyInAnyOrder(1L,2L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id IN (?,?) AND action='auto_sms_retry_requested'",Integer.class,firstEvent,secondEvent)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    protected ResultActions read()throws Exception{return mvc.perform(get("/api/v1/uav-events/"+eventId+"/advisory").header("Authorization","Bearer "+session));}
    @Test void currentReliableEvidenceReplacesManualObservationAndIsRechecked()throws Exception {
        evaluation("ILLEGAL","FRESH","[]",true,Instant.now(),true);
        read().andExpect(jsonPath("$.data.can_request_counter").value(true));
        observation("DEPARTED");
        read().andExpect(jsonPath("$.data.can_request_counter").value(true));
        evaluation("LEGAL","FRESH","[]",true,Instant.now(),true);
        read().andExpect(jsonPath("$.data.can_request_counter").value(false));
    }
    @Test void staleOrInsufficientSystemEvidenceCannotEnableCounter()throws Exception {
        read().andExpect(jsonPath("$.data.can_request_counter").value(false));
        evaluation("ILLEGAL","FRESH","[]",true,Instant.now(),true);
        jdbc.update("update target_latest_state set observed_at=? where target_id=?",Timestamp.from(Instant.now().minusSeconds(7200)),targetId);
        read().andExpect(jsonPath("$.data.can_request_counter").value(false));
    }
    private ResultActions retry(String key,long version)throws Exception{return mvc.perform(post("/api/v1/uav-events/"+eventId+"/advisory/auto-sms/retry").header("Authorization","Bearer "+session).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":"+version+",\"note\":\"已核对发送失败且目标仍在范围，申请补发\"}"));}
    private int count(String table){return jdbc.queryForObject("select count(*) from "+table+" where event_id=?",Integer.class,eventId);}
    private void assertBlocked()throws Exception{read().andExpect(jsonPath("$.data.auto_sms.status").value("BLOCKED"));assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=? and kind='SMS_SIMULATED'",Integer.class,eventId)).isZero();}
    private void observation(String outcome){jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,outcome,danger,note,urgent,simulated) values(?,?,0,'OBSERVATION',0,?,?,'UNKNOWN','人工现场核查',false,false)",UUID.randomUUID().toString(),eventId,userId,outcome);}
    protected void evaluation(String legal,String fresh,String unknowns,boolean linked,Instant at) {
        evaluation(legal,fresh,unknowns,linked,at,false);
    }
    private void evaluation(String legal,String fresh,String unknowns,boolean linked,Instant at,boolean sufficient) {
        var version=jdbc.queryForMap("SELECT rule_set_id,rule_set_version_id FROM rule_set_version FETCH FIRST 1 ROWS ONLY");
        String run=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();Timestamp time=Timestamp.from(at);
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at) values(?,?,?,'ACTIVE','SCHEDULED',?,?,'DONE','mock',?)",run,version.get("rule_set_id"),version.get("rule_set_version_id"),time,time,time);
        String alarm=linked?jdbc.queryForObject("select alarm_id from uav_event where event_id=?",String.class,eventId):null;
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,plan_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) values(?,?,?,'ACTIVE','TARGET',?,?,?,?,?,?,'FULL',?,CAST('[]' AS JSON),CAST('[]' AS JSON),CAST(? AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,'mock',?,?,?,CAST(? AS JSON))",id,run,version.get("rule_set_version_id"),targetId,pilotPlanId,time,time,time,fresh,legal,unknowns,alarm,ORG,DISTRICT,time,sufficient?"SUFFICIENT":null,sufficient?"test-v1":null,sufficient?"[]":null);
    }
}
