package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.*;

@SpringBootTest(properties={"app.handoff.channel=none","app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class DirectoryApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @org.springframework.boot.test.mock.mockito.SpyBean com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService checks;
 String session,org;
 @BeforeEach void login() throws Exception {
  session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
  org=jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'",String.class);
 }
 @Test void businessContactIsIndependentFromLoginAndPreservesOrganizationState() throws Exception {
  long users=jdbc.queryForObject("select count(*) from app_user",Long.class);
  JsonNode c=contact("PILOT");
  assertThat(c.path("user_id").isMissingNode()||c.path("user_id").isNull()).isTrue();
  assertThat(jdbc.queryForObject("select count(*) from app_user",Long.class)).isEqualTo(users);
  mvc.perform(auth(get("/api/v1/contacts/"+c.path("contact_id").asText()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("PILOT"));
  mvc.perform(auth(get("/api/v1/organization-profiles/"+org))).andExpect(status().isOk()).andExpect(jsonPath("$.data.contact_count").value(1));
 }
 @Test void sourceMappingAndPlanSubjectsAreExplicitAndPilotRoleIsMandatory() throws Exception {
  String plan=jdbc.queryForObject("select plan_id from flight_plan where source_id is not null order by plan_id fetch first 1 row only",String.class);
  String source=jdbc.queryForObject("select source_id from flight_plan where plan_id=?",String.class,plan);
  JsonNode binding=ok(write(post("/api/v1/plan-source-bindings"),Map.of("source_id",source,"external_org_code","EXTERNAL-"+UUID.randomUUID(),"org_id",org,"enabled",true)));
  JsonNode liaison=contact("UNIT_LIAISON");
  long version=jdbc.queryForObject("select version from flight_plan where plan_id=?",Long.class,plan);
  mvc.perform(write(patch("/api/v1/flight-plans/"+plan+"/subjects"),Map.of("source_binding_id",binding.path("binding_id").asText(),"operator_org_id",org,"pilot_contact_id",liaison.path("contact_id").asText(),"expected_version",version,"reason","核对来源报备"))).andExpect(status().isConflict());
  JsonNode pilot=contact("PILOT");
  JsonNode subjects=ok(write(patch("/api/v1/flight-plans/"+plan+"/subjects"),Map.of("source_binding_id",binding.path("binding_id").asText(),"operator_org_id",org,"pilot_contact_id",pilot.path("contact_id").asText(),"expected_version",version,"reason","依据来源原报备核对")));
  assertThat(subjects.path("reporting_org_id").asText()).isEqualTo(org);
  assertThat(subjects.path("pilot_contact_hint").asText()).doesNotContain("13800138000");
  mvc.perform(write(patch("/api/v1/flight-plans/"+plan+"/subjects"),Map.of("operator_org_id",org,"expected_version",version,"reason","旧版本"))).andExpect(status().isConflict());
 }
 @Test void planFeedbackFreezesActualUnitContactAndOldSnapshotDoesNotFollowEdits() throws Exception {
  String plan=jdbc.queryForObject("select plan_id from flight_plan where source_id is not null order by plan_id fetch first 1 row only",String.class);
  String source=jdbc.queryForObject("select source_id from flight_plan where plan_id=?",String.class,plan);
  JsonNode binding=ok(write(post("/api/v1/plan-source-bindings"),Map.of("source_id",source,"external_org_code","FB-"+UUID.randomUUID(),"org_id",org,"enabled",true)));
  JsonNode c=contact("PLAN_LIAISON");String cid=c.path("contact_id").asText();
  long version=jdbc.queryForObject("select version from flight_plan where plan_id=?",Long.class,plan);
  ok(write(patch("/api/v1/flight-plans/"+plan+"/subjects"),Map.of("source_binding_id",binding.path("binding_id").asText(),"operator_org_id",org,"expected_version",version,"reason","核对报送单位")));
  String settingId=UUID.randomUUID().toString();
  long now=System.currentTimeMillis();
  jdbc.update("insert into notification_setting(setting_id,purpose,routing_key,recipient_org_id,contact_id,source_binding_id,channel_type,enabled,created_at,updated_at,version) values(?,?,?,?,?,?,'MOCK',true,?,?,0)",settingId,"PLAN_FEEDBACK","PLAN_FEEDBACK:"+binding.path("binding_id").asText(),org,cid,binding.path("binding_id").asText(),now,now);
  String verification=UUID.randomUUID().toString();String actor=jdbc.queryForObject("select user_id from app_session where session_id=?",String.class,session);
  jdbc.update("insert into flight_plan_verification(verification_id,plan_id,revision_no,conclusion,takeoff_status,evidence,note,handled_by,handled_by_name,handled_at) values(?,?,1,'CHECK_INCOMPLETE','UNKNOWN','隔离测试设备检查','起飞情况未知',?,'测试',1)",verification,plan,actor);
  JsonNode sent=ok(write(post("/api/v1/flight-plans/"+plan+"/verifications/feedback"),Map.of("verification_id",verification,"recipient_id",source)));
  assertThat(sent.path("recipient_snapshot").path("contact_id").asText()).isEqualTo(cid);
  assertThat(sent.path("recipient_snapshot").path("setting_id").asText()).isEqualTo(settingId);
  assertThat(sent.path("recipient_snapshot").path("contact_hint").asText()).isEqualTo("138****8000");
  ok(write(patch("/api/v1/contacts/"+cid),Map.of("org_id",org,"name","后续更名联系人","roles",List.of("PLAN_LIAISON"),"phone","13900139000","enabled",false,"expected_version",0)));
  mvc.perform(auth(get("/api/v1/flight-plans/"+plan+"/verifications"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.feedback[0].recipient_snapshot.contact_name").value("业务联系人"));
  mvc.perform(write(post("/api/v1/flight-plans/"+plan+"/verifications/feedback"),Map.of("verification_id",verification,"recipient_id",source))).andExpect(status().isConflict());
 }
 @Test void riskSubmissionUsesOnlySuperiorAndFreezesRecipientEvenIfLegacyDirectoryChanges() throws Exception {
  String risk=UUID.randomUUID().toString();java.sql.Timestamp at=new java.sql.Timestamp(System.currentTimeMillis());
  jdbc.update("insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal','ROUTE_DEVIATION','HIGH','PENDING_NOTIFICATION','ROUTE_DEVIATION','隔离测试',?,?,'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',?,?,1)",risk,risk,at,at,at,at);
  JsonNode result=json.readTree(mvc.perform(write(post("/api/v1/handoffs"),Map.of("source_kind","RISK","source_id",risk,"handoff_type","RISK_NOTICE","expected_version",1))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(result.path("recipient_id").asText()).isEqualTo("fixed-superior-recipient");
  jdbc.update("update handoff_recipient set display_name='后来误改的目录名' where recipient_id='fixed-superior-recipient'");
  mvc.perform(auth(get("/api/v1/handoffs/"+result.path("handoff_id").asText()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.recipient_name").value("上级")).andExpect(jsonPath("$.data.recipient_snapshot.recipient_name").value("上级"));
 }
 @Test void maintenanceTodoExistsIndependentlyOfNotificationDelivery() throws Exception {
  String plan="seed-stage3-plan-legal",device=jdbc.queryForObject("select device_id from ops_device where deleted_at is null order by device_id fetch first 1 row only",String.class);
  var row=new com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.DeviceRow(device,"隔离测试异常设备",true,java.math.BigDecimal.ONE,"OFFLINE","ERROR",1L,1L,true,true,List.of());
  var check=new com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.Check(plan,"AUTO_DEVICE_ABNORMAL","隔离测试",System.currentTimeMillis(),java.math.BigDecimal.TEN,true,0,List.of(row),false);
  doReturn(check).when(checks).read(plan);
  String settingId=UUID.randomUUID().toString();
  long now=System.currentTimeMillis();
  jdbc.update("insert into notification_setting(setting_id,purpose,routing_key,recipient_org_id,channel_type,enabled,created_at,updated_at,version) values(?,?,?,?,'MOCK',true,?,?,0)",settingId,"DEVICE_MAINTENANCE","DEVICE_MAINTENANCE:"+org,org,now,now);
  JsonNode task=ok(write(post("/api/v1/flight-plans/"+plan+"/device-maintenance-tasks"),Map.of("device_id",device,"notification_setting_id",settingId)));
  assertThat(task.path("status").asText()).isEqualTo("PENDING");
  assertThat(task.path("recipient_snapshot").path("setting_id").asText()).isEqualTo(settingId);
  assertThat(task.path("notification_delivery_status").asText()).isEqualTo("PENDING_DELIVERY");
  assertThat(task.path("handled_at").isMissingNode()).isTrue();
  mvc.perform(auth(get("/api/v1/flight-plans/"+plan+"/device-maintenance-tasks").param("device_id",device))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].recipient_snapshot.setting_id").value(settingId));
 }
 @Test void changingPhoneClearsPreviousVerificationEvenIfClientReusesIt() throws Exception {
  JsonNode c=contact("PILOT");
  JsonNode changed=ok(write(patch("/api/v1/contacts/"+c.path("contact_id").asText()),Map.of("org_id",org,"name","业务联系人","roles",List.of("PILOT"),"phone","13900139000","enabled",true,"verified_at",c.path("verified_at").asLong(),"verification_basis","已核对来源报备","expected_version",0)));
  assertThat(changed.path("verified_at").isMissingNode()).isTrue();
  assertThat(changed.path("verification_basis").isMissingNode()).isTrue();
 }
 @Test void anonymousCannotReadDirectory() throws Exception {
  mvc.perform(get("/api/v1/organization-profiles")).andExpect(status().isUnauthorized());
 }
 JsonNode contact(String role) throws Exception {return ok(write(post("/api/v1/contacts"),Map.of("org_id",org,"name","业务联系人","roles",List.of(role),"phone","13800138000","enabled",true,"verified_at",System.currentTimeMillis()-1000,"verification_basis","已核对来源报备")));}
 MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r){return r.header("Authorization","Bearer "+session);}
 MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder r,Object body)throws Exception{return auth(r).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));}
 JsonNode ok(MockHttpServletRequestBuilder r)throws Exception{return json.readTree(mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
}
