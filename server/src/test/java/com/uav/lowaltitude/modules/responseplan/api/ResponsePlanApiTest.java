package com.uav.lowaltitude.modules.responseplan.api;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ResponsePlanApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    String session; String airspace="seed-stage3-airspace-prohibited";
    @BeforeEach void login()throws Exception{session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        airspace=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO airspace(airspace_id,airspace_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) SELECT ?,?,'预案隔离测试空域',source_id,source_mode,owner_org_id,district_id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0 FROM airspace WHERE airspace_id='seed-stage3-airspace-prohibited'",airspace,airspace);
        jdbc.update("INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,valid_from,created_at) VALUES(?,?,1,'PROHIBITED',?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),airspace,new java.sql.Timestamp(System.currentTimeMillis()-60000));
    }
    Map<String,Object> input(){var b=new HashMap<String,Object>();b.put("airspace_id",airspace);b.put("name","隔离测试预案");b.put("trigger_basis","依据有效研判和最新观测");b.put("action_steps","查看飞手短信及电话录音通知渠道的独立回执");b.put("manual_conditions","依据不足时人工核查；反制需有效授权");b.put("failure_handling","失败和超时保留未知状态并人工处理");b.put("source_mode","mock");b.put("valid_from",System.currentTimeMillis()-60000);b.put("valid_to",System.currentTimeMillis()+3600000);return b;}
    JsonNode create()throws Exception{return ok(write(post("/api/v1/response-plans"),input()));}
    JsonNode publish(JsonNode v)throws Exception{return ok(write(post("/api/v1/response-plan-versions/"+v.path("version_id").asText()+"/publish"),Map.of("expected_version",v.path("version").asLong(),"reason","隔离测试发布")));}
    JsonNode bind(JsonNode v,String previous)throws Exception{var b=new HashMap<String,Object>();b.put("version_id",v.path("version_id").asText());b.put("reason","隔离测试关联");if(previous!=null)b.put("expected_binding_id",previous);return ok(write(put("/api/v1/response-plans/airspaces/"+airspace),b));}
    @Test void draftPublishBindingHistoryAndAudit()throws Exception{
        var draft=create();String id=draft.path("version_id").asText();
        mvc.perform(write(put("/api/v1/response-plans/airspaces/"+airspace),Map.of("version_id",id,"reason","草稿不能使用"))).andExpect(status().isConflict());
        var published=publish(draft);var linked=bind(published,null);
        assertThat(linked.path("current").path("plan").path("version_id").asText()).isEqualTo(id);
        var edit=input();edit.put("expected_version",1);
        mvc.perform(write(patch("/api/v1/response-plan-versions/"+id),edit)).andExpect(status().isConflict());
        var b=new HashMap<String,Object>();b.put("expected_binding_id",linked.path("current").path("binding_id").asText());b.put("reason","结束本次关联");
        var unbound=ok(write(put("/api/v1/response-plans/airspaces/"+airspace),b));
        assertThat(unbound.path("current").isMissingNode()||unbound.path("current").isNull()).isTrue();
        assertThat(unbound.path("history").path("items").get(0).path("plan").path("version_id").asText()).isEqualTo(id);
        assertThat(unbound.path("history").path("items").get(0).path("end_reason").asText()).isEqualTo("结束本次关联");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE module_code='responsePlans'",Long.class)).isGreaterThanOrEqualTo(4);
    }
    @Test void newPublicationNeverSilentlyChangesBindingAndStaleBindFails()throws Exception{
        var first=publish(create());var current=bind(first,null);var second=ok(write(post("/api/v1/response-plans/"+first.path("plan_id").asText()+"/versions"),input()));publish(second);
        var read=ok(auth(get("/api/v1/airspaces/"+airspace+"/response-plan")));
        assertThat(read.path("current").path("plan").path("revision").asInt()).isEqualTo(1);
        mvc.perform(write(put("/api/v1/response-plans/airspaces/"+airspace),Map.of("version_id",second.path("version_id").asText(),"reason","使用旧关联状态"))).andExpect(status().isConflict());
        var replaced=bind(second,current.path("current").path("binding_id").asText());assertThat(replaced.path("history").path("total").asLong()).isEqualTo(1);
    }
    @Test void futureWithdrawnAndExpiredRemainDistinct()throws Exception{
        var b=input();long future=System.currentTimeMillis()+600000;b.put("valid_from",future);var v=publish(ok(write(post("/api/v1/response-plans"),b)));
        assertThat(bind(v,null).path("current").path("applicability").asText()).isEqualTo("SCHEDULED");
        String id=v.path("version_id").asText();ok(write(post("/api/v1/response-plan-versions/"+id+"/withdraw"),Map.of("expected_version",1,"reason","停止使用旧预案")));
        assertThat(ok(auth(get("/api/v1/airspaces/"+airspace+"/response-plan"))).path("current").path("applicability").asText()).isEqualTo("WITHDRAWN");
        b=input();b.put("valid_from",1);b.put("valid_to",2);var expired=ok(write(post("/api/v1/response-plans"),b));
        mvc.perform(write(post("/api/v1/response-plan-versions/"+expired.path("version_id").asText()+"/publish"),Map.of("expected_version",0,"reason","过期不能发布"))).andExpect(status().isConflict());
    }
    @Test void repeatCreateDoesNotDuplicateAndInvalidWindowWritesNothing()throws Exception{
        String key=UUID.randomUUID().toString();var body=input();ok(write(post("/api/v1/response-plans"),body,key));
        mvc.perform(write(post("/api/v1/response-plans"),body,key)).andExpect(status().isConflict());
        body.put("valid_to",body.get("valid_from"));mvc.perform(write(post("/api/v1/response-plans"),body)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM response_plan WHERE anchor_airspace_id=?",Long.class,airspace)).isEqualTo(1);
    }
    @Test void anonymousAndOutOfScopeAreRejected()throws Exception{
        mvc.perform(get("/api/v1/response-plans")).andExpect(status().isUnauthorized());
        var id=create().path("plan_id").asText();jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        mvc.perform(auth(get("/api/v1/response-plans/"+id+"/versions"))).andExpect(status().isForbidden());
    }
    @Test void applicableExpiresAtExclusiveEndAndReadPermissionCannotWrite()throws Exception{
        var v=publish(create());assertThat(bind(v,null).path("current").path("applicability").asText()).isEqualTo("APPLICABLE");
        jdbc.update("UPDATE response_plan_version SET valid_from=1,valid_to=2 WHERE version_id=?",v.path("version_id").asText());
        assertThat(ok(auth(get("/api/v1/airspaces/"+airspace+"/response-plan"))).path("current").path("applicability").asText()).isEqualTo("EXPIRED");
        limitedUser("ALL");
        mvc.perform(auth(get("/api/v1/response-plans"))).andExpect(status().isOk());
        mvc.perform(write(post("/api/v1/response-plans"),input())).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/airspaces/"+airspace+"/response-plan"))).andExpect(status().isForbidden());
    }
    @Test void assignedScopeDoesNotLeakOtherAirspaceOrVersion()throws Exception{
        var v=create();limitedUser("ASSIGNED");
        assertThat(ok(auth(get("/api/v1/response-plans"))).path("total").asLong()).isZero();
        mvc.perform(auth(get("/api/v1/response-plans/"+v.path("plan_id").asText()+"/versions"))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/response-plans/airspaces/"+airspace))).andExpect(status().isNotFound());
    }
    private void limitedUser(String scope){String uid=UUID.randomUUID().toString(),role="RP-"+uid.substring(0,8);session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'responsePlans','READ',TRUE)",role);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",uid,role,role,role,scope);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",session,uid,System.currentTimeMillis()+3600000);
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r){return r.header("Authorization","Bearer "+session);}
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder r,Object body)throws Exception{return write(r,body,UUID.randomUUID().toString());}
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder r,Object body,String key)throws Exception{return auth(r).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));}
    JsonNode ok(MockHttpServletRequestBuilder r)throws Exception{return json.readTree(mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
}
