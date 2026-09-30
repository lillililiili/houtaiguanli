package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Read-only history presentation fixture; never writes a business database or sends devices/notifications. */
@EnabledIfSystemProperty(named = "qa.observation.browser", matches = "true")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:observation_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "app.live-device.enabled=false",
        "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false" })
class HistoricalObservationBrowserFixtureTest extends HandoffPunishmentMaterialsApiTest {
    @LocalServerPort int port;

    @Test void serveFrozenHistory() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:observation_browser");
        }
        String actor = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, submitter);
        jdbc.update("""
                insert into uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,
                    outcome,danger,note,urgent,simulated)
                values(?,?,0,'OBSERVATION',0,?,'STILL_INSIDE','HIGH','隔离夹具：停用前人工观察历史',true,false)
                """, UUID.randomUUID().toString(), eventId, actor);
        try {
            String handoff = body(submit(submitter, eventId).andExpect(status().isCreated())).path("data").path("handoff_id").asText();
            var frozen = detail(handoff, submitter).path("material").path("advisory_records");
            assertThat(frozen).hasSize(1);
            Path directory = Path.of("target", "observation-browser").toAbsolutePath();
            Files.createDirectories(directory);
            Path stop = directory.resolve("stop");
            Files.deleteIfExists(stop);
            Files.writeString(directory.resolve("manifest.json"), objectMapper.writeValueAsString(Map.of(
                    "port", port, "handoff_id", handoff, "event_id", eventId,
                    "database", "isolated H2 memory", "simulated", true)));
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
            while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
            Files.deleteIfExists(stop);
            assertThat(detail(handoff, submitter).path("material").path("advisory_records")).isEqualTo(frozen);
        } finally { jdbc.update("delete from uav_event_advisory where event_id=?", eventId); }
    }
}
