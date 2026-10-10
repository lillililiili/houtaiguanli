package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRestartProcess;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.platform.worker.Item4HttpProcess;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** R05: real MQTT receipt committed in a JVM killed before authorization settlement. */
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "ITEM4_RESTART_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:(?:25432|5432)/stage456_verify_item4_[a-z0-9_]+")
class Item4ReceiptRecoveryPostgresTest extends DisposalMqttFlowTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.mqtt.enabled", () -> true);
        registry.add("app.outbox.enabled", () -> true);
        registry.add("app.dev-seed.password", () -> "changeme");
        // Allows cold JVM startup; this is not a default-timeout acceptance test.
        registry.add("app.lingyun-control.command-timeout-millis", () -> 120000);
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @RepeatedTest(3)
    void committedReceiptSettlesOnceAfterCrashWithoutCreatingASuccessor() throws Exception {
        String applicant = user("disposal:read", "disposal:request", "disposal:execute", "devices");
        String id = data(request("/api/v1/disposal-authorizations", applicant, key(), body("模拟回执提交后进程中断"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + id + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        request("/api/v1/disposal-authorizations/" + id + "/execute", applicant, key(), Map.of("expected_version", 1))
                .andExpect(status().isOk());
        String command = commandOf(id);
        jdbc.update("update mqtt_broker set enabled=false where broker_id=?", brokerId);
        supervisor.reconcile();
        jdbc.update("update mqtt_broker set enabled=true where broker_id=?", brokerId);
        String url;
        try (var connection = jdbc.getDataSource().getConnection()) { url = connection.getMetaData().getURL(); }
        Path output = Path.of("target", "item4-receipt-restart", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(output);
        Path marker = output.resolve("receipt-committed.ready");
        Process child = startCutProcess(url, output, marker, command);
        try {
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> Files.exists(Path.of(marker + ".subscribed")) || !child.isAlive());
            assertThat(child.isAlive()).withFailMessage("Receipt child failed: %s", output.resolve("receipt.log")).isTrue();
            assertThat(Files.exists(Path.of(marker + ".subscribed"))).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(frames).hasSize(1));
            reply(command, 0);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(marker));
            assertThat(Long.parseLong(Files.readString(marker))).isEqualTo(child.pid());
            assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("SUCCEEDED");
            assertThat(statusOf(id)).isEqualTo("EXECUTING");
            assertThat(children(id)).isEmpty();
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
        }
        CounterEvidenceFixture.seed(jdbc, eventId, clock.now());
        long now = clock.nowMillis();
        jdbc.update("update ops_device_state set connectivity='ONLINE',health_code='GOOD',has_alarm=false,observed_at=?,received_at=?,last_heartbeat_at=? where device_id=?",
                now, now, now, binding.opsDeviceId());
        for (int restart = 1; restart <= 2; restart++) {
            try (var recovered = Item4HttpProcess.start(url, output, "settlement-" + restart)) {
                var read = recovered.request(applicant, "GET", "/api/v1/disposal-authorizations/" + id, null);
                assertThat(read.statusCode()).isEqualTo(200);
                assertThat(json.readTree(read.body()).path("data").path("status").asText()).isEqualTo("COMPLETED");
                assertThat(children(id)).isEmpty();
                assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, eventId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from disposal_authorization_event where authorization_id=? and event_kind='COMPLETE'", Integer.class, id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from disposal_authorization_event where authorization_id=? and event_kind='RECEIPT'", Integer.class, id)).isEqualTo(1);
                assertThat(frames).hasSize(1);
            }
        }
        Files.writeString(output.resolve("evidence.json"), json.writeValueAsString(Map.of(
                "authorization_id", id, "command_id", command, "command_status", "SUCCEEDED", "authorization_status", statusOf(id),
                "jamming_children", children(id).size(), "wire_count", frames.size(), "simulated", true)));
    }

    private Process startCutProcess(String url, Path output, Path marker, String command) throws Exception {
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH, java.util.Arrays.stream(cp.split(java.io.File.pathSeparator))
                .map(value -> Path.of(value).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" ")));
        Path classpath = output.resolve("classpath.jar");
        try (var archive = new java.util.jar.JarOutputStream(Files.newOutputStream(classpath), manifest)) { }
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "-Dspring.devtools.restart.enabled=false", "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                AutomationRestartProcess.class.getName(), "CRASH_RECEIPT", marker.toString(), binding.opsDeviceId(), command)
                .redirectErrorStream(true).redirectOutput(output.resolve("receipt.log").toFile());
        builder.environment().put("AUTOMATION_RESTART_DB_URL", url);
        return builder.start();
    }
}
