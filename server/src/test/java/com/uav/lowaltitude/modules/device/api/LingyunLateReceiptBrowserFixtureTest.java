package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Opt-in browser fixture, restricted to loopback and a disposable in-memory database. */
@EnabledIfSystemProperty(named = "qa.lingyun.browser", matches = "true")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:lingyun_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "app.outbox.enabled=true",
        "app.live-device.enabled=false", "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false" })
class LingyunLateReceiptBrowserFixtureTest extends LingyunControlMqttTest {
    @LocalServerPort int port;

    @Test void serveReceiptFixture() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:lingyun_browser");
        }
        int caseIndex = 0;
        for (String state : java.util.List.of("TIMED_OUT", "CANCELLED"))
            for (int code : new int[]{0, 1}) {
                if (caseIndex++ > 0) { cleanup(); setup(); }
                lateCorrelatedReplyStaysWithOriginalCommandWithoutChangingNewerCommand(code, state);
            }
        // These are standalone protocol fixtures, not platform disposal authorizations.
        // Keep their external AUTH-LATE reference in lingyun_control_command, and avoid
        // inventing a platform authorization relation in the evidence presentation fixture.
        jdbc.update("update device_command set authorization_id=null where authorization_id in ('AUTH-LATE','AUTH-NEWER')");
        var commands = jdbc.queryForList("""
                select c.command_id,c.status,r.receipt_kind,r.device_result_code
                from device_command c join command_receipt r on r.command_id=c.command_id
                where r.receipt_kind='PROTOCOL_B_LATE' order by c.created_at
                """);
        assertThat(commands).hasSize(4);
        Path directory = Path.of("target", "lingyun-receipt-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path stop = directory.resolve("stop");
        Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), mapper.writeValueAsString(Map.of(
                "port", port, "commands", commands, "database", "isolated H2 memory", "simulated", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        Files.deleteIfExists(stop);
        assertThat(jdbc.queryForObject("select count(*) from command_receipt where receipt_kind='PROTOCOL_B_LATE'", Integer.class)).isEqualTo(4);
    }
}
