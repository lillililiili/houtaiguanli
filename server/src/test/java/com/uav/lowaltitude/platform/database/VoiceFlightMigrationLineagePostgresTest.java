package com.uav.lowaltitude.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Preserve both applied 20261026 histories and complete their missing features in isolated PostGIS. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class VoiceFlightMigrationLineagePostgresTest {
    private String schema;
    private DriverManagerDataSource source;
    private JdbcTemplate root, jdbc;

    @BeforeEach void setup() {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Disposable PostgreSQL database required");
        var base = new DriverManagerDataSource(url, System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
        root = new JdbcTemplate(base);
        assertThat(root.queryForObject("SELECT current_database()", String.class)).matches("stage456_verify_[a-z0-9_]+");
        schema = "voice_flight_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + "?currentSchema=" + schema + ",public", base.getUsername(), base.getPassword());
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach void cleanup() {
        if (schema != null && schema.matches("voice_flight_[a-f0-9]{32}")) root.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private FluentConfiguration configuration() {
        return Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration", "classpath:db/postgresql");
    }

    private void baseline(String branch) {
        if (branch.equals("empty")) return;
        var before = configuration();
        MigrationLineage.configure(before);
        if (branch.startsWith("flight")) {
            var locations = new ArrayList<>(Arrays.stream(before.getLocations()).map(location -> location.getDescriptor())
                    .filter(location -> !location.equals("classpath:db/lineage/voice-order")).toList());
            locations.add("classpath:db/lineage/flight-avoidance");
            before.locations(locations.toArray(String[]::new));
        }
        before.target(branch.equals("flight-distance") ? "202610270001" : "202610260001").load().migrate();
    }

    private List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    }

    @ParameterizedTest @ValueSource(strings={"empty", "voice", "flight", "flight-distance"})
    void preservesOriginalHistoryAndSettingsThenValidatesRepeatedUpgrade(String branch) throws Exception {
        baseline(branch);
        var before = branch.equals("empty") ? List.<Map<String, Object>>of() : history();
        List<Map<String, Object>> policy = List.of(), checks = List.of();
        if (branch.startsWith("flight")) {
            jdbc.update("UPDATE flight_avoidance_policy SET version=8,force_land_block_km=12,force_land_warn_km=35,updated_at=987 WHERE policy_id='counter'");
            insertCheck();
            policy = jdbc.queryForList("SELECT * FROM flight_avoidance_policy");
            checks = jdbc.queryForList("SELECT * FROM counter_flight_check");
        }
        var upgrade = configuration();
        MigrationLineage.configure(upgrade);
        assertThat(upgrade.isValidateOnMigrate()).isTrue();
        upgrade.load().migrate();
        assertThat(history().subList(0, before.size())).containsExactlyElementsOf(before);
        assertThat(column("uav_auto_voice_task", "response_order_basis_json")).isEqualTo(1);
        assertThat(column("handoff", "risk_case_id")).isEqualTo(1);
        assertThat(column("handoff_measured_basis", "basis_json")).isEqualTo(1);
        assertThat(column("counter_flight_distance", "force_land_block_km")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='handoff_measured_basis'::regclass AND tgname='trg_measured_handoff_basis_immutable'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='counter_flight_check'::regclass AND tgname='trg_app_data_change_ins'", Integer.class)).isEqualTo(1);
        if (branch.startsWith("flight")) {
            assertThat(jdbc.queryForList("SELECT * FROM flight_avoidance_policy")).isEqualTo(policy);
            assertThat(jdbc.queryForList("SELECT * FROM counter_flight_check")).isEqualTo(checks);
        }
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("LISTEN app_data_change");
            insertCheck();
            var notifications = connection.unwrap(PGConnection.class).getNotifications(5000);
            assertThat(notifications).isNotEmpty().anySatisfy(notification -> assertThat(notification.getParameter()).isEqualTo("disposal"));
        }
        var again = configuration();
        MigrationLineage.configure(again);
        assertThat(again.isOutOfOrder()).isFalse();
        assertThat(again.load().migrate().migrationsExecuted).isZero();
        again.load().validate();
    }

    @Test void unknownOriginalChecksumFailsBeforeApplyingAnything() {
        baseline("voice");
        jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='202610260001'");
        var before = history();
        assertThatThrownBy(() -> MigrationLineage.configure(configuration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unrecognized 20261026 migration history");
        assertThat(history()).isEqualTo(before);
        assertThat(column("flight_avoidance_policy", "policy_id")).isZero();
    }

    @Test void failedOriginalMigrationIsNotAssumedApplied() {
        baseline("flight");
        jdbc.update("UPDATE flyway_schema_history SET success=false WHERE version='202610260001'");
        var before = history();
        assertThatThrownBy(() -> MigrationLineage.configure(configuration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unrecognized 20261026 migration history");
        assertThat(history()).isEqualTo(before);
        assertThat(column("uav_auto_voice_task", "response_order_basis_json")).isZero();
    }

    @Test void partialFlightSchemaIsRejectedInsteadOfResettingPolicyOrHistory() {
        baseline("flight");
        jdbc.update("UPDATE flight_avoidance_policy SET version=8 WHERE policy_id='counter'");
        jdbc.execute("DROP TABLE adsb_aircraft");
        var original = jdbc.queryForMap("SELECT * FROM flyway_schema_history WHERE version='202610260001'");
        var upgrade = configuration();
        MigrationLineage.configure(upgrade);
        assertThatThrownBy(() -> upgrade.load().migrate()).hasStackTraceContaining("Incomplete flight avoidance schema");
        assertThat(jdbc.queryForMap("SELECT * FROM flyway_schema_history WHERE version='202610260001'")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT version FROM flight_avoidance_policy WHERE policy_id='counter'", Long.class)).isEqualTo(8L);
        assertThat(column("uav_auto_voice_task", "response_order_basis_json")).isZero();
    }

    private void insertCheck() {
        jdbc.update("""
                INSERT INTO counter_flight_check(check_id,device_id,countermeasure_mode,stage,result,
                    acknowledged,aircraft_json,policy_version,checked_at)
                VALUES(?,?,'FORCE_LAND','DIRECT','CLEAR',false,'[]',8,987)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    private int column(String table, String column) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?",
                Integer.class, schema, table, column);
    }
}
