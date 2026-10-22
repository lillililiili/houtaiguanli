package com.uav.lowaltitude.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Both actually applied 20261020 branches upgrade without rewriting old Flyway rows or user bindings. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class FeatureMigrationLineagePostgresTest {
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
        schema = "features_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + "?currentSchema=" + schema + ",public", base.getUsername(), base.getPassword());
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach void cleanup() {
        if (schema != null && schema.matches("features_[a-f0-9]{32}")) root.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private FluentConfiguration configuration() {
        return Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration", "classpath:db/postgresql");
    }

    private void baseline(String branch) {
        if (branch.equals("empty")) return;
        var before = configuration();
        MigrationLineage.configure(before);
        if (branch.equals("local")) before.target("202610210001");
        else {
            var locations = new java.util.ArrayList<>(Arrays.stream(before.getLocations())
                    .map(location -> location.getDescriptor())
                    .filter(location -> !location.contains("features-local")).toList());
            locations.add("classpath:db/lineage/features-remote");
            before.locations(locations.toArray(String[]::new));
            var originals = before.getJavaMigrationClassProvider().getClasses().stream()
                    .filter(type -> !type.getSimpleName().equals("V202610200002__unit_delegation")
                            && !type.getSimpleName().equals("V202610210001__unit_role_copy_origin")).toList();
            before.javaMigrationClassProvider(() -> originals).target("202610200002");
        }
        before.load().migrate();
    }

    private List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    }

    @ParameterizedTest @ValueSource(strings={"empty", "local", "remote"})
    void originalRowsAndBindingsSurviveCompleteUpgrade(String branch) {
        baseline(branch);
        var before = branch.equals("empty") ? List.<Map<String, Object>>of() : history();
        String org = UUID.randomUUID().toString(), role = "R-" + UUID.randomUUID().toString().substring(0, 15);
        String user = UUID.randomUUID().toString();
        List<Map<String, Object>> roles = List.of(), users = List.of();
        if (!branch.equals("empty")) {
            jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,true,12,13,7)", org, org, "迁移前单位");
            jdbc.update("INSERT INTO app_role(role_code,name,builtin,enabled,created_at,updated_at,version) VALUES(?,?,false,true,12,13,7)", role, "迁移前人员角色");
            jdbc.update("""
                    INSERT INTO app_user(user_id,account,name,org_id,role_code,status,password_hash,scope_mode,
                        permission_version,created_at,updated_at,version)
                    VALUES(?,?,?, ?,?,'ACTIVE','migration-fixture','ALL',5,12,13,7)
                    """, user, user, "迁移前人员", org, role);
            roles = jdbc.queryForList("SELECT role_code,name,enabled,version,created_at,updated_at FROM app_role WHERE role_code=?", role);
            users = jdbc.queryForList("SELECT user_id,org_id,role_code,scope_mode,permission_version,version FROM app_user WHERE user_id=?", user);
        }
        var upgrade = configuration(); MigrationLineage.configure(upgrade);
        assertThat(upgrade.isValidateOnMigrate()).isTrue();
        upgrade.load().migrate();
        assertThat(history().subList(0, before.size())).containsExactlyElementsOf(before);
        assertThat(column("disposal_authorization", "countermeasure_mode")).isEqualTo(1);
        assertThat(column("airspace", "deleted_at")).isEqualTo(1);
        assertThat(column("app_role", "owner_org_id")).isEqualTo(1);
        assertThat(column("app_role", "copied_from_role_code")).isEqualTo(1);
        assertThat(column("flight_plan", "filing_coordinate_system")).isEqualTo(1);
        assertThat(column("advisory_voice_recording", "purpose")).isEqualTo(1);
        assertThat(column("commission_task", "management_org_id")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM integration_source WHERE source_id='k03-prediction-live'", String.class))
                .isEqualTo("K03短时预测（实测来源）");
        assertThat(jdbc.queryForObject("SELECT name FROM integration_source WHERE source_id='k03-prediction-replay'", String.class))
                .isEqualTo("K03短时预测（实时模拟来源）");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='prediction_snapshot'::regclass AND NOT tgisinternal", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='prediction_current'::regclass AND NOT tgisinternal", Integer.class)).isEqualTo(2);
        if (!branch.equals("empty")) {
            assertThat(jdbc.queryForList("SELECT role_code,name,enabled,version,created_at,updated_at FROM app_role WHERE role_code=?", role)).isEqualTo(roles);
            assertThat(jdbc.queryForList("SELECT user_id,org_id,role_code,scope_mode,permission_version,version FROM app_user WHERE user_id=?", user)).isEqualTo(users);
        }
        var again = configuration(); MigrationLineage.configure(again);
        assertThat(again.isOutOfOrder()).isFalse();
        assertThat(again.load().migrate().migrationsExecuted).isZero();
        again.load().validate();
    }

    @Test void unknownChecksumStopsBeforeApplyingCompatibilityMigration() {
        baseline("remote");
        jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='202610200001'");
        var before = history();
        assertThatThrownBy(() -> MigrationLineage.configure(configuration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unrecognized or mixed 20261020");
        assertThat(history()).isEqualTo(before);
        assertThat(column("app_role", "owner_org_id")).isZero();
    }

    @Test void mixedKnownBranchesAreRejected() {
        baseline("local");
        jdbc.update("UPDATE flyway_schema_history SET script='V202610200002__local_airspace_lifecycle.sql',checksum=-852369259 WHERE version='202610200002'");
        assertThatThrownBy(() -> MigrationLineage.configure(configuration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unrecognized or mixed 20261020");
    }

    private int column(String table, String column) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?",
                Integer.class, schema, table, column);
    }
}
