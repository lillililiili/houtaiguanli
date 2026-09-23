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

@SpringBootTest(properties={"app.advisory.auto-sms.enabled=true","app.advisory.auto-voice.enabled=true","app.advisory.auto-voice.initial-delay-millis=3600000","app.advisory.auto-sms.initial-delay-millis=3600000","app.outbox.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutoVoiceApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AutoSmsService automatic;
    @Autowired com.uav.lowaltitude.modules.alarm.application.AutoVoiceService voiceService;
    @Autowired com.uav.lowaltitude.modules.alarm.application.AutoVoiceJob voiceJob;
    @SpyBean com.uav.lowaltitude.integration.mock.LocalAdvisoryVoiceAdapter voice;
    @SpyBean com.uav.lowaltitude.modules.alarm.application.AutoVoicePolicy voicePolicy;
    @SpyBean com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording recordings;
    private static final java.nio.file.Path AUDIO=createAudio();
    @org.springframework.test.context.DynamicPropertySource
    static void recording(org.springframework.test.context.DynamicPropertyRegistry p) {
        p.add("app.advisory.auto-voice.recording-id",()->"test-fixture-wave");
        p.add("app.advisory.auto-voice.recording-name",()->"隔离测试静音样本");
        p.add("app.advisory.auto-voice.recording-path",()->AUDIO.toString());
        p.add("app.advisory.auto-voice.recording-transcript",()->"仅验证音频格式的隔离测试样本，不含真实业务语音");
    }
    private static java.nio.file.Path createAudio() {
        try {
            var path=java.nio.file.Files.createTempFile("advisory-test-fixture-",".wav");path.toFile().deleteOnExit();
            var format=new javax.sound.sampled.AudioFormat(8000,16,1,true,false);
            try(var input=new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(new byte[1600]),format,800)) {
                javax.sound.sampled.AudioSystem.write(input,javax.sound.sampled.AudioFileFormat.Type.WAVE,path.toFile());
            }
            return path;
        } catch(Exception failure){throw new IllegalStateException(failure);}
    }
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

    @Test void configurationRevokedBetweenEligibilityAndClaimNeverSends()throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==2)jdbc.update("UPDATE notification_setting SET enabled=FALSE WHERE setting_id='advisory-voice'");return call.callRealMethod();})
                .when(directory).forPilotEvent(eq("ADVISORY_VOICE"),eq(eventId),any());
        voiceService.process(eventId);
        assertThat(jdbc.queryForObject("SELECT status FROM uav_auto_voice_task WHERE event_id=?",String.class,eventId)).isEqualTo("BLOCKED");
        verify(voice,never()).simulate(anyString(),any(),anyString());
    }
    @Test void legacyTaskWithoutSnapshotIsNotBackfilledFromCurrentDirectory()throws Exception {
        automatic.process(eventId);voiceService.process(eventId);
        jdbc.update("UPDATE uav_auto_sms_task SET recipient_snapshot=NULL WHERE event_id=?",eventId);
        jdbc.update("UPDATE uav_auto_voice_task SET recipient_snapshot=NULL WHERE event_id=?",eventId);
        jdbc.update("UPDATE business_contact SET name='后来的目录姓名' WHERE contact_id=(SELECT pilot_contact_id FROM flight_plan WHERE plan_id=?)",pilotPlanId);
        read().andExpect(jsonPath("$.data.auto_sms.recipient_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.auto_voice.recipient_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.recipient.name").value("后来的目录姓名"));
    }
    @Test void smsHistoryAndCurrentVoiceRecipientNeverBorrowEachOthersIdentity()throws Exception {
        automatic.process(eventId);
        jdbc.update("UPDATE business_contact SET name='后续关联飞手姓名',version=version+1 WHERE contact_id=(SELECT pilot_contact_id FROM flight_plan WHERE plan_id=?)",pilotPlanId);
        read().andExpect(jsonPath("$.data.auto_sms.recipient_snapshot.contact_name").value("测试关联飞手"))
                .andExpect(jsonPath("$.data.auto_voice.recipient_snapshot.contact_name").value("后续关联飞手姓名"))
                .andExpect(jsonPath("$.data.recipient.name").value("后续关联飞手姓名"));
        voiceService.process(eventId);
        assertThat(jdbc.queryForObject("SELECT recipient_name FROM uav_event_voice_advisory WHERE event_id=?",String.class,eventId)).isEqualTo("后续关联飞手姓名");
        assertThat(jdbc.queryForObject("SELECT recipient_name FROM uav_event_advisory WHERE event_id=? AND kind='SMS_SIMULATED'",String.class,eventId)).isEqualTo("测试关联飞手");
    }
    @AfterEach void clearSpy(){reset(directory);reset(sms);reset(audit);reset(voice);reset(voicePolicy);reset(recordings);}
    @Test void readingDoesNotScheduleOrSimulateVoice()throws Exception {
        read().andExpect(jsonPath("$.data.voice_mode").value("SIMULATED"))
                .andExpect(jsonPath("$.data.auto_voice.status").value("WAITING"));
        read().andExpect(status().isOk());
        assertThat(count("uav_auto_voice_task")).isZero();assertThat(count("uav_event_voice_advisory")).isZero();
        verify(voice,never()).simulate(anyString(),any(),anyString());
    }
    @Test void backgroundVoiceRunsWithoutSessionAndOnlyOnce()throws Exception {
        jdbc.update("delete from app_session where user_id=?",userId);
        voiceJob.poll();voiceJob.poll();
        assertThat(count("uav_event_voice_advisory")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from uav_auto_voice_task where event_id=?",String.class,eventId)).isEqualTo("SIMULATED_PLAYED");
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?",String.class,eventId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where object_id=? and action='auto_voice_played'",Integer.class,eventId)).isEqualTo(1);
    }
    @Test void smsAndVoiceBothCompleteInEitherOrderWithoutDuplicates()throws Exception {
        automatic.process(eventId);voiceService.process(eventId);automatic.process(eventId);voiceService.process(eventId);
        assertBothChannels();
        fixture();voiceService.process(eventId);automatic.process(eventId);voiceService.process(eventId);automatic.process(eventId);
        assertBothChannels();
    }
    private void assertBothChannels()throws Exception {
        assertThat(count("uav_event_advisory")).isEqualTo(1);assertThat(count("uav_event_voice_advisory")).isEqualTo(1);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("SIMULATED_DELIVERED"))
                .andExpect(jsonPath("$.data.auto_voice.status").value("SIMULATED_PLAYED"))
                .andExpect(jsonPath("$.data.auto_voice.answered_at").isNumber())
                .andExpect(jsonPath("$.data.auto_voice.playback_completed_at").isNumber())
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.can_request_counter").value(false));
    }
    @Test void unknownSmsDoesNotBorrowSuccessfulVoiceResultOrEnableResend()throws Exception {
        doReturn(null).when(sms).simulate(anyString(),anyString(),anyString(),anyString());
        automatic.process(eventId);voiceService.process(eventId);automatic.process(eventId);voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.auto_sms.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.auto_sms.can_retry").value(false))
                .andExpect(jsonPath("$.data.auto_voice.status").value("SIMULATED_PLAYED"));
        verify(sms,times(1)).simulate(anyString(),anyString(),anyString(),anyString());
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=? and kind='SMS_SIMULATED'",Integer.class,eventId)).isZero();
    }
    @Test void playedFactSurvivesDisabledPolicyMissingRecordingAndExpiredObservation()throws Exception {
        voiceService.process(eventId);doReturn(false).when(voicePolicy).enabled();doReturn(null).when(recordings).current();
        jdbc.update("update target_latest_state set observed_at=? where target_id=?",Timestamp.from(Instant.now().minusSeconds(500)),targetId);
        read().andExpect(jsonPath("$.data.auto_voice.enabled").value(false)).andExpect(jsonPath("$.data.auto_voice.status").value("SIMULATED_PLAYED"))
                .andExpect(jsonPath("$.data.auto_voice.recording_name").value("隔离测试静音样本"))
                .andExpect(jsonPath("$.data.auto_voice.answered_at").isNumber());
    }
    @Test void unconfiguredRecordingAndLiveSourceNeverPretendPlayed()throws Exception {
        doReturn(null).when(recordings).current();voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.auto_voice.status").value("UNAVAILABLE"));
        verify(voice,never()).simulate(anyString(),any(),anyString());assertThat(count("uav_event_voice_advisory")).isZero();
        reset(recordings);jdbc.update("update alarm set source_mode='live' where alarm_id=(select alarm_id from uav_event where event_id=?)",eventId);
        voiceService.process(eventId);read().andExpect(jsonPath("$.data.voice_mode").value("UNAVAILABLE"));
        assertThat(count("uav_event_voice_advisory")).isZero();
    }
    @Test void currentRuleAndObservationGuardVoiceWithoutMandatoryManualStep()throws Exception {
        jdbc.update("update uav_event set state_code='PENDING_VERIFICATION' where event_id=?",eventId);
        evaluation("ILLEGAL","FRESH","[]",true,Instant.now().minusSeconds(2));voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.auto_voice.status").value("SIMULATED_PLAYED"))
                .andExpect(jsonPath("$.data.auto_voice.trigger_source").value("RULE_ILLEGAL"));
        fixture();evaluation("LEGAL","FRESH","[]",true,Instant.now().minusSeconds(1));voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.auto_voice.status").value("BLOCKED"));assertThat(count("uav_event_voice_advisory")).isZero();
        fixture();observation("UNKNOWN");voiceService.process(eventId);read().andExpect(jsonPath("$.data.auto_voice.status").value("SIMULATED_PLAYED"));
    }
    @Test void manualContactBlocksVoiceButDoesNotFabricatePlayback()throws Exception {
        jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated) values(?,?,0,'CONTACT_RECORDED',0,?,'飞手','现场电话核对','已劝离',false,false)",UUID.randomUUID().toString(),eventId,userId);
        voiceService.process(eventId);read().andExpect(jsonPath("$.data.auto_voice.status").value("BLOCKED"));
        assertThat(count("uav_event_voice_advisory")).isZero();
    }
    @Test void clearFailureAllowsIdempotentQueueOnlyRetryUsingSameProviderKey()throws Exception {
        doReturn(new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoicePort.Delivery(true,"FAILED",null,null,null)).when(voice).simulate(anyString(),any(),anyString());
        voiceService.process(eventId);read().andExpect(jsonPath("$.data.auto_voice.status").value("FAILED"))
                .andExpect(jsonPath("$.data.auto_voice.can_retry").value(true));
        String key=UUID.randomUUID().toString();retry(key,1).andExpect(status().isOk());retry(key,1).andExpect(status().isOk());
        assertThat(count("uav_event_voice_advisory")).isZero();
        verify(voice,times(1)).simulate(eq("mock"),any(),eq("auto-advisory-voice:"+eventId));
        reset(voice);voiceService.process(eventId);read().andExpect(jsonPath("$.data.auto_voice.status").value("SIMULATED_PLAYED"));
        verify(voice).simulate(eq("mock"),any(),eq("auto-advisory-voice:"+eventId));
    }
    @Test void unknownOutcomeCannotRetryAndDoesNotBecomeSuccessfulContact()throws Exception {
        doThrow(new IllegalStateException("provider outcome unknown")).when(voice).simulate(anyString(),any(),anyString());
        voiceService.process(eventId);voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.auto_voice.status").value("UNKNOWN")).andExpect(jsonPath("$.data.auto_voice.can_retry").value(false));
        retry(UUID.randomUUID().toString(),1).andExpect(status().isConflict());
        assertThat(count("uav_event_voice_advisory")).isZero();verify(voice,times(1)).simulate(anyString(),any(),anyString());
    }
    @Test void answeredButPlaybackUnknownPreservesAnswerFactWithoutRetryOrSuccessfulContact()throws Exception {
        doAnswer(call->new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoicePort.Delivery(true,"ANSWERED","isolated-call",System.currentTimeMillis(),null)).when(voice).simulate(anyString(),any(),anyString());
        voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.auto_voice.status").value("UNKNOWN")).andExpect(jsonPath("$.data.auto_voice.answered_at").isNumber())
                .andExpect(jsonPath("$.data.auto_voice.playback_completed_at").doesNotExist()).andExpect(jsonPath("$.data.auto_voice.can_retry").value(false));
        assertThat(count("uav_event_voice_advisory")).isZero();
        assertThat(jdbc.queryForObject("select provider_call_id from uav_auto_voice_task where event_id=?",String.class,eventId)).isEqualTo("isolated-call");
        doReturn(false).when(voicePolicy).enabled();doReturn(null).when(recordings).current();
        read().andExpect(jsonPath("$.data.voice_mode").value("SIMULATED")).andExpect(jsonPath("$.data.auto_voice.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.auto_voice.answered_at").isNumber());
    }
    @Test void untrustedOrIncompletePlaybackReceiptNeverMarksSuccess()throws Exception {
        doAnswer(call->new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoicePort.Delivery(false,"SIMULATED_PLAYED","bad-call",System.currentTimeMillis(),System.currentTimeMillis())).when(voice).simulate(anyString(),any(),anyString());
        voiceService.process(eventId);read().andExpect(jsonPath("$.data.auto_voice.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.auto_voice.answered_at").doesNotExist());
        assertThat(count("uav_event_voice_advisory")).isZero();
    }
    @Test void receiptWithMissingStatusIsDurablyUnknown()throws Exception {
        doReturn(new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoicePort.Delivery(true,null,null,null,null)).when(voice).simulate(anyString(),any(),anyString());
        voiceService.process(eventId);read().andExpect(jsonPath("$.data.auto_voice.status").value("UNKNOWN"));
        assertThat(count("uav_event_voice_advisory")).isZero();
    }
    @Test void retryChecksPermissionScopeAndVersion()throws Exception {
        doReturn(new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoicePort.Delivery(true,"FAILED",null,null,null)).when(voice).simulate(anyString(),any(),anyString());
        voiceService.process(eventId);retry(UUID.randomUUID().toString(),0).andExpect(status().isConflict());
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='handoff:create'",role);
        retry(UUID.randomUUID().toString(),1).andExpect(status().isForbidden());
    }
    @Test void concurrentWorkersAndChannelsKeepOneVoiceRecordAndTwoContactFacts()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var a=pool.submit(()->voiceService.process(eventId));var b=pool.submit(()->voiceService.process(eventId));var c=pool.submit(()->automatic.process(eventId));
            a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);c.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertBothChannels();
        } finally {pool.shutdownNow();}
    }
    @Test void failedCommitLeavesClaimAndExpiredClaimBecomesUnknownWithoutRedial()throws Exception {
        doThrow(new IllegalStateException("audit unavailable")).when((com.uav.lowaltitude.platform.audit.AuditService)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit)).record(isNull(),eq("AUTO_VOICE"),anyString(),anyString(),eq("auto_voice_played"),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        assertThatThrownBy(()->voiceService.process(eventId)).isInstanceOf(IllegalStateException.class);
        assertThat(count("uav_event_voice_advisory")).isZero();reset(audit);
        jdbc.update("update uav_auto_voice_task set lease_until=0 where event_id=?",eventId);
        doReturn(false).when(voicePolicy).enabled();voiceJob.poll();voiceService.process(eventId);
        read().andExpect(jsonPath("$.data.auto_voice.status").value("UNKNOWN")).andExpect(jsonPath("$.data.auto_voice.can_retry").value(false));
        verify(voice,times(1)).simulate(eq("mock"),any(),eq("auto-advisory-voice:"+eventId));
    }
    @Test void retryCannotReplaceOriginallySelectedRecording()throws Exception {
        doReturn(new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoicePort.Delivery(true,"FAILED",null,null,null)).when(voice).simulate(anyString(),any(),anyString());
        voiceService.process(eventId);var original=recordings.current();
        doReturn(new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording(original.id(),"changed",original.transcript(),original.sha256())).when(recordings).current();
        retry(UUID.randomUUID().toString(),1).andExpect(status().isConflict());
        read().andExpect(jsonPath("$.data.auto_voice.can_retry").value(false));
    }
    protected ResultActions read()throws Exception{return mvc.perform(get("/api/v1/uav-events/"+eventId+"/advisory").header("Authorization","Bearer "+session));}
    private ResultActions retry(String key,long version)throws Exception{return mvc.perform(post("/api/v1/uav-events/"+eventId+"/advisory/auto-voice/retry").header("Authorization","Bearer "+session).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":"+version+",\"note\":\"已核对发送失败且目标仍在范围，申请补发\"}"));}
    private int count(String table){return jdbc.queryForObject("select count(*) from "+table+" where event_id=?",Integer.class,eventId);}
    private void observation(String outcome){jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,outcome,danger,note,urgent,simulated) values(?,?,0,'OBSERVATION',0,?,?,'UNKNOWN','人工现场核查',false,false)",UUID.randomUUID().toString(),eventId,userId,outcome);}
    protected void evaluation(String legal,String fresh,String unknowns,boolean linked,Instant at) {
        var version=jdbc.queryForMap("SELECT rule_set_id,rule_set_version_id FROM rule_set_version FETCH FIRST 1 ROWS ONLY");
        String run=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();Timestamp time=Timestamp.from(at);
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at) values(?,?,?,'ACTIVE','SCHEDULED',?,?,'DONE','mock',?)",run,version.get("rule_set_id"),version.get("rule_set_version_id"),time,time,time);
        String alarm=linked?jdbc.queryForObject("select alarm_id from uav_event where event_id=?",String.class,eventId):null;
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,plan_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at) values(?,?,?,'ACTIVE','TARGET',?,?,?,?,?,?,'FULL',?,CAST('[]' AS JSON),CAST('[]' AS JSON),CAST(? AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,'mock',?)",id,run,version.get("rule_set_version_id"),targetId,pilotPlanId,time,time,time,fresh,legal,unknowns,alarm,ORG,DISTRICT,time);
    }
}
