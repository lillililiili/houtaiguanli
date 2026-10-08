package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.platform.worker.Item4HttpProcess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** R02 persistence: actual cold HTTP processes read the same committed authorization. */
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "ITEM4_RESTART_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_item4_[a-z0-9_]+")
class Item4AuthorizationRecoveryPostgresTest extends DisposalMqttFlowTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.mqtt.enabled", () -> true);
        registry.add("app.outbox.enabled", () -> true);
        registry.add("app.dev-seed.password", () -> "changeme");
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @RepeatedTest(3)
    void savedApplicationAndApprovalSurviveProcessCrashesWithoutAnExecutionRequest() throws Exception {
        String id = data(request("/api/v1/disposal-authorizations", requester, key(), body("模拟申请审批持久化中断"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        String initial = statusOf(id);
        String url;
        try (var connection = jdbc.getDataSource().getConnection()) { url = connection.getMetaData().getURL(); }
        Path output = Path.of("target", "item4-authorization-restart", UUID.randomUUID().toString()).toAbsolutePath();
        try (var child = Item4HttpProcess.start(url, output, "application-saved")) {
            var read = child.request(requester, "GET", "/api/v1/disposal-authorizations/" + id, null);
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(json.readTree(read.body()).path("data").path("status").asText()).isEqualTo(initial);
            assertThat(commandOf(id)).isNull();
        }
        // Keep the independent fixture's input current; do not alter authorization or command state.
        CounterEvidenceFixture.seed(jdbc, eventId, clock.now());
        request("/api/v1/disposal-authorizations/" + id + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        var saved = jdbc.queryForMap("select requested_by,approved_by,valid_from,valid_until from disposal_authorization where authorization_id=?", id);
        assertThat(saved.get("requested_by")).isNotEqualTo(saved.get("approved_by"));
        for (int restart = 1; restart <= 2; restart++) {
            try (var child = Item4HttpProcess.start(url, output, "approved-" + restart)) {
                var read = child.request(requester, "GET", "/api/v1/disposal-authorizations/" + id, null);
                assertThat(read.statusCode()).isEqualTo(200);
                assertThat(json.readTree(read.body()).path("data").path("status").asText()).isEqualTo("APPROVED");
                supervisor.reconcile();
                outbox.poll();
                assertThat(commandOf(id)).isNull();
                assertThat(frames).isEmpty();
                assertThat(children(id)).isEmpty();
                assertThat(jdbc.queryForMap("select requested_by,approved_by,valid_from,valid_until from disposal_authorization where authorization_id=?", id))
                        .isEqualTo(saved);
            }
        }
        Files.writeString(output.resolve("evidence.json"), json.writeValueAsString(Map.of(
                "authorization_id", id, "initial_status", initial, "recovered_status", statusOf(id),
                "wire_count", frames.size(), "child_authorizations", children(id).size(), "simulated", true)));
    }
}
