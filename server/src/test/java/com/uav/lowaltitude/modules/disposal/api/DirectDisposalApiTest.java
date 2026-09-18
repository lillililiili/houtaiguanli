package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** All fixtures are isolated mock events. No external transport is enabled. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:direct_disposal;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DirectDisposalApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.uav.lowaltitude.modules.identity.application.AccessService access;
    private static final String DIRECT="disposal:direct", BASE="/api/v1/disposal-authorizations";
    private static final String ORG="seed-stage3-org", DISTRICT="seed-stage3-district";

    @Test void permissionIsCataloguedButNotGrantedEvenToAdmin() {
        assertThat(jdbc.queryForList("select name from app_permission where permission_code=?", String.class,DIRECT))
                .containsExactly("直接反制（免逐次审批）");
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission where permission_code=? and role_code='ROLE-ADMIN' and permission_level<>'NONE'",Integer.class,DIRECT)).isZero();
        assertThat(access.permissionCodes("ROLE-ADMIN")).doesNotContain(DIRECT);
    }
    @Test void existingApproverCannotUseDirectEndpoint() throws Exception {
        Actor actor=actor(List.of("disposal:request","disposal:approve","disposal:execute"));
        postDirect(actor,"{}",key()).andExpect(status().isForbidden());
    }
    @Test void directActorStartsManualExecutionWithoutInventingApproval() throws Exception {
        Actor actor=directActor();String event=event(actor,"CONFIRMED");
        JsonNode result=data(postDirect(actor,body(event,"COUNTERMEASURE"),key()).andExpect(status().isCreated()));
        assertThat(result.path("status").asText()).isEqualTo("EXECUTING");
        String id=result.path("authorization_id").asText();
        JsonNode detail=data(mvc.perform(get(BASE+"/"+id).header("Authorization",actor.bearer())).andExpect(status().isOk()));
        assertThat(detail.path("authorization_mode").asText()).isEqualTo("DIRECT");
        assertThat(detail.path("requested_by").asText()).isEqualTo(actor.id());
        assertThat(detail.hasNonNull("approved_by")).isFalse();
        assertThat(detail.path("valid_until").asLong()).isGreaterThan(detail.path("valid_from").asLong());
        assertThat(jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?",String.class,id))
                .contains("DIRECT_AUTHORIZE","EXECUTE").doesNotContain("APPROVE");
    }
    @Test void directDoesNotAllowSelfApprovalOfAnOrdinaryRequest() throws Exception {
        Actor actor=actor(List.of(DIRECT,"disposal:request","disposal:approve","disposal:execute"));String event=event(actor,"CONFIRMED");
        JsonNode created=data(post(actor,BASE,body(event,"COUNTERMEASURE"),key()).andExpect(status().isCreated()));
        assertThat(created.path("status").asText()).isEqualTo("REQUESTED");
        post(actor,BASE+"/"+created.path("authorization_id").asText()+"/approve","{\"expected_version\":0}",key())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TWO_PERSON_RULE"));
    }
    @Test void directCannotBypassEventConditionsOrUnknownFields() throws Exception {
        Actor actor=directActor();String event=event(actor,"PENDING_VERIFICATION");
        postDirect(actor,body(event,"COUNTERMEASURE"),key()).andExpect(status().isConflict());
        postDirect(actor,body(event,"COUNTERMEASURE").replace("\"reason\":","\"approved_by\":\"fake\",\"reason\":"),key())
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,event)).isZero();
    }
    @Test void directRepeatedSubmissionCannotDispatchTwice() throws Exception {
        Actor actor=directActor();String event=event(actor,"CONFIRMED"), key=key();
        postDirect(actor,body(event,"COUNTERMEASURE"),key).andExpect(status().isCreated());
        postDirect(actor,body(event,"COUNTERMEASURE"),key).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization_event e join disposal_authorization a on a.authorization_id=e.authorization_id where a.subject_id=? and e.event_kind='EXECUTE'",Integer.class,event)).isEqualTo(1);
    }
    @Test void revokedDirectPrivilegeBlocksFurtherCommands() throws Exception {
        Actor actor=directActor();String event=event(actor,"CONFIRMED");
        JsonNode result=data(postDirect(actor,body(event,"COUNTERMEASURE"),key()).andExpect(status().isCreated()));
        jdbc.update("update app_role_permission set permission_level='NONE' where role_code=? and permission_code=?",actor.role(),DIRECT);
        post(actor,BASE+"/"+result.path("authorization_id").asText()+"/manual-result",
                "{\"expected_version\":"+result.path("version").asLong()+",\"result\":\"FAILED\",\"detail\":\"模拟执行未成功\"}",key()).andExpect(status().isForbidden());
    }
    @Test void readLevelCannotActivateDirectPrivilege() throws Exception {
        Actor actor=directActor();jdbc.update("update app_role_permission set permission_level='READ' where role_code=? and permission_code=?",actor.role(),DIRECT);
        postDirect(actor,"{}",key()).andExpect(status().isForbidden());
        assertThat(access.permissionCodes(actor.role())).doesNotContain(DIRECT);
    }
    @Test void outOfScopeEventCannotBeDirectlyExecuted() throws Exception {
        Actor actor=directActor();String event=event(actor,"CONFIRMED");
        jdbc.update("update uav_event set owner_org_id='seed-stage3-other-org',district_id='seed-stage3-other-district' where event_id=?",event);
        postDirect(actor,body(event,"COUNTERMEASURE"),key()).andExpect(status().isNotFound());
    }
    @Test void advisoryExposesDirectAndNormalCapabilitySeparately() throws Exception {
        Actor actor=directActor();String event=event(actor,"CONFIRMED");
        mvc.perform(get("/api/v1/uav-events/"+event+"/advisory").header("Authorization",actor.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_direct_counter").value(true))
                .andExpect(jsonPath("$.data.can_request_counter").value(false));
    }
    @Test void directPermissionDoesNotReplaceDeviceControlPermission() throws Exception {
        Actor actor=directActor();String event=event(actor,"CONFIRMED");
        postDirect(actor,body(event,"COUNTERMEASURE").replace("\"channel\":\"MANUAL\"", "\"channel\":\"LINGYUN_B\",\"device_id\":\"unbound-test-device\""),key())
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,event)).isZero();
    }
    @Test void anotherDirectActorCannotCompleteTheOriginalActorsExecution() throws Exception {
        Actor actor=directActor(), other=directActor();String event=event(actor,"CONFIRMED");
        JsonNode result=data(postDirect(actor,body(event,"COUNTERMEASURE"),key()).andExpect(status().isCreated()));
        post(other,BASE+"/"+result.path("authorization_id").asText()+"/manual-result",
                "{\"expected_version\":"+result.path("version").asLong()+",\"result\":\"FAILED\",\"detail\":\"其他操作人\"}",key()).andExpect(status().isForbidden());
    }
    @Test void deviceBlockPersistsDirectAuthorizationAndDoesNotClaimExecution() throws Exception {
        Actor actor=directActor();String event=event(actor,"CONFIRMED");
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'devices','OP',false,current_timestamp)",actor.role());
        JsonNode result=data(postDirect(actor,body(event,"COUNTERMEASURE").replace("\"channel\":\"MANUAL\"", "\"channel\":\"LINGYUN_B\",\"device_id\":\"unbound-test-device\""),key()).andExpect(status().isCreated()));
        assertThat(result.path("status").asText()).isEqualTo("APPROVED");
        assertThat(result.path("execution_block_reason").asText()).isNotBlank();
        String id=result.path("authorization_id").asText();
        assertThat(jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?",String.class,id)).contains("DIRECT_AUTHORIZE").doesNotContain("EXECUTE","APPROVE");
        assertThat(jdbc.queryForObject("select execution_command_id from disposal_authorization where authorization_id=?",String.class,id)).isNull();
    }
    private Actor directActor(){return actor(List.of(DIRECT));}
    private Actor actor(List<String> permissions) {
        if(permissions.contains(DIRECT)) assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code=?",Integer.class,DIRECT)).as("new direct permission catalog row").isEqualTo(1);
        String id=UUID.randomUUID().toString(),role="DCT-"+UUID.randomUUID().toString().substring(0,8), token=UUID.randomUUID().toString();
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)",role,role);
        for(String p:java.util.stream.Stream.concat(permissions.stream(),List.of("disposal:read","alarm:read","target:read").stream()).distinct().toList())
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,?,false,current_timestamp)",role,p,p.endsWith(":read")?"READ":"OP");
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",id,role,"直接权限验证员",role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values (?,?,?)",id,ORG,DISTRICT);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",token,id,System.currentTimeMillis()+3600000);
        return new Actor(id,role,token);
    }
    private String event(Actor actor,String state) {
        String id=UUID.randomUUID().toString(),alarm=UUID.randomUUID().toString();Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("insert into alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) select ?,source_id,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,? from integration_source limit 1",alarm,id,now,now,ORG,DISTRICT,now);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,?,?,0)",id,alarm,state,ORG,DISTRICT,now,now);
        if("CONFIRMED".equals(state)) jdbc.update("insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,outcome,danger,note,urgent,simulated) values (?,?,0,'OBSERVATION',?,?,'STILL_INSIDE','HIGH','隔离测试：目标持续逼近受保护区域，当前证据充分，验证直接操作权限与审计',true,false)",UUID.randomUUID().toString(),id,System.currentTimeMillis(),actor.id());
        return id;
    }
    private String body(String event,String action){return "{\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""+event+"\",\"action_type\":\""+action+"\",\"channel\":\"MANUAL\",\"reason\":\"隔离模拟验证\"}";}
    private ResultActions postDirect(Actor a,String body,String key)throws Exception{return post(a,BASE+"/direct-execute",body,key);}
    private ResultActions post(Actor a,String path,String body,String key)throws Exception{return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",a.bearer()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
    private JsonNode data(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString()).path("data");}
    private String key(){return UUID.randomUUID().toString();}
    private record Actor(String id,String role,String token){String bearer(){return "Bearer "+token;}}
}
