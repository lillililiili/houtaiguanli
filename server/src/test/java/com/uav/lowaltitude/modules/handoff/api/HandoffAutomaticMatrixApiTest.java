package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Legacy frozen-material tests remain inherited; new completion batches require an explicit human notification. */
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@Transactional
class HandoffAutomaticMatrixApiTest extends HandoffPunishmentMaterialsApiTest {
 @Autowired HandoffSubmissionService handoffs;
 @Autowired AutomationRuntimeRepository runs;
 @SpyBean AutomationRuntimePolicy policy;
 @SpyBean NotificationDirectoryService directory;
 @MockBean HandoffChannelPort channel;
 @Override @AfterEach void cleanup() { }

 @ParameterizedTest(name="automation policy={0}")
 @CsvSource({"PASS","RULE_OFF","MANUAL_AFTER_WAIT","AUTO_AFTER_WAIT","ENGINE_OFF","FAILED_MANUAL_RETRY","STALE_DISABLED_RULES","STALE_VERSION"})
 void independentGenerationDeliveryAndManualRetryBatches(String batch) throws Exception {
  activate();configureChannel();
  doReturn(!"ENGINE_OFF".equals(batch)).when(policy).enabled();
  if("RULE_OFF".equals(batch)||batch.endsWith("AFTER_WAIT")||"STALE_DISABLED_RULES".equals(batch))
   jdbc.update("UPDATE automation_rule_condition SET enabled=false WHERE category='dispose'");
  if("STALE_VERSION".equals(batch))jdbc.update("UPDATE automation_rule_group SET version=version+1 WHERE category='dispose'");
  runs.state("dispose",eventId,0,null,"PASS",null,"{}","isolated-policy-matrix");
  handoffs.automaticAfterJamming(eventId);String id=handoffId();String snapshot=frozen(id);
  assertThat(jdbc.queryForObject("SELECT state FROM handoff_notification_decision WHERE handoff_id=?",String.class,id)).isEqualTo("PENDING_DECISION");
  assertThat(jdbc.queryForObject("SELECT recipient_id FROM handoff WHERE handoff_id=?",String.class,id)).isNull();
  assertThat(jdbc.queryForObject("SELECT submitted_by FROM handoff WHERE handoff_id=?",String.class,id)).isNull();
  assertThat(advisory().path("auto_handoff").path("status").asText()).isEqualTo("PENDING_DECISION");
  handoffs.automaticAfterJamming(eventId);handoffs.automaticAfterJamming(eventId);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?",Integer.class,id)).isEqualTo(1);
  verify(channel,never()).deliver(any());
  if("FAILED_MANUAL_RETRY".equals(batch)){
   doAnswer(call->{var at=((HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();return new DeliveryOutcome("FAILED","NOT_EXPECTED",null,"明确失败",at,null,null);}).when(channel).deliver(any());
   manualNotify(id,1);handoffs.automaticAfterJamming(eventId);verify(channel,times(1)).deliver(any());
   successfulChannel();manualNotify(id,2);verify(channel,times(2)).deliver(any());
  }else{
   manualNotify(id,1);verify(channel,times(1)).deliver(any());
  }
  handoffs.automaticAfterJamming(eventId);
  assertThat(jdbc.queryForObject("SELECT state FROM handoff_notification_decision WHERE handoff_id=?",String.class,id)).isEqualTo("NOTIFY");
  assertThat(frozen(id)).isEqualTo(snapshot);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Integer.class,eventId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM punishment_case WHERE event_id=?",Integer.class,eventId)).isZero();
 }

 @ParameterizedTest @CsvSource({"COUNTERMEASURE,COMPLETED","JAMMING,REQUESTED","JAMMING,FAILED"})
 void incompleteExecutionNeverCreatesAnInternalTask(String action,String state){
  activate();configureChannel();jdbc.update("UPDATE disposal_authorization SET action_type=?,status=? WHERE subject_id=?",action,state,eventId);
  handoffs.automaticAfterJamming(eventId);handoffs.automaticAfterJamming(eventId);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Integer.class,eventId)).isZero();verify(channel,never()).deliver(any());
 }

