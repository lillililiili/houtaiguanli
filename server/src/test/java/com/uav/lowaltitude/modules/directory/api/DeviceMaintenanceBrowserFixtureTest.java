package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;

/** Opt-in browser fixture. Only the disposable H2 test database is modified. */
@EnabledIfSystemProperty(named = "qa.maintenance.browser", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:maintenance_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.handoff.channel=none", "app.flight.status-advance.enabled=false",
        "app.live-device.enabled=false", "app.mqtt.enabled=false" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeviceMaintenanceBrowserFixtureTest extends DeviceMaintenanceWorkflowApiTest {
    @LocalServerPort int port;
    private String activeCommission;
    private String activeCommand;
    private String activeTracking;
    private String hiddenScopeOrg;

    void verifyBrowserDatabase(String url) {
        assertThat(url).startsWith("jdbc:h2:mem:maintenance_browser");
    }

    void prepareBrowserChecks() { }

    String browserDatabaseLabel() { return "isolated H2 memory"; }

    @Override void cleanCommittedFixture() {
        // This whole in-memory database disappears with the opt-in JVM.
    }

    @Test void verifyFixtureConditions() throws Exception {
        create(); action("START", 1, null);
        for (String kind : java.util.List.of("COMMAND", "TRACKING", "COMMISSION")) {
            applyScenario("ACTIVE_" + kind);
            assertThat(workflow().path("allowed_actions").toString()).doesNotContain("SUBMIT_VERIFICATION");
            applyScenario("CLOSED_" + kind);
            assertThat(workflow().path("allowed_actions").toString()).contains("SUBMIT_VERIFICATION");
        }
        applyScenario("DELETED_DEVICE");
        assertThat(jdbc.queryForObject("SELECT enabled FROM ops_device WHERE device_id=?", Boolean.class, device)).isFalse();
        applyScenario("RESTORED_DEVICE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_id=? AND deleted_at IS NULL AND enabled=TRUE", Long.class, device)).isEqualTo(1);
        applyScenario("LEGACY");
        assertThat(workflow().path("state").asText()).isEqualTo("LEGACY_HANDLED");
        assertThat(workflow().path("allowed_actions")).isEmpty();
    }

    @Test void verifyNotificationFixtureConditions() throws Exception {
        create();
        applyScenario("NOTICE_SUBMITTED");
        var submitted = resend(1, "隔离等待渠道结果", UUID.randomUUID().toString());
        assertThat(submitted.path("notification_delivery_status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("can_resend_notification").asBoolean()).isFalse();
        applyScenario("LEGACY");
        applyScenario("NOTICE_DELIVERED");
        applyScenario("NEW_TASK");
        applyScenario("NOTICE_FAILED");
        var failed = resend(1, "隔离失败场景", UUID.randomUUID().toString());
        assertThat(failed.path("notification_delivery_status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("notification_attempts").get(1).path("delivery_status").asText()).isEqualTo("DELIVERED");
        applyScenario("NOTICE_DELIVERED");
        var delivered = resend(2, "隔离再次送达", UUID.randomUUID().toString());
        assertThat(delivered.path("notification_delivery_status").asText()).isEqualTo("DELIVERED");
        applyScenario("NOTICE_UNKNOWN");
        var unknown = resend(3, "隔离结果未知", UUID.randomUUID().toString());
        assertThat(unknown.path("notification_attempts").get(0).path("outcome_state").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("can_resend_notification").asBoolean()).isFalse();
    }

    @Test void serveBrowserFixture() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            verifyBrowserDatabase(connection.getMetaData().getURL());
        }
        Path directory = Path.of("target", "maintenance-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path control = directory.resolve("control.json");
        Files.deleteIfExists(control);
        jdbc.update("UPDATE ops_device SET name='QA浏览器隔离模拟设备',source_mode='mock',simulated=TRUE WHERE device_id=?", device);
        create();
        healthy();
        prepareBrowserChecks();
        String lastId = "initial";
        manifest(directory, lastId, "READY");
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(30).toNanos();
        try {
            while (System.nanoTime() < deadline) {
                if (Files.exists(control)) {
                    var command = json.readTree(Files.readString(control));
                    String id = command.path("id").asText();
                    if (!id.equals(lastId)) {
                        String scenario = command.path("scenario").asText();
                        if ("STOP".equals(scenario)) { manifest(directory, id, "STOPPED"); return; }
                        applyScenario(scenario);
                        lastId = id;
                        manifest(directory, id, scenario);
                    }
                }
                Thread.sleep(250);
            }
            manifest(directory, lastId, "EXPIRED");
        } finally {
            Files.deleteIfExists(control);
        }
    }

    private void manifest(Path directory, String id, String scenario) throws Exception {
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of(
                "port", port, "task_id", taskId, "device_id", device, "plan_id", plan,
                "command_id", id, "scenario", scenario, "clock", now,
                "database", browserDatabaseLabel(), "simulated", true)));
    }

    private void applyScenario(String scenario) throws Exception {
        if ("TICK".equals(scenario)) { now += 1000; clock.setNow(Instant.ofEpochMilli(now)); return; }
        if ("RESTORE_SCOPE".equals(scenario)) {
            if (hiddenScopeOrg != null) jdbc.update("UPDATE app_org SET enabled=TRUE WHERE org_id=?", hiddenScopeOrg);
            sqlSession.clearCache(); return;
        }
        if ("NEW_TASK".equals(scenario)) { observe(true, now); create(); prepareBrowserChecks(); return; }
        if ("RESTORED_DEVICE".equals(scenario)) {
            jdbc.update("UPDATE ops_device SET deleted_at=NULL,enabled=TRUE WHERE device_id=?", device);
            return;
        }
        if ("EXPIRED_PASS".equals(scenario)) { now += 300001; clock.setNow(Instant.ofEpochMilli(now)); }
        healthy();
        switch (scenario) {
            case "HEALTHY", "EXPIRED_PASS" -> { }
            case "NOTICE_FAILED", "NOTICE_UNKNOWN", "NOTICE_SUBMITTED", "NOTICE_DELIVERED" -> {
                now += 61000;
                clock.setNow(Instant.ofEpochMilli(now));
                jdbc.update("UPDATE ops_device_state SET health_code='BAD',observed_at=?,last_heartbeat_at=? WHERE device_id=?", now, now, device);
                observe(true, now);
                prepareBrowserChecks();
                if ("NOTICE_UNKNOWN".equals(scenario)) {
                    doThrow(new IllegalStateException("隔离模拟通知结果未知")).when(channel).deliver(any());
                } else {
                    String status = scenario.substring("NOTICE_".length());
                    var at = clock.now().atOffset(java.time.ZoneOffset.UTC);
                    doReturn(new DeliveryOutcome(status, "FAILED".equals(status) ? "NOT_EXPECTED" : "PENDING", null,
                            "FAILED".equals(status) ? "隔离模拟渠道失败" : null,
                            "FAILED".equals(status) ? null : at, "DELIVERED".equals(status) ? at : null, null))
                            .when(channel).deliver(any());
                }
            }
            case "HIDE_SCOPE" -> {
                hiddenScopeOrg = jdbc.queryForObject("SELECT owner_org_id FROM ops_device_maintenance_task WHERE task_id=?", String.class, taskId);
                jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=?", hiddenScopeOrg);
            }
            case "DISABLED" -> jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?", device);
            case "OFFLINE" -> jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE' WHERE device_id=?", device);
            case "BAD", "DEGRADED", "UNKNOWN" -> jdbc.update("UPDATE ops_device_state SET health_code=? WHERE device_id=?", scenario, device);
            case "ALARM" -> jdbc.update("UPDATE ops_device_state SET has_alarm=TRUE WHERE device_id=?", device);
            case "PRE_REPORT" -> jdbc.update("UPDATE ops_device_state SET observed_at=(SELECT reported_at-1 FROM ops_device_maintenance_task WHERE task_id=?) WHERE device_id=?", taskId, device);
            case "STALE" -> jdbc.update("UPDATE ops_device_state SET observed_at=?,last_heartbeat_at=? WHERE device_id=?", now-300001, now-300001, device);
            case "FUTURE" -> jdbc.update("UPDATE ops_device_state SET observed_at=?,last_heartbeat_at=? WHERE device_id=?", now+1, now+1, device);
            case "WRONG_SOURCE" -> jdbc.update("UPDATE ops_device SET source_mode='replay' WHERE device_id=?", device);
            case "WRONG_SIMULATED" -> jdbc.update("UPDATE ops_device SET simulated=FALSE WHERE device_id=?", device);
            case "OPEN_INCIDENT" -> {
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO device_incident(incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) VALUES(?,?,?,'OTHER','LOW','PENDING',?,'隔离模拟未恢复异常',TRUE)", id, device, "INC-"+id, now);
            }
            case "ACTIVE_COMMISSION" -> activeCommission = commission(device, "mock", true, "CREATED");
            case "CLOSED_COMMISSION" -> {
                if (activeCommission != null) jdbc.update("UPDATE commission_task SET status='CANCELLED',finished_at=? WHERE commission_id=?", now, activeCommission);
            }
            case "ACTIVE_COMMAND" -> {
                activeCommand = UUID.randomUUID().toString();
                String actor = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
                jdbc.update("INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,?,'EO_BEGIN_TRACK','隔离活动指令夹具','SENT','mock',TRUE,?,?)", activeCommand, activeCommand, device, actor, now, now);
            }
            case "CLOSED_COMMAND" -> {
                if (activeCommand != null) jdbc.update("UPDATE device_command SET status='CANCELLED',completed_at=? WHERE command_id=?", now, activeCommand);
            }
            case "ACTIVE_TRACKING" -> {
                activeTracking = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO eo_tracking_task(task_id,ops_device_id,status,notes,bootstrap_json,created_at) VALUES(?,?,'OPEN','隔离活动跟踪夹具','{}',?)", activeTracking, device, now);
            }
            case "CLOSED_TRACKING" -> {
                if (activeTracking != null) jdbc.update("UPDATE eo_tracking_task SET status='ENDED',ended_at=? WHERE task_id=?", now, activeTracking);
            }
            case "LEGACY" -> jdbc.update("UPDATE ops_device_maintenance_task SET status='HANDLED',workflow_state='LEGACY_HANDLED',active_key=NULL,handled_by=reported_by,handled_by_name=reported_by_name,handled_at=?,handling_note='隔离历史反馈，不补造恢复结论' WHERE task_id=?", now, taskId);
            case "DELETED_DEVICE" -> jdbc.update("UPDATE ops_device SET enabled=FALSE,deleted_at=? WHERE device_id=?", now, device);
            default -> throw new IllegalArgumentException("Unknown isolated fixture scenario: " + scenario);
        }
        sqlSession.clearCache();
    }
}
