package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Read-only UI matrix backed by completed concurrent channel scenarios in disposable H2 only. */
@EnabledIfSystemProperty(named = "qa.notification.matrix.browser", matches = "true")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:notification_matrix_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "app.live-device.enabled=false",
        "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false",
        "app.advisory.auto-sms.enabled=true", "app.advisory.auto-voice.enabled=true", "app.outbox.enabled=false" })
class DualChannelReceiptBrowserFixtureTest extends DualChannelReceiptApiTest {
    @LocalServerPort int port;

    @Test void serveNotificationMatrix() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:notification_matrix_browser");
        }
        var scenarios = new ArrayList<Map<String, Object>>();
        capture("短信跨事件反序与重复", scenarios, this::matrixSmsOutOfOrderAcrossEventsCannotAdvanceTheOtherEvent);
        capture("电话跨事件反序与重复", scenarios, this::matrixVoiceOutOfOrderAcrossEventsCannotAdvanceTheOtherEvent);
        capture("旧短信旧电话过期后迟到", scenarios, this::matrixExpiredSmsAndVoiceReceiptsCannotOverwriteCurrentBatchOrUnknown);
        capture("实际等待短信3秒电话10秒后位置未知", scenarios, this::realTimeWatchWindowsCompleteOnlyAfterThreeAndTenSeconds);
        Path directory = Path.of("target", "notification-matrix-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path stop = directory.resolve("stop");
        Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of(
                "port", port, "database", "isolated H2 memory", "simulated", true,
                "scenarios", scenarios, "watch_windows", "real 3 seconds SMS and 10 seconds voice; no external channel")));
        var before = eventStates(scenarios);
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        assertThat(Files.exists(stop)).as("Browser owner must explicitly finish inspection").isTrue();
        Files.deleteIfExists(stop);
        assertThat(eventStates(scenarios)).isEqualTo(before);
    }

    private void capture(String label, List<Map<String, Object>> scenarios, Scenario action) throws Exception {
        reset(clock);
        clearSpy();
        var existing = new HashSet<>(jdbc.queryForList("select event_id from uav_event", String.class));
        fixture();
        action.run();
        reset(clock);
        var ids = jdbc.queryForList("select event_id from uav_event", String.class).stream()
                .filter(id -> !existing.contains(id)).toList();
        for (String id : ids) {
            String name = "QA-通知-" + label + "-" + id.substring(0, 6);
            jdbc.update("update target set target_no=? where target_id=(select a.target_id from alarm a join uav_event e on e.alarm_id=a.alarm_id where e.event_id=?)", name, id);
            var item = new LinkedHashMap<String, Object>();
            item.put("label", name); item.put("event_id", id);
            item.put("sms", jdbc.queryForList("select status,updated_at,delivery_record_id from uav_auto_sms_task where event_id=?", id));
            item.put("voice", jdbc.queryForList("select status,playback_completed_at,provider_call_id from uav_auto_voice_task where event_id=?", id));
            scenarios.add(item);
        }
    }
    private List<Map<String, Object>> eventStates(List<Map<String, Object>> scenarios) {
        return scenarios.stream().map(item -> jdbc.queryForMap("select event_id,state_code,version from uav_event where event_id=?", item.get("event_id"))).toList();
    }
    @FunctionalInterface private interface Scenario { void run() throws Exception; }
}
