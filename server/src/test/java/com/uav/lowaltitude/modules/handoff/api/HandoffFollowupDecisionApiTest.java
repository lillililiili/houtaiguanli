package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;

@SpringBootTest(properties={"app.handoff.channel=none","app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class HandoffFollowupDecisionApiTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
 @MockBean HandoffChannelPort channel;
 @SpyBean com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
 @Autowired com.uav.lowaltitude.modules.handoff.application.HandoffFollowupService followups;
 @Autowired com.uav.lowaltitude.modules.handoff.infrastructure.HandoffFollowupRepository followupRepository;
 @Autowired com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository voice;
 @SpyBean com.uav.lowaltitude.modules.handoff.application.HandoffMaterialAssembler materials;
 @SpyBean com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository noCounter;
 String recipient;java.util.List<String> enabledRecipients;
 String session,id,event,alarm;
 @BeforeEach void setup() throws Exception {
  session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
  id=UUID.randomUUID().toString();event=UUID.randomUUID().toString();alarm=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) VALUES(?,'seed-stage3-source',?,'UAV_INTRUSION','HIGH',current_timestamp,'mock','seed-stage3-org','seed-stage3-district',current_timestamp)",alarm,alarm);
  jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'CONFIRMED','seed-stage3-org','seed-stage3-district',current_timestamp,current_timestamp,1)",event,alarm);
  jdbc.update("INSERT INTO handoff(handoff_id,source_kind,source_id,event_id,handoff_type,source_version,owner_org_id,district_id,source_mode,created_at,trigger_source) VALUES(?,'UAV_EVENT',?,?,'UAV_PUNISHMENT',1,'seed-stage3-org','seed-stage3-district','mock',current_timestamp,'FIELD_HANDLING_ENDED')",id,event,event);
  jdbc.update("INSERT INTO handoff_notification_decision(handoff_id,event_id,state,version,field_handling) VALUES(?,?,'PENDING_DECISION',0,'{}')",id,event);
  jdbc.update("INSERT INTO handoff_material_snapshot(handoff_id,schema_version,snapshot,created_at) VALUES(?,2,CAST(? AS JSON),current_timestamp)",id,"{\"schema_version\":2,\"event\":{\"event_id\":\""+event+"\",\"state\":\"CONFIRMED\"}}");
  jdbc.update("INSERT INTO handoff_delivery(delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) VALUES(?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','PENDING_NOTIFICATION_DECISION',current_timestamp)",UUID.randomUUID().toString(),id);
 }
 @AfterEach void cleanup(){if(id==null)return;clearHandoff();jdbc.update("DELETE FROM uav_auto_voice_task WHERE event_id=?",event);jdbc.update("DELETE FROM uav_event WHERE event_id=?",event);jdbc.update("DELETE FROM alarm WHERE alarm_id=?",alarm);if(recipient!=null)jdbc.update("DELETE FROM handoff_recipient WHERE recipient_id=?",recipient);if(enabledRecipients!=null)enabledRecipients.forEach(r->jdbc.update("UPDATE handoff_recipient SET enabled=true WHERE recipient_id=?",r));}
 void clearHandoff(){jdbc.update("DELETE FROM handoff_notification_decision WHERE handoff_id=?",id);jdbc.update("DELETE FROM handoff_delivery WHERE handoff_id=?",id);jdbc.update("DELETE FROM handoff_material_snapshot WHERE handoff_id=?",id);jdbc.update("DELETE FROM handoff WHERE handoff_id=?",id);}
 @Test void pendingWithoutRecipientIsVisibleAndDeclinable() throws Exception {
  mvc.perform(get("/api/v1/handoffs").param("source_id",event).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].notification_decision.state").value("PENDING_DECISION"));
  mvc.perform(get(statusUrl()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(false)).andExpect(jsonPath("$.data.can_decline").value(true));
  verify(channel,never()).deliver(any());
 }
 @Test void declineRequiresReasonAndRetainsIndependentDeliveryFacts() throws Exception {
  decline(" ",0).andExpect(status().isBadRequest());
  decline("材料已通过另行核实，无需本次通知",0).andExpect(status().isOk()).andExpect(jsonPath("$.data.notification_decision.state").value("DO_NOT_NOTIFY")).andExpect(jsonPath("$.data.can_notify").value(false));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?",Long.class,id)).isEqualTo(1);
  decline("再次决定",0).andExpect(status().isConflict());
  mvc.perform(post(statusUrl()).header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":1}")).andExpect(status().isConflict());
  verify(channel,never()).deliver(any());
 }
 @Test void unknownAndAlreadySentCannotBeDeclined() throws Exception {
  jdbc.update("UPDATE handoff_delivery SET delivery_status='SUBMITTED',receipt_status='PENDING',blocked_reason='DELIVERY_OUTCOME_UNKNOWN' WHERE handoff_id=?",id);
  decline("不能撤销外部通知",0).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("SELECT state FROM handoff_notification_decision WHERE handoff_id=?",String.class,id)).isEqualTo("PENDING_DECISION");
 }
 @Test void pendingAndDeclinedAreNotCountedAsAwaitingSend() throws Exception {
  mvc.perform(get("/api/v1/handoffs").param("source_id",event).param("delivery_status","PENDING_DELIVERY").header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
  decline("本次不通知",0).andExpect(status().isOk());
  mvc.perform(get("/api/v1/handoffs").param("source_id",event).param("notification_decision","DO_NOT_NOTIFY").header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
  var stats=json.readTree(mvc.perform(get("/api/v1/handoffs/stats").param("source_id",event).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  long total=0;for(var bucket:stats.path("by_delivery"))total+=bucket.path("count").asLong();assertThat(total).isEqualTo(stats.path("total").asLong());
 }
 @Test void explicitNotifyFreezesRecipientAndDecisionBeforeExternalCall() throws Exception {
  configureRecipient();
  when(channel.deliver(any())).thenAnswer(call->{
   assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
   assertThat(jdbc.queryForObject("SELECT state FROM handoff_notification_decision WHERE handoff_id=?",String.class,id)).isEqualTo("NOTIFY");
   assertThat(jdbc.queryForObject("SELECT recipient_id FROM handoff WHERE handoff_id=?",String.class,id)).isEqualTo(recipient);
   var at=((HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();return new HandoffChannelPort.DeliveryOutcome("DELIVERED","PENDING",null,null,at,at,null);
  });
  notifyRequest().andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_status").value("DELIVERED"));
  decline("已经发出不可撤回",1).andExpect(status().isConflict());
  notifyRequest().andExpect(status().isConflict());verify(channel,times(1)).deliver(any());
 }
 @Test void missingOrAmbiguousConfigurationNeverBlocksDecline() throws Exception {
  configureRecipient();doCallRealMethod().when(directory).forHandoff("UAV_PUNISHMENT",recipient);
  mvc.perform(get(statusUrl()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(false)).andExpect(jsonPath("$.data.can_decline").value(true));
  notifyRequest().andExpect(status().isConflict());decline("通道配置尚未具备，另行处理",0).andExpect(status().isOk());verify(channel,never()).deliver(any());
 }
 @Test void liveCannotUseSimulationAndRevokedSourceCannotDecide() throws Exception {
  configureRecipient();jdbc.update("UPDATE handoff SET source_mode='live' WHERE handoff_id=?",id);notifyRequest().andExpect(status().isConflict());
  jdbc.update("UPDATE uav_event SET state_code='FALSE_POSITIVE' WHERE event_id=?",event);decline("不再属实",0).andExpect(status().isConflict());verify(channel,never()).deliver(any());
 }
 void configureRecipient(){
  enabledRecipients=jdbc.queryForList("SELECT recipient_id FROM handoff_recipient WHERE enabled=true AND handoff_type='UAV_PUNISHMENT'",String.class);
  jdbc.update("UPDATE handoff_recipient SET enabled=false WHERE handoff_type='UAV_PUNISHMENT'");recipient=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,'隔离上级接口','UAV_PUNISHMENT',true,current_timestamp,current_timestamp)",recipient);
  var target=new com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot(recipient,"隔离上级接口",null,null,null,null,null,"MOCK",null,null,1L,true,null,System.currentTimeMillis());
  doReturn(target).when(directory).forHandoff("UAV_PUNISHMENT",recipient);when(channel.simulated()).thenReturn(true);
 }
 @Test void frozenLeftCreatesOneTaskWithoutSendingEvenWithNoRecipient() throws Exception {
  clearHandoff();recordLeft(System.currentTimeMillis());followups.create(event);findCreated();followups.create(event);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Long.class,event)).isEqualTo(1);
  assertThat(followupRepository.decision(id).state()).isEqualTo("PENDING_DECISION");
  mvc.perform(get("/api/v1/handoffs/"+id).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.notification_decision.field_handling.kind").value("LEFT")).andExpect(jsonPath("$.data.notification_decision.field_handling.observation").doesNotExist());
  verify(channel,never()).deliver(any());
 }
 @Test void preDeploymentOpenEventWithNewCompletionEntersButOldCompletionDoesNot(){
  clearHandoff();long cutoff=followupRepository.cutoff();jdbc.update("UPDATE uav_event SET created_at=? WHERE event_id=?",new java.sql.Timestamp(cutoff-60000),event);
  recordLeft(cutoff-1000);assertThat(followupRepository.candidates()).doesNotContain(event);followups.create(event);assertNoHandoff();
  jdbc.update("DELETE FROM uav_auto_voice_task WHERE event_id=?",event);recordLeft(cutoff+1000);
  assertThat(followupRepository.candidates()).contains(event);followups.create(event);findCreated();assertThat(followupRepository.decision(id).state()).isEqualTo("PENDING_DECISION");
 }
 @Test void unknownUnconfirmedAndUnfinishedDeviceWorkDoNotCreate(){
  clearHandoff();followups.create(event);assertNoHandoff();recordLeft(System.currentTimeMillis());
  jdbc.update("UPDATE uav_event SET state_code='PENDING_VERIFICATION' WHERE event_id=?",event);followups.create(event);assertNoHandoff();
  jdbc.update("UPDATE uav_event SET state_code='CONFIRMED' WHERE event_id=?",event);doReturn("急停实际停机尚未核查").when(noCounter).operationBlock(event);followups.create(event);assertNoHandoff();
 }
 @Test void newWorkflowCannotBypassDecisionViaLegacyCreate() throws Exception {
  configureRecipient();mvc.perform(post("/api/v1/handoffs").header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("source_kind","UAV_EVENT","source_id",event,"handoff_type","UAV_PUNISHMENT","recipient_id",recipient,"expected_version",1)))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HANDOFF_FOLLOWUP_REQUIRED"));verify(channel,never()).deliver(any());
 }
 @Test void notificationDecisionRequiresActionPermissionAndObjectScope() throws Exception {
  String original=session;
  try{
   session=limitedSession(false);mvc.perform(get(statusUrl()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_decline").value(false));decline("无操作权限",0).andExpect(status().isForbidden());
   session=limitedSession(true);decline("超出机构区域",0).andExpect(status().isNotFound());
   assertThat(followupRepository.decision(id).state()).isEqualTo("PENDING_DECISION");verify(channel,never()).deliver(any());
  }finally{session=original;}
 }
 String limitedSession(boolean outsider){
  String uid=UUID.randomUUID().toString(),role="FOLLOW-"+uid.substring(0,8),token=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',false,true,0,0,0,false)",role,role);
  for(String permission:outsider?java.util.List.of("handoff:read","handoff:create","alarm:read"):java.util.List.of("handoff:read"))jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,?,false,current_timestamp)",role,permission,permission.endsWith(":read")?"READ":"OP");
  jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",uid,role,role,role,outsider?"ASSIGNED":"ALL");
  if(outsider)jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,'seed-stage3-other-org','seed-stage3-other-district')",uid);
  jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",token,uid,System.currentTimeMillis()+3600000);return token;
 }
 @Test void activeHumanNoCounterCreatesButInvalidatedDecisionDoesNot(){
  clearHandoff();var decision=new com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository.Decision(UUID.randomUUID().toString(),System.currentTimeMillis(),"隔离操作员","人工确认当前无风险",null,null,"seed-stage3-org","seed-stage3-district","mock");
  doReturn(decision).when(noCounter).decision(event);doReturn(false).when(noCounter).active(event);followups.create(event);assertNoHandoff();
  doReturn(true).when(noCounter).active(event);followups.create(event);findCreated();assertThat(followupRepository.decision(id).fieldHandling().path("kind").asText()).isEqualTo("NO_COUNTER");verify(channel,never()).deliver(any());
 }
 @Test void oversizedEvidenceStillCreatesVisibleDeclinableTask() throws Exception {
  clearHandoff();recordLeft(System.currentTimeMillis());doThrow(new com.uav.lowaltitude.platform.api.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"HANDOFF_EVIDENCE_TOO_LARGE","关联轨迹超过材料容量")).when(materials).assemble(event,true);
  followups.create(event);findCreated();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_material_snapshot WHERE handoff_id=?",Long.class,id)).isZero();
  mvc.perform(get(statusUrl()).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(false)).andExpect(jsonPath("$.data.can_decline").value(true)).andExpect(jsonPath("$.data.blocked_reason").value(org.hamcrest.Matchers.containsString("关联轨迹超过材料容量")));
  decline("材料暂不具备，本次不通知",0).andExpect(status().isOk());verify(channel,never()).deliver(any());
 }
 void recordLeft(long at){voice.initialize(event,at,"isolated-followup");voice.recordDeparture(new com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation(event,"SMS","RECORDED","LEFT",at-1000,at-1000,at,new com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Position(at,new java.math.BigDecimal("118.15"),new java.math.BigDecimal("37.29")),"有效设备位置证明离开"));}
 void findCreated(){id=jdbc.queryForObject("SELECT handoff_id FROM handoff WHERE event_id=?",String.class,event);}
 void assertNoHandoff(){assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Long.class,event)).isZero();}
 org.springframework.test.web.servlet.ResultActions notifyRequest()throws Exception{return mvc.perform(post(statusUrl()).header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":1}"));}
 String statusUrl(){return "/api/v1/handoffs/"+id+"/notifications";}
 org.springframework.test.web.servlet.ResultActions decline(String reason,int version)throws Exception{return mvc.perform(post("/api/v1/handoffs/"+id+"/notification-decision").header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("decision","DO_NOT_NOTIFY","expected_version",version,"reason",reason))));}
}
