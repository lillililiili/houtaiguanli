package com.uav.lowaltitude.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import com.uav.lowaltitude.modules.flight.infrastructure.LocalFlightPlanInputRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class LocalPlanNumberPostgresTest {
    @Test void migrationAndConcurrentAllocationPreserveExistingIdentities() throws Exception {
        String base = System.getenv("POSTGRES_TEST_URL");
        if (!base.startsWith("jdbc:postgresql:") || base.toLowerCase().contains("currentschema="))
            throw new IllegalStateException("Use an isolated PostgreSQL test database without currentSchema");
        String schema = "plan_number_" + UUID.randomUUID().toString().replace("-", "");
        DataSource rootSource = source(base);
        JdbcTemplate root = new JdbcTemplate(rootSource);
        root.execute("CREATE SCHEMA " + schema);
        try {
            var before = Flyway.configure().dataSource(rootSource).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).locations("classpath:db/migration", "classpath:db/postgresql")
                    .target("202610050002").load();
            before.migrate();
            DataSource ds = source(base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public");
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            String route = UUID.randomUUID().toString(), version = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,created_at,updated_at,version) "
                    + "VALUES(?,?,?,TRUE,'mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", route, route, "Number migration fixture");
            jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) "
                    + "VALUES(?,?,1,ST_GeomFromText('LINESTRING(118 37,118.1 37.1)',4326),100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", version, route);
            for (String mode : new String[]{"mock", "replay", "live"}) {
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,route_version_id,created_at,updated_at,version) "
                        + "VALUES(?,?,'PENDING',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", id,
                        mode.equals("live") ? "SIM-000001" : "EXT-SIM-" + id, mode, version);
            }
            var oldRows = jdbc.queryForList("SELECT * FROM flight_plan ORDER BY plan_id");
            var latest = Flyway.configure().dataSource(rootSource).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).locations("classpath:db/migration", "classpath:db/postgresql").load();
            latest.migrate();
            assertThat(jdbc.queryForList("SELECT * FROM flight_plan ORDER BY plan_id")).isEqualTo(oldRows);
            var repository = new LocalFlightPlanInputRepository(jdbc);
            assertThat(repository.nextPlanNo()).isEqualTo("SIM-000002");
            var allocated = new HashSet<String>();
            var pool = Executors.newFixedThreadPool(8);
            try {
                var calls = new ArrayList<Callable<String>>();
                for (int n = 0; n < 48; n++) calls.add(repository::nextPlanNo);
                for (var result : pool.invokeAll(calls)) assertThat(allocated.add(result.get())).isTrue();
            } finally { pool.shutdownNow(); }
            assertThat(allocated).hasSize(48);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
            String rolledBack = transaction.execute(status -> {
                String number = repository.nextPlanNo();
                status.setRollbackOnly();
                return number;
            });
            assertThat(repository.nextPlanNo()).isNotEqualTo(rolledBack);
            latest.migrate();
            String afterRestart = new LocalFlightPlanInputRepository(new JdbcTemplate(ds)).nextPlanNo();
            assertThat(allocated).doesNotContain(afterRestart);
            assertThat(jdbc.queryForList("SELECT * FROM flight_plan ORDER BY plan_id")).isEqualTo(oldRows);
        } finally { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    private DataSource source(String url) {
        return new DriverManagerDataSource(url, System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"));
    }
}
