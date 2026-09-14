package com.uav.lowaltitude.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FlightRouteSchemaMigrationTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void migrate() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:flight_route_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void createsRouteVersionAndFlightPlanWithExactVersionReference() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='route'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='route_version'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='flight_plan'", Integer.class))
                .isEqualTo(1);

        ownership();
        jdbc.update("""
                insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                values ('route-1','ROUTE-1','Route',true,'mock','org-1','district-1',current_timestamp,current_timestamp,0)
                """);
        jdbc.update("""
                insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,
                                           valid_from,created_at)
                values ('route-version-1','route-1',1,GEOMETRY 'SRID=4326;LINESTRING (120 30,121 31)',100,
                        current_timestamp,current_timestamp)
                """);
        jdbc.update("""
                insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,
                                         created_at,updated_at,version)
                values ('plan-1','PLAN-1','PENDING','mock','route-version-1','org-1','district-1',
                        current_timestamp,current_timestamp,0)
                """);

        assertThatThrownBy(() -> jdbc.update("""
                insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,
                                         created_at,updated_at,version)
                values ('plan-bad','PLAN-BAD','PENDING','mock','missing','org-1','district-1',
                        current_timestamp,current_timestamp,0)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPartialOwnershipAndAltitudeDatumPairs() {
        ownership();
        assertThatThrownBy(() -> jdbc.update("""
                insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                values ('route-bad','ROUTE-BAD','Route',true,'mock',null,'district-1',current_timestamp,current_timestamp,0)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                values ('route-2','ROUTE-2','Route',true,'mock','org-1','district-1',current_timestamp,current_timestamp,0)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                insert into route_version (route_version_id,route_id,version_no,centerline,min_altitude_m,valid_from,created_at)
                values ('route-version-bad','route-2',1,GEOMETRY 'SRID=4326;LINESTRING (120 30,121 31)',10,
                        current_timestamp,current_timestamp)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void ownership() {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values ('org-1','ORG-1','Org',true,0,0,0)");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values ('district-1','DIST-1','District',true,0,0,0)");
    }
}
