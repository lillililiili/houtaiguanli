package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeModel.Decision;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService;
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

/** Independent rollback batches; all delivery receipts below are explicitly simulated. */
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@Transactional
class HandoffAutomaticMatrixApiTest extends HandoffPunishmentMaterialsApiTest {
    @Autowired HandoffSubmissionService handoffs;
    @Autowired AutomationRuntimeRepository runs;
    @Autowired AutomationRuntimeService evaluator;
    @SpyBean AutomationRuntimePolicy policy;
    @SpyBean AutomationRuntimeEligibility eligibility;
    @SpyBean NotificationDirectoryService directory;
    @MockBean HandoffChannelPort channel;

    // A transaction owns all fixtures, including immutable PostgreSQL records: rollback, never delete history.
    @Override @AfterEach void cleanup() { }

    @ParameterizedTest(name="frozen then disabled; manual outcome={0}")
    @CsvSource({"DELIVERED", "SUBMITTED"})
    void supplementalFrozenWaitingThenRulesDisabledRequiresManualTakeover(String outcome) throws Exception {
        prepareSupplementalChannel();
        doReturn(true).when(policy).enabled();
        jdbc.update("update disposal_authorization set action_type='JAMMING' where subject_id=?", eventId);
        prepareCurrentDisposeFacts();
        // Rules are enabled, but this new event has not yet been evaluated: freeze before any send.
        assertThat(runs.state("dispose", eventId)).isNull();
        handoffs.automaticAfterJamming(eventId);
        String id = handoffId();
        String frozen = frozen(id);
        assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id=?", String.class, id))
                .isEqualTo("PENDING_DELIVERY");
        verify(channel, never()).deliver(any());
        // This rollback fixture supplies an old decision explicitly: the real facts reader uses
        // REQUIRES_NEW and cannot see uncommitted fixtures. The committed browser fixture separately
        // executes this exact freeze -> real evaluate PASS -> disable sequence against PostgreSQL.
        runs.state("dispose", eventId, 0, null, "PASS", null, "{}", "qa-frozen-then-disabled");
        assertThat(runs.state("dispose", eventId).status()).isEqualTo("PASS");
        jdbc.update("update automation_rule_condition set enabled=false where category='dispose'");
        jdbc.update("update automation_rule_group set version=version+1 where category='dispose'");
        assertThat(jdbc.queryForObject("select count(*) from automation_rule_condition where category='dispose' and enabled=true", Integer.class)).isZero();
        handoffs.automaticAfterJamming(eventId);
        handoffs.automaticAfterJamming(eventId);
        verify(channel, never()).deliver(any());
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, id)).isEqualTo(1);
        doAnswer(call -> {
            var at = ((HandoffChannelPort.HandoffDispatch) call.getArgument(0)).at();
            return new DeliveryOutcome(outcome, "PENDING", null, null, at,
                    "DELIVERED".equals(outcome) ? at : null, null);
        }).when(channel).deliver(any());
        manualNotify(id, 1);
        handoffs.automaticAfterJamming(eventId);
        mvc.perform(post("/api/v1/handoffs/{id}/notifications", id).header("Authorization", "Bearer " + submitter)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"expected_attempt_no\":2}"))
                .andExpect(status().isConflict());
        verify(channel, times(1)).deliver(any());
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(frozen(id)).isEqualTo(frozen);
    }

    @Test
    void supplementalManualHandoffWithoutAnyDisposalKeepsEmptyFrozenExecutionFacts() throws Exception {
        prepareSupplementalChannel();
        String originalEvent = eventId;
        String newAlarm = "pm-alarm-" + UUID.randomUUID().toString().substring(0, 8);
        eventId = "pm-event-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) select ?,target_id,source_id,?,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at from alarm where alarm_id=(select alarm_id from uav_event where event_id=?)", newAlarm, newAlarm, originalEvent);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) select ?,?,'CONFIRMED',owner_org_id,district_id,created_at,updated_at,version from uav_event where event_id=?", eventId, newAlarm, originalEvent);
        jdbc.update("insert into uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) select ?,?,version,previous_state,resulting_state,conclusion,'QA synthetic no execution',actor_id,created_at from uav_event_verification where event_id=?", UUID.randomUUID().toString(), eventId, originalEvent);
        int commandCount = jdbc.queryForObject("select count(*) from device_command", Integer.class);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, eventId)).isZero();
        String id = body(submit(submitter, eventId).andExpect(status().isCreated())).path("data").path("handoff_id").asText();
        var material = detail(id, submitter).path("material");
        // Existing v2 JSON omits empty disposal history; it must not invent a completed action.
        assertThat(material.has("disposals")).isFalse();
        assertThat(material.path("event").path("event_id").asText()).isEqualTo(eventId);
        String snapshot = frozen(id);
        jdbc.update("update uav_event set version=version+1 where event_id=?", eventId);
        assertThat(frozen(id)).isEqualTo(snapshot);
        assertThat(detail(id, submitter).path("material")).isEqualTo(material);
        handoffs.automaticAfterJamming(eventId);
        verify(channel, times(1)).deliver(any());
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where event_id=?", Integer.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from device_command", Integer.class)).isEqualTo(commandCount);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, id)).isEqualTo(1);
    }

    @ParameterizedTest(name="incomplete jamming={0}")
    @CsvSource({"COUNTERMEASURE,COMPLETED", "JAMMING,REQUESTED", "JAMMING,FAILED"})
    void supplementalIncompleteJammingNeverGeneratesAnAutomaticHandoff(String action, String state) {
        prepareSupplementalChannel();
        // Engine-off permits legacy send, so the result must be blocked specifically by missing completed jamming.
        doReturn(false).when(policy).enabled();
        jdbc.update("update disposal_authorization set action_type=?,status=? where subject_id=?", action, state, eventId);
        handoffs.automaticAfterJamming(eventId);
        handoffs.automaticAfterJamming(eventId);
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?", Integer.class, eventId)).isZero();
        verify(channel, never()).deliver(any());
    }

    @Test
    void supplementalMultipleRecipientsNeverAutoSelectAndManualSubmissionIsExplicit() throws Exception {
        String chosen = prepareSupplementalChannel();
        doReturn(false).when(policy).enabled();
        jdbc.update("update disposal_authorization set action_type='JAMMING' where subject_id=?", eventId);
        String other = UUID.randomUUID().toString();
        jdbc.update("insert into handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values(?,'QA second recipient','UAV_PUNISHMENT',true,current_timestamp,current_timestamp)", other);
        assertThat(jdbc.queryForObject("select count(*) from handoff_recipient where handoff_type='UAV_PUNISHMENT' and enabled=true", Integer.class)).isEqualTo(2);
        handoffs.automaticAfterJamming(eventId);
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?", Integer.class, eventId)).isZero();
        verify(channel, never()).deliver(any());
        // 页面如实写明要有权限的人选择接收单位，不再显示“等待自动移送”；当事人不明也提前写明。
        JsonNode waiting = advisory();
        assertThat(waiting.path("auto_handoff").path("status").asText()).isEqualTo("MANUAL_REQUIRED");
        assertThat(waiting.path("auto_handoff").path("reason").asText()).contains("2 个处罚接收单位");
        assertThat(waiting.path("auto_handoff").path("party_status").asText()).isEqualTo("UNIDENTIFIED");
        assertThat(waiting.path("auto_handoff").path("party_reasons").toString()).contains("报备计划");
        assertThat(waiting.path("can_handoff").asBoolean()).isTrue();
        String id = body(submit(submitter, eventId).andExpect(status().isCreated())).path("data").path("handoff_id").asText();
        assertThat(jdbc.queryForObject("select recipient_id from handoff where handoff_id=?", String.class, id)).isEqualTo(chosen);
        assertThat(jdbc.queryForObject("select trigger_source from handoff where handoff_id=?", String.class, id)).isEqualTo("MANUAL");
        JsonNode submitted = advisory().path("auto_handoff");
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("trigger_source").asText()).isEqualTo("MANUAL");
        assertThat(submitted.path("handoff_id").asText()).isEqualTo(id);
        assertThat(submitted.has("party_status")).isFalse();
        handoffs.automaticAfterJamming(eventId);
        verify(channel, times(1)).deliver(any());
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?", Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void supplementalNoRecipientIsBlockedAndOneRecipientWaitsForTheBackground() throws Exception {
        doReturn(false).when(policy).enabled();
        jdbc.update("update handoff_recipient set enabled=false where handoff_type='UAV_PUNISHMENT'");
        JsonNode beforeJamming = advisory().path("auto_handoff");
        assertThat(beforeJamming.path("status").asText()).isEqualTo("WAITING");
        jdbc.update("update disposal_authorization set action_type='JAMMING' where subject_id=?", eventId);
        assertThat(advisory().path("auto_handoff").path("status").asText()).isEqualTo("BLOCKED");
        jdbc.update("update handoff_recipient set enabled=true where recipient_id=?", recipientId);
        JsonNode one = advisory().path("auto_handoff");
        assertThat(one.path("status").asText()).isEqualTo("WAITING");
        assertThat(one.path("reason").asText()).contains("自动移送");
    }

    @Test
    void supplementalFalsePositiveNeedsNoHandoff() throws Exception {
        jdbc.update("update disposal_authorization set action_type='JAMMING' where subject_id=?", eventId);
        jdbc.update("update uav_event set state_code='FALSE_POSITIVE' where event_id=?", eventId);
        JsonNode progress = advisory().path("auto_handoff");
        assertThat(progress.path("status").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(progress.path("reason").asText()).contains("误报");
    }

    private String prepareSupplementalChannel() {
        jdbc.update("update handoff_recipient set enabled=false where handoff_type='UAV_PUNISHMENT' and recipient_id<>?", recipientId);
        String recipient = recipientId;
        doReturn(new RecipientSnapshot(recipient, "QA合成接收方", null, null, null, null, null,
                "MOCK", null, null, 1L, true, null, System.currentTimeMillis()))
                .when(directory).forHandoff(eq("UAV_PUNISHMENT"), eq(recipient));
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(call -> {
            var at = ((HandoffChannelPort.HandoffDispatch) call.getArgument(0)).at();
            return new DeliveryOutcome("DELIVERED", "PENDING", null, null, at, at, null);
        });
        return recipient;
    }

    private void prepareCurrentDisposeFacts() {
        String target = UUID.randomUUID().toString();
        var now = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);
        jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','mock','seed-stage3-org','seed-stage3-district',?,?)", target, "QA-SUPP-" + target, now, now, now, now);
        jdbc.update("insert into target_latest_state(target_id,observed_at,received_at,created_at,updated_at) values(?,?,?,?,?)", target, now, now, now, now);
        jdbc.update("update alarm set target_id=? where alarm_id=(select alarm_id from uav_event where event_id=?)", target, eventId);
        jdbc.update("delete from automation_rule_condition where category='dispose'");
        jdbc.update("update automation_rule_group set version=version+1,scope_mode='ALL',schedule_mode='ALL_DAY' where category='dispose'");
        jdbc.update("insert into automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) values(?,'dispose','QA事件关联','eventLink','风险与当前目标已关联',0,true,0,0,'QA')", UUID.randomUUID().toString());
    }

    private String handoffId() { return jdbc.queryForObject("select handoff_id from handoff where event_id=?", String.class, eventId); }
    private JsonNode advisory() throws Exception {
        return body(mvc.perform(get("/api/v1/uav-events/{id}/advisory", eventId).header("Authorization", "Bearer " + submitter))
                .andExpect(status().isOk())).path("data");
    }
    private String frozen(String id) { return jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?", String.class, id); }
    private void manualNotify(String id, int attempt) throws Exception {
        mvc.perform(post("/api/v1/handoffs/{id}/notifications", id).header("Authorization", "Bearer " + submitter)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"expected_attempt_no\":" + attempt + "}"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "independent batch={0}")
    @CsvSource({"PASS", "RULE_OFF", "MANUAL_AFTER_WAIT", "AUTO_AFTER_WAIT", "ENGINE_OFF", "FAILED_MANUAL_RETRY", "STALE_DISABLED_RULES", "STALE_VERSION"})
    void independentGenerationDeliveryAndManualRetryBatches(String batch) throws Exception {
        doReturn(!"ENGINE_OFF".equals(batch)).when(policy).enabled();
        jdbc.update("update disposal_authorization set action_type='JAMMING' where subject_id=?", eventId);
        // Auto generation requires exactly one explicit recipient, scoped to this rollback batch.
        jdbc.update("update handoff_recipient set enabled=false where handoff_type='UAV_PUNISHMENT' and recipient_id<>?", recipientId);
        String recipient = recipientId;
        doReturn(new RecipientSnapshot(recipient, "QA合成接收方", null, null, null, null, null,
                "MOCK", null, null, 1L, true, null, System.currentTimeMillis()))
                .when(directory).forHandoff(eq("UAV_PUNISHMENT"), eq(recipient));
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(call -> {
            var at = ((HandoffChannelPort.HandoffDispatch) call.getArgument(0)).at();
            return new DeliveryOutcome("FAILED_MANUAL_RETRY".equals(batch) ? "FAILED" : "DELIVERED",
                    "FAILED_MANUAL_RETRY".equals(batch) ? "NOT_EXPECTED" : "PENDING", null, null, at,
                    "FAILED_MANUAL_RETRY".equals(batch) ? null : at, null);
        });
        if ("RULE_OFF".equals(batch) || batch.endsWith("AFTER_WAIT")) {
            jdbc.update("update automation_rule_condition set enabled=false where category='dispose'");
            evaluator.evaluate("dispose", eventId);
            assertThat(runs.state("dispose", eventId).status()).isEqualTo("PAUSED");
        } else if (!"ENGINE_OFF".equals(batch)) {
            // Persisted PASS is an explicit integration input; rule-evaluator coverage lives in its own suite.
            runs.state("dispose", eventId, 0, null, "PASS", null, "{}", "qa-synthetic-pass");
        }
        if ("STALE_DISABLED_RULES".equals(batch)) {
            jdbc.update("update automation_rule_condition set enabled=false where category='dispose'");
        } else if ("STALE_VERSION".equals(batch)) {
            jdbc.update("update automation_rule_group set version=version+1 where category='dispose'");
        } else if ("PASS".equals(batch) || "FAILED_MANUAL_RETRY".equals(batch)) {
            doReturn(new Decision("PASS", "隔离测试当前资格", java.util.List.of(), java.util.Map.of(), null))
                    .when(eligibility).check("dispose", eventId);
        }
        handoffs.automaticAfterJamming(eventId);
        String handoff = jdbc.queryForObject("select handoff_id from handoff where event_id=?", String.class, eventId);
        String frozen = jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?", String.class, handoff);
        boolean waiting = "RULE_OFF".equals(batch) || batch.endsWith("AFTER_WAIT") || batch.startsWith("STALE_");
        assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id=?", String.class, handoff))
                .isEqualTo(waiting ? "PENDING_DELIVERY" : "FAILED_MANUAL_RETRY".equals(batch) ? "FAILED" : "DELIVERED");
        assertThat(jdbc.queryForObject("select trigger_source from handoff where handoff_id=?", String.class, handoff)).isEqualTo("JAMMING_COMPLETED");
        // 交接已建立但还没发出时，页面写“还没发出”，不能说成已移送。
        JsonNode progress = advisory().path("auto_handoff");
        assertThat(progress.path("status").asText()).isEqualTo(waiting ? "PENDING" : "FAILED_MANUAL_RETRY".equals(batch) ? "FAILED" : "SUBMITTED");
        assertThat(progress.path("trigger_source").asText()).isEqualTo("JAMMING_COMPLETED");
        if (waiting) assertThat(progress.path("reason").asText()).contains("没有自动发出");
        assertThat(jdbc.queryForObject("select submitted_by from handoff where handoff_id=?", String.class, handoff))
                .isEqualTo(jdbc.queryForObject("select requested_by from disposal_authorization where subject_id=?", String.class, eventId));
        handoffs.automaticAfterJamming(eventId);
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?", Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, handoff)).isEqualTo(1);
        verify(channel, times(waiting ? 0 : 1)).deliver(any());
        if ("AUTO_AFTER_WAIT".equals(batch)) {
            runs.state("dispose", eventId, 0, null, "PASS", null, "{}", "qa-current-pass");
            doReturn(new Decision("PASS", "隔离测试当前资格", java.util.List.of(), java.util.Map.of(), null))
                    .when(eligibility).check("dispose", eventId);
            handoffs.automaticAfterJamming(eventId);
            assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, handoff)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id=? and attempt_no=2", String.class, handoff)).isEqualTo("DELIVERED");
            handoffs.automaticAfterJamming(eventId);
            verify(channel, times(1)).deliver(any());
        }
        if ("MANUAL_AFTER_WAIT".equals(batch) || "FAILED_MANUAL_RETRY".equals(batch)) {
            doAnswer(call -> {
                var at = ((HandoffChannelPort.HandoffDispatch) call.getArgument(0)).at();
                return new DeliveryOutcome("DELIVERED", "PENDING", null, null, at, at, null);
            }).when(channel).deliver(any());
            mvc.perform(post("/api/v1/handoffs/{id}/notifications", handoff).header("Authorization", "Bearer " + submitter)
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                    .content("{\"expected_attempt_no\":1}"))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, handoff)).isEqualTo(2);
            handoffs.automaticAfterJamming(eventId);
            verify(channel, times(waiting ? 1 : 2)).deliver(any());
        }
        assertThat(jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?", String.class, handoff)).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where event_id=?", Integer.class, eventId)).isZero();
    }
}
