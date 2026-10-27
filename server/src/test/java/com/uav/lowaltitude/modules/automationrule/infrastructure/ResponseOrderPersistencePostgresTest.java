package com.uav.lowaltitude.modules.automationrule.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationInsert;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.RunRow;

/** All fixtures live in the guarded disposable PostgreSQL schema; no running service or device is used. */
@SpringBootTest @ActiveProfiles("test") @Transactional
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ResponseOrderPersistencePostgresTest {
    static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){DATABASE.springProperties(r);}
    @AfterAll static void close(){DATABASE.close();}
    @Autowired JdbcTemplate jdbc;@Autowired ResponseOrderRepository ordering;@Autowired AutomationRuntimeRepository runs;
    @Autowired UavRiskCaseIdentity cases;@Autowired DisposalRepository authorizations;
    @Autowired com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository voiceTasks;
    String event,first,second,target,mode,org,district;
    long now;
    @BeforeEach void fixture(){
        now=System.currentTimeMillis();
        var row=jdbc.queryForList("SELECT c.* FROM uav_risk_case c JOIN uav_event e ON e.event_id=c.root_event_id JOIN alarm a ON a.alarm_id=e.alarm_id WHERE c.episode_id IS NULL AND c.superseded_by IS NULL AND a.target_id IS NOT NULL ORDER BY c.risk_case_id FETCH FIRST 1 ROW ONLY").get(0);
        first=(String)row.get("risk_case_id");event=(String)row.get("root_event_id");target=(String)row.get("target_id");mode=(String)row.get("source_mode");org=(String)row.get("owner_org_id");district=(String)row.get("district_id");
        second=id();
        jdbc.update("INSERT INTO uav_risk_case(risk_case_id,root_event_id,airspace_id,target_id,owner_org_id,district_id,source_mode,created_at) VALUES(?,?,?,?,?,?,?,?)",second,event,id(),target,org,district,mode,now);
        jdbc.update("INSERT INTO uav_risk_case_member VALUES(?,?,'ROOT',?)",second,event,now);
    }
    @Test void defaultOffAndPriorityDeadlineSurvivesConfigurationAndRepositoryRestart(){
        var policy=ordering.policy(false);assertThat(policy.enabled()).isFalse();
        cases.runWithCase(first,()->{cases.lockCases(event);ordering.start("case:"+first,event,policy,now);});
        var frozen=ordering.window("case:"+first);
        jdbc.update("UPDATE response_order_policy SET version=version+1,max_wait_seconds=5,enabled=TRUE WHERE policy_id='uav'");
        var restarted=new ResponseOrderRepository(jdbc);
        cases.runWithCase(first,()->{cases.lockCases(event);restarted.start("case:"+first,event,restarted.policy(false),now+50_000);});
        assertThat(restarted.window("case:"+first)).isEqualTo(frozen);
        assertThat(frozen.deadlineAt()).isEqualTo(now+60_000);
    }
    @Test void voiceSendBasisSurvivesUnknownLateReceiptAndRepositoryRestart(){
        cases.runWithCase(first,()->{
            var original=voiceBasis("WAIT_TIMEOUT",now);var recording=voiceRecording();
            claimVoice("original");voiceTasks.prepareSend(event,"original",recording,now,original);
            voiceTasks.finish(event,"original","UNKNOWN","await receipt",null,null,null,null,now+1);
            voiceTasks.answer(event,"original","late-answer",now+10,now+11);
            String record=id();voiceTasks.append(record,event,100,now+21,recording,"late-answer",now+10,now+20,"isolated");
            voiceTasks.finish(event,"original","SIMULATED_PLAYED","played",record,"late-answer",now+10,now+20,now+21);
            jdbc.update("UPDATE response_order_policy SET version=version+1,max_wait_seconds=5 WHERE policy_id='uav'");
            var restarted=new com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository(jdbc);restarted.riskCases(cases);
            assertThat(restarted.find(event).responseOrderBasis()).isEqualTo(original);
            assertThat(restarted.find(event).status()).isEqualTo("SIMULATED_PLAYED");
        });
    }
    @Test void retryCannotReplaceOriginalSendBasisAndOtherRegionHasNoSnapshot(){
        cases.runWithCase(first,()->{
            var original=voiceBasis("START_FAILED",now);claimVoice("first-attempt");
            voiceTasks.prepareSend(event,"first-attempt",voiceRecording(),now,original);
            voiceTasks.finish(event,"first-attempt","FAILED","failed",null,null,null,null,now+1);
            voiceTasks.queueRetry(event,now+2);claimVoice("retry");
            voiceTasks.prepareSend(event,"retry",voiceRecording(),now+3,voiceBasis("WAIT_TIMEOUT",now+3));
            assertThat(voiceTasks.find(event).responseOrderBasis()).isEqualTo(original);
        });
        cases.runWithCase(second,()->{voiceTasks.initialize(event,now,"isolated");assertThat(voiceTasks.find(event).responseOrderBasis()).isNull();});
    }
    @Test void acceptedLegacyCallDoesNotAcquireAnInventedOrderingBasis(){
        cases.runWithCase(first,()->{
            claimVoice("legacy");
            jdbc.update("UPDATE uav_auto_voice_task SET send_started_at=? WHERE event_id=? AND risk_scope=?",now-1000,event,first);
            voiceTasks.prepareSend(event,"legacy",voiceRecording(),now,voiceBasis("WAIT_TIMEOUT",now));
            assertThat(voiceTasks.find(event).responseOrderBasis()).isNull();
        });
    }
    void claimVoice(String token){
        voiceTasks.initialize(event,now,"isolated");
        voiceTasks.claim(event,token,new com.uav.lowaltitude.modules.alarm.application.AdvisoryEligibilityService.Eligibility(true,"WAITING","isolated","COUNTER_FIRST",null,null),voiceRecording(),now);
    }
    com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording voiceRecording(){return new com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording("isolated-recording","isolated","isolated","hash");}
    com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.ResponseOrderBasis voiceBasis(String status,long at){return new com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.ResponseOrderBasis(status,"isolated send-time decision",60,at-60_000,at,4,at);}
    @Test void verificationAndCounterRunsRemainIndependentForTwoAreasOfOneEvent(){
        for(String category:List.of("verify","counter")){
            String a=id(),b=id();
            cases.runWithCase(first,()->saveRun(category,a,"PASS"));
            cases.runWithCase(second,()->saveRun(category,b,"WAITING"));
            cases.runWithCase(first,()->{assertThat(runs.state(category,event).runId()).isEqualTo(a);assertThat(runs.runBelongsToCase(a,event)).isTrue();assertThat(runs.runBelongsToCase(b,event)).isFalse();});
            cases.runWithCase(second,()->{assertThat(runs.state(category,event).runId()).isEqualTo(b);assertThat(runs.runBelongsToCase(a,event)).isFalse();});
            assertThat(runs.state(category,event)).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_case_state WHERE risk_case_id IN (?,?)",Long.class,first,second)).isEqualTo(4);
    }
    void saveRun(String category,String id,String status){runs.insertRun(new RunRow(id,category,event,target,1,status,"isolated",now,now,mode,"[]"),"{}");runs.state(category,event,1,id,status,null,"{}",id);}
    @Test void priorityCandidatesUseCaseVerificationInsteadOfTheSharedRawEvent(){
        jdbc.update("UPDATE uav_event SET state_code='CONFIRMED' WHERE event_id=?",event);
        assertThat(ordering.candidates("",10000)).as("one raw confirmation cannot confirm two regional risks").doesNotContain(event);
        verification(first,1,"CONFIRMED");
        jdbc.update("UPDATE uav_event SET state_code='PENDING_VERIFICATION' WHERE event_id=?",event);
        jdbc.update("UPDATE uav_risk_case SET created_at=? WHERE risk_case_id IN (?,?)",now-3*86_400_000L,first,second);
        assertThat(ordering.candidates("",10000)).as("case confirmation survives raw pending state and crossing midnight").contains(event);
        assertThat(ordering.candidates(event,10000)).as("keyset resumes strictly after the previous event").doesNotContain(event);
        verification(first,2,"FALSE_POSITIVE");
        assertThat(ordering.candidates("",10000)).as("latest case verdict controls eligibility").doesNotContain(event);
        verification(second,1,"CONFIRMED");
        assertThat(ordering.candidates("",10000)).contains(event);
        jdbc.update("UPDATE uav_risk_case SET superseded_by=? WHERE risk_case_id=?",first,second);
        assertThat(ordering.candidates("",10000)).as("superseded risk cannot retain candidate eligibility").doesNotContain(event);
    }
    @Test void priorityCandidatesRetainUniqueLegacyConfirmation(){
        jdbc.update("UPDATE uav_risk_case SET superseded_by=? WHERE risk_case_id=?",first,second);
        jdbc.update("UPDATE uav_event SET state_code='CONFIRMED' WHERE event_id=?",event);
        assertThat(ordering.candidates("",10000)).contains(event);
        jdbc.update("UPDATE uav_event SET state_code='PENDING_VERIFICATION' WHERE event_id=?",event);
        assertThat(ordering.candidates("",10000)).doesNotContain(event);
    }
    void verification(String risk,long version,String conclusion){
        String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        jdbc.update("INSERT INTO uav_risk_case_verification(verification_id,risk_case_id,case_version,event_id,event_version,conclusion,basis_kind,actor_id,note,created_at) VALUES(?,?,?,?,1,?,'OBSERVED',?,'isolated candidate test',?)",id(),risk,version,event,conclusion,actor,now);
    }
    @Test void onlyCurrentCasesRealStartReceiptReleasesThePhone(){
        String device=device(),auth=id(),command=id();
        String policy=jdbc.queryForObject("SELECT policy_code FROM disposal_policy WHERE status='ACTIVE' ORDER BY policy_code FETCH FIRST 1 ROW ONLY",String.class);
        String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        var at=OffsetDateTime.ofInstant(Instant.ofEpochMilli(now-500),ZoneOffset.UTC);
        authorizations.insert(new AuthorizationInsert(auth,"ORDER-"+auth.substring(0,12),"COUNTERMEASURE","UAV_EVENT",event,target,device,"COUNTERMEASURE_4CH","isolated",actor,at,"REQUESTED",policy,org,district,mode));
        jdbc.update("UPDATE disposal_authorization SET risk_case_id=? WHERE authorization_id=?",first,auth);
        jdbc.update("INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,source_mode,simulated,authorization_id,issued_at,completed_at,created_at,updated_at) VALUES(?,?,?,?,'COUNTERMEASURE_4CH','isolated','SUCCEEDED','live',TRUE,?,?,?,?,?)",command,command,device,actor,auth,now-400,now-100,now-500,now-100);
        jdbc.update("INSERT INTO countermeasure_4ch_command(command_id,action,mask,authorization_id) VALUES(?,'SET_MASK',13,?)",command,auth);
        jdbc.update("UPDATE disposal_authorization SET execution_command_id=? WHERE authorization_id=?",command,auth);
        assertThat(evidence(first)).isEqualTo(new ResponseOrderRepository.StartEvidence(false,false));
        String inbox=id();jdbc.update("INSERT INTO inbox_message(inbox_id,source,source_msg_id,received_at) VALUES(?,'isolated-order',?,?)",inbox,inbox,now-100);
        jdbc.update("INSERT INTO command_receipt(receipt_id,command_id,inbox_id,receipt_kind,received_at,payload) VALUES(?,?,?,'COMPLETED',?,'{}')",id(),command,inbox,now-100);
        assertThat(evidence(first).started()).isTrue();assertThat(evidence(second).started()).isFalse();
        jdbc.update("UPDATE device_command SET status='TIMED_OUT' WHERE command_id=?",command);assertThat(evidence(first)).isEqualTo(new ResponseOrderRepository.StartEvidence(false,false));
        jdbc.update("UPDATE device_command SET status='FAILED' WHERE command_id=?",command);assertThat(evidence(first).failed()).isTrue();
        jdbc.update("UPDATE device_command SET status='SUCCEEDED' WHERE command_id=?",command);jdbc.update("UPDATE countermeasure_4ch_command SET mask=0 WHERE command_id=?",command);assertThat(evidence(first).started()).isFalse();
        jdbc.update("UPDATE countermeasure_4ch_command SET mask=13 WHERE command_id=?",command);jdbc.update("UPDATE disposal_authorization SET risk_case_id=NULL WHERE authorization_id=?",auth);assertThat(evidence(first).started()).as("a shared legacy event cannot assign its old device result to a chosen region").isFalse();
    }
    ResponseOrderRepository.StartEvidence evidence(String scope){return ordering.startEvidence(scope,List.of(event),true,now-1000,now);}
    String device(){String id=id(),source=id();jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at) VALUES(?,?,?,'COUNTERMEASURE_TCP_4CH_V2_0','2.0','live',TRUE,'127.0.0.1/32',TRUE,0,?,?)",source,source,"isolated-order",now,now);jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) VALUES(?,?,?,?,?,'countermeasure','反制','反制直连',TRUE,'live',TRUE,0,?,?)",id,source,id,id,"isolated-order",now,now);return id;}
    static String id(){return UUID.randomUUID().toString();}
}
