package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.fasterxml.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UavAdvisoryApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    protected String eventId,session,userId,role;
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
    }
    @Test void manualObservationIsRetiredWithoutWritingOrAdvancing() throws Exception {
        act(observe(0,"STILL_INSIDE","HIGH",true),key()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MANUAL_OBSERVATION_RETIRED"));
        read().andExpect(jsonPath("$.data.event_version").value(0)).andExpect(jsonPath("$.data.records.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=?",Integer.class,eventId)).isZero();
    }
    @Test void replayReturnsSameResultWithoutDuplicateAndReusedKeyConflicts() throws Exception {
        String key=key();String first=act(sms(0),key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String repeated=act(sms(0),key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(repeated)).isEqualTo(json.readTree(first));
        act(sms(1),key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        act(sms(0),key()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }
    @Test void lackOfWritePermissionAndScopeCannotReadOrReplay() throws Exception {
        String key=key();act(sms(0),key).andExpect(status().isOk());
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='handoff:create'",role);
        act("not json",key).andExpect(status().isForbidden());
        jdbc.update("update app_user_data_scope set org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where user_id=?",userId);
        read().andExpect(status().isNotFound());
    }
    @Test void sameReadPermissionWithoutRequestPermissionCannotOfferCounter() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='disposal:request'",role);
        read().andExpect(jsonPath("$.data.can_request_counter").value(false));
    }
    @Test void liveCannotSimulateButManualContactCanBeRecorded() throws Exception {
        jdbc.update("update alarm set source_mode='live' where alarm_id=(select alarm_id from uav_event where event_id=?)",eventId);
        read().andExpect(jsonPath("$.data.sms_mode").value("UNAVAILABLE"));
        act(sms(0),key()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SMS_CHANNEL_UNAVAILABLE"));
        act(sms(0).replace("SMS_SIMULATED","CONTACT_RECORDED"),key()).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].simulated").value(false)).andExpect(jsonPath("$.data.records[0].delivery_status").doesNotExist());
    }
    @Test void pendingEventAndInvalidOrIncompleteObservationsDoNotAdvance() throws Exception {
        act(observe(0,"UNKNOWN","HIGH",true),key()).andExpect(status().isBadRequest());
        act("{\"expected_version\":0,\"kind\":\"OBSERVATION\",\"outcome\":\"UNKNOWN\"}",key()).andExpect(status().isBadRequest());
        jdbc.update("update uav_event set state_code='PENDING_VERIFICATION' where event_id=?",eventId);
        act(sms(0),key()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?",Long.class,eventId)).isZero();
    }
    @Test void historicalObservationRemainsReadableButCannotEnableCounter() throws Exception {
        jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,outcome,danger,note,urgent,simulated) values(?,?,0,'OBSERVATION',0,?,'STILL_INSIDE','HIGH','历史记录',true,false)",key(),eventId,userId);
        read().andExpect(jsonPath("$.data.records[0].kind").value("OBSERVATION")).andExpect(jsonPath("$.data.can_request_counter").value(false));
        counter().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ADVISORY_COUNTER_BLOCKED"));
    }
    @Test void notificationAloneDoesNotEnableCounter() throws Exception {
        act(sms(0),key()).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_request_counter").value(false));
        counter().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ADVISORY_COUNTER_BLOCKED"));
    }
    @Test void allManualObservationOutcomesAreRejected() throws Exception {
        for(String outcome:List.of("DEPARTED","UNKNOWN","STILL_INSIDE"))
            act(observe(0,outcome,"HIGH",false),key()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MANUAL_OBSERVATION_RETIRED"));
    }
    @Test void contactNumbersAreRedactedBeforePersistence() throws Exception {
        act(sms(0).replace("演示飞手","联系人 +8613800138000"),key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].recipient_name").value("联系人 [手机号已隐藏]"));
        assertThat(jdbc.queryForObject("select recipient_name from uav_event_advisory where event_id=?",String.class,eventId)).doesNotContain("13800138000");
    }
    @Test void disabledObservationCannotReplayHistoricalSuccess() throws Exception {
        String historicalKey=key();
        jdbc.update("insert into uav_event_advisory_request(actor_id,request_key,request_hash,event_id,response_text) values(?,?,?,?,?)",
                userId,historicalKey,"historical",eventId,"{}");
        act(observe(0,"STILL_INSIDE","HIGH",true),historicalKey).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MANUAL_OBSERVATION_RETIRED"));
        read().andExpect(jsonPath("$.data.event_version").value(0));
    }
    @Test void missingSystemEvidenceNeverOffersDirectCounter() throws Exception {
        read().andExpect(jsonPath("$.data.can_direct_counter").value(false))
            .andExpect(jsonPath("$.data.counter_block_reason").isNotEmpty());
    }
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired com.uav.lowaltitude.modules.disposal.application.DisposalCommandGuard commandGuard;
    private boolean dispatchAllowed(String id) {return Boolean.TRUE.equals(transaction.execute(s->commandGuard.mayStart(id)));}
    @Test void concurrentSameKeyCommitsOneRecord() throws Exception {
        String key=key();var barrier=new java.util.concurrent.CyclicBarrier(2);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> call=()->{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return act(sms(0),key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();};
            var a=pool.submit(call);var b=pool.submit(call);assertThat(json.readTree(a.get(30,java.util.concurrent.TimeUnit.SECONDS))).isEqualTo(json.readTree(b.get(30,java.util.concurrent.TimeUnit.SECONDS)));
        } finally {pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=?",Integer.class,eventId)).isEqualTo(1);
    }
    protected ResultActions read() throws Exception {return mvc.perform(get("/api/v1/uav-events/"+eventId+"/advisory").header("Authorization",bearer()));}
    protected ResultActions act(String body,String key) throws Exception {return mvc.perform(post("/api/v1/uav-events/"+eventId+"/advisory/actions").header("Authorization",bearer()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
    private ResultActions counter() throws Exception {return mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization",bearer()).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""+eventId+"\",\"action_type\":\"COUNTERMEASURE\",\"channel\":\"MANUAL\",\"reason\":\"高危险现场核查申请\"}"));}
    protected String sms(long version) {return "{\"expected_version\":"+version+",\"kind\":\"SMS_SIMULATED\",\"recipient_name\":\"演示飞手\",\"contact_basis\":\"模拟接收端\",\"content\":\"请立即飞离当前管制范围\"}";}
    protected String observe(long version,String outcome,String danger,boolean urgent) {return "{\"expected_version\":"+version+",\"kind\":\"OBSERVATION\",\"outcome\":\""+outcome+"\",\"danger\":\""+danger+"\",\"urgent\":"+urgent+",\"note\":\"现场人员核实无人机位置与持续运动方向，结合受保护区域情况记录当前危险依据\"}";}
    private String bearer(){return "Bearer "+session;}
    protected String key(){return UUID.randomUUID().toString();}
    private JsonNode data(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");}
}
