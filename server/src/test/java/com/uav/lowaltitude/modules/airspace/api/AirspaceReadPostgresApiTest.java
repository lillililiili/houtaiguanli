package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.OffsetDateTime;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository.ConflictRow;
import com.uav.lowaltitude.integration.mock.LocalStage3PlanningSeeder;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 真实 PostGIS 验证不把 H2 的 geometry 兼容模式当作空间证据：此处独立 schema 会在 finally 删除。
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class AirspaceReadPostgresApiTest {
    private static final String SCHEMA = "stage3_airspace_api_" + UUID.randomUUID().toString().replace("-", "");

    @Test
    void postgisUsesGeographyHalfWidthBufferRatherThanDegreeOrFullWidthGeometry() throws Exception {
        assertSafeSchema();
        DataSource rootDataSource = new DriverManagerDataSource(required("POSTGRES_TEST_URL"), required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
        JdbcTemplate root = new JdbcTemplate(rootDataSource); root.execute("create schema " + SCHEMA);
        try {
            Flyway.configure().dataSource(rootDataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false).cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(required("POSTGRES_TEST_URL")), required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD")));
            seed(jdbc);
            // 必须走生产 Repository 的候选过滤与 geography 半宽判定，不能只用测试里的 ST 函数复述算法。
            AirspaceReadRepository repository = new AirspaceReadRepository(jdbc, new DriverManagerDataSource(schemaUrl(required("POSTGRES_TEST_URL")), required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD")));
            OffsetDateTime now = jdbc.queryForObject("select current_timestamp", OffsetDateTime.class);
            PlanRow plan = new PlanRow("plan-1", "P-1", "PENDING", null, null, "mock", null, now.minusMinutes(1), now.plusMinutes(1), "org-1", "district-1", "rv-1", "route-1", "R-1", "Route", 1, null, now, now, 0, null, null, null);
            var facts = repository.conflicts(plan, new AccessDecision("postgres-reader", ScopeMode.ALL));
            assertThat(facts).anySatisfy(row -> { assertThat(row.airspaceVersionId()).isEqualTo("av-hit"); assertThat(row.horizontalRelation()).isEqualTo("OVERLAPS"); });
            // 仅 bbox 候选过滤即可排除半宽外空域；结果不能把全宽 100m 错当 100m 半径。
            assertThat(facts).noneMatch(row -> row.airspaceVersionId().equals("av-near-but-clear"));

            // 高度以同一基准才可比较；同基准分离、AGL/AMSL 不可换算及半开时间端点均保留事实而不擅自推论。
            airspace(jdbc, "height-clear", "POLYGON((118.005 36.9999,118.006 36.9999,118.006 37.0001,118.005 37.0001,118.005 36.9999))", 200, 300, "AMSL", "current_timestamp", null);
            airspace(jdbc, "agl", "POLYGON((118.005 36.9999,118.006 36.9999,118.006 37.0001,118.005 37.0001,118.005 36.9999))", 10, 100, "AGL", "current_timestamp", null);
            airspace(jdbc, "time-end", "POLYGON((118.005 36.9999,118.006 36.9999,118.006 37.0001,118.005 37.0001,118.005 36.9999))", 10, 100, "AMSL", "current_timestamp-interval '4 hour'", "current_timestamp-interval '3 minute'");
            var dimensionFacts = repository.conflicts(plan, new AccessDecision("postgres-reader", ScopeMode.ALL));
            assertThat(dimensionFacts).anySatisfy(row -> { assertThat(row.airspaceVersionId()).isEqualTo("av-height-clear"); assertThat(row.heightRelation()).isEqualTo("DISJOINT"); });
            assertThat(dimensionFacts).anySatisfy(row -> { assertThat(row.airspaceVersionId()).isEqualTo("av-agl"); assertThat(row.heightRelation()).isEqualTo("UNDETERMINED"); });
            assertThat(dimensionFacts).anySatisfy(row -> { assertThat(row.airspaceVersionId()).isEqualTo("av-time-end"); assertThat(row.timeRelation()).isEqualTo("DISJOINT"); });

            // 触边、缺边界及缺走廊必须经生产 Repository 返回具体未知原因，不能静默写成“不冲突”。
            jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values ('a-touch','A-TOUCH','touch','mock','org-1','district-1',current_timestamp,current_timestamp,0)");
            // 外环与生产走廊缓冲仅共边、不共享内部；避免测试自身使用固定度数近似。
            jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) select 'av-touch','a-touch',1,'PROHIBITED',ST_Multi(ST_Difference(ST_Buffer(centerline::geography,corridor_width_m/2+10)::geometry,ST_Buffer(centerline::geography,corridor_width_m/2)::geometry)),10,100,'AMSL',current_timestamp,current_timestamp from route_version where route_version_id='rv-1'");
            jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values ('a-null','A-NULL','null','mock','org-1','district-1',current_timestamp,current_timestamp,0)");
            jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values ('av-null','a-null',1,'PROHIBITED',null,current_timestamp,current_timestamp)");
            var unknownFacts = repository.conflicts(plan, new AccessDecision("postgres-reader", ScopeMode.ALL));
            assertFact(unknownFacts, "av-touch", "UNDETERMINED", "BOUNDARY_POLICY_UNKNOWN");
            assertFact(unknownFacts, "av-null", "UNDETERMINED", "AIRSPACE_BOUNDARY_UNKNOWN");

            jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values ('route-width','R-WIDTH','Route width unknown',true,'mock','org-1','district-1',current_timestamp,current_timestamp,0)");
            jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values ('rv-width','route-width',1,ST_GeomFromText('LINESTRING(118 37,118.01 37)',4326),null,current_timestamp,current_timestamp)");
            PlanRow widthUnknown = new PlanRow("plan-width", "P-WIDTH", "PENDING", null, null, "mock", null, now.minusMinutes(1), now.plusMinutes(1), "org-1", "district-1", "rv-width", "route-width", "R-WIDTH", "Route width unknown", 1, null, now, now, 0, null, null, null);
            assertFact(repository.conflicts(widthUnknown, new AccessDecision("postgres-reader", ScopeMode.ALL)), "av-hit", "UNDETERMINED", "CORRIDOR_WIDTH_UNKNOWN");

            // 被 assessment 引用的规则版本是历史输入，PG 触发器必须拒绝原位篡改其语义。
            assertThatThrownBy(() -> jdbc.update("update rule_version set status_code='REVOKED' where rule_version_id='rule-1'"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 相邻半开版本可跨计划窗读取；真正相交的两个版本才报告 VERSION_AMBIGUOUS 前置事实。
            jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values ('a-version','A-VERSION','versions','mock','org-1','district-1',current_timestamp,current_timestamp,0)");
            jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,valid_to,created_at) values ('av-v1','a-version',1,'PROHIBITED',ST_Multi(ST_GeomFromText('POLYGON((118 37,118.1 37,118.1 37.1,118 37.1,118 37))',4326)),current_timestamp-interval '2 hour',current_timestamp-interval '1 hour',current_timestamp)");
            jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,valid_to,created_at) values ('av-v2','a-version',2,'PROHIBITED',ST_Multi(ST_GeomFromText('POLYGON((118 37,118.1 37,118.1 37.1,118 37.1,118 37))',4326)),current_timestamp-interval '1 hour',current_timestamp+interval '1 hour',current_timestamp)");
            assertThat(repository.hasAmbiguousEffectiveVersion(plan, new AccessDecision("postgres-reader", ScopeMode.ALL))).isFalse();
            jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,valid_to,created_at) values ('av-v3','a-version',3,'PROHIBITED',ST_Multi(ST_GeomFromText('POLYGON((118 37,118.1 37,118.1 37.1,118 37.1,118 37))',4326)),current_timestamp-interval '30 minute',current_timestamp+interval '1 hour',current_timestamp)");
            assertThat(repository.hasAmbiguousEffectiveVersion(plan, new AccessDecision("postgres-reader", ScopeMode.ALL))).isTrue();
        } finally {
            try (Connection connection = rootDataSource.getConnection(); Statement statement = connection.createStatement()) { statement.execute("drop schema " + SCHEMA + " cascade"); }
        }
    }

    @Test
    void postgisRejectsOutOfRangeWgs84BoundaryCoordinates() throws Exception {
        assertSafeSchema();
        DataSource source = new DriverManagerDataSource(required("POSTGRES_TEST_URL"), required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
        JdbcTemplate root = new JdbcTemplate(source); root.execute("create schema " + SCHEMA);
        try {
            Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false).cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(required("POSTGRES_TEST_URL")), required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD")));
            jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values ('org-1','ORG-1','Org',true,0,0,0)");
            jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values ('district-1','DIST-1','District',true,0,0,0)");
            jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values ('a-1','A-1','Bad','mock','org-1','district-1',current_timestamp,current_timestamp,0)");
            // WGS-84 坐标顺序为经度、纬度；181 经度不能作为可信空域边界入库。
            assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values ('av-1','a-1',1,'PROHIBITED',ST_Multi(ST_GeomFromText('POLYGON((181 30,181 31,179 31,181 30))',4326)),current_timestamp,current_timestamp)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // 约束同时拒绝错误类型、错误 SRID、空几何和自交面，防止 Repository 接到不可复核的空间证据。
            assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values ('av-2','a-1',2,'PROHIBITED',ST_Multi(ST_GeomFromText('LINESTRING(118 37,118.1 37.1)',4326)),current_timestamp,current_timestamp)")) .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values ('av-3','a-1',3,'PROHIBITED',ST_Multi(ST_GeomFromText('POLYGON((118 37,118.1 37,118.1 37.1,118 37))',3857)),current_timestamp,current_timestamp)")) .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values ('av-4','a-1',4,'PROHIBITED',ST_GeomFromText('MULTIPOLYGON EMPTY',4326),current_timestamp,current_timestamp)")) .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values ('av-5','a-1',5,'PROHIBITED',ST_Multi(ST_GeomFromText('POLYGON((118 37,118.1 37.1,118.1 37,118 37.1,118 37))',4326)),current_timestamp,current_timestamp)")) .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) { statement.execute("drop schema " + SCHEMA + " cascade"); }
        }
    }

    @Test
    void localIllegalExampleReallyOverlapsTheProhibitedAirspace() throws Exception {
        assertSafeSchema();
        DataSource rootDataSource = new DriverManagerDataSource(required("POSTGRES_TEST_URL"), required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
        JdbcTemplate root = new JdbcTemplate(rootDataSource); root.execute("create schema " + SCHEMA);
        try {
            Flyway.configure().dataSource(rootDataSource).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false).cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
            DataSource schemaDataSource = new DriverManagerDataSource(schemaUrl(required("POSTGRES_TEST_URL")), required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
            JdbcTemplate jdbc = new JdbcTemplate(schemaDataSource);
            Instant at = Instant.parse("2026-09-05T00:00:00Z");
            new LocalStage3PlanningSeeder(jdbc, new AppClock(Clock.fixed(at, ZoneOffset.UTC))).run(null);

            AccessDecision all = new AccessDecision("postgres-reader", ScopeMode.ALL);
            FlightReadRepository flights = new FlightReadRepository(jdbc, schemaDataSource);
            AirspaceReadRepository airspaces = new AirspaceReadRepository(jdbc, schemaDataSource);
            PlanRow illegal = flights.findPlan("seed-stage3-plan-illegal", all);
            PlanRow legal = flights.findPlan("seed-stage3-plan-legal", all);

            // 固定样例的标签必须能由生产空间查询复核，不能只靠预写的 ILLEGAL/LEGAL 结论自证。
            assertThat(illegal).isNotNull();
            assertThat(legal).isNotNull();
            assertThat(illegal.sourceCode()).isEqualTo("STAGE3-PLANNING-MOCK");
            assertThat(jdbc.queryForObject("select count(*) from integration_source where source_id='seed-stage3-source'", Integer.class)).isEqualTo(1);
            assertThat(airspaces.conflicts(illegal, all)).anySatisfy(row -> {
                assertThat(row.airspaceVersionId()).isEqualTo("seed-stage3-av-prohibited");
                assertThat(row.horizontalRelation()).isEqualTo("OVERLAPS");
            });
            assertThat(airspaces.conflicts(legal, all))
                    .noneMatch(row -> row.airspaceVersionId().equals("seed-stage3-av-prohibited")
                            && row.horizontalRelation().equals("OVERLAPS"));
        } finally {
            try (Connection connection = rootDataSource.getConnection(); Statement statement = connection.createStatement()) { statement.execute("drop schema " + SCHEMA + " cascade"); }
        }
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values ('org-1','ORG-1','Org',true,0,0,0)");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values ('district-1','DIST-1','District',true,0,0,0)");
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values ('route-1','R-1','Route',true,'mock','org-1','district-1',current_timestamp,current_timestamp,0)");
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) values ('rv-1','route-1',1,ST_GeomFromText('LINESTRING(118 37,118.01 37)',4326),100,10,100,'AMSL',current_timestamp,current_timestamp)");
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values ('plan-1','P-1','PENDING','mock',current_timestamp-interval '1 minute',current_timestamp+interval '1 minute','rv-1','org-1','district-1',current_timestamp,current_timestamp,0)");
        jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,source_snapshot,created_at) values ('rule-1','LOCAL-DEMO-V1',1,'ACTIVE',current_timestamp,'mock','{}',current_timestamp)");
        jdbc.update("insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at) values ('assessment-1','plan-1','rv-1','rule-1',current_timestamp,'LEGAL','[]','[]','[]','mock',current_timestamp)");
        airspace(jdbc, "hit", "POLYGON((118.005 36.9999,118.006 36.9999,118.006 37.0001,118.005 37.0001,118.005 36.9999))");
        // 约 83 米，超过全宽 100m 对应的 50m 半径；若误把全宽当半径会得出错误相交。
        airspace(jdbc, "near-but-clear", "POLYGON((118.005 37.00075,118.006 37.00075,118.006 37.00085,118.005 37.00085,118.005 37.00075))");
    }
    private static void airspace(JdbcTemplate jdbc, String suffix, String polygon) {
        airspace(jdbc, suffix, polygon, 10, 100, "AMSL", "current_timestamp", null);
    }
    private static void airspace(JdbcTemplate jdbc, String suffix, String polygon, int min, int max, String datum, String validFrom, String validTo) {
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'mock','org-1','district-1',current_timestamp,current_timestamp,0)", "a-" + suffix, "A-" + suffix, suffix);
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,created_at) values (?,?,1,'PROHIBITED',ST_Multi(ST_GeomFromText(?,4326)),?,?,?," + validFrom + "," + (validTo == null ? "null" : validTo) + ",current_timestamp)", "av-" + suffix, "a-" + suffix, polygon, min, max, datum);
    }
    private static void assertFact(java.util.List<ConflictRow> facts, String id, String relation, String reason) {
        assertThat(facts).anySatisfy(row -> { assertThat(row.airspaceVersionId()).isEqualTo(id); assertThat(row.horizontalRelation()).isEqualTo(relation); assertThat(row.horizontalReason()).isEqualTo(reason); });
    }
    private static String schemaUrl(String baseUrl) { if (!baseUrl.startsWith("jdbc:postgresql:") || baseUrl.toLowerCase().contains("currentschema=")) throw new IllegalStateException("POSTGRES_TEST_URL must be PostgreSQL without currentSchema"); return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public"; }
    private static String required(String name) { String value = System.getenv(name); if (value == null) throw new IllegalStateException(name + " is required"); return value; }
    private static void assertSafeSchema() { if (!SCHEMA.matches("^stage3_airspace_api_[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe airspace API verification schema"); }
}