 @ParameterizedTest @CsvSource({"DELIVERED","SUBMITTED"})
 void frozenTaskNeverSendsWhenRulesChangeAndManualOutcomeRemainsSeparate(String outcome) throws Exception {
  activate();configureChannel();handoffs.automaticAfterJamming(eventId);String id=handoffId();String snapshot=frozen(id);
  runs.state("dispose",eventId,0,null,"PASS",null,"{}","isolated-current-pass");
  jdbc.update("UPDATE automation_rule_condition SET enabled=false WHERE category='dispose'");handoffs.automaticAfterJamming(eventId);
  verify(channel,never()).deliver(any());
  doAnswer(call->{var at=((HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();return new DeliveryOutcome(outcome,"PENDING",null,null,at,"DELIVERED".equals(outcome)?at:null,null);}).when(channel).deliver(any());
  manualNotify(id,1);
  mvc.perform(post("/api/v1/handoffs/"+id+"/notifications").header("Authorization","Bearer "+submitter).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expected_attempt_no\":2}")).andExpect(status().isConflict());
  handoffs.automaticAfterJamming(eventId);verify(channel,times(1)).deliver(any());assertThat(frozen(id)).isEqualTo(snapshot);
 }

 @Test void missingOrMultipleRecipientsStillCreateVisibleTask() throws Exception {
  activate();configureChannel();String other=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,'其他上级','UAV_PUNISHMENT',true,current_timestamp,current_timestamp)",other);
  handoffs.automaticAfterJamming(eventId);String id=handoffId();
  mvc.perform(get("/api/v1/handoffs/"+id+"/notifications").header("Authorization","Bearer "+submitter)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_notify").value(false)).andExpect(jsonPath("$.data.can_decline").value(true));
  jdbc.update("UPDATE handoff_recipient SET enabled=false WHERE handoff_type='UAV_PUNISHMENT'");
  assertThat(advisory().path("auto_handoff").path("status").asText()).isEqualTo("PENDING_DECISION");
  handoffs.automaticAfterJamming(eventId);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Integer.class,eventId)).isEqualTo(1);verify(channel,never()).deliver(any());
 }
 @Test void falsePositiveIsExcluded(){activate();jdbc.update("UPDATE uav_event SET state_code='FALSE_POSITIVE' WHERE event_id=?",eventId);handoffs.automaticAfterJamming(eventId);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Integer.class,eventId)).isZero();verify(channel,never()).deliver(any());}
 @Test void historicalCompletionIsNotBackfilled(){configureChannel();jdbc.update("UPDATE disposal_authorization SET action_type='JAMMING' WHERE subject_id=?",eventId);handoffs.automaticAfterJamming(eventId);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?",Integer.class,eventId)).isZero();verify(channel,never()).deliver(any());}
 private void activate(){jdbc.update("UPDATE uav_event SET created_at=current_timestamp WHERE event_id=?",eventId);jdbc.update("UPDATE disposal_authorization SET action_type='JAMMING' WHERE subject_id=?",eventId);}
 private void configureChannel(){jdbc.update("UPDATE handoff_recipient SET enabled=false WHERE handoff_type='UAV_PUNISHMENT' AND recipient_id<>?",recipientId);doReturn(new RecipientSnapshot(recipientId,"隔离上级",null,null,null,null,null,"MOCK",null,null,1L,true,null,System.currentTimeMillis())).when(directory).forHandoff("UAV_PUNISHMENT",recipientId);when(channel.simulated()).thenReturn(true);successfulChannel();}
 private void successfulChannel(){doAnswer(call->{var at=((HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();return new DeliveryOutcome("DELIVERED","PENDING",null,null,at,at,null);}).when(channel).deliver(any());}
 private String handoffId(){return jdbc.queryForObject("SELECT handoff_id FROM handoff WHERE event_id=?",String.class,eventId);}
 private String frozen(String id){return jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?",String.class,id);}
 private com.fasterxml.jackson.databind.JsonNode advisory()throws Exception{return body(mvc.perform(get("/api/v1/uav-events/"+eventId+"/advisory").header("Authorization","Bearer "+submitter)).andExpect(status().isOk())).path("data");}
 private void manualNotify(String id,int attempt)throws Exception{mvc.perform(post("/api/v1/handoffs/"+id+"/notifications").header("Authorization","Bearer "+submitter).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expected_attempt_no\":"+attempt+"}")).andExpect(status().isOk());}
}
