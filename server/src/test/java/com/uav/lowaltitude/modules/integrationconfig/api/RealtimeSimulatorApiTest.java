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
 private String login() throws Exception {
  return "Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
 }
 private void connect(String token) throws Exception {
  mvc.perform(post("/api/v1/local-interface-simulator/bindings").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"source_kind\":\"NOTIFICATION_CHANNEL\",\"source_id\":\"receiver\",\"enabled\":true}")).andExpect(status().isOk());
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
