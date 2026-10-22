package com.uav.lowaltitude.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.uav.lowaltitude.platform.database.MigrationLineage;

/** Upgrade real PostgreSQL/PostGIS in a disposable schema; never infer the datum of existing positions. */
@EnabledIfEnvironmentVariable(named="PLAN_FILING_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/plan_filing_verify_[a-zA-Z0-9_]+")
class PlanFilingCoordinateMigrationPostgresTest {
    @Test void upgradeKeepsHistoricalCoordinatesUnknownAndConstrainsNewDeclarations() {
        String url=System.getenv("PLAN_FILING_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/plan_filing_verify_[a-zA-Z0-9_]+"))
            throw new IllegalStateException("Dedicated plan_filing_verify database required");
        String schema="filing_datum_"+UUID.randomUUID().toString().replace("-","");
        var rootSource=new DriverManagerDataSource(url,System.getenv("PLAN_FILING_TEST_DB_USER"),System.getenv("PLAN_FILING_TEST_DB_PASSWORD"));
        var root=new JdbcTemplate(rootSource);
        root.execute("CREATE SCHEMA "+schema);
        try {
            var source=new DriverManagerDataSource(url+"?currentSchema="+schema+",public",rootSource.getUsername(),rootSource.getPassword());
            var before=configuration(source,schema).target("202610200004");
            MigrationLineage.configure(before);before.load().migrate();
            var jdbc=new JdbcTemplate(source);
            String route=UUID.randomUUID().toString(),version=UUID.randomUUID().toString(),plan=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,'mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                    route,route,"Coordinate declaration migration fixture");
            jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) VALUES(?,?,1,ST_GeomFromText('LINESTRING(-74 -23,-73 -22)',4326),100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    version,route);
            jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,route_version_id,takeoff_longitude,takeoff_latitude,created_at,updated_at,version) VALUES(?,?,'PENDING','mock',?,-74,-23,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                    plan,plan,version);
            String facts="SELECT plan_id,takeoff_longitude,takeoff_latitude,created_at,updated_at,version FROM flight_plan WHERE plan_id=?";
            var old=jdbc.queryForMap(facts,plan);
            var upgrade=configuration(source,schema);MigrationLineage.configure(upgrade);
            var latest=upgrade.load();latest.migrate();
            assertThat(jdbc.queryForMap(facts,plan)).isEqualTo(old);
            assertThat(jdbc.queryForObject("SELECT filing_coordinate_system FROM flight_plan WHERE plan_id=?",String.class,plan)).isNull();
            jdbc.update("UPDATE flight_plan SET filing_coordinate_system='WGS84' WHERE plan_id=?",plan);
            assertThat(jdbc.queryForObject("SELECT filing_coordinate_system FROM flight_plan WHERE plan_id=?",String.class,plan)).isEqualTo("WGS84");
            for(String datum:new String[]{"GCJ02","BD09","CGCS2000",""})
                assertThatThrownBy(()->jdbc.update("UPDATE flight_plan SET filing_coordinate_system=? WHERE plan_id=?",datum,plan))
                        .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            latest.validate();
        } finally {
            root.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }

    private static FluentConfiguration configuration(DriverManagerDataSource source,String schema) {
        return Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .createSchemas(false).cleanDisabled(true).locations("classpath:db/migration","classpath:db/postgresql");
    }
}
