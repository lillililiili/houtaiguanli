package com.uav.lowaltitude.modules.alarm.api;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the matrix and existing channel regressions only in the explicitly isolated QA database. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class DualChannelReceiptPostgresTest extends DualChannelReceiptApiTest {
    private static final String SCHEMA = "dual_receipt_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean created;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        initialize();
        p.add("spring.datasource.url", () -> System.getenv("POSTGRES_TEST_URL") + "?currentSchema=" + SCHEMA + ",public");
        p.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        p.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.enabled", () -> false);
        p.add("app.rule-engine.enabled", () -> false);
        p.add("app.rule-engine.replay.run-on-start", () -> false);
        p.add("app.fusion.enabled", () -> false);
        p.add("app.fusion.replay.run-on-start", () -> false);
        p.add("app.disposal.expiry.enabled", () -> false);
    }
    private static synchronized void initialize() {
        if (created) return;
        var jdbc = new JdbcTemplate(root());
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (database == null || !database.matches("stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Requires the dedicated notification QA database");
        jdbc.execute("create schema " + SCHEMA);
        created = true;
        Flyway.configure().dataSource(root()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
    }
    @AfterAll static void cleanSchema(@Autowired ConfigurableApplicationContext context) {
        context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).values()
                .forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::shutdown);
        if (created) { new JdbcTemplate(root()).execute("drop schema " + SCHEMA + " cascade"); created = false; }
    }
    private static DriverManagerDataSource root() {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Requires an isolated stage456_verify_ database");
        return new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"), System.getenv("POSTGRES_TEST_USER"),
                System.getenv("POSTGRES_TEST_PASSWORD"));
    }
}
