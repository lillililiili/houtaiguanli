package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 同一组上传、选用、并发和权限契约在隔离 PostgreSQL/PostGIS 模式上验证，另验证从上一版本升级。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/voice_recording_(verify|pg)_[a-z0-9_]+")
class AdvisoryVoiceRecordingPostgresTest extends AdvisoryVoiceRecordingApiTest {
    private static final String SCHEMA = "voice_recording_" + UUID.randomUUID().toString().replace("-", "");
    private static final String[] LOCATIONS = {"classpath:db/migration", "classpath:db/postgresql"};
    private static boolean created;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        initialize();
        p.add("spring.datasource.url", () -> System.getenv("POSTGRES_TEST_URL") + "?currentSchema=" + SCHEMA + ",public");
        p.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        p.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.enabled", () -> false);
        p.add("app.rule-engine.enabled", () -> false);
        p.add("app.rule-engine.replay.run-on-start", () -> false);
        p.add("app.rule-engine.c04.enabled", () -> false);
        p.add("app.fusion.enabled", () -> false);
        p.add("app.fusion.replay.run-on-start", () -> false);
        p.add("app.disposal.expiry.enabled", () -> false);
    }

    static synchronized void initialize() {
        if (created) return;
        var jdbc = new JdbcTemplate(root());
        if (!jdbc.queryForObject("select current_database()", String.class).matches("^voice_recording_(verify|pg)_[a-z0-9_]+$"))
            throw new IllegalStateException("Requires an isolated voice_recording_ database");
        jdbc.execute("create schema " + SCHEMA);
        created = true;
        flyway(SCHEMA, null).migrate();
    }

    @Test
    void upgradeFromPreviousVersionStartsWithoutSelectionAndRepeatsCleanly() {
        String schema = "voice_recording_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(root());
        jdbc.execute("create schema " + schema);
        try {
            flyway(schema, "202610050002").migrate();
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, schema + ".advisory_voice_recording")).isTrue();

            var upgraded = flyway(schema, null);
            assertThat(upgraded.migrate().migrationsExecuted).isPositive();
            assertThat(Arrays.stream(upgraded.info().applied()).map(MigrationInfo::getVersion).filter(Objects::nonNull).map(Object::toString))
                    .contains("202610069021", "202610069022");
            // 升级不预置录音、不替任何人选用：没有启动参数时电话仍跳过，直到后台上传并选用。
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".advisory_voice_recording", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT active_recording_id FROM " + schema + ".advisory_voice_recording_setting WHERE setting_id='global'", String.class)).isNull();
            upgraded.validate();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }

    @AfterAll
    static void cleanSchema(@Autowired ConfigurableApplicationContext context) {
        context.getBeansOfType(ThreadPoolTaskScheduler.class).values().forEach(ThreadPoolTaskScheduler::shutdown);
        if (created) {
            new JdbcTemplate(root()).execute("drop schema " + SCHEMA + " cascade");
            created = false;
        }
    }

    private static Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure().dataSource(root()).schemas(schema).defaultSchema(schema).createSchemas(false)
                .cleanDisabled(true).locations(LOCATIONS);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    static DataSource root() {
        return new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"), System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
    }
}
