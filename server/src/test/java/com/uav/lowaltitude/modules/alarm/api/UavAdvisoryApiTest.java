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
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UavAdvisoryApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
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
    @Test void manualActionsRouteIsAbsentAndWritesNothing() throws Exception {
        postActions("{\"expected_version\":0,\"kind\":\"OBSERVATION\",\"outcome\":\"STILL_INSIDE\",\"danger\":\"HIGH\",\"urgent\":true,\"note\":\"历史补记不再接受\"}")
                .andExpect(status().isNotFound());
        postActions("{\"expected_version\":0,\"kind\":\"SMS_SIMULATED\",\"recipient_name\":\"演示飞手\",\"contact_basis\":\"模拟接收端\",\"content\":\"请立即飞离\"}")
                .andExpect(status().isNotFound());
        read().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event_version").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=?", Integer.class, eventId)).isZero();
    }
    @Test void readingOutsideScopeIsNotFound() throws Exception {
        jdbc.update("update app_user_data_scope set org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where user_id=?", userId);
        read().andExpect(status().isNotFound());
    }
    @Test void sameReadPermissionWithoutRequestPermissionCannotOfferCounter() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='disposal:request'", role);
        read().andExpect(jsonPath("$.data.can_request_counter").value(false));
    }
    @Test void liveSmsModeStaysUnavailable() throws Exception {
        jdbc.update("update alarm set source_mode='live' where alarm_id=(select alarm_id from uav_event where event_id=?)", eventId);
        read().andExpect(status().isOk()).andExpect(jsonPath("$.data.sms_mode").value("UNAVAILABLE"));
    }
    @Test void historicalObservationRemainsReadableButCannotEnableCounter() throws Exception {
        jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,outcome,danger,note,urgent,simulated) values(?,?,0,'OBSERVATION',0,?,'STILL_INSIDE','HIGH','历史记录',true,false)", key(), eventId, userId);
        read().andExpect(jsonPath("$.data.records[0].kind").value("OBSERVATION")).andExpect(jsonPath("$.data.can_request_counter").value(false));
        counter().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ADVISORY_COUNTER_BLOCKED"));
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, eventId)).isZero();
    }
    @Test void recordedSmsDoesNotEnableCounter() throws Exception {
        jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated,delivery_status) values(?,?,0,'SMS_SIMULATED',0,?,'演示飞手','模拟接收端','请立即飞离',false,true,'SIMULATED_DELIVERED')", key(), eventId, userId);
        read().andExpect(jsonPath("$.data.records[0].kind").value("SMS_SIMULATED"))
                .andExpect(jsonPath("$.data.can_request_counter").value(false));
        counter().andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ADVISORY_COUNTER_BLOCKED"));
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, eventId)).isZero();
    }
    @Test void missingSystemEvidenceNeverOffersDirectCounter() throws Exception {
        read().andExpect(jsonPath("$.data.can_direct_counter").value(false))
            .andExpect(jsonPath("$.data.counter_block_reason").isNotEmpty());
    }
    protected ResultActions read() throws Exception {return mvc.perform(get("/api/v1/uav-events/"+eventId+"/advisory").header("Authorization",bearer()));}
    private ResultActions postActions(String body) throws Exception {return mvc.perform(post("/api/v1/uav-events/"+eventId+"/advisory/actions").header("Authorization",bearer()).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body));}
    private ResultActions counter() throws Exception {return mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization",bearer()).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""+eventId+"\",\"action_type\":\"COUNTERMEASURE\",\"channel\":\"COUNTERMEASURE_4CH\",\"device_id\":\"unbound-advisory-device\",\"reason\":\"高危险现场核查申请\"}"));}
    private String bearer(){return "Bearer "+session;}
    private String key(){return UUID.randomUUID().toString();}
}
