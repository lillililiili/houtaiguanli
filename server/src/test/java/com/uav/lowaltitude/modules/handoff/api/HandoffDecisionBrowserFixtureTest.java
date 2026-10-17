package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation;
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Position;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.handoff.application.HandoffFollowupService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Explicitly opted-in browser server in an owned disposable schema. No development seeders,
 * imported sessions, scheduled jobs, device transport, or external notification transport run.
 * The browser operator logs in normally using the throwaway test administrator password supplied
 * through QA_HANDOFF_BROWSER_PASSWORD; neither credentials nor sessions enter the manifest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
@EnabledIfSystemProperty(named = "qa.handoff.decision.browser", matches = "true")
class HandoffDecisionBrowserFixtureTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    private static final String DECLINE_REASON = "合成验收：现场处置已结束，本次不通知";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        DATABASE.springProperties(properties);
        // The shared helper normally supports seeded API tests; browser acceptance explicitly does not.
        properties.add("app.dev-seed.enabled", () -> false);
        properties.add("app.bootstrap-admin.enabled", () -> true);
        properties.add("app.bootstrap-admin.account", () -> "admin1");
        properties.add("app.bootstrap-admin.name", () -> "合成验收管理员");
        properties.add("app.bootstrap-admin.password", HandoffDecisionBrowserFixtureTest::browserPassword);
        properties.add("app.super-admin.account", () -> "admin1");
        properties.add("app.super-admin-recovery.enabled", () -> false);
        properties.add("app.notifications.transport", () -> "mock");
        properties.add("app.handoff.channel", () -> "none");
    }

    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired HandoffFollowupService followups;
    @Autowired AutoVoiceRepository voice;
    @MockBean HandoffChannelPort channel;
    @LocalServerPort int port;

    @Test
    void servePendingNotificationDecisions() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .matches("stage456_verify_[a-z0-9_]+");
        assertThat(environment.getProperty("app.dev-seed.enabled", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm", Integer.class)).isZero();
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(invocation -> {
            var at = ((HandoffChannelPort.HandoffDispatch) invocation.getArgument(0)).at();
            return new DeliveryOutcome("DELIVERED", "PENDING", null, null, at, at, null);
        });

        String admin = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        // This is the newly bootstrapped account in this fixture's owned schema, never a real account.
        jdbc.update("UPDATE app_user SET must_change_password=FALSE WHERE user_id=?", admin);
        String org = id(), district = id(), recipient = id();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,'QA合成验收单位',TRUE,0,0,0)", org, org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,'QA合成验收区域',TRUE,0,0,0)", district, district);
        jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,'QA合成处罚接收单位','UAV_PUNISHMENT',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", recipient);
        jdbc.update("INSERT INTO notification_setting(setting_id,purpose,routing_key,recipient_org_id,recipient_id,channel_type,enabled,created_at,updated_at,version) VALUES(?,'UAV_PUNISHMENT',?,?,?,'MOCK',TRUE,0,0,0)", id(), "QA_SYNTHETIC_PUNISHMENT:" + org, org, recipient);

        var notify = pending("QA合成-通知", "mock", org, district, admin);
        var decline = pending("QA合成-不通知", "replay", org, district, admin);
        var blocked = pending("QA合成-真实来源通道阻断", "live", org, district, admin);
        var samples = List.of(notify, decline, blocked);
        var snapshots = new LinkedHashMap<String, String>();
        for (var sample : samples) {
            String handoff = sample.get("handoff_id");
            snapshots.put(handoff, frozen(handoff));
            assertThat(decision(handoff)).containsEntry("state", "PENDING_DECISION");
            assertThat(deliveryCount(handoff)).isEqualTo(1);
        }
        verify(channel, never()).deliver(any());

        Path folder = Path.of("target", "handoff-decision-browser").toAbsolutePath();
        Files.createDirectories(folder);
        String run = id();
        Path stop = folder.resolve(run + ".stop");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("run_id", run);
        manifest.put("phase", "BEFORE");
        manifest.put("port", port);
        manifest.put("base_url", "http://127.0.0.1:" + port);
        manifest.put("login_account", "admin1");
        manifest.put("synthetic_fixture", true);
        manifest.put("dev_seed_enabled", false);
        manifest.put("stop_file", stop.toString());
        manifest.put("notify", notify);
        manifest.put("decline", decline);
        manifest.put("blocked", blocked);
        manifest.put("decline_reason", DECLINE_REASON);
        manifest.put("instruction", "正常登录，在处罚移送页分别人工通知与填写原因不通知；确认真实来源模拟通道阻断，刷新/重登不重复发送。完成后写入 stop_file。");
        json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("manifest.json").toFile(), manifest);

        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) {
            verify(channel, atMost(1)).deliver(any());
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("state.json").toFile(),
                    Map.of("run_id", run, "notify", decision(notify.get("handoff_id")),
                            "decline", decision(decline.get("handoff_id")),
                            "blocked", decision(blocked.get("handoff_id"))));
            Thread.sleep(1000);
        }
        assertThat(Files.exists(stop)).as("Browser owner must explicitly finish within 30 minutes").isTrue();

        String notified = notify.get("handoff_id"), declined = decline.get("handoff_id"), guarded = blocked.get("handoff_id");
        assertThat(decision(notified)).containsEntry("state", "NOTIFY");
        var declinedDecision = decision(declined);
        assertThat(declinedDecision).containsEntry("state", "DO_NOT_NOTIFY").containsEntry("reason", DECLINE_REASON);
        assertThat(declinedDecision.get("decided_by")).isEqualTo(admin);
        assertThat(declinedDecision.get("decided_at")).isNotNull();
        assertThat(deliveryCount(notified)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM handoff_delivery WHERE handoff_id=? AND attempt_no=2", String.class, notified)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT receipt_status FROM handoff_delivery WHERE handoff_id=? AND attempt_no=2", String.class, notified)).isEqualTo("PENDING");
        assertThat(deliveryCount(declined)).isEqualTo(1);
        assertThat(deliveryCount(guarded)).isEqualTo(1);
        assertThat(decision(guarded)).containsEntry("state", "PENDING_DECISION");
        for (var sample : samples) {
            String handoff = sample.get("handoff_id");
            followups.create(sample.get("event_id"));
            assertThat(frozen(handoff)).isEqualTo(snapshots.get(handoff));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE event_id=?", Integer.class, sample.get("event_id"))).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Integer.class)).isZero();
        verify(channel, times(1)).deliver(argThat(dispatch -> notified.equals(dispatch.handoffId())));
        verify(channel, times(1)).deliver(any());
        manifest.put("phase", "COMPLETE");
        manifest.put("verified_channel_calls", 1);
        manifest.put("notify_result", decision(notified));
        manifest.put("decline_result", declinedDecision);
        json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("manifest.json").toFile(), manifest);
    }

    /** Creates a synthetic confirmed event and a persisted, explicit LEFT fact; entry uses production service. */
    private Map<String, String> pending(String label, String mode, String org, String district, String admin) {
        String source = id(), target = id(), alarm = id(), event = id();
        long now = System.currentTimeMillis();
        Timestamp at = Timestamp.from(Instant.ofEpochMilli(now));
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,?,?,?,0)", source, source, label + "来源", mode, at, at);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'UAV',?,?,?,?,?,?,?,0)", target, label, at, at, mode, org, district, at, at);
        jdbc.update("INSERT INTO target_latest_state(target_id,location,observed_at,received_at,created_at,updated_at) VALUES(?,CAST('SRID=4326;POINT(118.80 37.60)' AS GEOMETRY),?,?,?,?)", target, at, at, at, at);
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,?,'UAV_INTRUSION','HIGH',?,?,?,?,?,?)", alarm, target, source, label, at, at, mode, org, district, at);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'CONFIRMED',?,?,?,?,1)", event, alarm, org, district, at, at);
        jdbc.update("INSERT INTO uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) VALUES(?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','QA合成确认属实',?,?)", id(), event, admin, at);
        voice.initialize(event, now, "QA_SYNTHETIC_BROWSER");
        assertThat(voice.recordDeparture(new DepartureObservation(event, "VOICE", "RECORDED", "LEFT", now - 1000, now - 1000, now,
                new Position(now, new BigDecimal("118.80"), new BigDecimal("37.60")), "QA合成有效位置明确已飞离；未实际拨号"))).isTrue();
        followups.create(event);
        followups.create(event);
        String handoff = jdbc.queryForObject("SELECT handoff_id FROM handoff WHERE event_id=?", String.class, event);
        assertThat(jdbc.queryForObject("SELECT trigger_source FROM handoff WHERE handoff_id=?", String.class, handoff)).isEqualTo("FIELD_HANDLING_ENDED");
        return Map.of("label", label, "source_mode", mode, "target_id", target, "event_id", event, "handoff_id", handoff);
    }

    private Map<String, Object> decision(String handoff) {
        return jdbc.queryForMap("SELECT state,version,reason,decided_by,decided_by_name,decided_at FROM handoff_notification_decision WHERE handoff_id=?", handoff);
    }
    private int deliveryCount(String handoff) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=?", Integer.class, handoff);
    }
    private String frozen(String handoff) {
        return jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?", String.class, handoff);
    }
    private static String browserPassword() {
        String value = System.getenv("QA_HANDOFF_BROWSER_PASSWORD");
        if (value == null || value.isBlank()) throw new IllegalStateException("QA_HANDOFF_BROWSER_PASSWORD is required; use a throwaway fixture-only password");
        return value;
    }
    private static String id() { return UUID.randomUUID().toString(); }
}
