package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class FlightReadPostgresApiTest {

    private static final String SCHEMA = "stage3_flight_api_" + UUID.randomUUID().toString().replace("-", "");

    @Test
    void referencedRouteVersionCannotBeRewrittenInPostgis() throws Exception {
        assertSafeSchema();
        DataSource rootDataSource = new DriverManagerDataSource(required("POSTGRES_TEST_URL"), required("POSTGRES_TEST_USER"),
                required("POSTGRES_TEST_PASSWORD"));
        JdbcTemplate root = new JdbcTemplate(rootDataSource);
        root.execute("create schema " + SCHEMA);
        try {
            Flyway.configure().dataSource(rootDataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false)
                    .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(required("POSTGRES_TEST_URL")),
                    required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD")));
            jdbc.update("""
                    insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version)
                    values ('org-1','ORG-1','Org',true,0,0,0)
                    """);
            jdbc.update("""
                    insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version)
                    values ('district-1','DISTRICT-1','District',true,0,0,0)
                    """);
            jdbc.update("""
                    insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                    values ('route-1','ROUTE-1','Route',true,'mock','org-1','district-1',current_timestamp,current_timestamp,0)
                    """);
            jdbc.update("""
                    insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at)
                    values ('route-version-1','route-1',1,ST_GeomFromText('LINESTRING(120 30,121 31)',4326),100,
                            current_timestamp,current_timestamp)
                    """);
            jdbc.update("""
                    insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,
                                             created_at,updated_at,version)
                    values ('plan-1','PLAN-1','PENDING','mock','route-version-1','org-1','district-1',
                            current_timestamp,current_timestamp,0)
                    """);

            assertThatThrownBy(() -> jdbc.update("""
                    update route_version set corridor_width_m=200 where route_version_id='route-version-1'
                    """)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            try (Connection connection = rootDataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("drop schema " + SCHEMA + " cascade");
            }
        }
    }

    private static String schemaUrl(String baseUrl) {
        if (!baseUrl.startsWith("jdbc:postgresql:") || baseUrl.toLowerCase().contains("currentschema=")) {
            throw new IllegalStateException("POSTGRES_TEST_URL must be PostgreSQL without currentSchema");
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void assertSafeSchema() {
        if (!SCHEMA.matches("^stage3_flight_api_[a-f0-9]{32}$")) {
            throw new IllegalStateException("Unsafe flight API verification schema");
        }
    }
}
