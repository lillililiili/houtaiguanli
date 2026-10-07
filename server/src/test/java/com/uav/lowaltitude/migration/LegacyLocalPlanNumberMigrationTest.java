package com.uav.lowaltitude.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import com.uav.lowaltitude.modules.flight.infrastructure.LocalFlightPlanInputRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyLocalPlanNumberMigrationTest {
    @Test void convertsOnlyLocalLegacyNumbersInH2() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:legacy_number_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        verify(ds, ds, "public", false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches=".+")
    @EnabledIfEnvironmentVariable(named="POSTGRES_TEST_USER", matches=".+")
    @EnabledIfEnvironmentVariable(named="POSTGRES_TEST_PASSWORD", matches=".*")
    void convertsOnlyLocalLegacyNumbersInPostgis() {
        String base = System.getenv("POSTGRES_TEST_URL");
        if (!base.startsWith("jdbc:postgresql:") || base.toLowerCase().contains("currentschema="))
            throw new IllegalStateException("Use an isolated PostgreSQL test database without currentSchema");
        String schema = "legacy_number_" + UUID.randomUUID().toString().replace("-", "");
        DataSource rootSource = pg(base);
        JdbcTemplate root = new JdbcTemplate(rootSource);
        root.execute("CREATE SCHEMA " + schema);
        try {
            verify(rootSource, pg(base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public"), schema, true);
        } finally { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    private void verify(DataSource root, DataSource ds, String schema, boolean pg) {
        String[] locations = pg ? new String[]{"classpath:db/migration", "classpath:db/postgresql"}
                : new String[]{"classpath:db/migration"};
        Flyway.configure().dataSource(root).schemas(schema).defaultSchema(schema).createSchemas(false)
                .locations(locations).target("202610060002").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String route = id(), version = id(), actor = id();
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,created_at,updated_at,version) "
                + "VALUES(?,?,?,TRUE,'mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", route, route, "Legacy number fixture");
        String geometry = pg ? "ST_GeomFromText('LINESTRING(118 37,118.1 37.1)',4326)" : "GEOMETRY 'SRID=4326;LINESTRING (118 37,118.1 37.1)'";
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) "
                + "VALUES(?,?,1," + geometry + ",100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", version, route);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash) VALUES(?,?,?,'ROLE-ADMIN','ACTIVE','test-only')",
                actor, actor, "Migration test operator");
        List<String> converted = new ArrayList<>();
        for (String mode : List.of("mock", "replay")) {
            String plan = id();
            plan(jdbc, plan, "EXT-SIM-" + plan, mode, version);
            message(jdbc, plan, actor);
            converted.add(plan);
        }
        String live = id(), unrelated = id(), custom = id(), shortPlan = id();
        plan(jdbc, live, "EXT-SIM-" + live, "live", version); message(jdbc, live, actor);
        plan(jdbc, unrelated, "EXT-SIM-" + unrelated, "mock", version);
        plan(jdbc, custom, "EXTERNAL-" + custom, "replay", version); message(jdbc, custom, actor);
        plan(jdbc, shortPlan, "SIM-000001", "mock", version); message(jdbc, shortPlan, actor);
        // Stable relationships must survive the rename, including duplicate registries.
        jdbc.update("INSERT INTO flight_plan_duplicate(duplicate_plan_id,canonical_plan_id,reason) VALUES(?,?,'fixture')",
                converted.get(1), converted.get(0));
        var before = jdbc.queryForList("SELECT * FROM flight_plan ORDER BY plan_id");
        var receipts = jdbc.queryForList("SELECT * FROM local_interface_message ORDER BY message_id");
        var links = jdbc.queryForList("SELECT * FROM flight_plan_duplicate");
        var latest = Flyway.configure().dataSource(root).schemas(schema).defaultSchema(schema).createSchemas(false)
                .locations(locations).load();
        latest.migrate();
        for (Map<String,Object> old : before) {
            String planId = (String) old.get("plan_id");
            var current = jdbc.queryForMap("SELECT * FROM flight_plan WHERE plan_id=?", planId);
            if (converted.contains(planId)) {
                String newNumber = (String) current.get("plan_no");
                assertThat(newNumber).matches("SIM-[0-9]{6,}").isNotEqualTo("SIM-000001");
                assertThat(jdbc.queryForMap("SELECT old_plan_no,new_plan_no FROM local_flight_plan_number_change WHERE plan_id=?", planId))
                        .containsEntry("old_plan_no", old.get("plan_no")).containsEntry("new_plan_no", newNumber);
                current.put("plan_no", old.get("plan_no"));
            }
            assertThat(current).isEqualTo(old);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_flight_plan_number_change", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM local_interface_message ORDER BY message_id")).isEqualTo(receipts);
        assertThat(jdbc.queryForList("SELECT * FROM flight_plan_duplicate")).isEqualTo(links);
        var numbers = jdbc.queryForList("SELECT plan_no FROM flight_plan", String.class);
        assertThat(numbers).doesNotHaveDuplicates();
        assertThat(numbers).doesNotContain(new LocalFlightPlanInputRepository(jdbc).nextPlanNo());
        var mapping = jdbc.queryForList("SELECT * FROM local_flight_plan_number_change ORDER BY plan_id");
        latest.migrate();
        assertThat(jdbc.queryForList("SELECT * FROM local_flight_plan_number_change ORDER BY plan_id")).isEqualTo(mapping);
        assertThat(jdbc.queryForList("SELECT plan_no FROM flight_plan", String.class)).containsExactlyElementsOf(numbers);
    }

    private void plan(JdbcTemplate jdbc, String id, String number, String mode, String version) {
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,route_version_id,created_at,updated_at,version) "
                + "VALUES(?,?,'CANCELLED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,3)", id, number, mode, version);
    }

    private void message(JdbcTemplate jdbc, String plan, String actor) {
        jdbc.update("INSERT INTO local_interface_message(message_id,external_id,kind,direction,subject_id,created_by,state,payload,result,created_at,updated_at) "
                + "VALUES(?,?,'FLIGHT_PLAN','IN',?,?,'ACCEPTED','{}',?,1,1)", id(), id(), plan, actor, "{\"plan_no\":\"EXT-SIM-" + plan + "\"}");
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static DataSource pg(String url) {
        return new DriverManagerDataSource(url, System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
    }
}
