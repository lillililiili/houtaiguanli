package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.platform.worker.Item4HttpRestartProcess;

@EnabledIfEnvironmentVariable(named="ITEM4_RESTART_TESTS", matches="true")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/maintenance_flow_verify_item4_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class Item4MaintenanceRecoveryPostgresTest extends DeviceMaintenanceWorkflowApiTest {
    private static final String SCHEMA = "item4_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean created;

    private static String databaseUrl() {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:25432/maintenance_flow_verify_item4_[a-z0-9_]+"))
            throw new IllegalArgumentException("Only the independent item4 maintenance database is permitted");
        return url;
    }
    private static DriverManagerDataSource root() {
        return new DriverManagerDataSource(databaseUrl(), System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
    }
    @DynamicPropertySource static synchronized void database(DynamicPropertyRegistry registry) {
        if (!created) {
            new JdbcTemplate(root()).execute("CREATE SCHEMA " + SCHEMA);
            created = true;
            Flyway.configure().dataSource(root()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false).cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
        }
        registry.add("spring.datasource.url", () -> databaseUrl() + "?currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("app.dev-seed.enabled", () -> true); // Isolated fixture only; cold-start child disables seeds.
    }
    @AfterAll static void dropSchema() {
        if (created) new JdbcTemplate(root()).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @RepeatedTest(3)
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void committedPassCannotCompleteAfterRestartAndNewFault() throws Exception {
        create(); action("START", 1, null); action("SUBMIT_VERIFICATION", 2, null); healthy();
        assertThat(action("VERIFY_RECOVERY", 3, null).path("recovery").path("result").asText()).isEqualTo("PASS");
        Path output = Path.of("target", "item4-maintenance-restart", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(output);
        try {
            try (Child first = child(output, "before-crash")) {
                var read = http(first.port(), "GET", "/api/v1/device-maintenance-tasks/" + taskId + "/workflow", null);
                assertThat(read.statusCode()).isEqualTo(200);
                assertThat(json.readTree(read.body()).path("data").path("recovery").path("result").asText()).isEqualTo("PASS");
            } // Forcibly terminates the actual server after the persisted PASS was read.
            jdbc.update("UPDATE ops_device_state SET has_alarm=TRUE,health_code='BAD',observed_at=?,last_heartbeat_at=? WHERE device_id=?",
                    System.currentTimeMillis(), System.currentTimeMillis(), device);
            try (Child recovered = child(output, "after-crash")) {
                var rejected = http(recovered.port(), "POST", path(), json.writeValueAsString(Map.of("action", "COMPLETE", "expected_version", 4)));
                assertThat(rejected.statusCode()).isEqualTo(409);
                assertThat(json.readTree(rejected.body()).path("error").path("code").asText()).isEqualTo("MAINTENANCE_RECOVERY_BLOCKED");
                var read = http(recovered.port(), "GET", "/api/v1/device-maintenance-tasks/" + taskId + "/workflow", null);
                assertThat(read.statusCode()).isEqualTo(200);
                var data = json.readTree(read.body()).path("data");
                assertThat(data.path("state").asText()).isEqualTo("PENDING_VERIFICATION");
                assertThat(data.path("version").asLong()).isEqualTo(4);
                assertThat(data.path("events")).hasSize(3);
                Files.writeString(output.resolve("evidence.json"), json.writeValueAsString(Map.of("task_id", taskId,
                        "completion_http", rejected.statusCode(), "state", data.path("state").asText(), "event_count", data.path("events").size())));
            }
        } finally {
            jdbc.update("DELETE FROM ops_maintenance_workflow_request WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM ops_maintenance_workflow_event WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM ops_maintenance_message_read WHERE task_id=?", taskId);
        }
    }

    private HttpResponse<String> http(int port, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + session);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private Child child(Path output, String name) throws Exception {
        Path marker = output.resolve(name + ".ready"), log = output.resolve(name + ".log"), cpJar = output.resolve(name + ".jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH, java.util.Arrays.stream(cp.split(java.io.File.pathSeparator))
                .map(value -> Path.of(value).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" ")));
        try (var archive = new java.util.jar.JarOutputStream(Files.newOutputStream(cpJar), manifest)) { }
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(java, "-Dspring.devtools.restart.enabled=false", "-Dfile.encoding=UTF-8", "-cp", cpJar.toString(),
                Item4HttpRestartProcess.class.getName(), marker.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("ITEM4_HTTP_DB_URL", databaseUrl() + "?currentSchema=" + SCHEMA + ",public");
        Process process = builder.start();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> Files.exists(marker) || !process.isAlive());
            assertThat(Files.exists(marker)).withFailMessage("Child startup failed: %s", log).isTrue();
            String[] values = Files.readString(marker).split(":");
            assertThat(Long.parseLong(values[0])).isEqualTo(process.pid());
            return new Child(process, Integer.parseInt(values[1]));
        } catch (Throwable error) {
            new Child(process, 0).close();
            throw error;
        }
    }
    private record Child(Process process, int port) implements AutoCloseable {
        @Override public void close() throws Exception {
            if (process.isAlive()) process.destroyForcibly();
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        }
    }
}
