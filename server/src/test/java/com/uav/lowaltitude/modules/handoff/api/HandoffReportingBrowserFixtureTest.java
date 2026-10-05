package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in disposable PostgreSQL browser server. All legal/receipt facts are clearly synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@EnabledIfSystemProperty(named = "qa.handoff.reporting.browser", matches = "true")
class HandoffReportingBrowserFixtureTest extends HandoffPunishmentMaterialsApiTest {
    private static final DeviceMonitoringPostgresFixture DB = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DB.springProperties(registry); }
    @AfterAll static void closeDatabase() { DB.close(); }
    @Override @AfterEach void cleanup() { } // Whole owned schema is dropped, including immutable histories.
    @Autowired HandoffSubmissionService handoffs;
    @Autowired AutomationRuntimeService evaluator;
    @Autowired AutomationRuntimeRepository runs;
    @SpyBean AutomationRuntimePolicy policy;
    @SpyBean NotificationDirectoryService directory;
    @MockBean HandoffChannelPort channel;
    @LocalServerPort int port;
    private volatile String deliveryStatus = "DELIVERED";

    /** R14 diagnostic-only browser fixture: never advances into manual creation or submission. */
    @Test
    @EnabledIfSystemProperty(named = "qa.handoff.r14.browser", matches = "true")
    void serveMultipleRecipientReadOnlyProgress() throws Exception {
        Map<String, Object> sample = prepareCompletionSample("MULTIPLE_RECIPIENTS");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=? and action_type='JAMMING' and status='COMPLETED'",
                Integer.class, eventId)).isGreaterThan(0);
        Map<String, Object> before = r14ReadOnlySnapshot();
        var progress = readR14BlockedProgress();
        assertThat(r14ReadOnlySnapshot()).isEqualTo(before);
        Path folder = Path.of("target", "handoff-r14-browser").toAbsolutePath();
        Files.createDirectories(folder);
        Files.deleteIfExists(folder.resolve("stop"));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("port", port);
        manifest.put("stage", "MULTIPLE_RECIPIENTS");
        manifest.put("phase", "BEFORE");
        manifest.put("sample", sample);
        manifest.put("synthetic_fixture", true);
        manifest.put("api_verify_only", false);
        manifest.put("auto_handoff", progress);
        manifest.put("snapshot_before", before);
        manifest.put("instruction", "在同一事件告警详情读取处罚移送进度：应明确接收方不唯一，无人工创建入口；刷新和重登后仍无交接/设备指令。核对完写stop结束。");
        Files.writeString(folder.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(30).toNanos();
        int reads = 1;
        while (!Files.exists(folder.resolve("stop")) && System.nanoTime() < deadline) {
            assertThat(readR14BlockedProgress()).isEqualTo(progress);
            assertThat(r14ReadOnlySnapshot()).isEqualTo(before);
            reads++;
            Files.writeString(folder.resolve("state.json"), objectMapper.writeValueAsString(Map.of(
                    "event_id", eventId, "reads", reads, "auto_handoff", progress, "snapshot", r14ReadOnlySnapshot())));
            Thread.sleep(1000);
        }
        assertThat(Files.exists(folder.resolve("stop"))).as("Browser owner must explicitly finish R14 read-only inspection").isTrue();
        assertThat(readR14BlockedProgress()).isEqualTo(progress);
        assertThat(r14ReadOnlySnapshot()).isEqualTo(before);
        verify(channel, never()).deliver(any());
        manifest.put("phase", "COMPLETE");
        manifest.put("snapshot_after", r14ReadOnlySnapshot());
        manifest.put("reads", reads + 1);
        Files.writeString(folder.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
        Files.deleteIfExists(folder.resolve("stop"));
    }

    private com.fasterxml.jackson.databind.JsonNode readR14BlockedProgress() throws Exception {
        var progress = body(mvc.perform(get("/api/v1/uav-events/{id}/advisory", eventId)
                .header("Authorization", "Bearer " + submitter)).andExpect(status().isOk()))
                .path("data").path("auto_handoff");
        assertThat(progress.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(progress.path("reason").asText()).contains("无法确定唯一接收方");
        assertThat(progress.hasNonNull("handoff_id")).isFalse();
        assertThat(progress.toString()).doesNotContain("QA-MULTIPLE_RECIPIENTS-接收方A", "QA-MULTIPLE_RECIPIENTS-接收方B", "pm-recipient-");
        return progress;
    }

    private Map<String, Object> r14ReadOnlySnapshot() {
        return Map.of(
                "event_version", jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId),
                "handoffs", jdbc.queryForObject("select count(*) from handoff", Long.class),
                "deliveries", jdbc.queryForObject("select count(*) from handoff_delivery", Long.class),
                "frozen_materials", jdbc.queryForObject("select count(*) from handoff_material_snapshot", Long.class),
                "commands", jdbc.queryForObject("select count(*) from device_command", Long.class),
                "authorizations", jdbc.queryForObject("select count(*) from disposal_authorization", Long.class));
    }

    /**
     * Browser completion for main 99 and supplemental R12/R14. New manual creation was withdrawn
     * from the frontend on 2026-09-17; the two CREATE stages deliberately use the existing API and
     * must never be reported as a successful browser creation flow. Every transport is synthetic.
     */
    @Test void serveManualHandoffCompletionMatrix() throws Exception {
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(call -> {
            var at = ((HandoffChannelPort.HandoffDispatch) call.getArgument(0)).at();
            String outcome = deliveryStatus;
            return new DeliveryOutcome(outcome, "FAILED".equals(outcome) ? "NOT_EXPECTED" : "PENDING",
                    null, "FAILED".equals(outcome) ? "QA显式失败，可人工重试" : null, at,
                    "DELIVERED".equals(outcome) ? at : null, null);
        });
        doAnswer(call -> new RecipientSnapshot(call.getArgument(1), "QA合成处罚接收方", null, null, null, null, null,
                "MOCK", null, null, 1L, true, null, System.currentTimeMillis()))
                .when(directory).forHandoff(anyString(), anyString());
        Path folder = Path.of("target", "handoff-completion-browser").toAbsolutePath();
        Files.createDirectories(folder);
        Files.deleteIfExists(folder.resolve("advance"));
        Files.deleteIfExists(folder.resolve("stop"));
        var completed = new ArrayList<Map<String, Object>>();
        var stages = List.of("RULE_OFF", "ENGINE_OFF", "FAILED_RETRY", "MANUAL_NO_DISPOSAL", "MULTIPLE_RECIPIENTS");
        boolean verifyOnly = Boolean.getBoolean("qa.handoff.reporting.verify-only");
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(40).toNanos();
        for (String stage : stages) {
            if (!completed.isEmpty()) fixture();
            Map<String, Object> sample = prepareCompletionSample(stage);
            String sourceEvent = eventId;
            int commandsBefore = jdbc.queryForObject("select count(*) from device_command", Integer.class);
            writeCompletionManifest(folder, stage, "BEFORE", sample, completed, verifyOnly);
            if (!verifyOnly) awaitCompletionSignal(folder, "advance", deadline);
            boolean apiAssisted = stage.equals("MANUAL_NO_DISPOSAL") || stage.equals("MULTIPLE_RECIPIENTS");
            if (apiAssisted) {
                // Read-only page inspection must not create a handoff or issue a device command.
                assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?", Integer.class, sourceEvent)).isZero();
                String id = body(submit(submitter, sourceEvent).andExpect(status().isCreated()))
                        .path("data").path("handoff_id").asText();
                sample.put("handoff_id", id);
                sample.put("creation_evidence", "EXISTING_API_ASSISTED_NOT_BROWSER_CREATION");
                sample.put("frozen_snapshot", frozenCompletionMaterial(id));
                if (stage.equals("MANUAL_NO_DISPOSAL")) {
                    assertThat(detail(id, submitter).path("material").has("disposals")).isFalse();
                    assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, sourceEvent)).isZero();
                }
                assertThat(jdbc.queryForObject("select recipient_id from handoff where handoff_id=?", String.class, id)).isEqualTo(sample.get("recipient_id"));
            } else if (verifyOnly) {
                String id = (String) sample.get("handoff_id");
                mvc.perform(post("/api/v1/handoffs/{id}/notifications", id).header("Authorization", "Bearer " + submitter)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"expected_attempt_no\":1}")).andExpect(status().isOk());
            }
            assertCompletionSample(stage, sample, commandsBefore);
            writeCompletionManifest(folder, stage, "AFTER", sample, completed, verifyOnly);
            if (!verifyOnly) awaitCompletionSignal(folder, "advance", deadline);
            assertCompletionSample(stage, sample, commandsBefore);
            sample.remove("frozen_snapshot");
            completed.add(new LinkedHashMap<>(sample));
        }
        writeCompletionManifest(folder, "COMPLETE", "COMPLETE", Map.of(), completed, verifyOnly);
        if (!verifyOnly) awaitCompletionSignal(folder, "stop", deadline);
    }

    private Map<String, Object> prepareCompletionSample(String stage) {
        // Read the recipient created by fixture(), without depending on the parent's field visibility.
        // Earlier stages rename their own recipient, so exactly one fresh fixture recipient remains.
        String recipientId = jdbc.queryForObject("select recipient_id from handoff_recipient where recipient_id like 'pm-recipient-%' and display_name='演示处罚接收方'", String.class);
        jdbc.update("update handoff_recipient set enabled=(recipient_id=?) where handoff_type='UAV_PUNISHMENT'", recipientId);
        jdbc.update("update handoff_recipient set display_name=? where recipient_id=?", "QA-" + stage + "-接收方A", recipientId);
        doReturn(!stage.equals("ENGINE_OFF")).when(policy).enabled();
        jdbc.update("update disposal_authorization set action_type='JAMMING' where subject_id=?", eventId);
        jdbc.update("delete from automation_rule_condition where category='dispose'");
        jdbc.update("update automation_rule_group set version=version+1,scope_mode='ALL',schedule_mode='ALL_DAY' where category='dispose'");
        jdbc.update("insert into automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) values(?,'dispose','QA事件关联','eventLink','风险与当前目标已关联',0,?,0,0,'QA')", UUID.randomUUID().toString(), !stage.equals("RULE_OFF"));
        if (stage.equals("MANUAL_NO_DISPOSAL")) eventId = cloneConfirmedEventWithoutDisposal(eventId);
        String target = UUID.randomUUID().toString();
        var now = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);
        jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','mock','seed-stage3-org','seed-stage3-district',?,?)", target, "QA-" + stage, now, now, now, now);
        jdbc.update("insert into target_latest_state(target_id,observed_at,received_at,created_at,updated_at) values(?,?,?,?,?)", target, now, now, now, now);
        jdbc.update("update alarm set target_id=?,source_alarm_id=?,occurred_at=?,received_at=? where alarm_id=(select alarm_id from uav_event where event_id=?)", target, "QA-" + stage, now, now, eventId);
        var sample = new LinkedHashMap<String, Object>();
        sample.put("batch", stage); sample.put("event_id", eventId); sample.put("target_id", target);
        sample.put("source_no", "QA-" + stage); sample.put("recipient_id", recipientId);
        sample.put("engine_enabled", !stage.equals("ENGINE_OFF"));
        sample.put("dispose_rule_enabled", !stage.equals("RULE_OFF"));
        sample.put("synthetic_fixture", true);
        if (stage.equals("MULTIPLE_RECIPIENTS")) {
            String second = "pm-recipient-" + UUID.randomUUID().toString().substring(0, 8);
            jdbc.update("insert into handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values(?,'QA-MULTIPLE_RECIPIENTS-接收方B','UAV_PUNISHMENT',true,current_timestamp,current_timestamp)", second);
            sample.put("other_recipient_id", second);
            assertThat(jdbc.queryForObject("select count(*) from handoff_recipient where handoff_type='UAV_PUNISHMENT' and enabled=true", Integer.class)).isEqualTo(2);
        }
        if (!stage.equals("ENGINE_OFF")) {
            evaluator.evaluate("dispose", eventId);
            assertThat(runs.state("dispose", eventId).status()).isEqualTo(stage.equals("RULE_OFF") ? "PAUSED" : "PASS");
        }
        boolean noAutomatic = stage.equals("MANUAL_NO_DISPOSAL") || stage.equals("MULTIPLE_RECIPIENTS");
        deliveryStatus = stage.equals("ENGINE_OFF") || stage.equals("FAILED_RETRY") ? "FAILED" : "DELIVERED";
        handoffs.automaticAfterJamming(eventId);
        handoffs.automaticAfterJamming(eventId);
        if (noAutomatic) {
            assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?", Integer.class, eventId)).isZero();
            sample.put("initial_delivery_status", "NO_HANDOFF");
            sample.put("browser_creation", "NOT_APPLICABLE_WITHDRAWN_2026_09_17");
        } else {
            String id = jdbc.queryForObject("select handoff_id from handoff where event_id=?", String.class, eventId);
            String expected = stage.equals("RULE_OFF") ? "PENDING_DELIVERY" : "FAILED";
            assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id=?", String.class, id)).isEqualTo(expected);
            sample.put("handoff_id", id); sample.put("initial_delivery_status", expected);
            sample.put("frozen_snapshot", frozenCompletionMaterial(id));
        }
        deliveryStatus = "DELIVERED";
        return sample;
    }

    private String cloneConfirmedEventWithoutDisposal(String original) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String id = "pm-event-" + suffix, alarm = "pm-alarm-" + suffix;
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) select ?,null,source_id,?,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at from alarm where alarm_id=(select alarm_id from uav_event where event_id=?)", alarm, alarm, original);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) select ?,?,'CONFIRMED',owner_org_id,district_id,created_at,updated_at,version from uav_event where event_id=?", id, alarm, original);
        jdbc.update("insert into uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) select ?,?,version,previous_state,resulting_state,conclusion,'QA合成无反制记录事件',actor_id,created_at from uav_event_verification where event_id=?", UUID.randomUUID().toString(), id, original);
        return id;
    }

    private String frozenCompletionMaterial(String id) {
        return jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?", String.class, id);
    }

    private void assertCompletionSample(String stage, Map<String, Object> sample, int commandsBefore) {
        String id = (String) sample.get("handoff_id");
        int attempts = stage.equals("MANUAL_NO_DISPOSAL") || stage.equals("MULTIPLE_RECIPIENTS") ? 1 : 2;
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?", Integer.class, sample.get("event_id"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, id)).isEqualTo(attempts);
        assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id=? and attempt_no=?", String.class, id, attempts)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select receipt_status from handoff_delivery where handoff_id=? and attempt_no=?", String.class, id, attempts)).isEqualTo("PENDING");
        assertThat(frozenCompletionMaterial(id)).isEqualTo(sample.get("frozen_snapshot"));
        assertThat(jdbc.queryForObject("select count(*) from device_command", Integer.class)).isEqualTo(commandsBefore);
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where event_id=?", Integer.class, sample.get("event_id"))).isZero();
    }

    private void writeCompletionManifest(Path folder, String stage, String phase, Map<String, Object> sample,
            List<Map<String, Object>> completed, boolean verifyOnly) throws Exception {
        var visible = new LinkedHashMap<>(sample);
        visible.remove("frozen_snapshot");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("port", port); manifest.put("stage", stage); manifest.put("phase", phase);
        manifest.put("sample", visible); manifest.put("completed", completed);
        manifest.put("synthetic_fixture", true); manifest.put("api_verify_only", verifyOnly);
        manifest.put("advance_action", "BEFORE: 页面补发/重试完成后推进；无交接阶段推进会使用现有API明确选择A创建。AFTER: 只读复查后推进。");
        Files.writeString(folder.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
    }

    private void awaitCompletionSignal(Path folder, String signal, long deadline) throws Exception {
        Path requested = folder.resolve(signal);
        while (!Files.exists(requested) && System.nanoTime() < deadline) {
            if (!signal.equals("stop") && Files.exists(folder.resolve("stop")))
                throw new AssertionError("Browser completion stopped before every isolated stage was inspected");
            Thread.sleep(250);
        }
        assertThat(Files.exists(requested)).as("Browser owner must explicitly provide %s", signal).isTrue();
        Files.deleteIfExists(requested);
    }

    @Test void serveSyntheticHandoffAndReportingMatrix() throws Exception {
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(call -> {
            var at = ((HandoffChannelPort.HandoffDispatch) call.getArgument(0)).at();
            return new DeliveryOutcome(deliveryStatus, "FAILED".equals(deliveryStatus) ? "NOT_EXPECTED" : "PENDING",
                    null, "FAILED".equals(deliveryStatus) ? "QA显式失败" : null, at,
                    "DELIVERED".equals(deliveryStatus) ? at : null, null);
        });
        doAnswer(call -> new RecipientSnapshot(call.getArgument(1), "QA合成处罚接收方", null, null, null, null, null,
                "MOCK", null, null, 1L, true, null, System.currentTimeMillis()))
                .when(directory).forHandoff(anyString(), anyString());
        var batches = new ArrayList<Map<String, Object>>();
        for (String batch : List.of("PASS", "RULE_OFF", "MANUAL_AFTER_WAIT", "ENGINE_OFF", "FAILED_MANUAL_RETRY", "STALE_DISABLED_RULES", "STALE_VERSION", "STALE_FACTS", "FROZEN_THEN_DISABLED_DELIVERED", "FROZEN_THEN_DISABLED_SUBMITTED")) {
            if (!batches.isEmpty()) fixture();
            doReturn(!"ENGINE_OFF".equals(batch)).when(policy).enabled();
            jdbc.update("update disposal_authorization set action_type='JAMMING' where subject_id=?", eventId);
            // Only this batch's newly created recipient is enabled when automatic selection runs.
            String recipient = jdbc.queryForObject("select recipient_id from handoff_recipient where handoff_type='UAV_PUNISHMENT' and recipient_id like 'pm-recipient-%' order by created_at desc fetch first 1 row only", String.class);
            jdbc.update("update handoff_recipient set enabled=(recipient_id=?) where handoff_type='UAV_PUNISHMENT'", recipient);
            String target = UUID.randomUUID().toString();
            var now = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','mock','seed-stage3-org','seed-stage3-district',?,?)", target, "QA-" + batch, now, now, now, now);
            jdbc.update("insert into target_latest_state(target_id,observed_at,received_at,created_at,updated_at) values(?,?,?,?,?)", target, now, now, now, now);
            jdbc.update("update alarm set target_id=? where alarm_id=(select alarm_id from uav_event where event_id=?)", target, eventId);
            jdbc.update("delete from automation_rule_condition where category='dispose'");
            jdbc.update("update automation_rule_group set version=version+1,scope_mode='ALL',schedule_mode='ALL_DAY' where category='dispose'");
            jdbc.update("insert into automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) values(?,'dispose','QA事件关联','eventLink','风险与当前目标已关联',0,true,0,0,'QA')", UUID.randomUUID().toString());
            boolean frozenThenDisabled = batch.startsWith("FROZEN_THEN_DISABLED_");
            if (!"ENGINE_OFF".equals(batch) && !frozenThenDisabled) {
                evaluator.evaluate("dispose", eventId);
                assertThat(runs.state("dispose", eventId).status()).isEqualTo("PASS");
            }
            if (List.of("RULE_OFF", "MANUAL_AFTER_WAIT", "STALE_DISABLED_RULES").contains(batch)) {
                jdbc.update("update automation_rule_condition set enabled=false where category='dispose'");
                if (!batch.startsWith("STALE")) evaluator.evaluate("dispose", eventId);
            }
            if ("STALE_VERSION".equals(batch)) jdbc.update("update automation_rule_group set version=version+1 where category='dispose'");
            if ("STALE_FACTS".equals(batch)) jdbc.update("update target_latest_state set observed_at=? where target_id=?", now.minusMinutes(10), target);
            deliveryStatus = "FAILED_MANUAL_RETRY".equals(batch) ? "FAILED" : "DELIVERED";
            handoffs.automaticAfterJamming(eventId);
            String id = jdbc.queryForObject("select handoff_id from handoff where event_id=?", String.class, eventId);
            String expected = List.of("PASS", "ENGINE_OFF").contains(batch) ? "DELIVERED" : "FAILED_MANUAL_RETRY".equals(batch) ? "FAILED" : "PENDING_DELIVERY";
            assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id=?", String.class, id)).isEqualTo(expected);
            String snapshot = jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?", String.class, id);
            if (frozenThenDisabled) {
                // Facts above are committed; the independent REQUIRES_NEW reader sees the real batch.
                assertThat(jdbc.queryForObject("select count(*) from automation_rule_condition where category='dispose' and enabled=true", Integer.class)).isEqualTo(1);
                evaluator.evaluate("dispose", eventId);
                assertThat(runs.state("dispose", eventId).status()).isEqualTo("PASS");
                jdbc.update("update automation_rule_condition set enabled=false where category='dispose'");
                jdbc.update("update automation_rule_group set version=version+1 where category='dispose'");
                handoffs.automaticAfterJamming(eventId);
                handoffs.automaticAfterJamming(eventId);
                assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select submitted_at from handoff_delivery where handoff_id=?", OffsetDateTime.class, id)).isNull();
                deliveryStatus = batch.endsWith("SUBMITTED") ? "SUBMITTED" : "DELIVERED";
                mvc.perform(post("/api/v1/handoffs/{id}/notifications", id).header("Authorization", "Bearer " + submitter)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"expected_attempt_no\":1}"))
                        .andExpect(status().isOk());
                expected = deliveryStatus;
                mvc.perform(post("/api/v1/handoffs/{id}/notifications", id).header("Authorization", "Bearer " + submitter)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"expected_attempt_no\":2}"))
                        .andExpect(status().isConflict());
                assertThat(jdbc.queryForObject("select delivery_status from handoff_delivery where handoff_id=? and attempt_no=2", String.class, id)).isEqualTo(expected);
            }
            handoffs.automaticAfterJamming(eventId);
            assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?", Integer.class, id)).isEqualTo(frozenThenDisabled ? 2 : 1);
            assertThat(jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?", String.class, id)).isEqualTo(snapshot);
            batches.add(Map.of("batch", batch, "event_id", eventId, "handoff_id", id, "delivery_status", expected, "synthetic_fixture", true));
        }
        // Keep all batch recipients usable for the browser's explicit manual-retry branches.
        jdbc.update("update handoff_recipient set enabled=true where recipient_id like 'pm-recipient-%'");
        deliveryStatus = "DELIVERED";
        String org = prepareStatistics();
        Path folder = Path.of("target", "handoff-reporting-browser").toAbsolutePath();
        Files.createDirectories(folder);
        for (String name : List.of("stop", "issue", "revoke")) Files.deleteIfExists(folder.resolve(name));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("port", port); manifest.put("batches", batches); manifest.put("org_id", org);
        manifest.put("anchor_date", "2005-01-01"); manifest.put("expected_targets", 2); manifest.put("expected_filings", 1);
        manifest.put("decision_stage", "NONE"); manifest.put("synthetic_fixture", true);
        Files.writeString(folder.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
        if (Boolean.getBoolean("qa.handoff.reporting.verify-only")) return;
        boolean issued = false, revoked = false;
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(25).toNanos();
        while (!Files.exists(folder.resolve("stop")) && System.nanoTime() < deadline) {
            if (!issued && Files.exists(folder.resolve("issue"))) {
                issueSyntheticDecision(); issued = true; manifest.put("decision_stage", "FORMAL_SYNTHETIC");
                Files.writeString(folder.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
            }
            if (issued && !revoked && Files.exists(folder.resolve("revoke"))) {
                revokeSyntheticDecision();
                revoked = true; manifest.put("decision_stage", "REVOKED");
                Files.writeString(folder.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
            }
            Thread.sleep(500);
        }
    }

    private String prepareStatistics() {
        String org = UUID.randomUUID().toString();
        var at = OffsetDateTime.parse("2005-01-01T00:00:00+08:00");
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", org, org, "QA合成处罚统计-非真实执法");
        for (int index = 0; index < 2; index++) {
            String id = UUID.randomUUID().toString();
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','live',?,'seed-stage3-district',?,?)", id, "QA统计目标" + index, at, at.plusDays(4), org, at, at);
        }
        jdbc.update("update punishment_case set owner_org_id=?,source_mode='live',filed_at=?,party_type='ORG',party_name='QA合成主体-非真实处罚' where case_id='seed-stage14-case-investigating'", org, at);
        jdbc.update("update handoff set created_at=? where handoff_id='seed-stage14-handoff-punish'", at.minusDays(1));
        return org;
    }

    private void issueSyntheticDecision() {
        var at = OffsetDateTime.parse("2005-01-01T01:00:00+08:00");
        String actor = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        jdbc.update("insert into penalty_rule(rule_code,violation_code,title,legal_basis,fine_min,fine_max,penalty_types,schema_status,enabled,created_at,updated_at) values('QA-BROWSER','OTHER','QA合成规则','合成测试值，不构成法律依据',0,999999,'[\"FINE\"]','CONFIRMED',true,?,?)", at, at);
        jdbc.update("update penalty_discretion set rule_code='QA-BROWSER',penalty_type='FINE',fine_amount=12345,status='CONFIRMED',decided_by=?,decided_at=? where discretion_id='seed-stage14-discretion-draft'", actor, at);
        // Match the state required by the normal issue endpoint; this remains explicitly synthetic legal material.
        jdbc.update("update punishment_case set status='DECIDED',decided_at=?,updated_at=?,version=version+1 where case_id='seed-stage14-case-investigating'",at,at);
        jdbc.update("insert into penalty_decision_document(document_id,document_no,case_id,discretion_id,template_version,status,fields,rendered_sha256,issued_by,issued_at,updated_at) values('qa-browser-formal-doc','QA合成决定-非真实执法','seed-stage14-case-investigating','seed-stage14-discretion-draft','qa-synthetic-v1','ISSUED',CAST('{\"synthetic_fixture\":true}' AS JSON),?,?,?,?)", "a".repeat(64), actor, at, at);
    }

    private void revokeSyntheticDecision() throws Exception {
        String session=UUID.randomUUID().toString();
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) select ?,user_id,?,'127.0.0.1',permission_version from app_user where account='admin1'",session,System.currentTimeMillis()+3600000);
        mvc.perform(post("/api/v1/decision-documents/{id}/revoke","qa-browser-formal-doc")
                .header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content("{\"reason\":\"QA合成撤销\",\"expected_version\":0}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from penalty_decision_document where document_id='qa-browser-formal-doc'",String.class)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select status from punishment_case where case_id='seed-stage14-case-investigating'",String.class)).isEqualTo("DECIDED");
    }
}
