package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Controlled presentation inputs, not an accuracy assessment of the legality algorithm. */
@EnabledIfSystemProperty(named = "qa.legality.browser", matches = "true")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:legality_scope_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "app.live-device.enabled=false",
        "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false" })
class LegalityScopeBrowserFixtureTest extends LegalityReviewApiTest {
    @LocalServerPort int port;
    @Override @AfterEach void cleanup() { /* All fixture rows disappear with this in-memory context. */ }

    @Test void serveTypeAndAssuranceMatrix() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:legality_scope_browser");
        }
        var cases = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (String label : java.util.List.of("BIRD", "BALLOON", "KITE", "UNKNOWN", "LEGAL", "ILLEGAL")) {
            if (index++ > 0) fixture();
            String type = "BIRD".equals(label) ? "BIRD" : java.util.List.of("LEGAL", "ILLEGAL").contains(label) ? "UAV" : "UNKNOWN";
            jdbc.update("update target set object_type_code=?,subtype=? where target_id=?", type, label, target);
            String selected = evaluation;
            if ("UAV".equals(type)) {
                selected = "s7r-browser-" + suffix;
                insertEvaluation(selected, run, label, "[]", "ILLEGAL".equals(label) ? "LOW" : null,
                        "ILLEGAL".equals(label) ? BigDecimal.ZERO : null, "SUFFICIENT", OffsetDateTime.parse("2026-09-06T02:00:00Z"), null);
                insertReview(selected, "PENDING_REVIEW", 0);
                // Prepare fresh synthetic inputs before exposing the disposable fixture.
                // This does not mutate any production or frozen historical material.
                jdbc.update("update rule_evaluation set hit_details=CAST('[]' AS JSON) where evaluation_id=?", selected);
                jdbc.update("update rule_set_version set param_status='CONFIRMED' where rule_set_version_id=(select rule_set_version_id from rule_evaluation where evaluation_id=?)", selected);
            }
            cases.add(Map.of("label", label, "target_id", target, "evaluation_id", selected, "object_type_code", type));
        }
        Path directory = Path.of("target", "legality-scope-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path stop = directory.resolve("stop"); Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), json.writeValueAsString(Map.of("port", port, "cases", cases, "simulated", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        Files.deleteIfExists(stop);
        assertThat(jdbc.queryForObject("select count(*) from legality_review_history where evaluation_id like 's7r-browser-%'", Integer.class)).isZero();
    }
}
