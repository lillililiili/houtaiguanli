package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"app.notifications.transport=simulator","app.handoff.channel=simulator"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class RealtimeSimulatorApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json;
 @Autowired com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport transport;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
 private String login() throws Exception {
  return "Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
 }
 private void connect(String token) throws Exception {
  mvc.perform(post("/api/v1/local-interface-simulator/bindings").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"source_kind\":\"NOTIFICATION_CHANNEL\",\"source_id\":\"receiver\",\"enabled\":true}")).andExpect(status().isOk());
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"DISPERSED","NOT_DISPERSED"})
 void explicitRiskProcessingResultSurvivesReadbackWithoutChangingFrozenMaterial(String processing) throws Exception {
  String token=login();connect(token);
  String risk=java.util.UUID.randomUUID().toString();
  var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
  jdbc.update("INSERT INTO flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal','ROUTE_DEVIATION','HIGH','PENDING_NOTIFICATION','ROUTE_DEVIATION','isolated simulated risk',?,?,'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',?,?,1)",risk,risk,now,now,now,now);
  jdbc.update("UPDATE notification_setting SET enabled=TRUE,channel_type='API',endpoint_ref='local-data-simulator' WHERE setting_id='risk-superior'");
  var created=json.readTree(mvc.perform(post("/api/v1/handoffs").header("Authorization",token).header("Idempotency-Key",risk)
   .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("source_kind","RISK","source_id",risk,"handoff_type","RISK_NOTICE","expected_version",1))))
   .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
  String handoff=created.path("handoff_id").asText();
  String frozen=jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?",String.class,handoff);
  String message=jdbc.queryForObject("SELECT message_id FROM simulator_notification_message WHERE subject_id=?",String.class,handoff);
  String url="/api/v1/local-interface-simulator/messages/"+message+"/receipt";
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"expected_version\":0,\"outcome\":\"DELIVERED\",\"receipt_result\":\"DISPERSED\"}")).andExpect(status().isConflict());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"expected_version\":0,\"outcome\":\"DELIVERED\"}")).andExpect(status().isOk());
  String ack=json.writeValueAsString(java.util.Map.of("expected_version",1,"outcome","ACKNOWLEDGED","receipt_result",processing));
  for(int repeat=0;repeat<2;repeat++)mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(ack))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.result.receipt_result").value(processing));
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content(json.writeValueAsString(java.util.Map.of("expected_version",2,"outcome","ACKNOWLEDGED","receipt_result",processing.equals("DISPERSED")?"NOT_DISPERSED":"DISPERSED"))))
   .andExpect(status().isConflict());
  mvc.perform(get("/api/v1/handoffs/"+handoff).header("Authorization",token)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.receipt_status").value("ACKNOWLEDGED")).andExpect(jsonPath("$.data.receipt_result").value(processing));
  assertThat(jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?",String.class,handoff)).isEqualTo(frozen);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?",Long.class,handoff)).isEqualTo(1);
 }
 @Test void processingResultCannotBeInventedForPunishmentOrUnknownCode() throws Exception {
  String token=login();connect(token);
  var request=transport.submit("UAV_PUNISHMENT","missing-event",java.util.UUID.randomUUID().toString(),json.createObjectNode());
  String url="/api/v1/local-interface-simulator/messages/"+request.messageId()+"/receipt";
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"expected_version\":0,\"outcome\":\"ACKNOWLEDGED\",\"receipt_result\":\"DISPERSED\"}")).andExpect(status().isConflict());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"expected_version\":0,\"outcome\":\"ACKNOWLEDGED\",\"receipt_result\":\"COMPLETED\"}")).andExpect(status().isBadRequest());
 }
 @Test void submissionNeverBecomesSuccessWithoutOrderedAuthenticatedReceipt() throws Exception {
  String token=login();connect(token);
  var payload=json.createObjectNode().put("handoff_id","missing-test-handoff");
  var request=transport.submit("RISK_NOTICE","missing-test-handoff","transport-test-one",payload);
  assertThat(request.state()).isEqualTo("SUBMITTED");assertThat(request.result().has("delivered_at")).isFalse();
  assertThat(transport.submit("RISK_NOTICE","missing-test-handoff","transport-test-one",payload).messageId()).isEqualTo(request.messageId());
  String url="/api/v1/local-interface-simulator/messages/"+request.messageId()+"/receipt";
  mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"outcome\":\"DELIVERED\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"outcome\":\"ACKNOWLEDGED\"}")).andExpect(status().isConflict());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"outcome\":\"DELIVERED\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("DELIVERED")).andExpect(jsonPath("$.data.result.projection_status").value("IGNORED_STALE_ATTEMPT"));
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"outcome\":\"ACKNOWLEDGED\"}")).andExpect(status().isConflict());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":1,\"outcome\":\"ACKNOWLEDGED\"}")).andExpect(status().isOk());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":2,\"outcome\":\"FAILED\"}")).andExpect(status().isConflict());
 }
 @Test void expiredReceiverCannotAcceptNewRequestsOrFallBackToMock() throws Exception {
  String token=login();connect(token);assertThat(transport.online()).isTrue();
  jdbc.update("UPDATE simulator_notification_receiver SET expires_at=0");
  assertThat(transport.online()).isFalse();
  org.assertj.core.api.Assertions.assertThatThrownBy(()->transport.submit("RISK_NOTICE","x","offline-test",json.createObjectNode())).isInstanceOf(com.uav.lowaltitude.platform.api.ApiException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulator_notification_message WHERE attempt_key='offline-test'",Long.class)).isZero();
 }
 @Test void noReceiptBacklogCannotHideTheNextNotification() throws Exception {
  String token=login();connect(token);
  for(int i=0;i<101;i++)transport.submit("RISK_NOTICE","queue-"+i,"queue-"+i,json.createObjectNode().put("handoff_id","queue-"+i));
  mvc.perform(get("/api/v1/local-interface-simulator/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.receiver_messages.length()").value(101));
 }
 @Test void ordinaryInterfaceReaderKeepsContextButCannotReadTheReceiverQueue() throws Exception {
  String token=login();connect(token);
  transport.submit("RISK_NOTICE","private","private-queue",json.createObjectNode());
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-SIM-READ','接口只读','',FALSE,TRUE,0,0,0,FALSE)");
  jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-SIM-READ','interfaces','READ',TRUE)");
  jdbc.update("UPDATE app_user SET role_code='ROLE-SIM-READ' WHERE account='admin1'");
  token=login();
  mvc.perform(get("/api/v1/local-interface-simulator/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.receiver_messages.length()").value(0));
  mvc.perform(post("/api/v1/local-interface-simulator/bindings").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"source_kind\":\"NOTIFICATION_CHANNEL\",\"source_id\":\"receiver\",\"enabled\":true}")).andExpect(status().isForbidden());
 }
 @Test void connectingReceiverWiresOnlyStillUnconfiguredGlobalChannelsToSimulator() throws Exception {
  jdbc.update("UPDATE notification_setting SET channel_type='NONE',endpoint_ref=NULL,enabled=FALSE,valid_until=NULL WHERE setting_id IN('risk-superior','advisory-sms')");
  jdbc.update("UPDATE notification_setting SET channel_type='SMS',endpoint_ref='customer-sms-gateway',enabled=FALSE WHERE setting_id='advisory-voice'");
  long before=jdbc.queryForObject("SELECT version FROM notification_setting WHERE setting_id='advisory-sms'",Long.class);
  String token=login();connect(token);connect(token);
  for(String id:new String[]{"risk-superior","advisory-sms"}) {
   assertThat(jdbc.queryForObject("SELECT channel_type FROM notification_setting WHERE setting_id=?",String.class,id)).isEqualTo("API");
   assertThat(jdbc.queryForObject("SELECT endpoint_ref FROM notification_setting WHERE setting_id=?",String.class,id)).isEqualTo("local-data-simulator");
   assertThat(jdbc.queryForObject("SELECT enabled FROM notification_setting WHERE setting_id=?",Boolean.class,id)).isTrue();
  }
  // 第二次续租不再改动同一行。
  assertThat(jdbc.queryForObject("SELECT version FROM notification_setting WHERE setting_id='advisory-sms'",Long.class)).isEqualTo(before+1);
  assertThat(jdbc.queryForObject("SELECT channel_type FROM notification_setting WHERE setting_id='advisory-voice'",String.class)).isEqualTo("SMS");
  assertThat(jdbc.queryForObject("SELECT endpoint_ref FROM notification_setting WHERE setting_id='advisory-voice'",String.class)).isEqualTo("customer-sms-gateway");
  assertThat(directory.forHandoff("RISK_NOTICE",null).configured()).isTrue();
 }
 @Test void punishmentRecipientsFollowTheSimulatorSelection() throws Exception {
  String token=login();connect(token);
  var orgs=jdbc.queryForList("SELECT org_id FROM app_org WHERE enabled=TRUE ORDER BY org_id FETCH FIRST 2 ROWS ONLY",String.class);
  String first=orgs.get(0),second=orgs.get(1);
  String url="/api/v1/local-interface-simulator/punishment-recipients";
  long others=jdbc.queryForObject("SELECT COUNT(*) FROM handoff_recipient WHERE handoff_type='UAV_PUNISHMENT' AND enabled=TRUE",Long.class);
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("org_ids",java.util.List.of(first)))))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.selected.length()").value(1)).andExpect(jsonPath("$.data.selected[0]").value(first))
   .andExpect(jsonPath("$.data.enabled_recipients.length()").value(others+1));
  String firstRecipient=jdbc.queryForObject("SELECT recipient_id FROM notification_setting WHERE routing_key=?",String.class,"UAV_PUNISHMENT:SIMULATOR:"+first);
  var target=directory.forHandoff("UAV_PUNISHMENT",firstRecipient);
  assertThat(target.configured()).isTrue();
  assertThat(target.recipientName()).isEqualTo(jdbc.queryForObject("SELECT name FROM app_org WHERE org_id=?",String.class,first));
  assertThat(target.endpointRef()).isEqualTo("local-data-simulator");
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("org_ids",java.util.List.of(first,second)))))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.selected.length()").value(2)).andExpect(jsonPath("$.data.enabled_recipients.length()").value(others+2));
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("org_ids",java.util.List.of(second)))))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.selected.length()").value(1)).andExpect(jsonPath("$.data.selected[0]").value(second));
  assertThat(jdbc.queryForObject("SELECT enabled FROM handoff_recipient WHERE recipient_id=?",Boolean.class,firstRecipient)).isFalse();
  mvc.perform(get(url).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.selected[0]").value(second))
   .andExpect(jsonPath("$.data.organizations.length()").value(jdbc.queryForObject("SELECT COUNT(*) FROM app_org WHERE enabled=TRUE",Long.class)));
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"org_ids\":[\"missing-org\"]}")).andExpect(status().isBadRequest());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"org_ids\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}")).andExpect(status().isBadRequest());
  mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"org_ids\":[]}")).andExpect(status().isUnauthorized());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"org_ids\":[]}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.selected.length()").value(0)).andExpect(jsonPath("$.data.enabled_recipients.length()").value(others));
 }
 @Test void receiverRequiresLoginAndRenewalIsExplicit() throws Exception {
  String body="{\"source_kind\":\"NOTIFICATION_CHANNEL\",\"source_id\":\"receiver\",\"enabled\":true}";
  mvc.perform(post("/api/v1/local-interface-simulator/bindings").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
  var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse();
  String token="Bearer "+json.readTree(login.getContentAsString()).path("data").path("session_id").asText();
  var response=mvc.perform(post("/api/v1/local-interface-simulator/bindings").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true)).andReturn().getResponse();
  long expiry=json.readTree(response.getContentAsString()).path("data").path("expires_at").asLong();
  assertThat(expiry-System.currentTimeMillis()).isBetween(1000L,30000L);
  mvc.perform(get("/api/v1/local-interface-simulator/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.receiver_messages").isArray());
 }
}
