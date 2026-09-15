package com.uav.lowaltitude.modules.target.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class TargetReadPostgresApiTest {

    private static final String SCHEMA_PREFIX = "stage2_target_api_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 4, 12, 0, 0, 0, ZoneOffset.UTC);

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DataSource dataSource;

    private String suffix;
    private String role;
    private String userId;
    private String sessionId;
    private String orgA;
    private String districtA;
    private String orgB;
    private String districtB;
    private String sourceId;
    private String deviceId;
    private String targetNewest;
    private String targetNoLocation;
    private String targetOtherScope;
    private String newestLink;
    private String noLocationLink;
    private String otherLink;
    private String newestTrack;
    private String olderTrack;
    private String otherTrack;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        initializeSchema();
        registry.add("spring.datasource.url", () -> schemaUrl(requiredEnvironment("POSTGRES_TEST_URL")));
        registry.add("spring.datasource.username", () -> requiredEnvironment("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("app.dev-seed.enabled", () -> "false");
        registry.add("app.live-device.enabled", () -> "false");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (!schemaCreated) return;
        assertSafeSchema();
        try (Connection connection = rootDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema " + SCHEMA + " cascade");
        } finally {
            schemaCreated = false;
        }
    }

    @BeforeEach
    void seedPostgresFixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).containsIgnoringCase("PostgreSQL");
        }
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);

        suffix = UUID.randomUUID().toString().substring(0, 8);
        role = "ROLE-TARGET-PG-" + suffix;
        userId = id();
        sessionId = id();
        orgA = id();
        districtA = id();
        orgB = id();
        districtB = id();
        sourceId = id();
        deviceId = id();
        targetNewest = id();
        targetNoLocation = id();
        targetOtherScope = id();
        newestLink = id();
        noLocationLink = id();
        otherLink = id();
        newestTrack = id();
        olderTrack = id();
        otherTrack = id();

        jdbc.update("""
                insert into app_role
                    (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)
                values (?,?,'',false,true,0,0,0,false)
                """, role, "Postgres target reader " + suffix);
        jdbc.update("""
                insert into app_role_permission
                    (role_code,permission_code,permission_level,menu_enabled,created_at)
                values (?,'target:read','READ',false,current_timestamp)
                """, role);
        organization(orgA, "PG-ORG-A-" + suffix);
        district(districtA, "PG-DIST-A-" + suffix);
        organization(orgB, "PG-ORG-B-" + suffix);
        district(districtB, "PG-DIST-B-" + suffix);
        jdbc.update("""
                insert into app_user
                    (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,
                     permission_version,created_at,updated_at,version)
                values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)
                """, userId, "pg-target-reader-" + suffix, "Postgres target reader", role);
        jdbc.update("""
                insert into app_user_data_scope (user_id,org_id,district_id,created_at)
                values (?,?,?,current_timestamp)
                """, userId, orgA, districtA);
        jdbc.update("""
                insert into app_session (session_id,user_id,expire_at,ip,permission_version)
                values (?,?,?,'127.0.0.1',0)
                """, sessionId, userId, System.currentTimeMillis() + 3_600_000L);

        jdbc.update("""
                insert into integration_source
                    (source_id,source_code,name,protocol_code,protocol_version,enabled,credential_ref,
                     source_mode,created_at,updated_at,version)
                values (?,?,?,'RADAR','3.0',true,'postgres-secret','mock',?,?,0)
                """, sourceId, "PG-SOURCE-" + suffix, "Postgres source", T0, T0);
        jdbc.update("""
                insert into device
                    (device_id,source_id,external_device_id,device_no,name,enabled,source_mode,
                     owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,?, ?,?,true,'mock',?,?,?, ?,0)
                """, deviceId, sourceId, "PG-EXT-DEVICE-" + suffix, "PG-DEVICE-" + suffix,
                "Postgres radar", orgA, districtA, T0, T0);

        target(targetNewest, "PG-TARGET-A-" + suffix, T0, T0.plusSeconds(9), orgA, districtA);
        target(targetNoLocation, "PG-TARGET-B-" + suffix, T0, T0.plusSeconds(4), orgA, districtA);
        target(targetOtherScope, "PG-TARGET-C-" + suffix, T0, T0.plusSeconds(20), orgB, districtB);
        latestState(targetNewest, true, T0.plusSeconds(9));
        latestState(targetNoLocation, false, T0.plusSeconds(4));
        latestState(targetOtherScope, false, T0.plusSeconds(20));

        link(newestLink, targetNewest, "session-newest", "external-newest");
        link(noLocationLink, targetNoLocation, "session-no-location", "external-no-location");
        link(otherLink, targetOtherScope, "session-other", "external-other");
        track(newestTrack, targetNewest, newestLink, "track-newest", T0.plusSeconds(7));
        track(olderTrack, targetNewest, newestLink, "track-older", T0.plusSeconds(6));
        track(otherTrack, targetOtherScope, otherLink, "track-other", T0.plusSeconds(21));
        point(id(), newestTrack, 2, T0.plusSeconds(8), T0.plusSeconds(10), 120.2, 30.2);
        point(id(), newestTrack, 1, null, T0.plusSeconds(5), 120.1, 30.1);
        point(id(), otherTrack, 1, T0.plusSeconds(21), T0.plusSeconds(22), 121.0, 31.0);
    }

    @Test
    void postgresPostgisServesAllTargetApisWithTimeFiltersPagingAndStableOrdering() throws Exception {
        long from = T0.toInstant().toEpochMilli();
        long to = T0.plusSeconds(10).toInstant().toEpochMilli();

        JsonNode firstPage = getJson("/api/v1/targets?seen_from=" + from + "&seen_to=" + to
                + "&page=1&size=1").path("data");
        assertThat(firstPage.path("total").asLong()).isEqualTo(2);
        assertThat(firstPage.path("items")).hasSize(1);
        assertThat(firstPage.path("items").get(0).path("target_id").asText()).isEqualTo(targetNewest);
        assertThat(firstPage.path("items").get(0).path("latest_state").path("location")
                .path("coordinate_system").asText()).isEqualTo("WGS84");
        assertThat(firstPage.path("items").get(0).path("latest_state").path("location")
                .path("longitude").decimalValue()).isEqualByComparingTo("120.125");

        JsonNode secondPage = getJson("/api/v1/targets?seen_from=" + from + "&seen_to=" + to
                + "&page=2&size=1").path("data");
        assertThat(secondPage.path("items").get(0).path("target_id").asText()).isEqualTo(targetNoLocation);
        assertThat(secondPage.path("items").get(0).path("latest_state").has("location")).isFalse();

        JsonNode detail = getJson("/api/v1/targets/" + targetNewest).path("data");
        assertThat(detail.path("source_links")).hasSize(1);
        assertThat(detail.path("latest_state").path("location").path("latitude").decimalValue())
                .isEqualByComparingTo("30.25");

        JsonNode tracks = getJson("/api/v1/targets/" + targetNewest
                + "/tracks?started_from=" + T0.plusSeconds(6).toInstant().toEpochMilli()
                + "&started_to=" + T0.plusSeconds(7).toInstant().toEpochMilli()
                + "&page=1&size=1").path("data");
        assertThat(tracks.path("total").asLong()).isEqualTo(2);
        assertThat(tracks.path("items").get(0).path("track_id").asText()).isEqualTo(newestTrack);

        JsonNode firstPointPage = getJson("/api/v1/tracks/" + newestTrack
                + "/points?time_from=" + T0.plusSeconds(5).toInstant().toEpochMilli()
                + "&time_to=" + T0.plusSeconds(8).toInstant().toEpochMilli()
                + "&page=1&size=1").path("data");
        assertThat(firstPointPage.path("total").asLong()).isEqualTo(2);
        assertThat(firstPointPage.path("items").get(0).path("point_seq").asLong()).isEqualTo(1);
        assertThat(firstPointPage.path("items").get(0).path("time_basis").asText()).isEqualTo("RECEIVED");
        assertThat(firstPointPage.path("items").get(0).path("location").path("longitude").decimalValue())
                .isEqualByComparingTo("120.1");

        JsonNode secondPointPage = getJson("/api/v1/tracks/" + newestTrack
                + "/points?page=2&size=1").path("data");
        assertThat(secondPointPage.path("items").get(0).path("point_seq").asLong()).isEqualTo(2);
        assertThat(firstPage.toString()).doesNotContain("credential_ref", "postgres-secret", "raw_position");
    }

    @Test
    void postgresKeepsPermissionAndAssignedTupleScopeEnforcement() throws Exception {
        JsonNode list = getJson("/api/v1/targets?size=100").path("data");
        assertThat(list.path("total").asLong()).isEqualTo(2);
        assertThat(list.path("items").findValuesAsText("target_id"))
                .containsExactly(targetNewest, targetNoLocation)
                .doesNotContain(targetOtherScope);
        assertError("/api/v1/targets/" + targetOtherScope, 404, "TARGET_NOT_FOUND");
        assertError("/api/v1/targets/" + targetOtherScope + "/tracks", 404, "TARGET_NOT_FOUND");
        assertError("/api/v1/tracks/" + otherTrack + "/points", 404, "TRACK_NOT_FOUND");

        jdbc.update("delete from app_role_permission where role_code=? and permission_code='target:read'", role);
        assertError("/api/v1/targets?page=0", 403, "FORBIDDEN");
        assertError("/api/v1/targets/" + targetNewest, 403, "FORBIDDEN");
        assertError("/api/v1/tracks/" + newestTrack + "/points", 403, "FORBIDDEN");
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void assertError(String path, int expectedStatus, String code) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private void organization(String id, String code) {
        jdbc.update("""
                insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version)
                values (?,?,?,true,0,0,0)
                """, id, code, code);
    }

    private void district(String id, String code) {
        jdbc.update("""
                insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version)
                values (?,?,?,true,0,0,0)
                """, id, code, code);
    }

    private void target(String id, String number, OffsetDateTime firstSeen, OffsetDateTime lastSeen,
            String organizationId, String districtId) {
        jdbc.update("""
                insert into target
                    (target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,
                     owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,'UAV',?,?,'mock',?,?,?, ?,0)
                """, id, number, firstSeen, lastSeen, organizationId, districtId, T0, T0.plusSeconds(30));
    }

    private void latestState(String targetId, boolean withLocation, OffsetDateTime observedAt) {
        if (withLocation) {
            jdbc.update("""
                    insert into target_latest_state
                        (target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                    values (?,ST_SetSRID(ST_MakePoint(120.125,30.25),4326),?,?,cast(? as jsonb),?,?,0)
                    """, targetId, observedAt, observedAt.plusSeconds(1), "[]", observedAt, observedAt);
            return;
        }
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,null,?,?,cast(? as jsonb),?,?,0)
                """, targetId, observedAt, observedAt.plusSeconds(1), "[]", observedAt, observedAt);
    }

    private void link(String id, String targetId, String sessionKey, String externalTargetId) {
        jdbc.update("""
                insert into target_source_link
                    (link_id,target_id,source_id,device_id,source_session_key,external_target_id,created_at)
                values (?,?,?,?,?,?,?)
                """, id, targetId, sourceId, deviceId, sessionKey, externalTargetId, T0);
    }

    private void track(String id, String targetId, String linkId, String externalTrackId,
            OffsetDateTime startedAt) {
        jdbc.update("""
                insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at)
                values (?,?,?,?,?,?)
                """, id, targetId, linkId, externalTrackId, startedAt, T0);
    }

    private void point(String id, String trackId, long sequence, OffsetDateTime observedAt,
            OffsetDateTime receivedAt, double longitude, double latitude) {
        jdbc.update("""
                insert into track_point
                    (point_id,track_id,point_seq,observed_at,received_at,location,raw_position,created_at)
                values (?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326),cast(? as jsonb),?)
                """, id, trackId, sequence, observedAt, receivedAt, longitude, latitude,
                "{\"secret\":true}", T0);
    }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        if (database == null || !database.matches("^stage2_target_verify_[a-z0-9_]+$")) {
            throw new IllegalStateException("Refusing target API verification outside an isolated database");
        }
        String extension = root.queryForObject(
                "select extversion from pg_extension where extname='postgis'", String.class);
        if (extension == null || extension.isBlank()) {
            throw new IllegalStateException("PostGIS is required for target API verification");
        }
        root.execute("create schema " + SCHEMA);
        schemaCreated = true;
        try {
            Flyway.configure()
                    .dataSource(rootDataSource())
                    .schemas(SCHEMA)
                    .defaultSchema(SCHEMA)
                    .createSchemas(false)
                    .cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql")
                    .load()
                    .migrate();
        } catch (RuntimeException exception) {
            try {
                root.execute("drop schema " + SCHEMA + " cascade");
            } finally {
                schemaCreated = false;
            }
            throw exception;
        }
    }

    private static DataSource rootDataSource() {
        return new DriverManagerDataSource(
                requiredEnvironment("POSTGRES_TEST_URL"),
                requiredEnvironment("POSTGRES_TEST_USER"),
                requiredEnvironment("POSTGRES_TEST_PASSWORD"));
    }

    private static String schemaUrl(String baseUrl) {
        if (!baseUrl.startsWith("jdbc:postgresql:") || baseUrl.toLowerCase().contains("currentschema=")) {
            throw new IllegalStateException("POSTGRES_TEST_URL must be PostgreSQL without currentSchema");
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void assertSafeSchema() {
        if (!SCHEMA.matches("^stage2_target_api_[a-f0-9]{32}$")) {
            throw new IllegalStateException("Unsafe target API verification schema");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
