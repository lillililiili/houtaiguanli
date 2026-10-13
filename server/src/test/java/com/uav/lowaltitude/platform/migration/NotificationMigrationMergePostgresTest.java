package com.uav.lowaltitude.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Both published histories must upgrade without editing migration SQL or losing execution facts. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class NotificationMigrationMergePostgresTest {
    private static final String ORIGINAL = "V202610110001__notification_departure_snapshot.sql";
    private static final String RENAMED = "V202610120001__notification_departure_snapshot.sql";
    private static final Set<String> NEW_MAIN = Set.of(
            "V202610090002__confirm_current_project_parameters.sql",
            "V202610100001__device_maintenance_cases.sql",
            "V202610100002__device_recovery_evidence.sql",
            "V202610100003__maintenance_resend_plan.sql",
            "V202610110001__eo_tracking_failure_evidence.sql");
    @TempDir Path temporary;
    String schema;
    DataSource rootSource, schemaSource;
    JdbcTemplate root, jdbc;

    @BeforeEach void isolate() {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("An isolated stage456_verify_ database is required");
        rootSource = source(url);
        root = new JdbcTemplate(rootSource);
        assertThat(root.queryForObject("SELECT current_database()", String.class)).matches("stage456_verify_[a-z0-9_]+");
        schema = "merge_migration_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        schemaSource = source(url + "?currentSchema=" + schema + ",public");
        jdbc = new JdbcTemplate(schemaSource);
    }

    @AfterEach void close() {
        if (schema != null && schema.matches("merge_migration_[a-f0-9]{32}"))
            root.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void freshInstallationAppliesBothMigrations() {
        current(null).migrate();
        assertCombinedSchema();
        current(null).validate();
    }

    @Test void mainHistoryUpgradesWithoutAnyReconciliation() {
        current("202610110001").migrate();
        Map<String, Object> before = history("202610110001");
        current(null).migrate();
        assertThat(history("202610110001")).isEqualTo(before);
        assertCombinedSchema();
        current(null).validate();
    }

    @Test void notificationHistoryPreservesExecutionFactsAndExistingSnapshotData() throws Exception {
        originalNotificationBranch();
        Map<String, Object> before = new LinkedHashMap<>(history("202610110001"));
        // A test-only data marker proves the manual coordination never clears database contents.
        jdbc.execute("CREATE TABLE merge_data_marker(id VARCHAR(36) PRIMARY KEY, snapshot VARCHAR(200))");
        String marker = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO merge_data_marker VALUES(?,?)", marker, "existing notification evidence");
        reconcile();
        Map<String, Object> after = new LinkedHashMap<>(history("202610120001"));
        before.remove("version"); before.remove("script");
        after.remove("version"); after.remove("script");
        assertThat(after).isEqualTo(before);
        assertThatThrownBy(() -> current(null).validate())
                .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
        current(null, true).migrate();
        assertCombinedSchema();
        assertThat(jdbc.queryForObject("SELECT snapshot FROM merge_data_marker WHERE id=?", String.class, marker))
                .isEqualTo("existing notification evidence");
        current(null).validate();
    }

    @ParameterizedTest
    @ValueSource(strings={"checksum", "script", "description", "success", "missing_column", "destination"})
    void reconciliationRejectsUnrecognizedOrIncompleteHistory(String mismatch) throws Exception {
        originalNotificationBranch();
        switch (mismatch) {
            case "checksum" -> jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='202610110001'");
            case "script" -> jdbc.update("UPDATE flyway_schema_history SET script='V202610110001__different.sql' WHERE version='202610110001'");
            case "description" -> jdbc.update("UPDATE flyway_schema_history SET description='different migration' WHERE version='202610110001'");
            case "success" -> jdbc.update("UPDATE flyway_schema_history SET success=false WHERE version='202610110001'");
            case "missing_column" -> jdbc.execute("ALTER TABLE uav_auto_voice_task DROP COLUMN departure_channel");
            case "destination" -> jdbc.update("UPDATE flyway_schema_history SET version='202610120001' WHERE version='202610110001'");
            default -> throw new IllegalArgumentException(mismatch);
        }
        var before = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        assertThatThrownBy(this::reconcile).isInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(before);
    }

    private void originalNotificationBranch() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("original"));
        var resolver = new PathMatchingResourcePatternResolver();
        for (String location : new String[]{"migration", "postgresql"}) {
            for (var resource : resolver.getResources("classpath*:db/" + location + "/*.sql")) {
                String name = resource.getFilename();
                if (NEW_MAIN.contains(name) || RENAMED.equals(name) || laterThanRename(name)) continue;
                try (var input = resource.getInputStream()) { Files.copy(input, directory.resolve(name)); }
            }
        }
        try (var input = new ClassPathResource("db/migration/" + RENAMED).getInputStream()) {
            Files.copy(input, directory.resolve(ORIGINAL));
        }
        Flyway.configure().dataSource(rootSource).schemas(schema).defaultSchema(schema).createSchemas(false)
                .cleanDisabled(true).locations("filesystem:" + directory.toAbsolutePath())
                // Reproduce the historical branch only; later migrations belong to the upgrade below.
                .target("202610110001")
                .javaMigrations(originalJavaMigrations()).load().migrate();
        assertThat(history("202610110001").get("checksum")).isEqualTo(-1287981834);
    }

    /** The original notification database predates every migration numbered after the renamed one. */
    private static boolean laterThanRename(String name) {
        int end = name.indexOf("__");
        if (!name.startsWith("V") || end < 2) return false;
        try { return Long.parseLong(name.substring(1, end)) > 202610120001L; }
        catch (NumberFormatException notVersioned) { return false; }
    }

    private void reconcile() throws Exception {
        String sql = Files.readString(Path.of("scripts/reconcile_notification_departure_migration_20261012.sql"));
        try (Connection connection = schemaSource.getConnection(); var statement = connection.createStatement()) {
            try { statement.execute(sql); }
            catch (java.sql.SQLException error) { statement.execute("ROLLBACK"); throw error; }
        }
    }

    private Flyway current(String target) {
        return current(target, false);
    }

    private org.flywaydb.core.api.migration.JavaMigration[] originalJavaMigrations() throws Exception {
        var migrations = new java.util.ArrayList<org.flywaydb.core.api.migration.JavaMigration>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:db/migration/V*.class")) {
            String name = resource.getFilename();
            if (name.contains("$") || laterThanRename(name)) continue;
            var type = Class.forName("db.migration." + name.substring(0, name.length() - 6));
            if (org.flywaydb.core.api.migration.JavaMigration.class.isAssignableFrom(type))
                migrations.add((org.flywaydb.core.api.migration.JavaMigration) type.getDeclaredConstructor().newInstance());
        }
        assertThat(migrations).isNotEmpty();
        return migrations.toArray(org.flywaydb.core.api.migration.JavaMigration[]::new);
    }

    private Flyway current(String target, boolean outOfOrder) {
        var configuration = Flyway.configure().dataSource(rootSource).schemas(schema).defaultSchema(schema)
                .createSchemas(false).cleanDisabled(true).outOfOrder(outOfOrder)
                .locations("classpath:db/migration", "classpath:db/postgresql");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private Map<String, Object> history(String version) {
        return jdbc.queryForMap("SELECT * FROM flyway_schema_history WHERE version=?", version);
    }

    private void assertCombinedSchema() {
        assertThat(history("202610110001").get("script")).isEqualTo("V202610110001__eo_tracking_failure_evidence.sql");
        assertThat(history("202610120001").get("script")).isEqualTo(RENAMED);
        assertThat(history("202610120001").get("checksum")).isEqualTo(-1287981834);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='eo_tracking_task' AND column_name IN ('last_failure_observed_at','last_failure_received_at')", Integer.class, schema)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='uav_auto_voice_task' AND column_name LIKE 'departure_%'", Integer.class, schema)).isEqualTo(9);
    }

    private static DataSource source(String url) {
        return new DriverManagerDataSource(url, System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
    }
}
