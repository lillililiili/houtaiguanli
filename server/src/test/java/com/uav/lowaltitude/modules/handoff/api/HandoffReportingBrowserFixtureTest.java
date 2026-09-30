package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
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
    private String deliveryStatus = "DELIVERED";

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
