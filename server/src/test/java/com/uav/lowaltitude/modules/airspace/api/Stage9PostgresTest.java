package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.uav.lowaltitude.integration.mock.LocalStage3PlanningSeeder;
import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.integration.mock.LocalStage9SpaceRiskSeeder;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskEvaluationService;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 阶段 9 PostgreSQL/PostGIS 专项验证。沿用 Stage7/Stage8PostgresTest 的做法：env 门禁、`stage456_verify_` 库名限制、
 * 随机 `stage456_` schema、手动 Flyway（含 `db/postgresql`）、不使用测试级事务（并发用例要求真正提交/回滚）、只删本 schema。
 * 用例顺序显式声明：迁移与目录断言最先，写路径与并发最后。
 *
 * 本轮（9.4 起草）只包含**不依赖执行者类**的用例：迁移与目录基线、`airspace_version` 的现行不可变语义（阶段 3 触发器）。
 * 契约要求的接替式更新、导入项几何/字典 CHECK、`space_risk_fact`/`rule_evaluation_run` 只增、C04 走廊判定、机场几何、
 * 并发 confirm 一成一 `VERSION_CONFLICT` 等，都依赖 E1 的迁移 061/064 与 `R__stage9_airspace_succession.sql`、
 * E2 的迁移 062/063 与 `R__stage9_space_risk_and_airport.sql`；它们落地后按文末 TODO 补齐并重跑。
 * 现在就写死这些断言只会让本类在迁移落地前整体失败，掩盖真正的回归。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage9PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage9PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage9PostgresTest 未在真实 PostgreSQL 上执行")
