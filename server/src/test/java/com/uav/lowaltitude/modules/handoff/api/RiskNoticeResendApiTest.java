package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.handoff.application.RiskNoticeResendJob;

/**
 * 新-23：点“通知上级”时数据模拟器接收端没连上，接收端恢复后系统自动补发一次，上级回执照常让风险变“已回执”。
 * 已经发出去、只差回执的不补发；结果未知的不补发；超过 30 分钟的不补发；补发只有一次。
 */
@SpringBootTest(properties={"app.notifications.transport=simulator","app.handoff.channel=simulator"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class RiskNoticeResendApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Autowired RiskNoticeResendJob job;
 String token;

 @BeforeEach void receiverAndSuperiorChannel() throws Exception {
  token="Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
   .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
  connect();
  jdbc.update("UPDATE notification_setting SET enabled=TRUE,channel_type='API',endpoint_ref='local-data-simulator' WHERE setting_id='risk-superior'");
 }

 @Test void noticeLeftUnsentWhileReceiverWasDownIsResentOnceAfterItReconnects() throws Exception {
  receiverDown();
  String risk=risk();
  JsonNode created=notifySuperior(risk);
  assertThat(created.path("delivery_status").asText()).isEqualTo("PENDING_DELIVERY");
  assertThat(created.path("blocked_reason").asText()).isEqualTo("数据模拟器接收端未连接或心跳已失效");
  String handoff=created.path("handoff_id").asText();
  // 接收端还没回来：不补发，也不占掉这一次。
  assertThat(job.resend()).isZero();
  assertThat(deliveries(handoff)).isOne();
  connect();
  assertThat(job.resend()).isOne();
  assertThat(job.resend()).isZero();
  assertThat(deliveries(handoff)).isEqualTo(2);
  assertThat(count("SELECT COUNT(*) FROM simulator_notification_message WHERE subject_id=? AND kind='RISK_NOTICE'",handoff)).isOne();
  mvc.perform(get("/api/v1/handoffs/{id}",handoff).header("Authorization",token)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.delivery_status").value("SUBMITTED")).andExpect(jsonPath("$.data.receipt_status").value("PENDING"));
  assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='handoff_notification_resent' AND object_id=?",handoff)).isOne();
  // 上级经接收端回执：补发的这一次被采纳，风险变“已回执”。
  String message=jdbc.queryForObject("SELECT message_id FROM simulator_notification_message WHERE subject_id=?",String.class,handoff);
  String url="/api/v1/local-interface-simulator/messages/"+message+"/receipt";
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"expected_version\":0,\"outcome\":\"DELIVERED\"}")).andExpect(status().isOk());
  mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"expected_version\":1,\"outcome\":\"ACKNOWLEDGED\",\"receipt_result\":\"DISPERSED\"}")).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.result.projection_status").value("APPLIED"));
  mvc.perform(get("/api/v1/handoffs/{id}",handoff).header("Authorization",token)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.receipt_status").value("ACKNOWLEDGED")).andExpect(jsonPath("$.data.receipt_result").value("DISPERSED"));
  assertThat(jdbc.queryForObject("SELECT state_code FROM flight_risk WHERE risk_id=?",String.class,risk)).isEqualTo("ACKNOWLEDGED");
 }

 @Test void noticeAlreadySentAndWaitingForReceiptIsNotResent() throws Exception {
  String handoff=notifySuperior(risk()).path("handoff_id").asText();
  assertThat(job.resend()).isZero();
  assertThat(deliveries(handoff)).isOne();
  assertThat(count("SELECT COUNT(*) FROM simulator_notification_message WHERE subject_id=?",handoff)).isOne();
 }

 @Test void unknownResultOrNoticeOlderThanThirtyMinutesIsNotResent() throws Exception {
  receiverDown();
  String old=notifySuperior(risk()).path("handoff_id").asText();
  String unknown=notifySuperior(risk()).path("handoff_id").asText();
  jdbc.update("UPDATE handoff SET created_at=? WHERE handoff_id=?",OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(31),old);
  // 结果未知：可能已经发出去了，补发会让上级收到两份。
  jdbc.update("UPDATE handoff_delivery SET delivery_status='SUBMITTED',receipt_status='PENDING',blocked_reason='DELIVERY_OUTCOME_UNKNOWN' WHERE handoff_id=?",unknown);
  connect();
  assertThat(job.resend()).isZero();
  assertThat(deliveries(old)).isOne();
  assertThat(deliveries(unknown)).isOne();
 }

 private void connect() throws Exception {
  mvc.perform(post("/api/v1/local-interface-simulator/bindings").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"source_kind\":\"NOTIFICATION_CHANNEL\",\"source_id\":\"receiver\",\"enabled\":true}")).andExpect(status().isOk());
 }
 private void receiverDown() { jdbc.update("UPDATE simulator_notification_receiver SET expires_at=0"); }
 private String risk() {
  String risk=UUID.randomUUID().toString();var now=OffsetDateTime.now(ZoneOffset.UTC);
  jdbc.update("INSERT INTO flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal','ROUTE_DEVIATION','HIGH','PENDING_NOTIFICATION','ROUTE_DEVIATION','isolated simulated risk',?,?,'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',?,?,1)",risk,risk,now,now,now,now);
  return risk;
 }
 private JsonNode notifySuperior(String risk) throws Exception {
  return json.readTree(mvc.perform(post("/api/v1/handoffs").header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString())
   .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("source_kind","RISK","source_id",risk,"handoff_type","RISK_NOTICE","expected_version",1))))
   .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
 }
 private long deliveries(String handoff) { return count("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?",handoff); }
 private long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
}
