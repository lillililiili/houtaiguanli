package com.uav.lowaltitude.modules.device.api;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Only disposable schemas in an explicitly configured stage456_verify_* database are writable. */
public final class DeviceMonitoringPostgresFixture {
    private final String schema = "monitor_events_" + UUID.randomUUID().toString().replace("-", "");
    private boolean created;

    synchronized void initialize() {
        if (created) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("SELECT current_database()", String.class);
        if (database == null || !database.matches("stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Device monitoring tests require an isolated stage456_verify_ database");
        root.execute("CREATE SCHEMA " + schema);
        created = true;
        try {
            Flyway.configure().dataSource(rootDataSource()).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    DataSource dataSource() {
        return new DriverManagerDataSource(schemaUrl(), environment("POSTGRES_TEST_USER"), environment("POSTGRES_TEST_PASSWORD"));
    }

    public void springProperties(DynamicPropertyRegistry registry) {
        initialize();
        registry.add("spring.datasource.url", this::schemaUrl);
        registry.add("spring.datasource.username", () -> environment("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> environment("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Some subclasses inherit only the test profile, so apply the same bounded pool here.
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.idle-timeout", () -> 10000);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("app.dev-seed.enabled", () -> true);
        registry.add("app.handoff.channel", () -> "none");
        for (String key : new String[]{"app.live-device.enabled", "app.mqtt.enabled", "app.outbox.enabled",
                "app.device-monitor-events.enabled", "app.fusion.enabled", "app.fusion.live-promotion.enabled",
                "app.fusion.replay.run-on-start", "app.rule-engine.enabled", "app.rule-engine.c04.enabled",
                "app.rule-engine.replay.run-on-start", "app.automation-rules.enabled", "app.flight.status-advance.enabled",
                "app.advisory.auto-sms.enabled", "app.advisory.auto-voice.enabled", "app.disposal.expiry.enabled",
                "app.disposal.receipt-sync.enabled", "app.eo-edge.auto-track.enabled"})
            registry.add(key, () -> false);
    }

    public synchronized void close() {
        if (!created) return;
        assertSafeSchema();
        new JdbcTemplate(rootDataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
        created = false;
    }

    private String schemaUrl() {
        String url = environment("POSTGRES_TEST_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("POSTGRES_TEST_URL must identify a stage456_verify_ database without query parameters");
        return url + "?currentSchema=" + schema + ",public";
    }

    private DataSource rootDataSource() {
        schemaUrl(); // Validate the URL before any connection or DDL.
        return new DriverManagerDataSource(environment("POSTGRES_TEST_URL"), environment("POSTGRES_TEST_USER"), environment("POSTGRES_TEST_PASSWORD"));
    }

    private void assertSafeSchema() {
        if (!schema.matches("monitor_events_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
    }

    private static String environment(String key) {
        String value = System.getenv(key);
        if (value == null) throw new IllegalStateException(key + " is required");
        return value;
    }

    /** Some legacy jobs have no enable flag; disable registration of all scheduled callbacks in this test context. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class NoScheduledJobs {
        @Bean static BeanFactoryPostProcessor disableScheduledCallbacks() {
            return factory -> {
                String name = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                if (factory instanceof BeanDefinitionRegistry registry && registry.containsBeanDefinition(name))
                    registry.removeBeanDefinition(name);
            };
        }
    }
}
