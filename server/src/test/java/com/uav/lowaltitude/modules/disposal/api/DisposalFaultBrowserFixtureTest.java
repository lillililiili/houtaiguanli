package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Explicitly enabled, loopback-only browser evidence in a disposable in-memory database. */
@EnabledIfSystemProperty(named = "qa.disposal.browser", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:disposal_fault_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "app.outbox.enabled=false",
        "app.live-device.enabled=false", "app.mqtt.enabled=false" })
class DisposalFaultBrowserFixtureTest extends EmergencyStopApiTest {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired FixtureClock fixtureClock;

    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        FixtureClock fixtureClock() { return new FixtureClock(); }
    }

    static class FixtureClock extends com.uav.lowaltitude.platform.time.AppClock {
        private volatile java.time.Instant frozenAt;
        void freeze() { frozenAt = java.time.Instant.now(); }
        @Override public java.time.Instant now() { return frozenAt == null ? super.now() : frozenAt; }
        @Override public long nowMillis() { return now().toEpochMilli(); }
    }

    @Test void serveFrozenHandoffFixture() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:disposal_fault_browser");
        }
        directHandoffKeepsInitiatorAndFrozenEvidenceAfterLaterUpload();
        String handoff = jdbc.queryForObject("select handoff_id from handoff where source_id=?", String.class, eventId);
        Path directory = Path.of("target", "disposal-handoff-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path stop = directory.resolve("stop");
        Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of(
                "port", port, "event_id", eventId, "handoff_id", handoff, "database", "isolated H2 memory", "simulated", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        Files.deleteIfExists(stop);
    }

    @Test void serveInvalidEvidenceFixture() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:disposal_fault_browser");
        }
        var cases = new java.util.ArrayList<Map<String, String>>();
        for (String condition : java.util.List.of("STALE", "LEGAL", "UNKNOWN", "INSUFFICIENT", "OTHER_EVENT")) {
            eventId = event();
            everyCounterEntryRejectsInvalidCurrentEvidence("EXECUTE", condition);
            String authorization = jdbc.queryForObject(
                    "select authorization_id from disposal_authorization where subject_id=?", String.class, eventId);
            cases.add(Map.of("condition", condition, "event_id", eventId, "authorization_id", authorization));
        }
        // 页面操作耗时不得把 LEGAL/UNKNOWN 等场景意外变成观测过期；仅固定该隔离夹具时钟。
        fixtureClock.freeze();
        Path directory = Path.of("target", "disposal-evidence-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path stop = directory.resolve("stop");
        Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of(
                "port", port, "cases", cases, "database", "isolated H2 memory", "simulated", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        Files.deleteIfExists(stop);
        for (var sample : cases) {
            assertThat(statusOf(sample.get("authorization_id"))).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("select count(*) from disposal_authorization_event where authorization_id=? and event_kind='EXECUTE'", Integer.class, sample.get("authorization_id"))).isZero();
            assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=(select device_id from disposal_authorization where authorization_id=?)", Integer.class, sample.get("authorization_id"))).isZero();
        }
    }

    @Test void serveFaultFixture() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:disposal_fault_browser");
        }
        // 故障、离线的设备在申请时就被拒绝（BUG-03）；受阻样例改为批准后设备才出状况。
        String authorization = blockedAfterApproval("FAULT");
        eventId = event();
        differentEventsCannotQueueStartsOnTheSameBusyDevice("QUEUED");
        String busyAuthorization = jdbc.queryForObject(
                "select authorization_id from disposal_authorization_event where event_kind='DEVICE_BUSY'",
                String.class);
        eventId = event();
        String offlineAuthorization = blockedAfterApproval("OFFLINE");
        Path directory = Path.of("target", "disposal-fault-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path stop = directory.resolve("stop");
        Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of(
                "port", port, "authorization_id", authorization, "busy_authorization_id", busyAuthorization,
                "offline_authorization_id", offlineAuthorization,
                "database", "isolated H2 memory", "simulated", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        Files.deleteIfExists(stop);
    }
}
