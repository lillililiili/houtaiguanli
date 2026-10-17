package com.uav.lowaltitude.platform.database;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Upgrade both real historical script sets in disposable schemas, preserving their recorded hashes and data. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class MigrationLineagePostgresTest {
    @TempDir Path oldPostgres;
    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate root, jdbc;

    @BeforeEach void setup() {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Disposable PostgreSQL database required");
        var base = new DriverManagerDataSource(url, System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
        root = new JdbcTemplate(base);
        assertThat(root.queryForObject("SELECT current_database()", String.class)).matches("stage456_verify_[a-z0-9_]+");
        schema = "lineage_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        dataSource = new DriverManagerDataSource(url+"?currentSchema="+schema+",public",base.getUsername(),base.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }
    @AfterEach void cleanup() {
        if (schema != null && schema.matches("lineage_[a-f0-9]{32}")) root.execute("DROP SCHEMA " + schema + " CASCADE");
    }
    private FluentConfiguration configuration() {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration", "classpath:db/postgresql");
    }
    private void baseline(String branch) throws Exception {
        if (branch.equals("empty")) return;
        if (branch.equals("automatic")) {
            configuration().locations("classpath:db/migration","classpath:db/postgresql","classpath:db/lineage/automatic")
                    .target("202610160003").load().migrate();
        } else {
            // The published manual branch did not contain the independently added fusion index.
            try (var paths = Files.list(Path.of("src/main/resources/db/postgresql"))) {
                for (Path file : paths.filter(Files::isRegularFile).toList())
                    if (!file.getFileName().toString().startsWith("V202610160003__"))
                        Files.copy(file,oldPostgres.resolve(file.getFileName()));
            }
            configuration().locations("classpath:db/migration","filesystem:"+oldPostgres.toAbsolutePath(),
                    "classpath:db/lineage/manual","classpath:db/lineage/manual-postgresql")
                    .target("202610169001").load().migrate();
        }
    }
    private List<Map<String,Object>> history() {
        return jdbc.queryForList("SELECT installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success FROM flyway_schema_history ORDER BY installed_rank");
    }
    @ParameterizedTest @ValueSource(strings={"empty","automatic","manual"})
    void upgradesWithoutRewritingHistoricalRowsOrData(String branch) throws Exception {
        baseline(branch);
        List<Map<String,Object>> before = branch.equals("empty") ? List.of() : history();
        String command = UUID.randomUUID().toString();
        Object cutoff = null;
        if (branch.equals("automatic"))
            jdbc.update("INSERT INTO countermeasure_start_attempt(command_id,authorization_id,device_id,attempted_at) VALUES(?,?,?,?)",command,UUID.randomUUID().toString(),UUID.randomUUID().toString(),123L);
        if (branch.equals("manual")) cutoff=jdbc.queryForObject("SELECT eligible_from FROM handoff_followup_policy WHERE policy_id=1",Object.class);
        var upgrade=configuration(); MigrationLineage.configure(upgrade);
        assertThat(upgrade.isValidateOnMigrate()).isTrue();
        upgrade.load().migrate();
        assertThat(history().subList(0,before.size())).containsExactlyElementsOf(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='simulator_observation_source' AND column_name='origin_ops_device_id'",Integer.class,schema)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff_followup_policy",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgrelid='handoff_notification_decision'::regclass AND NOT tgisinternal",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='202610160003' AND success",Integer.class)).isEqualTo(1);
        if (branch.equals("automatic")) assertThat(jdbc.queryForObject("SELECT attempted_at FROM countermeasure_start_attempt WHERE command_id=?",Long.class,command)).isEqualTo(123L);
        if (branch.equals("manual")) assertThat(jdbc.queryForObject("SELECT eligible_from FROM handoff_followup_policy WHERE policy_id=1",Object.class)).isEqualTo(cutoff);
        var again=configuration(); MigrationLineage.configure(again);
        assertThat(again.isOutOfOrder()).isFalse();
        assertThat(again.load().migrate().migrationsExecuted).isZero();
        again.load().validate();
    }
    @Test void unknownChecksumStopsBeforeAnyMigration() throws Exception {
        baseline("automatic");
        jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='202610160001'");
        var before=history();
        assertThatThrownBy(()->MigrationLineage.configure(configuration())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unrecognized or mixed");
        assertThat(history()).isEqualTo(before);
    }
    @Test void mixedHistoriesAreRejectedInsteadOfGuessing() throws Exception {
        baseline("automatic");
        jdbc.update("UPDATE flyway_schema_history SET script='V202610160002__handoff_internal_recipient_null_guard.sql',checksum=-933697544 WHERE version='202610160002'");
        assertThatThrownBy(()->MigrationLineage.configure(configuration())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unrecognized or mixed");
    }
}
