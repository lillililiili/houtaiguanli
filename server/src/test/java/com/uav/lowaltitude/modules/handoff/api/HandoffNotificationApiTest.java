package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fasterxml.jackson.databind.*;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;

@SpringBootTest(properties={"app.handoff.channel=none", "app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class HandoffNotificationApiTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
 @MockBean HandoffChannelPort channel; @SpyBean NotificationDirectoryService directory;
 String session,id,event,recipient,alarm;
 @BeforeEach void setup() throws Exception {
  session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
  String suffix=UUID.randomUUID().toString().substring(0,8);id="hn-h-"+suffix;event="hn-e-"+suffix;alarm="hn-a-"+suffix;recipient="hn-r-"+suffix;
  var at=Timestamp.from(Instant.parse("2026-09-08T02:00:00Z"));
  String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
  String src=jdbc.queryForObject("SELECT source_id FROM integration_source ORDER BY source_id FETCH FIRST 1 ROW ONLY",String.class);
  jdbc.update("INSERT INTO alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock','seed-stage3-org','seed-stage3-district',?)",alarm,src,alarm,at,at,at);
  jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'CONFIRMED','seed-stage3-org','seed-stage3-district',?,?,1)",event,alarm,at,at);
  jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,'测试处罚部门','UAV_PUNISHMENT',true,?,?)",recipient,at,at);
  jdbc.update("INSERT INTO handoff(handoff_id,source_kind,source_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) VALUES(?,'UAV_EVENT',?,?,'UAV_PUNISHMENT',?,1,'seed-stage3-org','seed-stage3-district','mock',?,?)",id,event,event,recipient,actor,at);
  jdbc.update("INSERT INTO handoff_material_snapshot(handoff_id,schema_version,snapshot,created_at) VALUES(?,2,CAST(? AS JSON),?)",id,"{\"schema_version\":2,\"event\":{\"event_id\":\""+event+"\",\"state\":\"CONFIRMED\"}}",at);
  jdbc.update("INSERT INTO handoff_delivery(delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) VALUES(?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','CHANNEL_NOT_CONNECTED',?)",UUID.randomUUID().toString(),id,at);
  var target=new RecipientSnapshot(recipient,"测试处罚部门",null,null,null,null,null,"MOCK",null,null,1L,true,null,System.currentTimeMillis());
  doReturn(target).when(directory).forHandoff("UAV_PUNISHMENT",recipient);
  when(channel.simulated()).thenReturn(true);
  when(channel.deliver(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var dispatch=(HandoffChannelPort.HandoffDispatch)call.getArgument(0);var time=dispatch.at();return new DeliveryOutcome("DELIVERED","PENDING",null,null,time,time,null);});
 }
 @AfterEach void cleanup(){
  if(id==null)return;
  jdbc.update("DELETE FROM handoff_delivery WHERE handoff_id=?",id);jdbc.update("DELETE FROM handoff_material_snapshot WHERE handoff_id=?",id);jdbc.update("DELETE FROM handoff WHERE handoff_id=?",id);jdbc.update("DELETE FROM handoff_recipient WHERE recipient_id=?",recipient);jdbc.update("DELETE FROM uav_event WHERE event_id=?",event);jdbc.update("DELETE FROM alarm WHERE alarm_id=?",alarm);
 }
 @Test void missingChannelSettingStillNotifiesTheOriginalRecipient() throws Exception {
  doCallRealMethod().when(directory).forHandoff("UAV_PUNISHMENT",recipient);
  mvc.perform(get(url()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(true));
  send(1,UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_status").value("DELIVERED"));
  verify(channel,times(1)).deliver(any());
 }
 @Test void sendsOriginalMaterialOnceAndRefreshNeverSends() throws Exception {
  String before=jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?",String.class,id);
  mvc.perform(get(url()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(true));
  verify(channel,never()).deliver(any());
  String key=UUID.randomUUID().toString();send(1,key).andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_status").value("DELIVERED")).andExpect(jsonPath("$.data.receipt_status").value("PENDING"));
  send(1,key).andExpect(status().isConflict());send(2,UUID.randomUUID().toString()).andExpect(status().isConflict());
  mvc.perform(get(url()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(false));
  assertThat(jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?",String.class,id)).isEqualTo(before);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?",Long.class,id)).isEqualTo(2);
  verify(channel,times(1)).deliver(any());
 }
 @Test void channelExceptionPersistsUnknownAndBlocksRetry() throws Exception {
  doThrow(new IllegalStateException("connection lost")).when(channel).deliver(any());send(1,UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_status").value("SUBMITTED")).andExpect(jsonPath("$.data.blocked_reason").value("DELIVERY_OUTCOME_UNKNOWN"));
  send(2,UUID.randomUUID().toString()).andExpect(status().isConflict());verify(channel,times(1)).deliver(any());
 }
 @Test void staleAttemptAndNoAuthenticationNeverSend() throws Exception {
  send(0,UUID.randomUUID().toString()).andExpect(status().isConflict());
  mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":1}")).andExpect(status().isUnauthorized());verify(channel,never()).deliver(any());
 }
 @Test void submittedAcknowledgedOrPreviouslyDeliveredCannotResend() throws Exception {
  for(String state:List.of("SUBMITTED","DELIVERED")){jdbc.update("UPDATE handoff_delivery SET delivery_status=?,receipt_status='PENDING' WHERE handoff_id=?",state,id);send(1,UUID.randomUUID().toString()).andExpect(status().isConflict());}
  jdbc.update("UPDATE handoff_delivery SET delivery_status='FAILED',receipt_status='ACKNOWLEDGED' WHERE handoff_id=?",id);send(1,UUID.randomUUID().toString()).andExpect(status().isConflict());verify(channel,never()).deliver(any());
 }
 @Test void definitiveFailureCanRetryAndHistoryIsRetained() throws Exception {
  doAnswer(call->{var time=((HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();return new DeliveryOutcome("FAILED","NOT_EXPECTED",null,"模拟发送失败",time,null,null);}).when(channel).deliver(any());
  send(1,UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_status").value("FAILED"));
  send(2,UUID.randomUUID().toString()).andExpect(status().isOk());verify(channel,times(2)).deliver(any());
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?",Long.class,id)).isEqualTo(3);
 }
 @Test void liveSourceCannotUseMockChannel() throws Exception {
  jdbc.update("UPDATE handoff SET source_mode='live' WHERE handoff_id=?",id);send(1,UUID.randomUUID().toString()).andExpect(status().isConflict());verify(channel,never()).deliver(any());
 }
 @Test void invalidBodyAndRevokedSourcePreventSending() throws Exception {
  mvc.perform(post(url()).header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":1,\"delivery_status\":\"DELIVERED\"}")).andExpect(status().isBadRequest());
  jdbc.update("UPDATE uav_event SET state_code='FALSE_POSITIVE' WHERE event_id=?",event);send(1,UUID.randomUUID().toString()).andExpect(status().isConflict());verify(channel,never()).deliver(any());
 }
 @Test void eachAttemptKeepsItsRecipientSnapshot() throws Exception {
  send(1,UUID.randomUUID().toString()).andExpect(status().isOk());
  String snapshot=jdbc.queryForObject("SELECT recipient_snapshot FROM handoff_delivery WHERE handoff_id=? AND attempt_no=2",String.class,id);
  assertThat(snapshot).contains("测试处罚部门");
  doReturn(new RecipientSnapshot(recipient,"后来改名的部门",null,null,null,null,null,"NONE",null,null,2L,false,"已停用",System.currentTimeMillis())).when(directory).forHandoff("UAV_PUNISHMENT",recipient);
  mvc.perform(get(url()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.simulated").value(true)).andExpect(jsonPath("$.data.recipient_snapshot.recipient_name").value("测试处罚部门"));
  assertThat(jdbc.queryForObject("SELECT recipient_snapshot FROM handoff_delivery WHERE handoff_id=? AND attempt_no=1",String.class,id)).isNull();
 }
 @Test void enabledPunishNotifyRuleBlocksTheButtonUntilRemoved() throws Exception {
  String ruleId="hn-rule-"+id;
  jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES(?,'dispose',?,'riskActive','未解除且未排除',0,true,0,0,'handoff-notify-test')",ruleId,"通知处罚-"+id);
  try {
   mvc.perform(get(url()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(false)).andExpect(jsonPath("$.data.blocked_reason").value(org.hamcrest.Matchers.containsString("通知处罚")));
   send(1,UUID.randomUUID().toString()).andExpect(status().isConflict());
   verify(channel,never()).deliver(any());
  } finally {
   jdbc.update("DELETE FROM automation_rule_condition WHERE rule_id=?",ruleId);
  }
  mvc.perform(get(url()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(true));
 }
 @Test void permissionsAndScopeAreRequiredByBackend() throws Exception {
  String reader=limitedSession(false),outsider=limitedSession(true);
  mvc.perform(get(url()).header("Authorization","Bearer "+reader)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(false));
  mvc.perform(post(url()).header("Authorization","Bearer "+reader).contentType(MediaType.APPLICATION_JSON).content("{}"))
    .andExpect(status().isForbidden());
  mvc.perform(get(url()).header("Authorization","Bearer "+outsider)).andExpect(status().isNotFound());
  mvc.perform(post(url()).header("Authorization","Bearer "+outsider).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":1}"))
    .andExpect(status().isNotFound());
  verify(channel,never()).deliver(any());
 }
 String limitedSession(boolean outsider){
  String uid=UUID.randomUUID().toString(),role="HN-"+uid.substring(0,8),token=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',false,true,0,0,0,false)",role,role);
  for(String permission:outsider?List.of("handoff:read","handoff:create","alarm:read"):List.of("handoff:read"))jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,?,false,current_timestamp)",role,permission,permission.endsWith(":read")?"READ":"OP");
  jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",uid,role,role,role,outsider?"ASSIGNED":"ALL");
  if(outsider)jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,'seed-stage3-other-org','seed-stage3-other-district')",uid);
  jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",token,uid,System.currentTimeMillis()+3600000);return token;
 }
 String url(){return "/api/v1/handoffs/"+id+"/notifications";}
 org.springframework.test.web.servlet.ResultActions send(int attempt,String key)throws Exception{return mvc.perform(post(url()).header("Authorization","Bearer "+session).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":"+attempt+"}"));}
}
