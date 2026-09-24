package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="app.handoff.channel=mock") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class LocalInterfaceSimulatorApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Autowired com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort channel;
 String token;
 static final String BASE="/api/v1/local-interface-simulator";
 @BeforeEach void login() throws Exception { token="Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText(); }
 @Test void planInputPersistsAndReplaysButConflictingMessageCannotOverwrite() throws Exception {
  var body=plan("input-plan-one");
  var first=send("/plans",body,200);String id=first.path("subject_id").asText();
  assertThat(id).isNotBlank();assertThat(first.path("result").path("source_mode").asText()).isEqualTo("mock");
  assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
  body.put("uav_sn","CHANGED");send("/plans",body,409);
  assertThat(jdbc.queryForObject("select count(*) from flight_plan where plan_id=?",Long.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select status_code from flight_plan where plan_id=?",String.class,id)).isEqualTo("PENDING");
 }
 @Test void pastWeatherPeriodsRemainReadableWithoutRewritingPublicationTime() throws Exception {
  var plan=send("/plans",plan("weather-plan"),200);String id=plan.path("subject_id").asText();
  long published=System.currentTimeMillis()-2*86400000L;var period=Map.of("from",published,"to",published+3600000,"summary","模拟小雨","temperature_c",22,"wind_speed_ms",4,"gust_ms",6,"wind_direction_deg",180,"precipitation_probability_pct",80,"humidity_pct",75);
  var input=Map.of("message_id","weather-one","plan_id",id,"area_name","模拟区域","published_at",published,"periods",List.of(period));
  var received=send("/weather",input,200);
  assertThat(received.path("result").path("status").asText()).isEqualTo("READY");
  assertThat(send("/weather",input,200).path("message_id")).isEqualTo(received.path("message_id"));
  var first=mvc.perform(get("/api/v1/flight-plans/"+id+"/weather-forecast").header("Authorization",token))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"))
   .andExpect(jsonPath("$.data.message").doesNotExist()).andExpect(jsonPath("$.data.forecast.source_mode").value("mock"))
   .andExpect(jsonPath("$.data.forecast.published_at").value(published))
   .andExpect(jsonPath("$.data.forecast.periods[0].from").value(published))
   .andExpect(jsonPath("$.data.forecast.periods[0].to").value(published+3600000))
   .andExpect(jsonPath("$.data.forecast.periods[0].summary").value("模拟小雨")).andReturn().getResponse().getContentAsString();
  var second=mvc.perform(get("/api/v1/flight-plans/"+id+"/weather-forecast").header("Authorization",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(json.readTree(second).path("data")).isEqualTo(json.readTree(first).path("data"));
 }
 @Test void rejectsInvalidInputAndUnauthenticatedAccess() throws Exception {
  mvc.perform(get(BASE+"/context")).andExpect(status().isUnauthorized());
  var body=plan("invalid");body.put("end_at",body.get("start_at"));send("/plans",body,400);
  send("/bindings",Map.of("source_kind","UAV_EVENT","source_id","missing","enabled",true),404);
 }
 @Test void realDispatchRequiresDeliveryBeforeAckAndCannotRegress() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String hid=(String)h.get("handoff_id"), sid=(String)h.get("source_id");
  send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  var at=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
  var out=channel.deliver(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch(hid,"RISK",sid,"RISK_NOTICE",(String)h.get("recipient_id"),"上级","{}",at));
  assertThat(out.deliveryStatus()).isEqualTo("SUBMITTED");
  jdbc.update("update handoff_delivery set delivery_status='SUBMITTED',receipt_status='PENDING',blocked_reason=?,submitted_at=?,delivered_at=null,acknowledged_at=null where handoff_id=?",out.blockedReason(),at,hid);
  String mid=jdbc.queryForObject("select message_id from local_interface_message where subject_id=? and direction='OUT'",String.class,hid);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",0,"outcome","ACKNOWLEDGED"),409);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",0,"outcome","DELIVERED"),200);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",1,"outcome","ACKNOWLEDGED"),200);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",1,"outcome","ACKNOWLEDGED"),200);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",2,"outcome","FAILED"),409);
  assertThat(jdbc.queryForObject("select receipt_status from handoff_delivery where handoff_id=? order by attempt_no desc fetch first 1 rows only",String.class,hid)).isEqualTo("ACKNOWLEDGED");
 }
 @Test void expiredExplicitReceiverDoesNotFallBackToAutomaticMockSuccess() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String sid=(String)h.get("source_id");send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  jdbc.update("update local_interface_binding set expires_at=1 where source_kind='RISK' and source_id=?",sid);
  var out=channel.deliver(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch((String)h.get("handoff_id"),"RISK",sid,"RISK_NOTICE",(String)h.get("recipient_id"),"上级","{}",java.time.OffsetDateTime.now()));
  assertThat(out.deliveryStatus()).isEqualTo("PENDING_DELIVERY");assertThat(out.deliveredAt()).isNull();
 }
 @Test void interfaceReadOnlyCannotPushPlans() throws Exception {
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-EXT-READ','接口只读','',FALSE,TRUE,0,0,0,FALSE)");
  jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-EXT-READ','interfaces','READ',TRUE)");
  jdbc.update("UPDATE app_user SET role_code='ROLE-EXT-READ' WHERE account='admin1'");
  send("/plans",plan("no-permission"),403);
 }
 @Test void contextDoesNotRequireUnrelatedBusinessPermissions() throws Exception {
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-EXT-ONLY','接口操作','',FALSE,TRUE,0,0,0,FALSE)");
  jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-EXT-ONLY','interfaces','OP',TRUE)");
  jdbc.update("UPDATE app_user SET role_code='ROLE-EXT-ONLY' WHERE account='admin1'");
  mvc.perform(get(BASE+"/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.routes").isEmpty()).andExpect(jsonPath("$.data.unavailable_sections").isNotEmpty());
 }
 @Test void expiredBindingCanBeTakenOverByAnotherAuthorizedOperator() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String sid=(String)h.get("source_id");send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  String other=jdbc.queryForObject("select user_id from app_user where account<>'admin1' fetch first 1 rows only",String.class);
  jdbc.update("update local_interface_binding set created_by=?,expires_at=1 where source_kind='RISK' and source_id=?",other,sid);
  send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
 }
 @Test void interruptedDeliveryAssociationIsUnknownAndCannotAcceptReceipt() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String hid=(String)h.get("handoff_id"),sid=(String)h.get("source_id");send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  channel.deliver(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch(hid,"RISK",sid,"RISK_NOTICE",(String)h.get("recipient_id"),"上级","{}",java.time.OffsetDateTime.now()));
  String mid=jdbc.queryForObject("select message_id from local_interface_message where subject_id=? and direction='OUT'",String.class,hid);
  mvc.perform(get(BASE+"/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.messages[?(@.message_id=='"+mid+"')].state").value("UNKNOWN"));
  send("/messages/"+mid+"/receipt",Map.of("expected_version",0,"outcome","DELIVERED"),409);
 }
 Map<String,Object> plan(String message) {
  String rv=jdbc.queryForObject("select v.route_version_id from route_version v join route r on r.route_id=v.route_id where r.source_mode='mock' and r.enabled=true and r.owner_org_id is not null fetch first 1 rows only",String.class);
  var body=new HashMap<String,Object>();body.put("message_id",message);body.put("route_version_id",rv);body.put("uav_sn","SIM-INPUT-001");body.put("start_at",System.currentTimeMillis()+300000);body.put("end_at",System.currentTimeMillis()+3900000);return body;
 }
 JsonNode send(String path,Object body,int expected) throws Exception {
  var result=mvc.perform(post(BASE+path).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn();
  return json.readTree(result.getResponse().getContentAsString()).path("data");
 }
}
