package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the same HTTP contract against PostgreSQL, including durable writes from overview/follow-ups. */
@ActiveProfiles(value = "postgres-test", inheritProfiles = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class EmergencyStopPostgresTest extends EmergencyStopApiTest {
    private static final String SCHEMA = "stage456_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean created;

    @Test
    void emergencyStopAuditEventsAreAppendOnlyOnPostgres() throws Exception {
        authorization("COUNTERMEASURE", true);
        String stop = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop").path("stop_id").asText();
        for (String sql : java.util.List.of("update disposal_emergency_stop_event set note='改写' where stop_id=?",
                "delete from disposal_emergency_stop_event where stop_id=?")) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(sql, stop));
            assertThat(failure).isNotNull();
            Throwable cause = failure;
            while (cause != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
            assertThat(cause).isInstanceOf(java.sql.SQLException.class);
            assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("23514");
        }
        assertThat(jdbc.queryForObject("select count(*) from disposal_emergency_stop_event where stop_id=?", Long.class, stop))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameKeyStopsCommitOneDurableStop() throws Exception {
        authorization("COUNTERMEASURE", true);
        String requestKey = key();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> operation = () -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return data(stop(operator, requestKey).andExpect(status().isOk())).path("latest_stop").path("stop_id").asText();
            };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(second.get(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from disposal_emergency_stop where event_id=?", Long.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void migrationsUpgradeExistingMaintenanceSchemaWithoutOutOfOrder() {
        String schema = "stage456_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate rootJdbc = new JdbcTemplate(root());
        rootJdbc.execute("create schema " + schema);
        try {
            Flyway.configure().dataSource(root()).schemas(schema).defaultSchema(schema).createSchemas(false)
                    .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql")
                    .target("202609150001").load().migrate();
            Flyway upgraded = Flyway.configure().dataSource(root()).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(3);
            upgraded.validate();
            assertThat(rootJdbc.queryForObject("select count(*) from information_schema.columns where table_schema=?"
                    + " and table_name='flight_plan' and column_name='pilot_name'", Integer.class, schema)).isEqualTo(1);
            assertThat(rootJdbc.queryForObject("select count(*) from information_schema.triggers where trigger_schema=?"
                    + " and trigger_name='trg_emergency_stop_event_append_only'", Integer.class, schema)).isEqualTo(2);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
        } finally {
            rootJdbc.execute("drop schema " + schema + " cascade");
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        initialize();
        String url = env("POSTGRES_TEST_URL");
        if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase().contains("currentschema=")) {
            throw new IllegalStateException("PostgreSQL URL without currentSchema required");
        }
        properties.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        properties.add("spring.datasource.username", () -> env("POSTGRES_TEST_USER"));
        properties.add("spring.datasource.password", () -> env("POSTGRES_TEST_PASSWORD"));
        properties.add("spring.flyway.enabled", () -> false);
        properties.add("app.dev-seed.enabled", () -> false);
        properties.add("app.live-device.enabled", () -> false);
        properties.add("app.rule-engine.enabled", () -> false);
        properties.add("app.rule-engine.replay.run-on-start", () -> false);
        properties.add("app.rule-engine.c04.enabled", () -> false);
        properties.add("app.fusion.enabled", () -> false);
        properties.add("app.fusion.replay.run-on-start", () -> false);
        properties.add("app.disposal.expiry.enabled", () -> false);
    }

    @Override
    @BeforeEach
    void fixture() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version)"
                + " select 'seed-stage3-org','ESTOP-ORG','急停测试机构',true,0,0,0"
                + " where not exists(select 1 from app_org where org_id='seed-stage3-org')");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version)"
                + " select 'seed-stage3-district','ESTOP-DIST','急停测试区域',true,0,0,0"
                + " where not exists(select 1 from app_district where district_id='seed-stage3-district')");
        super.fixture();
    }

    static synchronized void initialize() {
        if (created) return;
        JdbcTemplate root = new JdbcTemplate(root());
        String database = root.queryForObject("select current_database()", String.class);
        if (database == null || !database.matches("^stage456_verify_[a-z0-9_]+$")) {
            throw new IllegalStateException("Emergency stop verification requires isolated stage456_verify_ database");
        }
        root.execute("create schema " + SCHEMA);
        created = true;
        try {
            Flyway.configure().dataSource(root()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false)
                    .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
        } catch (RuntimeException failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    static void cleanup() {
        if (!created) return;
        if (!SCHEMA.matches("^stage456_[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe schema");
        new JdbcTemplate(root()).execute("drop schema " + SCHEMA + " cascade");
        created = false;
    }

    static DataSource root() {
        return new DriverManagerDataSource(env("POSTGRES_TEST_URL"), env("POSTGRES_TEST_USER"), env("POSTGRES_TEST_PASSWORD"));
    }

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " is required");
        return value;
    }
}