class Stage9PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 6, 12, 0, 0, 0, ZoneOffset.UTC);
    /** 契约 C04/C05 的 DEMO 参数目录（迁移 062，E2）。 */
    private static final List<String> DEMO_PARAM_KEYS = List.of(
            "C04:corridor_near_m", "C04:climb_band_agl_m", "C04:approach_band_agl_m", "C04:flock_count_threshold",
            "C04:trend_window_min", "C04:plan_window_pad_min", "C05:procedure_buffer_m", "C05:protected_target_pad_m");

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired SpaceRiskEvaluationService evaluationService;
    @Autowired RiskIngestionService riskIngestion;
    @Autowired SpaceRiskRepository spaceRisks;
    @Autowired AppClock clock;
    @Autowired RuleEngineRepository ruleEngine;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort spatialPort;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    private String suffix, org, district, airspaceId, versionId, planId, routeVersionId;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        initializeSchema();
        registry.add("spring.datasource.url", () -> schemaUrl(requiredEnvironment("POSTGRES_TEST_URL")));
        registry.add("spring.datasource.username", () -> requiredEnvironment("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("app.dev-seed.enabled", () -> "false");
        registry.add("app.live-device.enabled", () -> "false");
        registry.add("app.rule-engine.enabled", () -> "false");
        registry.add("app.rule-engine.replay.run-on-start", () -> "false");
        registry.add("app.fusion.enabled", () -> "false");
        registry.add("app.fusion.replay.run-on-start", () -> "false");
        registry.add("app.rule-engine.c04.enabled", () -> "false");
        // C04 端到端用例要真正评估：DEMO 参数版本在默认配置下会被拒（DEMO_PARAMS_NOT_ALLOWED），这里显式放开。
        registry.add("app.rule-engine.allow-demo-active", () -> "true");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (!schemaCreated) return;
        assertSafeSchema();
        try (Connection connection = rootDataSource().getConnection(); Statement statement = connection.createStatement()) {
            // 只清理本次随机创建并已校验前缀的 schema，绝不触碰 public 或其他 schema。
            statement.execute("drop schema " + SCHEMA + " cascade");
        } finally {
            schemaCreated = false;
        }
    }

    @BeforeEach
    void fixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).containsIgnoringCase("PostgreSQL");
        }
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        org = id(); district = id(); airspaceId = id(); versionId = id(); planId = null; routeVersionId = null;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-S9-" + suffix, "阶段九验证机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-S9-" + suffix, "阶段九验证区域");
    }

    @Test
    @Order(1)
    void stage9MigrationsAndPermissionCatalogAreAppliedToIsolatedPostgresSchema() {
        List<String> versions = jdbc.queryForList("select version from flyway_schema_history where success=true and version is not null", String.class);
        assertThat(versions).contains("202609050060");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();
        List<String> actions = jdbc.queryForList(
                "select permission_code from app_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') order by permission_code",
                String.class);
        assertThat(actions).containsExactly("airport:manage", "airport:read", "airspace:manage", "risk:evaluate");
        // 动作码不带菜单键；两个既有 MODULE 行在迁移 060 升级为真实菜单后，又被 V202609070010 撤回为别名
        // （用户 2026-09-07 裁定，见 docs/backend-stage9/menu-scope-deviation.md）：route_key 为空、行仍在。
        assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') and route_key is not null", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code in ('airspace','risk')", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code in ('airspace','risk') and route_key is not null", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select sort_order from app_permission where permission_code='airspace:manage'", Integer.class)).isEqualTo(960);
        // flight:authorize 与 flight_plan_authorization 已按 F8 裁定由 V202609090106 撤除：目录与表都不该再存在。
        assertThat(jdbc.queryForObject("select count(*) from app_permission where permission_code='flight:authorize'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission where permission_code='flight:authorize'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='flight_plan_authorization'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_proc where proname='prevent_stage9_plan_authorization_mutation'", Long.class)).isZero();
        // 迁移只登记目录，不给任何角色授权，也不插入任何业务数据。
        assertThat(jdbc.queryForObject(
                "select count(*) from app_role_permission where permission_code in ('airspace:manage','airport:read','airport:manage','risk:evaluate') and role_code <> 'ROLE-ADMIN'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from airspace", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_type='SPACE_OBJECT'", Long.class)).isZero();
        // PostGIS 是阶段 9 的硬前提：C04/C05 的米制判定与导入几何校验都依赖它。
        assertThat(jdbc.queryForObject("select extversion from pg_extension where extname='postgis'", String.class)).isNotBlank();
    }

    @Test
    @Order(2)
    void airspaceVersionGeometryAndAltitudeConstraintsHoldOnPostgis() {
        insertAirspaceWithVersion();
        // 阶段 3 的 WGS-84 检查：非 4326、空几何、非 MULTIPOLYGON 与越界包络都进不去。
        assertThatThrownBy(() -> insertVersion(id(), 2, "PROHIBITED", "SRID=3857;MULTIPOLYGON(((13200000 4470000,13200100 4470000,13200100 4470100,13200000 4470100,13200000 4470000)))", T0.plusDays(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertVersion(id(), 2, "PROHIBITED", "SRID=4326;MULTIPOLYGON(((200 37.4,200.01 37.4,200.01 37.41,200 37.41,200 37.4)))", T0.plusDays(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 单个 POLYGON 不会被拒：PostGIS 的 typmod 赋值会把它提升为 MULTIPOLYGON。这不是漏洞，但导入端不能依赖
        // “非 MULTIPOLYGON 会被数据库挡住”——落库后一律是 MULTIPOLYGON，几何种类的校验必须在导入解析时做。
        String promoted = id();
        insertVersion(promoted, 2, "PROHIBITED", "SRID=4326;POLYGON((118.6 37.4,118.61 37.4,118.61 37.41,118.6 37.41,118.6 37.4))", T0.plusDays(1));
        assertThat(jdbc.queryForObject("select GeometryType(boundary) from airspace_version where airspace_version_id=?", String.class, promoted)).isEqualTo("MULTIPOLYGON");
        assertThat(jdbc.queryForObject("select ST_SRID(boundary) from airspace_version where airspace_version_id=?", Integer.class, promoted)).isEqualTo(4326);
        // 高度三元组要么全空要么齐全且基准合法、下界不高于上界。
        assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) values (?,?,3,'PROHIBITED',ST_GeomFromEWKT(?),120,null,'AMSL',?,?)",
                id(), airspaceId, boundary(), T0.plusDays(2), T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) values (?,?,3,'PROHIBITED',ST_GeomFromEWKT(?),200,120,'AMSL',?,?)",
                id(), airspaceId, boundary(), T0.plusDays(2), T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) values (?,?,3,'PROHIBITED',ST_GeomFromEWKT(?),0,120,'ELLIPSOID',?,?)",
                id(), airspaceId, boundary(), T0.plusDays(2), T0)).isInstanceOf(DataIntegrityViolationException.class);
        // valid_to 必须晚于 valid_from；同一空域版本号唯一。
        assertThatThrownBy(() -> jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,valid_to,created_at) values (?,?,3,'PROHIBITED',ST_GeomFromEWKT(?),?,?,?)",
                id(), airspaceId, boundary(), T0.plusDays(2), T0.plusDays(1), T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertVersion(id(), 1, "PROHIBITED", boundary(), T0.plusDays(3)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // kind_code 走迁移 061 的字典 CHECK：字典外的值进不来。
        assertThatThrownBy(() -> insertVersion(id(), 4, "BOGUS_KIND", boundary(), T0.plusDays(4)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from airspace_version where airspace_id=?", Long.class, airspaceId)).isEqualTo(2L);
    }

    /**
     * 现行（阶段 3）语义基线：`airspace_version` 完全不可变。契约 9-1 要求 E1 用新触发器替换为“只允许 valid_to 从 NULL 变为非 NULL”，
     * 落地后本用例改为断言接替式语义（见 TODO）。先把现状钉住，避免替换触发器时把 DELETE 或几何改写一并放开。
     */
    @Test
    @Order(3)
    void airspaceVersionIsStillFullyImmutableBeforeStage9SuccessionTriggerLands() {
        insertAirspaceWithVersion();
        boolean successionLanded = jdbc.queryForObject(
                "select count(*) from pg_trigger where tgrelid='airspace_version'::regclass and tgname='trg_stage3_airspace_version_immutable'", Long.class) == 0L;
        if (successionLanded) {
            // E1 的 R__stage9 已落地：这里只保证几何与 DELETE 仍被拒；接替式的完整分支见用例 18。
            assertThatThrownBy(() -> jdbc.update("update airspace_version set boundary=ST_GeomFromEWKT(?) where airspace_version_id=?", boundary(), versionId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from airspace_version where airspace_version_id=?", versionId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            return;
        }
        assertThatThrownBy(() -> jdbc.update("update airspace_version set valid_to=? where airspace_version_id=?", T0.plusDays(5), versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update airspace_version set kind_code='RESTRICTED' where airspace_version_id=?", versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from airspace_version where airspace_version_id=?", versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, versionId)).isEqualTo("PROHIBITED");
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", OffsetDateTime.class, versionId)).isNull();
    }

    /** 阶段 3 的其余只增约束在阶段 9 迁移之后仍然有效：研判结论不可改写。 */
    @Test
    @Order(4)
    void stage3AppendOnlyGuaranteesSurviveStage9Migrations() {
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_trigger where tgrelid='assessment_result'::regclass and tgname='trg_stage3_assessment_result_append_only'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_trigger where tgrelid='rule_evaluation'::regclass and tgname='trg_stage7_rule_evaluation_append_only'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_trigger where tgrelid='target_lineage'::regclass and tgname='trg_stage8_lineage_append_only'", Long.class)).isEqualTo(1L);
    }


    /**
     * E2 先验清单 6/7：`space_risk_fact.target_location` 的写入与读取，以及 `unknown_reasons` 的 JSON 往返。
     * 关键结论：PostgreSQL 上 `CAST(target_location AS VARCHAR)` 回的是 **EWKB 十六进制**（`0101000020E6100000…`），
     * 不是 EWKT——H2 上通过不代表 PG 上能取到坐标。读侧必须走 `ST_X/ST_Y`（或 `ST_AsEWKT`）分支。
     */
    @Test
    @Order(5)
    void spaceRiskFactGeometryAndJsonRoundTripOnPostgis() {
        String riskId = insertSpaceRisk();
        jdbc.update("insert into space_risk_fact (risk_id,subtype_code,rule_version_id,rule_set_version_id,corridor_relation,altitude_band,trend,"
                + "unknown_reasons,target_location,window_from,window_to,created_at) values (?,'BIRD_FLOCK','space-risk-c04-v1','space-risk-demo-v1',"
                + "'INSIDE','CLIMB','UNKNOWN',cast(? as json),cast(? as geometry),?,?,?)",
                riskId, "[\"OBJECT_COUNT_UNAVAILABLE\",\"TREND_UNAVAILABLE\"]", "SRID=4326;POINT (118.6199544 37.4200215)",
                T0.minusMinutes(30), T0, T0);
        // 文本读取在 PG 上拿到的是 EWKB 十六进制：能查出来但解析不出经纬度，页面会静默丢点。
        String asVarchar = jdbc.queryForObject("select cast(target_location as varchar) from space_risk_fact where risk_id=?", String.class, riskId);
        assertThat(asVarchar).as("PG 的几何文本形态是 EWKB 十六进制，不是 EWKT").matches("^[0-9A-F]+$");
        assertThat(asVarchar).doesNotContain("POINT");
        // 正确的 PG 读法：ST_X/ST_Y（与 AirportRepository.coordinates() 同一写法）。
        assertThat(jdbc.queryForObject("select ST_X(target_location) from space_risk_fact where risk_id=?", Double.class, riskId)).isEqualTo(118.6199544);
        assertThat(jdbc.queryForObject("select ST_Y(target_location) from space_risk_fact where risk_id=?", Double.class, riskId)).isEqualTo(37.4200215);
        assertThat(jdbc.queryForObject("select ST_AsEWKT(target_location) from space_risk_fact where risk_id=?", String.class, riskId))
                .isEqualTo("SRID=4326;POINT(118.6199544 37.4200215)");
        assertThat(jdbc.queryForObject("select ST_SRID(target_location) from space_risk_fact where risk_id=?", Integer.class, riskId)).isEqualTo(4326);
        // unknown_reasons：CAST(? AS JSON) 写入后在 PG 上按文本原样回读，元素顺序与内容不变。
        assertThat(jdbc.queryForObject("select cast(unknown_reasons as text) from space_risk_fact where risk_id=?", String.class, riskId))
                .isEqualTo("[\"OBJECT_COUNT_UNAVAILABLE\",\"TREND_UNAVAILABLE\"]");
        assertThat(jdbc.queryForObject("select json_array_length(unknown_reasons) from space_risk_fact where risk_id=?", Integer.class, riskId)).isEqualTo(2);
        // 决策 9-18：位置快照可为空（当时没有可信坐标），页面据此不画点。
        String noLocation = insertSpaceRisk();
        jdbc.update("insert into space_risk_fact (risk_id,subtype_code,rule_version_id,rule_set_version_id,corridor_relation,altitude_band,trend,"
                + "unknown_reasons,target_location,window_from,window_to,created_at) values (?,'BALLOON','space-risk-c04-v1','space-risk-demo-v1',"
                + "'UNKNOWN','UNKNOWN','UNKNOWN',cast('[]' as json),null,?,?,?)", noLocation, T0.minusMinutes(30), T0, T0);
        assertThat(jdbc.queryForObject("select count(*) from space_risk_fact where risk_id=? and target_location is null", Long.class, noLocation)).isEqualTo(1L);
    }

    /** E2 先验清单 8：位置快照的 WGS-84 CHECK 与四个 GIST 索引。 */
    @Test
    @Order(6)
    void spaceRiskAndAirportGeometryConstraintsAndIndexesExist() {
        String riskId = insertSpaceRisk();
        assertThatThrownBy(() -> jdbc.update("insert into space_risk_fact (risk_id,subtype_code,rule_version_id,rule_set_version_id,corridor_relation,"
                + "altitude_band,trend,unknown_reasons,target_location,window_from,window_to,created_at) values (?,'BIRD_FLOCK','space-risk-c04-v1',"
                + "'space-risk-demo-v1','INSIDE','CLIMB','UNKNOWN',cast('[]' as json),ST_SetSRID(ST_MakePoint(200,37.4),4326),?,?,?)",
                riskId, T0.minusMinutes(30), T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into space_risk_fact (risk_id,subtype_code,rule_version_id,rule_set_version_id,corridor_relation,"
                + "altitude_band,trend,unknown_reasons,target_location,window_from,window_to,created_at) values (?,'BIRD_FLOCK','space-risk-c04-v1',"
                + "'space-risk-demo-v1','INSIDE','CLIMB','UNKNOWN',cast('[]' as json),ST_SetSRID(ST_MakePoint(118.6,37.4),3857),?,?,?)",
                riskId, T0.minusMinutes(30), T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname=? and indexname in "
                + "('idx_stage9_space_fact_location_gist','idx_stage9_airport_reference_gist','idx_stage9_procedure_centerline_gist','idx_stage9_protected_location_gist')"
                + " order by indexname", String.class, SCHEMA))
                .containsExactly("idx_stage9_airport_reference_gist", "idx_stage9_procedure_centerline_gist",
                        "idx_stage9_protected_location_gist", "idx_stage9_space_fact_location_gist");
        // 机场参考点同样要求 4326 且坐标合法。
        assertThatThrownBy(() -> jdbc.update("insert into airport (airport_id,icao_code,name,reference_point,owner_org_id,district_id,enabled,created_by,created_at,version)"
                + " values (?,?,?,ST_SetSRID(ST_MakePoint(200,37.4),4326),?,?,true,null,?,0)", id(), "ZS" + suffix.substring(0, 2), "越界机场", org, district, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** E2 先验清单 3：`space_risk_fact` 只增；`rule_evaluation_run` 只允许 RUNNING 收尾一次、身份列不可改、禁止删除。 */
    @Test
    @Order(7)
    void spaceRiskFactAndEvaluationRunAreAppendOnlyOnPostgres() {
        String riskId = insertSpaceRisk();
        jdbc.update("insert into space_risk_fact (risk_id,subtype_code,rule_version_id,rule_set_version_id,corridor_relation,altitude_band,trend,"
                + "unknown_reasons,window_from,window_to,created_at) values (?,'BIRD_FLOCK','space-risk-c04-v1','space-risk-demo-v1','INSIDE','CLIMB',"
                + "'UNKNOWN',cast('[]' as json),?,?,?)", riskId, T0.minusMinutes(30), T0, T0);
        assertThatThrownBy(() -> jdbc.update("update space_risk_fact set corridor_relation='OUTSIDE' where risk_id=?", riskId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from space_risk_fact where risk_id=?", riskId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select corridor_relation from space_risk_fact where risk_id=?", String.class, riskId)).isEqualTo("INSIDE");

        String run = id();
        jdbc.update("insert into rule_evaluation_run (run_id,rule_code,trigger_kind,window_from,window_to,status,started_at) values (?,'C04','MANUAL',?,?,'RUNNING',?)",
                run, T0.minusHours(1), T0, T0);
        // 收尾一次：状态与计数一次性写入。
        assertThat(jdbc.update("update rule_evaluation_run set status='SUCCESS',targets_seen=3,risks_created=1,finished_at=? where run_id=?", T0, run)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update rule_evaluation_run set risks_created=2 where run_id=?", run))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select risks_created from rule_evaluation_run where run_id=?", Integer.class, run)).isEqualTo(1);
        // RUNNING 期间身份列也不可改；DELETE 一律禁止。
        String running = id();
        jdbc.update("insert into rule_evaluation_run (run_id,rule_code,trigger_kind,window_from,window_to,status,started_at) values (?,'C04','MANUAL',?,?,'RUNNING',?)",
                running, T0.minusHours(1), T0, T0);
        assertThatThrownBy(() -> jdbc.update("update rule_evaluation_run set rule_code='C05' where run_id=?", running))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from rule_evaluation_run where run_id=?", running))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * E2 先验清单 1 与契约 DEMO 参数目录：细类别名匹配 `CAST(aliases AS TEXT) LIKE '%"别名"%'` 在 PG 上按预期只命中一行；
     * `SPACE-RISK-DEMO` 的八个参数齐全、全部 DEMO，且这些是 C04/C05 唯一的阈值来源。
     */
    @Test
    @Order(8)
    void subtypeAliasMatchingAndDemoParameterCatalogAreUsableOnPostgres() {
        assertThat(jdbc.queryForList("select subtype_code from space_object_subtype where cast(aliases as text) like concat('%\"', ?, '\"%')", String.class, "鸟群"))
                .containsExactly("BIRD_FLOCK");
        assertThat(jdbc.queryForList("select subtype_code from space_object_subtype where cast(aliases as text) like concat('%\"', ?, '\"%')", String.class, "无人机"))
                .as("字典外的别名不得命中任何细类：匹配不到就不进 C04（决策 9-6）").isEmpty();
        assertThat(jdbc.queryForList("select subtype_code from space_object_subtype order by subtype_code", String.class))
                .containsExactly("BALLOON", "BIRD_FLOCK", "KITE", "OTHER_OBJECT", "SKY_LANTERN");
        List<String> params = jdbc.queryForList("select p.rule_code||':'||p.param_key from rule_param p"
                + " join rule_set_version v on v.rule_set_version_id=p.rule_set_version_id"
                + " join rule_set s on s.rule_set_id=v.rule_set_id where s.rule_set_code='SPACE-RISK-DEMO' order by 1", String.class);
        assertThat(params).containsExactlyElementsOf(DEMO_PARAM_KEYS.stream().sorted().toList());
        assertThat(jdbc.queryForObject("select count(*) from rule_param p join rule_set_version v on v.rule_set_version_id=p.rule_set_version_id"
                + " join rule_set s on s.rule_set_id=v.rule_set_id where s.rule_set_code='SPACE-RISK-DEMO' and p.param_status<>'DEMO'", Long.class))
                .as("阶段 9 阈值全部未经业务方确认").isZero();
        assertThat(jdbc.queryForObject("select status_code from rule_set_version where rule_set_version_id='space-risk-demo-v1'", String.class)).isEqualTo("PUBLISHED");
    }


    /**
     * 先验清单 2/5 与 C04 端到端（领导 2026-09-07 指派）：用 E2 的 `LocalStage9SpaceRiskSeeder` 造事实，
     * 再走 `SpaceRiskEvaluationService.evaluate` 与真实 HTTP 读路径，把 9.5 集成验收里要手工做的这段固化成回归。
     *
     * 走廊判定不写死坐标期望，而是先用 PostGIS 量出每个种子目标到航线中心线的真实距离，再据此断言：
     * 半宽内必须命中且为 INSIDE、`corridor_near_m` 之外必须不生成。这样种子几何调整时用例仍然成立。
     */
    @Test
    @Order(9)
    void c04EvaluationOnPostgisGeneratesOnlyCorridorAndNearRisks() {
        seedStage9SpaceRisk();
        // E2 的种子目标观测时刻是固定常量 T0(2026-09-05)，而阶段 3 计划窗口是 clock.now()+5min，两者永不重叠，
        // 因此种子数据本身跑不出 C04（见报告“给 E2 的发现”）。这里另造落在计划窗口内、几何位置精确可控的目标。
        OffsetDateTime planFrom = jdbc.queryForObject("select start_at from flight_plan where plan_id=?", OffsetDateTime.class, LocalStage9SpaceRiskSeeder.PLAN);
        OffsetDateTime planTo = jdbc.queryForObject("select end_at from flight_plan where plan_id=?", OffsetDateTime.class, LocalStage9SpaceRiskSeeder.PLAN);
        OffsetDateTime observedAt = planFrom.plusMinutes(1);
        double halfWidth = jdbc.queryForObject("select corridor_width_m/2.0 from route_version where route_version_id=?",
                Double.class, LocalStage9SpaceRiskSeeder.ROUTE_VERSION);
        double nearM = Double.parseDouble(jdbc.queryForObject("select p.value_text from rule_param p"
                + " join rule_set_version v on v.rule_set_version_id=p.rule_set_version_id"
                + " join rule_set s on s.rule_set_id=v.rule_set_id"
                + " where s.rule_set_code='SPACE-RISK-DEMO' and p.rule_code='C04' and p.param_key='corridor_near_m'", String.class));

        // 中心线 LINESTRING(118.02 37.02,118.03 37.03)：中点正上方即走廊内；北移 0.006° 约 660 m，远超 corridor_near_m。
        // 航线基准是 AMSL：只有同基准的高度才进带（AGL 与 AMSL 不互比），因此两个走廊内目标分别用 AMSL 与 AGL。
        String routeDatum = jdbc.queryForObject("select altitude_datum from route_version where route_version_id=?",
                String.class, LocalStage9SpaceRiskSeeder.ROUTE_VERSION);
        assertThat(routeDatum).as("阶段 3 演示航线声明了高度基准").isEqualTo("AMSL");
        String inside = spaceTarget("inside", "BIRD_FLOCK", 118.025, 37.025, null, new BigDecimal("100.00"), observedAt);
        // MQTT 当前只有主类别；空间计算不能漏掉它，也不能把回放来源改成 live。
        jdbc.update("update target set subtype=null,source_mode='replay' where target_id=?", inside);
        String insideOtherDatum = spaceTarget("datum", "BIRD_FLOCK", 118.0255, 37.0255, new BigDecimal("100.00"), null, observedAt);
        String far = spaceTarget("far", "BIRD_FLOCK", 118.025, 37.031, null, new BigDecimal("100.00"), observedAt);
        double insideDistance = distanceToRoute(inside), farDistance = distanceToRoute(far);
        assertThat(insideDistance).as("夹具目标应落在走廊内").isLessThanOrEqualTo(halfWidth);
        assertThat(farDistance).as("夹具目标应远超 corridor_near_m").isGreaterThan(nearM);

        SpaceRiskRepository.RunRow run = evaluationService.evaluate("C04", planFrom, planTo, "MANUAL", null);
        assertThat(run.status()).as("PostGIS 可用且规则集已激活时必须真正评估，而不是 UNAVAILABLE：" + run.message()).isEqualTo("SUCCESS");
        assertThat(run.targetsSeen()).as("窗口内有观测的异物目标必须计入 targets_seen").isPositive();
        // targets_seen 是**去重后的目标数**，而风险按 (计划, 目标) 生成（source_risk_id 含 plan_id）：
        // 同一元组下有多条时间重叠的计划时，风险数会大于目标数，这不是重复入库。
        assertThat(run.risksCreated()).as("走廊内目标必须产出风险").isPositive();
        assertThat(jdbc.queryForList("select distinct source_mode from flight_risk where target_id=?", String.class, inside))
                .containsExactly("replay");
        assertThat(run.targetsSeen()).as("三个夹具目标都被观测到（含走廊外那个）").isGreaterThanOrEqualTo(3);
        // "看到"与"判成风险"是两件事：走廊外的目标计入 targets_seen 但不产生风险，页面不能把两者混为一谈。
        // 只统计本用例自己的三个夹具目标（种子另有两条演示风险，按自身归属过滤才不会串味）。
        int riskedFixtures = jdbc.queryForObject("select count(distinct r.target_id) from flight_risk r"
                + " where r.risk_type='SPACE_OBJECT' and r.target_id in (?,?,?)", Integer.class, inside, insideOtherDatum, far);
        assertThat(riskedFixtures).as("三个夹具目标里只有走廊内的两个被判成风险").isEqualTo(2);

        // 走廊内：必须命中，关系 INSIDE，等级 HIGH（高度带内 + 有活动计划）。
        // 同一元组下可能有多条时间重叠的计划，契约的 source_risk_id 含 plan_id，因此每条计划各生成一条风险。
        // 同一元组下有多条时间重叠的计划，契约的 source_risk_id 含 plan_id，因此每条计划各生成一条风险；
        // 而高度带只在该计划航线声明了基准时才可判定，所以逐条断言锁定到声明了 AMSL 的那条航线。
        assertThat(factsOf(inside)).as("走廊内目标（" + insideDistance + " m ≤ 半宽 " + halfWidth + " m）必须生成风险").isNotEmpty();
        // 不断言"每条重叠计划都产出一条风险"：距离是按各自航线算的，元组内还有一条几何相距很远的计划（走廊外，不生成）。
        List<Map<String, Object>> insideOnRoute = factsOnRoute(inside, LocalStage9SpaceRiskSeeder.ROUTE_VERSION);
        assertThat(insideOnRoute).hasSize(1);
        assertThat(insideOnRoute.get(0)).containsEntry("corridor_relation", "INSIDE").containsEntry("altitude_band", "CLIMB");
        assertThat(insideOnRoute.get(0).get("severity")).as("走廊内 + 同基准高度在带内 + 有活动计划 → HIGH").isEqualTo("HIGH");
        assertThat(((Number) insideOnRoute.get(0).get("distance_to_route_m")).doubleValue()).isLessThanOrEqualTo(halfWidth);
        // 计划航线没声明高度基准时，高度带只能是 UNKNOWN——不猜，也不拿别的航线的基准顶替。
        assertThat(factsOf(inside)).filteredOn(f -> !LocalStage9SpaceRiskSeeder.ROUTE_VERSION.equals(f.get("route_version_id")))
                .allSatisfy(fact -> assertThat(fact).containsEntry("altitude_band", "UNKNOWN"));
        // 同样在走廊内，但目标高度是 AGL 而航线基准是 AMSL：不得互比，高度带只能 UNKNOWN，等级降为 MEDIUM。
        List<Map<String, Object>> datumOnRoute = factsOnRoute(insideOtherDatum, LocalStage9SpaceRiskSeeder.ROUTE_VERSION);
        assertThat(datumOnRoute).hasSize(1);
        assertThat(datumOnRoute.get(0)).containsEntry("corridor_relation", "INSIDE").containsEntry("altitude_band", "UNKNOWN");
        assertThat(datumOnRoute.get(0).get("severity")).as("AGL 与 AMSL 不互比，高度不可判定时降为 MEDIUM").isEqualTo("MEDIUM");
        // 300 m 之外：不得生成任何风险。
        assertThat(factsOf(far)).as("距中心线 " + farDistance + " m，超出 corridor_near_m=" + nearM + " m").isEmpty();
        // 命中的事实一律不是 OUTSIDE：OUTSIDE 说明判定与生成口径脱节。
        assertThat(jdbc.queryForObject("select count(*) from space_risk_fact where corridor_relation='OUTSIDE'", Long.class)).isZero();

        // 幂等：同窗口再评估不新增风险，只累计 deduplicated；事实行不被重写（只增触发器也会挡）。
        long before = jdbc.queryForObject("select count(*) from flight_risk where risk_type='SPACE_OBJECT'", Long.class);
        SpaceRiskRepository.RunRow again = evaluationService.evaluate("C04", planFrom, planTo, "MANUAL", null);
        assertThat(again.status()).isEqualTo("SUCCESS");
        assertThat(again.risksCreated()).as("同一窗口重复评估不得再造风险").isZero();
        assertThat(again.risksDeduplicated()).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_type='SPACE_OBJECT'", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation_run where rule_code='C04' and status='SUCCESS' and finished_at is not null", Long.class))
                .isGreaterThanOrEqualTo(2L);
    }

    /**
     * 先验清单 2/5 的读路径：汇总分组谓词、空间事实的坐标双分支、机场坐标双分支，全部走真实 HTTP 接口在 PG 上验证。
     * 这几条我在 SQL 片段层面验不了（要经 E2 的 Java 读路径），因此在这里端到端跑一遍。
     */
    @Test
    @Order(10)
    void spaceRiskSummaryAndAirportReadPathsReturnCoordinatesOnPostgres() throws Exception {
        seedStage9SpaceRisk();
        String reader = session(readerRole());
        String window = "from=" + LocalStage9SpaceRiskSeeder.T0.minusSeconds(3600).toEpochMilli()
                + "&to=" + LocalStage9SpaceRiskSeeder.T0.plusSeconds(3600).toEpochMilli();
        // 汇总：分组谓词在 PG 上可执行，且计数与库内事实一致（按种子自身归属过滤，不受其他阶段数据影响）。
        long expected = jdbc.queryForObject("select count(*) from flight_risk where risk_type='SPACE_OBJECT' and owner_org_id=? and district_id=?",
                Long.class, LocalStage9SpaceRiskSeeder.ORG, LocalStage9SpaceRiskSeeder.DISTRICT);
        mvc.perform(get("/api/v1/space-risks/summary?" + window + "&owner_org_id=" + LocalStage9SpaceRiskSeeder.ORG
                        + "&district_id=" + LocalStage9SpaceRiskSeeder.DISTRICT).header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_subtype").isArray())
                .andExpect(jsonPath("$.data.by_severity").isArray())
                .andExpect(jsonPath("$.data.by_state").isArray())
                .andExpect(jsonPath("$.data.by_altitude_band").isArray())
                .andExpect(jsonPath("$.data.rule_version.rule_set_code").value("SPACE-RISK-DEMO"))
                .andExpect(jsonPath("$.data.rule_version.param_status").value("DEMO"));
        assertThat(expected).as("种子至少写了一条空间风险，汇总才有意义").isPositive();

        // 空间事实读路径：PG 分支必须给出经纬度（EWKB 十六进制读法在这里会静默丢坐标）。
        String riskId = jdbc.queryForObject("select f.risk_id from space_risk_fact f join flight_risk r on r.risk_id=f.risk_id"
                + " where r.owner_org_id=? and f.target_location is not null order by f.created_at limit 1",
                String.class, LocalStage9SpaceRiskSeeder.ORG);
        mvc.perform(get("/api/v1/risks/{id}/space-fact", riskId).header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtype_code").isNotEmpty())
                .andExpect(jsonPath("$.data.longitude").isNumber())
                .andExpect(jsonPath("$.data.latitude").isNumber());

        // 机场读路径：参考点与保护目标坐标同样走 ST_X/ST_Y 分支。
        mvc.perform(get("/api/v1/airports/{id}", LocalStage9SpaceRiskSeeder.AIRPORT_ID).header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.airport.icao_code").value(LocalStage9SpaceRiskSeeder.AIRPORT_ICAO))
                .andExpect(jsonPath("$.data.airport.longitude").isNumber())
                .andExpect(jsonPath("$.data.airport.latitude").isNumber())
                .andExpect(jsonPath("$.data.protected_targets[0].longitude").isNumber());
    }

    /**
     * 决策 9-5 与契约："无活动计划 → 不生成风险，只计 targets_seen"（领导 2026-09-07 裁定：`observations` 改 LEFT JOIN）。
     *
     * 用一个**没有任何计划**的归属元组与一个只在本窗口内有观测的异物目标，把两件事分开钉住：
     * 目标被"看到"（计入 targets_seen）与目标被"判成风险"是两回事——没有计划就没有可关联的飞行活动，
     * 因此不产生风险，但运行仍是 SUCCESS 且必须如实报出看到了几个目标，否则页面会把"没算"误读成"没有异物"。
     */
    @Test
    @Order(11)
    void targetsWithoutActivePlanAreCountedButNeverProduceRisks() {
        seedStage9SpaceRisk();
        // 独立元组：这个 org/district 下没有任何飞行计划，因此窗口内不可能有活动计划。
        String lonelyOrg = id(), lonelyDistrict = id();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                lonelyOrg, "ORG-S9-NP-" + suffix, "阶段九无计划机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                lonelyDistrict, "DIST-S9-NP-" + suffix, "阶段九无计划区域");
        // 专属窗口：只有这一个目标在窗口内有观测，targets_seen 才能被精确断言。
        OffsetDateTime windowFrom = T0.plusYears(1), windowTo = windowFrom.plusHours(1);
        OffsetDateTime observedAt = windowFrom.plusMinutes(10);
        String targetId = "s9pg-target-noplan-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,subtype,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'BIRD','BIRD_FLOCK',?,?,'mock',?,?,?,?,0)", targetId, "目标-S9PG-NP-" + suffix,
                observedAt, observedAt, lonelyOrg, lonelyDistrict, observedAt, observedAt);
        jdbc.update("insert into target_latest_state (target_id,location,height_agl_m,altitude_amsl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version)"
                + " values (?,ST_GeomFromEWKT('SRID=4326;POINT (118.025 37.025)'),null,?,?,?,cast('[]' as jsonb),?,?,0)",
                targetId, new BigDecimal("100.00"), observedAt, observedAt, observedAt, observedAt);
        assertThat(jdbc.queryForObject("select count(*) from flight_plan where owner_org_id=? and district_id=?", Long.class, lonelyOrg, lonelyDistrict))
                .as("该元组下不得有任何计划").isZero();

        long risksBefore = jdbc.queryForObject("select count(*) from flight_risk where risk_type='SPACE_OBJECT'", Long.class);
        SpaceRiskRepository.RunRow run = evaluationService.evaluate("C04", windowFrom, windowTo, "MANUAL", null);

        assertThat(run.status()).as("没有计划不是失败，也不是不可评估：运行本身是成功的（" + run.message() + "）").isEqualTo("SUCCESS");
        assertThat(run.targetsSeen()).as("窗口内唯一有观测的异物目标必须被计入 targets_seen（契约：无计划只计数）").isEqualTo(1);
        assertThat(run.risksCreated()).as("没有活动计划就没有可关联的飞行活动，不得生成风险").isZero();
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where target_id=?", Long.class, targetId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_type='SPACE_OBJECT'", Long.class)).isEqualTo(risksBefore);
        // 运行记录如实留痕：看到了目标、没有产出风险。
        assertThat(jdbc.queryForList("select status,targets_seen,risks_created from rule_evaluation_run where run_id=?", run.runId()))
                .singleElement().satisfies(row -> {
                    assertThat(row).containsEntry("status", "SUCCESS");
                    assertThat(((Number) row.get("targets_seen")).intValue()).isEqualTo(1);
                    assertThat(((Number) row.get("risks_created")).intValue()).isZero();
                });
    }

    /**
     * E1 9.1 自查项：`AirspaceDiffService` 曾在 `@Transactional(readOnly=true)` 里调用带 `FOR UPDATE` 的查询。
     * H2 会放行，PostgreSQL 直接报 `25006 cannot execute FOR UPDATE in a read-only transaction`——真实库上 diff 接口 500。
     * H2 单测抓不到这一类，所以这条只能在 PG 上钉：diff 必须 200，且字段差与几何可用性如实给出。
     */
    @Test
    @Order(12)
    void airspaceVersionDiffRunsInsideReadOnlyTransactionOnPostgres() throws Exception {
        String manager = session(managerRole());
        String airspaceNo = "AS-DIFF-" + suffix;
        // 第 1 版：禁飞、无高度带；第 2 版接替：限高并带高度三元组，制造可比较的字段差。
        String created = mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "diff-create-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(airspaceNo, "PROHIBITED", T0, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String airspaceId = json.readTree(created).path("data").path("airspace_id").asText();
        String firstVersion = json.readTree(created).path("data").path("airspace_version_id").asText();
        String addedVersion = json.readTree(mvc.perform(post("/api/v1/airspaces/{id}/versions", airspaceId)
                        .header("Authorization", "Bearer " + manager).header("Idempotency-Key", "diff-version-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(versionBody("ALTITUDE_LIMIT", T0.plusDays(1))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").path("airspace_version_id").asText();

        // 关键断言：只读事务里跑 diff 不得因行锁在 PG 上 500。
        mvc.perform(get("/api/v1/airspaces/{id}/versions/{a}/diff/{b}", airspaceId, firstVersion, addedVersion)
                        .header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fields").isArray())
                .andExpect(jsonPath("$.data.fields[?(@.field=='kind_code')].from").value("PROHIBITED"))
                .andExpect(jsonPath("$.data.fields[?(@.field=='kind_code')].to").value("ALTITUDE_LIMIT"))
                .andExpect(jsonPath("$.data.geometry.availability").value("AVAILABLE"));
        // 接替式：上一版被关闭到新版生效时刻，其余列不变（R__stage9 触发器只放开 valid_to）。
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", OffsetDateTime.class, firstVersion))
                .as("新版本插入时应把上一开放版本关闭").isNotNull();
        assertThat(jdbc.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, firstVersion))
                .isEqualTo("PROHIBITED");
    }

    /** 两条真实连接并发建同一 `airspace_no`（各自幂等键）：唯一编号只能有一个赢家，另一个必须是 409 AIRSPACE_NO_EXISTS。 */
    @Test
    @Order(13)
    void twoRealConnectionsCreatingSameAirspaceNoCommitExactlyOne() throws Exception {
        String manager = session(managerRole());
        String airspaceNo = "AS-RACE-" + suffix;
        List<MvcResult> results = race(
                post("/api/v1/airspaces").header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "race-a-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(airspaceNo, "PROHIBITED", T0, null)),
                post("/api/v1/airspaces").header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "race-b-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(airspaceNo, "RESTRICTED", T0, null)));
        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).as("同一编号并发建库必须一成一败，不能双双成功").containsExactly(201, 409);
        MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(json.readTree(loser.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("AIRSPACE_NO_EXISTS");
        // 数据库里只有一条该编号的空域与一条初版；落败方整体回滚，不留半个空域。
        assertThat(jdbc.queryForObject("select count(*) from airspace where airspace_no=?", Long.class, airspaceNo)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from airspace_version v join airspace a on a.airspace_id=v.airspace_id"
                + " where a.airspace_no=?", Long.class, airspaceNo)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from airspace_version_origin o join airspace_version v"
                + " on v.airspace_version_id=o.airspace_version_id join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=?",
                Long.class, airspaceNo)).isEqualTo(1L);
    }

    /** 两条真实连接并发确认同一导入批次：一成一 409 `VERSION_CONFLICT`，只对 accepted 项建空域，落败方不留半个批次。 */
    @Test
    @Order(14)
    void twoRealConnectionsConfirmingSameImportBatchCommitExactlyOne() throws Exception {
        String manager = session(managerRole());
        String batch = json.readTree(mvc.perform(post("/api/v1/airspaces/import-batches").header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "import-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("AS-IMP-" + suffix)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("batch_id").asText();
        long acceptedItems = jdbc.queryForObject("select count(*) from airspace_import_item where batch_id=? and accepted=true", Long.class, batch);
        assertThat(acceptedItems).as("暂存批次里应有可确认的要素").isPositive();

        List<MvcResult> results = race(
                post("/api/v1/airspaces/import-batches/{id}/confirm", batch).header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "confirm-a-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"),
                post("/api/v1/airspaces/import-batches/{id}/confirm", batch).header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "confirm-b-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"));
        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).as("并发确认同一批次必须一成一败").containsExactly(200, 409);
        MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        // 决策 9-23：批次已被另一请求决定 → IMPORT_ALREADY_DECIDED；只有客户端自带的 expected_version 过期才是 VERSION_CONFLICT。
        assertThat(json.readTree(loser.getResponse().getContentAsString()).path("error").path("code").asText())
                .as("并发落败方是“批次已被决定”，不是版本过期").isEqualTo("IMPORT_ALREADY_DECIDED");

        // 批次只被确认一次：状态 CONFIRMED、版本恰好加一，创建的空域数等于 accepted 项数（不多不少）。
        Map<String, Object> row = jdbc.queryForMap("select status,version from airspace_import_batch where batch_id=?", batch);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from airspace_import_item where batch_id=? and result_airspace_version_id is not null",
                Long.class, batch)).isEqualTo(acceptedItems);
        assertThat(jdbc.queryForObject("select count(*) from airspace where airspace_no like ?", Long.class, "AS-IMP-" + suffix + "%"))
                .as("只对 accepted 项建空域，不因并发建两份").isEqualTo(acceptedItems);

        // 版本过期是另一回事：另起一个仍处于 STAGED 的批次，用陈旧的 expected_version 确认 → VERSION_CONFLICT。
        String staged = json.readTree(mvc.perform(post("/api/v1/airspaces/import-batches").header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "import-stale-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("AS-IMPS-" + suffix)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("batch_id").asText();
        mvc.perform(post("/api/v1/airspaces/import-batches/{id}/confirm", staged).header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "confirm-stale-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        // 版本冲突不得留下任何副作用：批次仍是 STAGED，没有建出空域。
        assertThat(jdbc.queryForObject("select status from airspace_import_batch where batch_id=?", String.class, staged)).isEqualTo("STAGED");
        assertThat(jdbc.queryForObject("select count(*) from airspace where airspace_no like ?", Long.class, "AS-IMPS-" + suffix + "%")).isZero();
    }

    /**
     * E1 9.1 的两处只增/唯一保证：`airspace_version_origin` 一条版本只能有一条来源记录且不可改写；
     * 导入项几何走 `ck_stage9_import_item_boundary_wgs84`（非 4326 / 非法几何 / 非 MULTIPOLYGON 都不得进确认流程）。
     */
    @Test
    @Order(15)
    void airspaceOriginIsAppendOnlyAndImportItemGeometryIsChecked() throws Exception {
        String manager = session(managerRole());
        String created = mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + manager)
                        .header("Idempotency-Key", "origin-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("AS-ORG-" + suffix, "PROHIBITED", T0, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String versionId = json.readTree(created).path("data").path("airspace_version_id").asText();
        Map<String, Object> origin = jdbc.queryForMap("select origin_id,origin_kind,actor_id from airspace_version_origin where airspace_version_id=?", versionId);
        assertThat(origin.get("origin_kind")).as("人工建空域的来源是 MANUAL").isEqualTo("MANUAL");
        assertThat(origin.get("actor_id")).as("人工与导入都必须留下操作者").isNotNull();
        // 一条版本只能有一条来源记录（UNIQUE），且来源记录只增不改。
        assertThatThrownBy(() -> jdbc.update("insert into airspace_version_origin (origin_id,airspace_version_id,origin_kind,actor_id,created_at)"
                + " values (?,?,'SEED',null,?)", id(), versionId, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update airspace_version_origin set origin_kind='SEED' where airspace_version_id=?", versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from airspace_version_origin where airspace_version_id=?", versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select origin_kind from airspace_version_origin where airspace_version_id=?", String.class, versionId)).isEqualTo("MANUAL");
        // 非 SEED 必须有操作者、非导入不得带 import_item_id：两条成对 CHECK。
        assertThatThrownBy(() -> jdbc.update("insert into airspace_version_origin (origin_id,airspace_version_id,origin_kind,actor_id,created_at)"
                + " values (?,?,'MANUAL',null,?)", id(), insertLooseVersion(), T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into airspace_version_origin (origin_id,airspace_version_id,origin_kind,actor_id,import_item_id,created_at)"
                + " values (?,?,'SEED',null,?,?)", id(), insertLooseVersion(), id(), T0)).isInstanceOf(DataIntegrityViolationException.class);

        // 导入项几何有两道闸，各自负责不同的坏数据，断言到具体机制而不是"反正抛错了"：
        //   ① 列的 typmod 先挡住 SRID 不符与几何种类不符；② R__stage9 的 CHECK 再挡住自相交等非法几何。
        String batch = stageBatchRow();
        assertThatThrownBy(() -> insertImportItem(batch, 1, "SRID=3857;MULTIPOLYGON(((13200000 4470000,13200100 4470000,13200100 4470100,13200000 4470100,13200000 4470000)))"))
                .as("非 4326 由列的 SRID 修饰符挡住").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("does not match column SRID");
        assertThatThrownBy(() -> insertImportItem(batch, 1, "SRID=4326;LINESTRING(118.60 37.40,118.61 37.41)"))
                .as("非 MULTIPOLYGON 由列的几何类型修饰符挡住").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("does not match column type");
        assertThatThrownBy(() -> insertImportItem(batch, 1, "SRID=4326;MULTIPOLYGON(((118.60 37.40,118.62 37.42,118.62 37.40,118.60 37.42,118.60 37.40)))"))
                .as("自相交多边形由 R__stage9 的 ST_IsValid 检查挡住").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_stage9_import_item_boundary_wgs84");
        // 合法 MULTIPOLYGON 可以进；几何可为空（只有属性、还没解析出边界的要素）。
        assertThat(insertImportItem(batch, 2, boundary())).isEqualTo(1);
        assertThat(insertImportItem(batch, 3, null)).isEqualTo(1);
    }

    /**
     * 决策 9-24：PostGIS 可用时种子不再直插演示风险，而是播种后同步跑一次真实 C04。
     * 因此页面上看到的每一条空间风险都出自评估器，而不是"看起来像研判结论"的演示数据。
     * 这里钉住三件事：走廊内那只鸟群恰好一条 HIGH（不是两条、也不是演示那条）、它带 `space_risk_fact`、
     * 种子留下了 `rule_evaluation_run`；另外两只按几何各自落到 MEDIUM 与不生成。
     */
    @Test
    @Order(16)
    void seederOnPostgisProducesEvaluatedRisksInsteadOfDemoRows() {
        seedStage9SpaceRisk();
        // 走廊内鸟群：恰一条风险，HIGH，且确实带评估事实（INSIDE + 同基准高度带）。
        // 注意：风险按 (计划, 目标) 生成（source_risk_id 含 plan_id），而阶段 3 演示数据里有两条时间重叠、
        // 几何相同的计划（legal 声明 AMSL，undetermined 没有高度基准）。所以"走廊内那只鸟群"会有两条风险：
        // 声明了基准的那条判到 HIGH，没基准的那条只能 MEDIUM。断言因此锁定"HIGH 恰好一条"，而不是"风险恰好一条"。
        List<Map<String, Object>> flockA = jdbc.queryForList("select r.risk_id,r.severity,r.reason_text,r.route_version_id,"
                + "f.corridor_relation,f.altitude_band from flight_risk r join space_risk_fact f on f.risk_id=r.risk_id"
                + " where r.target_id=? and r.risk_type='SPACE_OBJECT'", LocalStage9SpaceRiskSeeder.TARGET_FLOCK_A);
        assertThat(flockA).as("走廊内鸟群至少有一条经评估器产出的风险").isNotEmpty();
        List<Map<String, Object>> highOnRoute = flockA.stream()
                .filter(r -> "HIGH".equals(r.get("severity"))).toList();
        assertThat(highOnRoute).as("恰好一条 HIGH：只有声明了 AMSL 基准的那条计划航线判得出高度带").hasSize(1);
        assertThat(highOnRoute.get(0)).containsEntry("corridor_relation", "INSIDE").containsEntry("altitude_band", "CLIMB")
                .containsEntry("route_version_id", LocalStage9SpaceRiskSeeder.ROUTE_VERSION);
        // PG 分支不插演示风险：原因文案里不得再出现"未经评估器"的演示后缀。
        assertThat(flockA).allSatisfy(row -> assertThat((String) row.get("reason_text"))
                .as("PostGIS 上的风险必须出自评估器，不是演示直插").doesNotContain("未经评估器"));
        // 同一 (计划, 目标) 不得有第二条风险：重复播种与重复评估由 ingest 幂等挡住（用例 9 已直接验过再评估一次不新增）。
        // 只看带目标的风险：本类前面用例 5/7 手插的夹具风险没有 target_id，它们同属一个夹具计划，
        // 按 (plan_id, target_id) 分组时 NULL 会被归并成一组，与评估器的幂等性无关。
        List<Map<String, Object>> duplicated = jdbc.queryForList("select r.plan_id,r.target_id,count(*) as n,"
                + " min(f.window_from) as first_window, max(f.window_from) as last_window from flight_risk r"
                + " join space_risk_fact f on f.risk_id=r.risk_id where r.risk_type='SPACE_OBJECT' and r.target_id is not null"
                + " group by r.plan_id,r.target_id having count(*)>1");
        assertThat(duplicated).as("同一计划下同一目标只能有一条空间风险，实际重复组：" + duplicated).isEmpty();

        // 另一只鸟群只有 AGL 高度，航线基准是 AMSL：高度带判不出来，等级降为 MEDIUM。
        List<Map<String, Object>> flockB = jdbc.queryForList("select r.severity,f.corridor_relation,f.altitude_band from flight_risk r"
                + " join space_risk_fact f on f.risk_id=r.risk_id where r.target_id=? and r.risk_type='SPACE_OBJECT'",
                LocalStage9SpaceRiskSeeder.TARGET_FLOCK_B);
        assertThat(flockB).isNotEmpty();
        assertThat(flockB).allSatisfy(row -> {
            assertThat(row.get("severity")).as("AGL 与航线的 AMSL 不互比 → 高度带 UNKNOWN → 降为 MEDIUM").isEqualTo("MEDIUM");
            assertThat(row).containsEntry("altitude_band", "UNKNOWN");
        });
        // 气球超出 corridor_near_m：只被看到，不生成风险。
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where target_id=? and risk_type='SPACE_OBJECT'",
                Long.class, LocalStage9SpaceRiskSeeder.TARGET_BALLOON)).as("超出 corridor_near_m 的气球不生成风险").isZero();

        // 种子播种后确实跑过一次真实评估，并如实留痕。
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation_run where rule_code='C04' and status='SUCCESS'", Long.class))
                .as("种子应留下至少一条成功的 C04 运行记录").isPositive();
        // 每条评估器产出的空间风险都必须有对应的事实行：没有事实的风险等于"没有判定依据的结论"。
        // 同样只看带目标的风险：用例 6 故意让两次 space_risk_fact 插入被 CHECK 拒掉，留下一条无事实的夹具风险。
        assertThat(jdbc.queryForObject("select count(*) from flight_risk r where r.risk_type='SPACE_OBJECT' and r.target_id is not null"
                + " and not exists (select 1 from space_risk_fact f where f.risk_id=r.risk_id)", Long.class)).isZero();
    }


    /**
     * E1 9.1 的 `R__stage9_airspace_succession` 接替式不变量的完整分支（此前只在 psql 里逐条验过，领导要求写成用例）。
     * 阶段 9 唯一放开的写法是"把仍然开放的版本关闭到某个时刻"：`valid_to` NULL → 非 NULL，且必须晚于 `valid_from`。
     * 其余任何列的改动、把已关闭的版本再改、把 `valid_to` 改回 NULL、DELETE，一律拒——历史空域版本是已保存研判的输入证据。
     * 触发器给了三种不同的拒绝理由，用例按理由分别断言，避免"反正抛错了就算过"。
     */
    @Test
    @Order(18)
    void airspaceVersionSuccessionAllowsOnlyClosingValidTo() {
        insertAirspaceWithVersion();
        // 阶段 3 的全禁触发器是被**替换**掉的，不是两个并存：并存会让接替式 UPDATE 永远失败。
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='airspace_version'::regclass"
                + " and tgname='trg_stage3_airspace_version_immutable'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='airspace_version'::regclass"
                + " and tgname='trg_stage9_airspace_version_succession'", Long.class)).isEqualTo(1L);

        // ① 唯一放开的写法：把开放版本关闭到晚于 valid_from 的时刻。
        String closed = insertOpenVersion(90);
        assertThat(jdbc.update("update airspace_version set valid_to=? where airspace_version_id=?", T0.plusDays(1), closed))
                .as("valid_to NULL→非 NULL 是接替的正常写法").isEqualTo(1);
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", OffsetDateTime.class, closed))
                .isEqualTo(T0.plusDays(1));

        // ② 已关闭的版本不能再改 valid_to（接替只发生一次），也不能改回 NULL（那等于把一段已结束的生效期重新打开）。
        assertThatThrownBy(() -> jdbc.update("update airspace_version set valid_to=? where airspace_version_id=?", T0.plusDays(2), closed))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable except closing valid_to");
        assertThatThrownBy(() -> jdbc.update("update airspace_version set valid_to=null where airspace_version_id=?", closed))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("immutable except closing valid_to");
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", OffsetDateTime.class, closed))
                .as("两次被拒之后关闭时刻保持第一次的值").isEqualTo(T0.plusDays(1));

        // ③ 关闭时刻必须晚于 valid_from：零长度与倒挂的生效区间都说不清"这一版何时有效"。
        String windowCheck = insertOpenVersion(91);
        assertThatThrownBy(() -> jdbc.update("update airspace_version set valid_to=? where airspace_version_id=?", T0, windowCheck))
                .as("valid_to = valid_from").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("must be later than valid_from");
        assertThatThrownBy(() -> jdbc.update("update airspace_version set valid_to=? where airspace_version_id=?", T0.minusDays(1), windowCheck))
                .as("valid_to < valid_from").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("must be later than valid_from");
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", OffsetDateTime.class, windowCheck)).isNull();

        // ④ 不关闭 valid_to 而单改其他列：落在第一道判断上（连"只能改 valid_to"那一条都到不了）。
        String stable = insertOpenVersion(92);
        for (String setClause : List.of("airspace_version_id='" + id() + "'", "airspace_id='" + id() + "'", "version_no=99",
                "kind_code='RESTRICTED'", "boundary=ST_GeomFromEWKT('SRID=4326;MULTIPOLYGON(((118.70 37.50,118.71 37.50,118.71 37.51,118.70 37.51,118.70 37.50)))')",
                "min_altitude_m=100", "max_altitude_m=900", "altitude_datum='AMSL'", "valid_from='2026-01-01T00:00:00Z'",
                "change_reason='事后补写的理由'", "created_at='2026-01-01T00:00:00Z'")) {
            assertThatThrownBy(() -> jdbc.update("update airspace_version set " + setClause + " where airspace_version_id=?", stable))
                    .as("单改 " + setClause).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("immutable except closing valid_to");
        }

        // ⑤ 关闭 valid_to 的同时夹带别的列改动：这才落到第二道判断上。区分这两条很重要——
        //    否则"合法关闭顺手改几何"这种最危险的写法可能因为第一道判断先命中而被误认为已覆盖。
        for (String setClause : List.of("kind_code='RESTRICTED'", "valid_from='2026-01-01T00:00:00Z'",
                "boundary=ST_GeomFromEWKT('SRID=4326;MULTIPOLYGON(((118.70 37.50,118.71 37.50,118.71 37.51,118.70 37.51,118.70 37.50)))')")) {
            assertThatThrownBy(() -> jdbc.update("update airspace_version set valid_to=?, " + setClause
                    + " where airspace_version_id=?", T0.plusDays(1), stable))
                    .as("关闭 valid_to 顺带改 " + setClause).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("only valid_to may change");
        }
        assertThat(jdbc.queryForList("select version_no,kind_code,valid_to,change_reason from airspace_version where airspace_version_id=?", stable))
                .as("所有被拒的写入都没有留下痕迹").singleElement().satisfies(row -> {
                    assertThat(((Number) row.get("version_no")).intValue()).isEqualTo(92);
                    assertThat(row).containsEntry("kind_code", "PROHIBITED").containsEntry("valid_to", null).containsEntry("change_reason", null);
                });

        // ⑥ DELETE 一律拒：删掉历史版本会让引用它的研判结论失去依据。
        assertThatThrownBy(() -> jdbc.update("delete from airspace_version where airspace_version_id=?", stable))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update("delete from airspace_version where airspace_version_id=?", closed))
                .as("已关闭的版本同样不能删").isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cannot be deleted");
    }

    private String insertOpenVersion(int versionNo) {
        String id = id();
        insertVersion(id, versionNo, "PROHIBITED", boundary(), T0);
        return id;
    }


    /**
     * 决策 9-28 的引擎过滤，在真实库上核一次。阶段 9 起 `rule_set` 表里同时存在合法性规则集与空间风险规则集，
     * 后者的成员只有 C04/C05。合法性引擎（`RuleEngineWorker`）若把 SPACE-RISK-DEMO 也当作自己的规则集运行，
     * 会因缺 `C03.fresh_seconds` 等参数而**整轮失败**——不是少判一条，是合法性判定整体停摆。
     * 因此过滤条件必须是"成员里含 C03"，而不是"有生效版本"。
     */
    @Test
    @Order(20)
    void legalityEngineNeverPicksUpTheSpaceRiskRuleSet() {
        seedStage9SpaceRisk();                            // 激活 SPACE-RISK-DEMO
        new LocalStage7RuleEngineSeeder(jdbc).run(null);  // 让 LEGALITY-DEMO 也处于激活态，构成真实的共存场景
        // 前提成立才有意义：两个规则集都确实有生效版本，否则这条用例会因为"根本没激活"而假绿。
        assertThat(jdbc.queryForObject("select active_version_id from rule_set where rule_set_code='SPACE-RISK-DEMO'", String.class))
                .as("种子应已激活 SPACE-RISK-DEMO").isNotNull();
        assertThat(jdbc.queryForObject("select active_version_id from rule_set where rule_set_code='LEGALITY-DEMO'", String.class))
                .as("阶段 7 种子应已激活 LEGALITY-DEMO").isNotNull();
        // 两者的成员确实不同：一个含 C03，一个只有 C04/C05。
        assertThat(jdbc.queryForList("select distinct rv.rule_code from rule_set_member m join rule_version rv on rv.rule_version_id=m.rule_version_id"
                + " join rule_set s on s.active_version_id=m.rule_set_version_id where s.rule_set_code='SPACE-RISK-DEMO' order by 1", String.class))
                .as("空间风险规则集不含 C03").isNotEmpty().doesNotContain("C03");

        assertThat(ruleEngine.ruleSetsWithVersions().stream().map(RuleEngineRepository.RuleSetRow::ruleSetCode).toList())
                .as("合法性引擎每个 tick 只跑含 C03 的规则集；把 SPACE-RISK-DEMO 带进来会因缺 C03 参数整轮失败（决策 9-28）")
                .containsExactly("LEGALITY-DEMO");
    }

    /**
     * 阶段 10 迁移 074（决策 10-2 / 10-5）在真实 PG 上的形态：`ck_stage9_airspace_kind_code` 收紧为 AirspaceKind 五值，
     * 061 保留的两个历史写法 HEIGHT_LIMIT / TEMPORARY 不再进得来。
     * 断言 SQLSTATE 23514 并点名约束：只断言"抛了"，换成触发器或别的约束拒绝也照样绿。
     */
    @Test
    @Order(21)
    void migration074RejectsLegacyKindCodesAndAcceptsTheFiveValueDictionary() {
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success=true and version is not null", String.class))
                .as("074 必须已在本 schema 应用").contains("202609050074");
        String definition = jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint where conrelid='airspace_version'::regclass and conname='ck_stage9_airspace_kind_code'",
                String.class);
        assertThat(definition).as("074 重建的同名 CHECK 只含五值")
                .contains("PROHIBITED", "RESTRICTED", "ALTITUDE_LIMIT", "PERMITTED", "TEMPORARY_CONTROL")
                .doesNotContain("HEIGHT_LIMIT", "'TEMPORARY'");

        insertAirspaceWithVersion();
        for (String legacy : List.of("HEIGHT_LIMIT", "TEMPORARY")) {
            Throwable failure = catchThrowable(() -> insertVersion(id(), 200, legacy, boundary(), T0.plusDays(3)));
            assertThat(failure).as("历史写法 " + legacy).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(sqlState(failure)).as(legacy + " 必须以 23514（check_violation）被拒").isEqualTo("23514");
            assertThat(failure).hasMessageContaining("ck_stage9_airspace_kind_code");
        }
        assertThat(jdbc.queryForObject("select count(*) from airspace_version where airspace_id=? and version_no=200", Long.class, airspaceId))
                .as("被拒的插入不留行").isZero();

        // 五值字典逐个可插：不是"约束存在"就够了，收紧过头（例如漏了 PERMITTED）同样是回归。
        int versionNo = 210;
        for (String kind : List.of("PROHIBITED", "RESTRICTED", "ALTITUDE_LIMIT", "PERMITTED", "TEMPORARY_CONTROL")) {
            insertVersion(id(), versionNo++, kind, boundary(), T0.plusDays(3));
        }
        assertThat(jdbc.queryForList("select kind_code from airspace_version where airspace_id=? and version_no between 210 and 214 order by version_no", String.class, airspaceId))
                .containsExactly("PROHIBITED", "RESTRICTED", "ALTITUDE_LIMIT", "PERMITTED", "TEMPORARY_CONTROL");
    }

    /**
     * 决策 10-5 的另一半：074 不含 UPDATE，若某个库仍有旧值行，迁移必须以 PostgreSQL 自带的
     * "check constraint ... is violated by some row" 明确失败，而不是被接替式触发器打回（那会误导运维去查触发器），
     * 更不能静默放过。在一个独立的随机 schema 里手动模拟：先迁到 073.5 停下（073.5 已把存量归一，之后再插的才是"074 面对的旧值行"）、插一行 HEIGHT_LIMIT、再迁到 074。
     * 随后按 074 注释里给运维的处置（临时摘触发器归一→恢复→重跑）走一遍，证明这条处置路径确实能收尾。
     */
    @Test
    @Order(22)
    void migration074FailsLoudlyOnLegacyRowsWithCheckViolationNotTriggerError() throws Exception {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe scratch schema");
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        root.execute("create schema " + schema);
        try {
            // 决策 10-13：db/postgresql 的 073.5 会在 074 之前把旧值归一，所以"存量旧值行"必须在 073.5 之后、074 之前造出来，
            // 才能验证 074 本身遇到旧值时的失败方式（PG 的 CHECK 违反，不是触发器报错）。
            Flyway before074 = flywayFor(schema, "202609050073.5");
            before074.migrate();
            JdbcTemplate scratch = new JdbcTemplate(new DriverManagerDataSource(
                    requiredEnvironment("POSTGRES_TEST_URL") + (requiredEnvironment("POSTGRES_TEST_URL").contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                    requiredEnvironment("POSTGRES_TEST_USER"), requiredEnvironment("POSTGRES_TEST_PASSWORD")));
            assertThat(scratch.queryForObject("select current_schema()", String.class)).isEqualTo(schema);
            List<String> applied = scratch.queryForList("select version from flyway_schema_history where success=true and version is not null", String.class);
            assertThat(applied).as("target=073.5 时 073/073.5 已应用、074 未应用").contains("202609050073", "202609050073.5").doesNotContain("202609050074");

            // 061 的 CHECK 仍容许历史写法：这就是"存量旧值行"的来源。
            String orgId = id(), districtId = id(), legacyAirspace = id(), legacyVersion = id();
            scratch.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "ORG-074-" + suffix, "074 存量验证机构");
            scratch.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", districtId, "DIST-074-" + suffix, "074 存量验证区域");
            scratch.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'live',?,?,?,?,0)",
                    legacyAirspace, "AS-074-" + suffix, "074 存量验证空域", orgId, districtId, T0, T0);
            scratch.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values (?,?,1,'HEIGHT_LIMIT',ST_GeomFromEWKT(?),?,?)",
                    legacyVersion, legacyAirspace, boundary(), T0, T0);
            boolean successionTriggerPresent = scratch.queryForObject("select count(*) from pg_trigger where tgrelid=(quote_ident(?)||'.airspace_version')::regclass and tgname='trg_stage9_airspace_version_succession'",
                    Long.class, schema) == 1L;

            Throwable failure = catchThrowable(() -> flywayFor(schema, "202609050074").migrate());
            assertThat(failure).as("存量旧值行上 074 必须失败").isInstanceOf(FlywayException.class);
            assertThat(sqlState(failure)).as("失败原因是 CHECK（23514），不是触发器或其他").isEqualTo("23514");
            assertThat(failure).hasMessageContaining("violated by some row").hasMessageContaining("ck_stage9_airspace_kind_code");
            assertThat(failure.getMessage()).as("不能是接替式触发器的报错").doesNotContain("immutable except closing valid_to");
            // PostgreSQL 的 DDL 在事务里：整条迁移回滚，旧值行与 061 的七值 CHECK 原封不动；Flyway 的历史表里 074 没有成功记录。
            assertThat(scratch.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, legacyVersion)).isEqualTo("HEIGHT_LIMIT");
            assertThat(scratch.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid=(quote_ident(?)||'.airspace_version')::regclass and conname='ck_stage9_airspace_kind_code'", String.class, schema))
                    .contains("HEIGHT_LIMIT");
            assertThat(scratch.queryForObject("select count(*) from flyway_schema_history where version='202609050074' and success=true", Long.class)).isZero();
            assertThat(scratch.queryForObject("select count(*) from flyway_schema_history where version='202609050074'", Long.class))
                    .as("PG 支持事务性 DDL：Flyway 回滚后不记失败行（记的话 repair 前无法重跑）").isZero();

            // 074 注释给运维的处置路径：临时摘触发器 → 归一 → 恢复 → 重跑 074。摘触发器只在"确实有触发器"时有意义，两种情况都要能收尾。
            scratch.execute("alter table airspace_version disable trigger user");
            assertThat(scratch.update("update airspace_version set kind_code='ALTITUDE_LIMIT' where kind_code='HEIGHT_LIMIT'")).isEqualTo(1);
            scratch.execute("alter table airspace_version enable trigger user");
            flywayFor(schema, "202609050074").migrate();
            assertThat(scratch.queryForObject("select count(*) from flyway_schema_history where version='202609050074' and success=true", Long.class)).isEqualTo(1L);
            assertThat(scratch.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();
            assertThat(scratch.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, legacyVersion)).isEqualTo("ALTITUDE_LIMIT");
            if (successionTriggerPresent) {
                assertThat(scratch.queryForObject("select tgenabled from pg_trigger where tgrelid=(quote_ident(?)||'.airspace_version')::regclass and tgname='trg_stage9_airspace_version_succession'", String.class, schema))
                        .as("处置完成后触发器必须恢复启用").isEqualTo("O");
            }
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    /** 只迁到指定版本的 Flyway：与 {@link #initializeSchema()} 同一套位置与开关，仅多一个 target。 */
    private static Flyway flywayFor(String schema, String targetVersion) {
        return Flyway.configure().dataSource(rootDataSource()).schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration", "classpath:db/postgresql")
                .target(MigrationVersion.fromVersion(targetVersion)).load();
    }

    /** 取最内层 PostgreSQL 异常的 SQLSTATE：只断言"抛了"会让约束换成别的机制时照样通过。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return failure == null ? null : "<no SQLException: " + failure + ">";
    }






    /** 造一条 SPACE_OBJECT 风险（`space_risk_fact.risk_id` 是它的外键）。计划与航线版本用本用例自己的夹具，不碰种子数据。 */
    private String insertSpaceRisk() {
        if (planId == null) {
            planId = id(); routeVersionId = id();
            String routeId = id();
            jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'live',?,?,?,?,0)",
                    routeId, "R-S9-" + suffix, "阶段九验证航线", org, district, T0, T0);
            jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,ST_GeomFromEWKT(?),100,?,?)",
                    routeVersionId, routeId, "SRID=4326;LINESTRING(118.60 37.40,118.70 37.50)", T0, T0);
            jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                    + " values (?,?,'APPROVED','live',?,?,?,?,?,?,?,0)", planId, "P-S9-" + suffix, T0, T0.plusHours(2), routeVersionId, org, district, T0, T0);
        }
        String riskId = id();
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,"
                + "received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,'rule-engine-space-risk-mock',?,?,?,"
                + "'SPACE_OBJECT','HIGH','PENDING_VERIFICATION','SPACE_OBJECT','鸟群逼近航线',?,'UNKNOWN','mock',?,?,?,?,0)",
                riskId, "C04:" + riskId, planId, routeVersionId, T0, org, district, T0, T0);
        return riskId;
    }


    /**
     * postgres-test profile 下种子不注册（它们只在 local/test 生效），这里直接实例化写入本 schema；两个种子都是幂等的。
     * 阶段 9 种子的第一行就是 `if (!planExists()) return;`——它挂在阶段 3 的 `seed-stage3-plan-legal` 上，
     * 所以必须先跑阶段 3 计划种子，否则它会静默什么都不做（本用例第一次就是这么空跑的）。
     */
    private void seedStage9SpaceRisk() {
        if (jdbc.queryForObject("select count(*) from target where target_id=?", Long.class, LocalStage9SpaceRiskSeeder.TARGET_FLOCK_A) > 0) return;
        // 阶段 9 种子的机场行是 `select ... from app_user where role_code='ROLE-ADMIN'`（记录建档人），
        // 而 postgres-test 下没有用户种子；缺这个用户机场就不会被创建，机场读路径用例会 404。
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " select 'ROLE-ADMIN','超级管理员','',true,true,0,0,0,true where not exists (select 1 from app_role where role_code='ROLE-ADMIN')");
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " select ?,?,?,'ROLE-ADMIN','ACTIVE','unused',0,'ALL',0,0,0,0 where not exists (select 1 from app_user where role_code='ROLE-ADMIN')",
                id(), "s9-admin-" + suffix, "阶段九建档人");
        new LocalStage3PlanningSeeder(jdbc, clock).run(null);
        assertThat(jdbc.queryForObject("select count(*) from flight_plan where plan_id=?", Long.class, LocalStage9SpaceRiskSeeder.PLAN))
                .as("阶段 9 种子依赖阶段 3 的 seed-stage3-plan-legal").isEqualTo(1L);
        // 决策 9-24：种子按空间后端分流，必须带上空间端口与评估服务；postgres-test 下种子不是 bean（它只在 local/test 注册），
        // 所以这里用容器里的依赖显式构造完整实例，而不是走那个会抛错的过渡构造器。
        new LocalStage9SpaceRiskSeeder(jdbc, riskIngestion, spaceRisks, clock, spatialPort, evaluationService, transactions).run(null);
        assertThat(jdbc.queryForObject("select count(*) from target where target_id=?", Long.class, LocalStage9SpaceRiskSeeder.TARGET_FLOCK_A))
                .as("阶段 9 种子应已写入异物目标").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from airport where airport_id=?", Long.class, LocalStage9SpaceRiskSeeder.AIRPORT_ID))
                .as("阶段 9 种子应已写入演示机场").isEqualTo(1L);
    }


    /** 造一个落在阶段 3 计划窗口内、位置精确可控的异物目标（subtype 走细类别名字典）。 */
    private String spaceTarget(String tag, String subtype, double lon, double lat, BigDecimal aglM, BigDecimal amslM, OffsetDateTime observedAt) {
        String targetId = "s9pg-target-" + tag + "-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,subtype,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'BIRD',?,?,?,'mock',?,?,?,?,0)", targetId, "目标-S9PG-" + tag + "-" + suffix, subtype,
                observedAt, observedAt, LocalStage9SpaceRiskSeeder.ORG, LocalStage9SpaceRiskSeeder.DISTRICT, observedAt, observedAt);
        jdbc.update("insert into target_latest_state (target_id,location,height_agl_m,altitude_amsl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version)"
                + " values (?,ST_GeomFromEWKT(?),?,?,?,?,cast('[]' as jsonb),?,?,0)", targetId,
                "SRID=4326;POINT (" + lon + " " + lat + ")", aglM, amslM, observedAt, observedAt, observedAt, observedAt);
        return targetId;
    }

    private double distanceToRoute(String targetId) {
        return jdbc.queryForObject("select ST_Distance(rv.centerline::geography, s.location::geography) from route_version rv, target_latest_state s"
                + " where rv.route_version_id=? and s.target_id=?", Double.class, LocalStage9SpaceRiskSeeder.ROUTE_VERSION, targetId);
    }

    private List<Map<String, Object>> factsOf(String targetId) {
        return jdbc.queryForList("select f.corridor_relation,f.altitude_band,f.distance_to_route_m,r.severity,r.route_version_id"
                + " from space_risk_fact f join flight_risk r on r.risk_id=f.risk_id where r.target_id=? and r.risk_type='SPACE_OBJECT'", targetId);
    }

    /** 只取挂在指定航线版本上的事实：同一元组下多条计划各自成风险，高度带取决于该计划航线是否声明了基准。 */
    private List<Map<String, Object>> factsOnRoute(String targetId, String routeVersionId) {
        return factsOf(targetId).stream().filter(f -> routeVersionId.equals(f.get("route_version_id"))).toList();
    }



    /** 不经接口直接插一条独立空域版本，供来源表的成对 CHECK 反例使用（每次新建，避免撞 UNIQUE）。 */
    private String insertLooseVersion() {
        String looseAirspace = id(), looseVersion = id();
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,'live',?,?,?,?,0)", looseAirspace, "AS-LOOSE-" + UUID.randomUUID().toString().substring(0, 8),
                "来源约束反例空域", org, district, T0, T0);
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at)"
                + " values (?,?,1,'PROHIBITED',ST_GeomFromEWKT(?),?,?)", looseVersion, looseAirspace, boundary(), T0, T0);
        return looseVersion;
    }

    /** 直接插一条 STAGED 批次行，供导入项几何 CHECK 的反例使用（不经接口，避免被解析层提前挡下）。 */

    /** 导入项直插：`created_at` 非空，几何为空时不调 ST_GeomFromEWKT（用于“只有属性、无边界”的要素）。 */
    private int insertImportItem(String batchId, int seq, String ewkt) {
        if (ewkt == null) {
            return jdbc.update("insert into airspace_import_item (item_id,batch_id,seq,boundary_geojson,boundary,issues,accepted,created_at)"
                    + " values (?,?,?,cast('{}' as json),null,cast('[]' as json),false,?)", id(), batchId, seq, T0);
        }
        return jdbc.update("insert into airspace_import_item (item_id,batch_id,seq,boundary_geojson,boundary,issues,accepted,created_at)"
                + " values (?,?,?,cast('{}' as json),ST_GeomFromEWKT(?),cast('[]' as json),true,?)", id(), batchId, seq, ewkt, T0);
    }

    private String stageBatchRow() {
        String batchId = id();
        jdbc.update("insert into airspace_import_batch (batch_id,status,feature_count,accepted_count,owner_org_id,district_id,created_by,created_at,version)"
                + " select ?,'STAGED',0,0,?,?,u.user_id,?,0 from app_user u where u.role_code='ROLE-ADMIN' fetch first 1 row only",
                batchId, org, district, T0);
        return batchId;
    }

    private String managerRole() {
        String role = "ROLE-S9-MANAGE-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values"
                + " (?,'airspace:read','READ',false,current_timestamp),(?,'airspace:manage','OP',false,current_timestamp)", role, role);
        return role;
    }

    private String createBody(String airspaceNo, String kindCode, OffsetDateTime validFrom, OffsetDateTime validTo) {
        return "{\"airspace_no\":\"" + airspaceNo + "\",\"name\":\"阶段九验证空域\",\"kind_code\":\"" + kindCode + "\","
                + "\"boundary\":" + geoJsonMultiPolygon() + ",\"valid_from\":" + validFrom.toInstant().toEpochMilli()
                + (validTo == null ? "" : ",\"valid_to\":" + validTo.toInstant().toEpochMilli())
                + ",\"owner_org_id\":\"" + LocalStage9SpaceRiskSeeder.ORG + "\",\"district_id\":\"" + LocalStage9SpaceRiskSeeder.DISTRICT + "\"}";
    }

    private String versionBody(String kindCode, OffsetDateTime validFrom) {
        return "{\"kind_code\":\"" + kindCode + "\",\"boundary\":" + geoJsonMultiPolygon()
                + ",\"min_altitude_m\":0,\"max_altitude_m\":120,\"altitude_datum\":\"AMSL\","
                + "\"valid_from\":" + validFrom.toInstant().toEpochMilli() + ",\"change_reason\":\"阶段九验证接替\",\"expected_version\":0}";
    }

    private String importBody(String airspaceNo) {
        return "{\"geojson\":{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                + "\"properties\":{\"airspace_no\":\"" + airspaceNo + "\",\"name\":\"导入验证空域\",\"kind_code\":\"PROHIBITED\"},"
                + "\"geometry\":" + geoJsonMultiPolygon() + "}]},"
                + "\"defaults\":{\"kind_code\":\"PROHIBITED\",\"valid_from\":" + T0.toInstant().toEpochMilli() + "},"
                + "\"owner_org_id\":\"" + LocalStage9SpaceRiskSeeder.ORG + "\",\"district_id\":\"" + LocalStage9SpaceRiskSeeder.DISTRICT + "\"}";
    }

    private static String geoJsonMultiPolygon() {
        return "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118.60,37.40],[118.61,37.40],[118.61,37.41],[118.60,37.41],[118.60,37.40]]]]}";
    }

    /** 两个请求各占一个线程、各自从连接池取独立连接；屏障保证它们同时进入竞争。 */
    private List<MvcResult> race(MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return mvc.perform(first).andReturn(); });
            Future<MvcResult> b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return mvc.perform(second).andReturn(); });
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private String readerRole() {
        String role = "ROLE-S9-READ-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values"
                + " (?,'risk:read','READ',false,current_timestamp),(?,'airport:read','READ',false,current_timestamp),(?,'target:read','READ',false,current_timestamp)",
                role, role, role);
        return role;
    }

    /** ASSIGNED 用户配种子自身的 (org,district) 授权元组，走与生产一致的精确范围谓词。 */
    private String session(String roleCode) {
        String user = id(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", user, "s9-pg-" + suffix, "阶段九验证员", roleCode);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", user,
                LocalStage9SpaceRiskSeeder.ORG, LocalStage9SpaceRiskSeeder.DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private void insertAirspaceWithVersion() {
        if (jdbc.queryForObject("select count(*) from airspace where airspace_id=?", Long.class, airspaceId) > 0) return;
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'live',?,?,?,?,0)",
                airspaceId, "AS-S9-" + suffix, "阶段九验证空域", org, district, T0, T0);
        insertVersion(versionId, 1, "PROHIBITED", boundary(), T0);
    }

    private void insertVersion(String id, int versionNo, String kind, String ewkt, OffsetDateTime validFrom) {
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values (?,?,?,?,ST_GeomFromEWKT(?),?,?)",
                id, airspaceId, versionNo, kind, ewkt, validFrom, T0);
    }

    private static String boundary() {
        return "SRID=4326;MULTIPOLYGON(((118.60 37.40,118.61 37.40,118.61 37.41,118.60 37.41,118.60 37.40)))";
    }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        // 只接受专用验证库，防止误连生产库或日常联调库。
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 9 verification outside a stage456_verify_ database");
        }
        String extension = root.queryForObject("select extversion from pg_extension where extname='postgis'", String.class);
        if (extension == null || extension.isBlank()) throw new IllegalStateException("PostGIS is required for Stage 9 verification");
        root.execute("create schema " + SCHEMA);
        schemaCreated = true;
        try {
            Flyway.configure().dataSource(rootDataSource()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false).cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
        } catch (RuntimeException exception) {
            try { root.execute("drop schema " + SCHEMA + " cascade"); } finally { schemaCreated = false; }
            throw exception;
        }
    }

    private static DataSource rootDataSource() {
        return new DriverManagerDataSource(requiredEnvironment("POSTGRES_TEST_URL"), requiredEnvironment("POSTGRES_TEST_USER"), requiredEnvironment("POSTGRES_TEST_PASSWORD"));
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe Stage 9 verification schema");
    }

    @Test
    @Order(23)
    void migration0735RenormalizesLegacyRowsSoThat074SucceedsOnUpgradedDatabases() throws Exception {
        // 决策 10-13 的正题：已有库（065 之后旧版种子又写过 HEIGHT_LIMIT/TEMPORARY）升级到阶段 10 时，
        // 073.5 先归一、074 再收紧，整条链必须一次跑通——领导用验收库启动新 jar 时正是这条路径先失败、加了 073.5 才通过。
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe scratch schema");
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        root.execute("create schema " + schema);
        try {
            flywayFor(schema, "202609050073").migrate();
            JdbcTemplate scratch = new JdbcTemplate(new DriverManagerDataSource(
                    requiredEnvironment("POSTGRES_TEST_URL") + (requiredEnvironment("POSTGRES_TEST_URL").contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                    requiredEnvironment("POSTGRES_TEST_USER"), requiredEnvironment("POSTGRES_TEST_PASSWORD")));
            String orgId = id(), districtId = id(), legacyAirspace = id(), legacyVersion = id(), legacyVersion2 = id(), legacyAirspace2 = id();
            scratch.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "ORG-0735-" + suffix, "073.5 升级验证机构");
            scratch.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", districtId, "DIST-0735-" + suffix, "073.5 升级验证区域");
            scratch.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'live',?,?,?,?,0)",
                    legacyAirspace, "AS-0735A-" + suffix, "073.5 升级验证空域 A", orgId, districtId, T0, T0);
            scratch.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'live',?,?,?,?,0)",
                    legacyAirspace2, "AS-0735B-" + suffix, "073.5 升级验证空域 B", orgId, districtId, T0, T0);
            scratch.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values (?,?,1,'HEIGHT_LIMIT',ST_GeomFromEWKT(?),?,?)",
                    legacyVersion, legacyAirspace, boundary(), T0, T0);
            scratch.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values (?,?,1,'TEMPORARY',ST_GeomFromEWKT(?),?,?)",
                    legacyVersion2, legacyAirspace2, boundary(), T0, T0);

            flywayFor(schema, "202609050074").migrate();

            assertThat(scratch.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, legacyVersion)).isEqualTo("ALTITUDE_LIMIT");
            assertThat(scratch.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, legacyVersion2)).isEqualTo("TEMPORARY_CONTROL");
            assertThat(scratch.queryForList("select version from flyway_schema_history where success=true and version is not null", String.class))
                    .contains("202609050073.5", "202609050074");
            assertThat(scratch.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid=(quote_ident(?)||'.airspace_version')::regclass and conname='ck_stage9_airspace_kind_code'", String.class, schema))
                    .doesNotContain("HEIGHT_LIMIT").doesNotContain("'TEMPORARY'");
            // 073.5 摘掉又恢复了触发器：升级后接替式保护仍在。
            assertThat(scratch.queryForObject("select tgenabled from pg_trigger where tgrelid=(quote_ident(?)||'.airspace_version')::regclass and tgname='trg_stage9_airspace_version_succession'", String.class, schema)).isEqualTo("O");
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    /**
     * 10.2 交办的先验：**074 的版本号比库里已应用的迁移小**（202609050074 < 202609070001/0010），
     * 而 `application.yml` 没有开 `spring.flyway.out-of-order`（默认 false）。
     * 于是在一个"已经升级过、当时还没有 074"的库上，074 有可能被 Flyway 判成 IGNORED 而**静默不执行**——
     * 那样 CHECK 永远不会收紧，`HEIGHT_LIMIT` 还能写进去，而启动日志里看不出任何异常。
     *
     * <p>怎么造这个局面：全量迁移一遍拿到"已升级"的库，然后删掉 073.5/074 的历史行并把 CHECK 还原成七值——
     * 这正是"那台库当年升级时这两支迁移还不存在"的样子（历史最大版本 202609070001，而 074 缺席）。
     * 再跑一次 migrate，看它到底补不补。
     *
     * <p>用例接受两种结局，但**不接受第三种**：要么 074 被补上（CHECK 收紧、旧值写不进去），
     * 要么 migrate 响亮地失败并点名缺的是哪支迁移；**不可以静默跳过**——那样应用照常启动，
     * CHECK 一直停在七值，旧值继续写得进去，谁也不会发现。写成两分支是为了让它在领导选定修法之后仍然成立：
     * 无论是开 out-of-order、给迁移重新编号，还是别的办法，"不静默"这条都不该变。
     */
    @Test
    @Order(24)
    void migration074IsStillAppliedOnDatabasesAlreadyUpgradedPastItsVersion() throws Exception {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe scratch schema");
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        root.execute("create schema " + schema);
        try {
            flywayForAll(schema).migrate();
            JdbcTemplate scratch = scratchTemplate(schema);
            // 前提确认：库里确实存在版本号大于 074 的已应用迁移，否则这条用例根本没在测乱序。
            assertThat(scratch.queryForList("select version from flyway_schema_history where success=true and version is not null", String.class))
                    .as("必须有比 074 更新的迁移已应用，乱序场景才成立").anyMatch(v -> v.compareTo("202609050074") > 0);

            // 回退成"当年升级时还没有 073.5/074"的样子：历史里抹掉这两行，CHECK 还原为迁移 061 的七值。
            scratch.update("delete from flyway_schema_history where version in ('202609050073.5','202609050074')");
            scratch.execute("alter table airspace_version drop constraint ck_stage9_airspace_kind_code");
            scratch.execute("alter table airspace_version add constraint ck_stage9_airspace_kind_code check ("
                    + "kind_code in ('PROHIBITED','RESTRICTED','ALTITUDE_LIMIT','PERMITTED','TEMPORARY_CONTROL','HEIGHT_LIMIT','TEMPORARY'))");

            String failure = null;
            try {
                flywayForAll(schema).migrate();
            } catch (FlywayException blocked) {
                failure = blocked.getMessage();
            }

            boolean applied = scratch.queryForList("select version from flyway_schema_history where success=true and version is not null", String.class)
                    .contains("202609050074");
            if (applied) {
                // 期望的结局：074 被补上，CHECK 收紧，旧值真的写不进去。
                assertThat(scratch.queryForObject("select pg_get_constraintdef(oid) from pg_constraint"
                        + " where conrelid=(quote_ident(?)||'.airspace_version')::regclass and conname='ck_stage9_airspace_kind_code'", String.class, schema))
                        .as("补上 074 之后 CHECK 必须是五值").doesNotContain("HEIGHT_LIMIT").doesNotContain("'TEMPORARY'");
                String orgId = id(), districtId = id(), airspaceId = id();
                scratch.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "ORG-OOO-" + suffix, "乱序验证机构");
                scratch.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", districtId, "DIST-OOO-" + suffix, "乱序验证区域");
                scratch.update("insert into airspace (airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,'live',?,?,?,?,0)",
                        airspaceId, "AS-OOO-" + suffix, "乱序验证空域", orgId, districtId, T0, T0);
                assertThat(sqlState(catching(() -> scratch.update(
                        "insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values (?,?,1,'HEIGHT_LIMIT',ST_GeomFromEWKT(?),?,?)",
                        id(), airspaceId, boundary(), T0, T0))))
                        .as("补上 074 之后旧值必须真的写不进去").isEqualTo("23514");
                return;
            }

            // 没被补上——那就**必须是响亮地失败**，而且失败信息要点名缺的是哪两支迁移。
            // 最坏的结局是"静默跳过"：迁移被判 IGNORED，应用照常启动，而 CHECK 一直是七值、旧值继续写得进去，
            // 谁也不会发现。所以这一支断言的是"不是静默"，不是"迁移成功"。
            assertThat(failure).as("074 既没被补上，migrate 也没报错——那就是静默跳过，最危险的一种").isNotNull();
            assertThat(failure).contains("202609050074").contains("outOfOrder");
            // 静默跳过的反证：库停在旧状态，没有被半途改成一个说不清的中间态。
            assertThat(scratch.queryForObject("select pg_get_constraintdef(oid) from pg_constraint"
                    + " where conrelid=(quote_ident(?)||'.airspace_version')::regclass and conname='ck_stage9_airspace_kind_code'", String.class, schema))
                    .as("迁移被拦下时库应保持原状").contains("HEIGHT_LIMIT");
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    /** 与 {@link #flywayFor} 相同，但不设 target：跑到最新版本。 */
    private static Flyway flywayForAll(String schema) {
        return Flyway.configure().dataSource(rootDataSource()).schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration", "classpath:db/postgresql").load();
    }

    private static JdbcTemplate scratchTemplate(String schema) {
        String base = requiredEnvironment("POSTGRES_TEST_URL");
        return new JdbcTemplate(new DriverManagerDataSource(base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                requiredEnvironment("POSTGRES_TEST_USER"), requiredEnvironment("POSTGRES_TEST_PASSWORD")));
    }

    /** 捕获期望中的写入失败：返回异常本身（null 表示写入意外成功）。 */
    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException expected) {
            return expected;
        }
    }

    private static String id() { return UUID.randomUUID().toString(); }
}
