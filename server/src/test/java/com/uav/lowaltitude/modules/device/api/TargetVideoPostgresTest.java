package com.uav.lowaltitude.modules.device.api;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same real endpoint/permission/receipt tests in a disposable PostgreSQL/PostGIS schema. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class TargetVideoPostgresTest extends EoManualTrackApiTest {
    private static final String SCHEMA = "target_video_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean created;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        initialize();
        properties.add("spring.datasource.url", () -> System.getenv("POSTGRES_TEST_URL") + "?currentSchema=" + SCHEMA + ",public");
        properties.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> false);
        properties.add("app.rule-engine.enabled", () -> false);
        properties.add("app.rule-engine.replay.run-on-start", () -> false);
        properties.add("app.fusion.enabled", () -> false);
        properties.add("app.fusion.replay.run-on-start", () -> false);
        properties.add("app.disposal.expiry.enabled", () -> false);
    }

    static synchronized void initialize() {
        if (created) return;
        var jdbc = new JdbcTemplate(root());
        if (!jdbc.queryForObject("select current_database()", String.class).matches("^advisory_verify_[a-z0-9_]+$"))
            throw new IllegalStateException("Requires isolated advisory_verify_ database");
        jdbc.execute("create schema " + SCHEMA);
        created = true;
        Flyway.configure().dataSource(root()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
    }

    @AfterAll static void cleanSchema(@org.springframework.beans.factory.annotation.Autowired
                                     org.springframework.context.ConfigurableApplicationContext context) {
        context.getBeansOfType(ThreadPoolTaskScheduler.class).values()
                .forEach(ThreadPoolTaskScheduler::shutdown);
        if (created) { new JdbcTemplate(root()).execute("drop schema " + SCHEMA + " cascade"); created = false; }
    }
    static DataSource root() {
        return new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"), System.getenv("POSTGRES_TEST_USER"),
                System.getenv("POSTGRES_TEST_PASSWORD"));
    }
}
