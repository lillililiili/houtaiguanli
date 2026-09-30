package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in process interruption fixture. Tokens remain only in ignored target files; no external transport. */
@EnabledIfSystemProperty(named = "qa.disposal.restart", matches = "true")
@ActiveProfiles(value = "postgres-test", inheritProfiles = false)
@Import(com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "app.outbox.enabled=false", "app.live-device.enabled=false",
        "app.mqtt.enabled=false", "app.handoff.channel=none", "app.evidence-dir=./target/evidence-restart-test" })
class DisposalRestartBrowserFixtureTest extends EmergencyStopApiTest {
    private static final String SCHEMA = System.getProperty("qa.disposal.restart.schema", "");
    private static final Path DIRECTORY = Path.of("target", "disposal-restart-browser", SCHEMA).toAbsolutePath();
    @LocalServerPort int port;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        if (!SCHEMA.matches("restart_qa_[a-f0-9]{32}")) throw new IllegalStateException("Explicit disposable schema required");
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Loopback stage456_verify database required");
        var root = new DriverManagerDataSource(url, System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
        var rootJdbc = new JdbcTemplate(root);
        assertThat(rootJdbc.queryForObject("select current_database()", String.class)).startsWith("stage456_verify_");
        assertThat(rootJdbc.queryForObject("select count(*) from pg_extension where extname='postgis'", Integer.class)).isEqualTo(1);
        rootJdbc.execute("create schema if not exists " + SCHEMA);
        Flyway.configure().dataSource(root).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
        // 持久化夹具重启时已有测试用户，必须提供正式启动完整性检查要求的唯一管理员。
        // 账号仅存在此随机隔离 schema；不启用会批量灌入业务样本的开发 seeder。
        rootJdbc.update("insert into " + SCHEMA + ".app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) "
                + "select ?,'admin1','隔离重启测试管理员','ROLE-ADMIN','ACTIVE','unused',0,'ALL',0,0,0,0 where not exists(select 1 from " + SCHEMA + ".app_user where account='admin1')",
                java.util.UUID.randomUUID().toString());
        registry.add("spring.datasource.url", () -> url + "?currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("app.dev-seed.enabled", () -> false);
    }

    @Override @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        if (Files.exists(DIRECTORY.resolve("private-manifest.json"))) return;
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values ('seed-stage3-org','RESTART-QA','重启隔离机构',true,0,0,0)");
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values ('seed-stage3-district','RESTART-QA','重启隔离区域',true,0,0,0)");
        super.fixture();
    }

    @Test void serveRestartFixture() throws Exception {
        Files.createDirectories(DIRECTORY);
        Path manifestPath = DIRECTORY.resolve("private-manifest.json");
        com.fasterxml.jackson.databind.node.ObjectNode manifest;
        boolean restored = Files.exists(manifestPath);
        if (restored) manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(manifestPath));
        else {
            var cases = new ArrayList<Map<String, Object>>();
            for (String condition : List.of("REQUESTED", "APPROVED", "INTERRUPTED")) {
                eventId = event();
                String device = fourChannel();
                String requesterToken = user("disposal:request", "disposal:read");
                String approverToken = user("disposal:approve", "disposal:read");
                String executorToken = user("disposal:execute", "disposal:read", "devices");
                var created = data(request("/api/v1/disposal-authorizations", requesterToken, key(), Map.of(
                        "subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "隔离进程中断测试"))
                        .andExpect(status().isCreated()));
                String authorization = created.path("authorization_id").asText();
                if (!"REQUESTED".equals(condition)) request("/api/v1/disposal-authorizations/" + authorization + "/approve",
                        approverToken, key(), Map.of("expected_version", 0)).andExpect(status().isOk());
                cases.add(Map.of("condition", condition, "event_id", eventId, "device_id", device,
                        "authorization_id", authorization, "requester_token", requesterToken,
                        "approver_token", approverToken, "executor_token", executorToken,
                        "approve_key", key(), "cancel_key", key(), "execute_key", key()));
            }
            manifest = json.createObjectNode();
            manifest.set("cases", json.valueToTree(cases));
            manifest.put("schema", SCHEMA);
        }
        manifest.put("port", port).put("pid", ProcessHandle.current().pid()).put("restored", restored);
        Files.deleteIfExists(DIRECTORY.resolve("hold"));
        Files.deleteIfExists(DIRECTORY.resolve("held.json"));
        Files.deleteIfExists(DIRECTORY.resolve("release"));
        Files.deleteIfExists(DIRECTORY.resolve("stop"));
        Files.writeString(manifestPath, json.writeValueAsString(manifest));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(DIRECTORY.resolve("stop")) && System.nanoTime() < deadline) {
            if (Files.exists(DIRECTORY.resolve("hold"))) {
                String event = manifest.path("cases").get(2).path("event_id").asText();
                try (var connection = jdbc.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("select event_id from uav_event where event_id=? for update")) {
                        statement.setString(1, event);
                        statement.executeQuery().close();
                    }
                    int pid;
                    try (var statement = connection.createStatement(); var rs = statement.executeQuery("select pg_backend_pid()")) {
                        rs.next(); pid = rs.getInt(1);
                    }
                    Files.writeString(DIRECTORY.resolve("held.json"), json.writeValueAsString(Map.of("backend_pid", pid, "event_id", event)));
                    while (!Files.exists(DIRECTORY.resolve("release")) && !Files.exists(DIRECTORY.resolve("stop"))
                            && System.nanoTime() < deadline) Thread.sleep(100);
                    connection.rollback();
                }
                Files.deleteIfExists(DIRECTORY.resolve("hold"));
            }
            Thread.sleep(100);
        }
        for (var sample : manifest.path("cases")) {
            assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, sample.path("device_id").asText())).isBetween(0, 1);
            assertThat(jdbc.queryForObject("select count(*) from disposal_authorization_event where authorization_id=? and event_kind='EXECUTE'", Integer.class, sample.path("authorization_id").asText())).isBetween(0, 1);
        }
        Files.deleteIfExists(DIRECTORY.resolve("stop"));
    }
}
