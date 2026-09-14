package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 阶段 4 PostgreSQL/PostGIS 专项验证：真实迁移（含 020/021/022 与 db/postgresql 可重复脚本）、
 * 外键/唯一/检查约束、带版本条件更新，以及两条真实连接并发提交同一无人机事件与同一风险。
 *
 * 与 H2 套件的区别：这里不使用测试级事务。并发用例要求业务事务真正提交或真正回滚，
 * 行锁必须发生在 PostgreSQL 的两个独立连接之间，而不是同一连接内的模拟。
 * 夹具全部写入随机 stage456_ schema，测试结束只删除这一个 schema。
 *
 * 缺少 POSTGRES_TEST_URL/USER/PASSWORD 时整类跳过，并以 disabledReason 明确输出“未验证”，
 * 不允许把跳过记成通过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage4PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage4PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage4PostgresTest 未在真实 PostgreSQL 上执行")
class Stage4PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneOffset.UTC);

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;

    private String suffix;
    private String org;
    private String district;
    private String source;
    private String alarm;
    private String eventId;
    private String routeId;
    private String routeVersionId;
    private String planId;
    private String riskId;
    private String sessionA;
    private String sessionB;
    private String userA;
    private String userB;

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
        org = id(); district = id(); source = id(); alarm = id(); eventId = id();
        routeId = id(); routeVersionId = id(); planId = id(); riskId = id();
        userA = id(); userB = id();

        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "ORG-S4-" + suffix, "阶段四验证机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-S4-" + suffix, "阶段四验证区域");
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,0)",
                source, "SRC-S4-" + suffix, "阶段四验证来源", T0, T0);
        jdbc.update("insert into alarm (alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarm, source, "AL-S4-" + suffix, T0, T0.plusSeconds(1), org, district, T0);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING_VERIFICATION',?,?,?,?,0)",
                eventId, alarm, org, district, T0, T0);

        // 风险固定关联已保存的计划与航线版本；组织/区域元组与风险一致，否则列表和锁定谓词都不可见。
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,?,?,0)",
                routeId, "ROUTE-S4-" + suffix, "阶段四验证航线", org, district, T0, T0);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,ST_GeomFromText('LINESTRING(118.6 37.4,118.7 37.5)',4326),100,?,?)",
                routeVersionId, routeId, T0, T0);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING','mock',?,?,?,?,?,0)",
                planId, "PLAN-S4-" + suffix, routeVersionId, org, district, T0, T0);
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,'FLIGHT_OPERATION','HIGH','PENDING_VERIFICATION','ROUTE_DEVIATION','阶段四验证风险',?,'UNKNOWN','mock',?,?,?,?,0)",
                riskId, source, "RISK-S4-" + suffix, planId, routeVersionId, T0.plusSeconds(2), org, district, T0, T0);

        String role = role();
        sessionA = session(userA, role, "s4-a-" + suffix);
        sessionB = session(userB, role, "s4-b-" + suffix);
    }

    @Test
    void migrationsIncludingStage4AndPostgresRepeatablesAreAppliedToIsolatedSchema() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success=true and version is not null", String.class);
        assertThat(versions).contains("202609050020", "202609050021", "202609050022", "202609110001");
        List<String> repeatables = jdbc.queryForList(
                "select description from flyway_schema_history where success=true and version is null", String.class);
        assertThat(repeatables).anyMatch(d -> d.contains("stage2 postgres constraints"))
                .anyMatch(d -> d.contains("stage3 airspace spatial"))
                .anyMatch(d -> d.contains("stage3 route spatial"));
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema=? and table_name in ('uav_event','uav_event_verification','flight_risk','flight_risk_verification')",
                String.class, SCHEMA);
        assertThat(tables).containsExactlyInAnyOrder("uav_event", "uav_event_verification", "flight_risk", "flight_risk_verification");
        List<String> permissions = jdbc.queryForList(
                "select permission_code from app_permission where permission_code in ('alarm:verify','risk:read','risk:verify') order by permission_code", String.class);
        assertThat(permissions).containsExactly("alarm:verify", "risk:read", "risk:verify");
    }

    @Test
    void foreignKeyUniqueAndCheckConstraintsAreEnforcedByPostgres() {
        // 同一来源告警只能形成一个事件；接收链与 seeder 都依赖这条数据库唯一约束而不是应用层判断。
        assertThatThrownBy(() -> jdbc.update(
                "insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING_VERIFICATION',?,?,?,?,0)",
                id(), alarm, org, district, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING_VERIFICATION',?,?,?,?,0)",
                id(), "missing-alarm-" + suffix, org, district, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'DISPOSED',?,?,?,?,0)",
                id(), alarm, org, district, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update uav_event set state_code='EVIDENCE_REQUIRED' where event_id=?", eventId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 核实历史按 (event_id,version) 唯一；两条相同版本的历史意味着并发写穿透，数据库必须拒绝。
        jdbc.update("insert into uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','约束验证',?,?)",
                id(), eventId, userA, T0);
        assertThatThrownBy(() -> jdbc.update(
                "insert into uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','FALSE_POSITIVE','FALSE_POSITIVE','重复版本',?,?)",
                id(), eventId, userA, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,2,'CONFIRMED','CONFIRMED','CONFIRMED','操作人不存在',?,?)",
                id(), eventId, "missing-user-" + suffix, T0)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,2,'PENDING_VERIFICATION','EVIDENCE_REQUIRED','EVIDENCE_REQUIRED','历史结论保留',?,?)",
                id(), eventId, userA, T0);

        // 风险的 (plan_id,route_version_id) 是复合外键：计划存在但航线版本不匹配同样不能入库。
        assertThatThrownBy(() -> jdbc.update(
                "insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,'FLIGHT_OPERATION','HIGH','PENDING_VERIFICATION','ROUTE_DEVIATION','航线版本不匹配',?,'UNKNOWN','mock',?,?,?,?,0)",
                id(), source, "RISK-BAD-ROUTE-" + suffix, planId, "missing-route-version-" + suffix, T0, org, district, T0, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,'FLIGHT_OPERATION','HIGH','PENDING_VERIFICATION','ROUTE_DEVIATION','来源风险重复',?,'UNKNOWN','mock',?,?,?,?,0)",
                id(), source, "RISK-S4-" + suffix, planId, routeVersionId, T0, org, district, T0, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 高度与基准必须成对：只给高度不给 AGL/AMSL 会被检查约束拒绝，不允许半个事实入库。
        assertThatThrownBy(() -> jdbc.update(
                "insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,received_at,observed_altitude_m,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,'FLIGHT_OPERATION','HIGH','PENDING_VERIFICATION','ALTITUDE_UNKNOWN','缺少高度基准',?,120,'UNKNOWN','mock',?,?,?,?,0)",
                id(), source, "RISK-NO-DATUM-" + suffix, planId, routeVersionId, T0, org, district, T0, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 风险核验历史的迁移检查约束：只允许 PENDING_VERIFICATION 出发，且结论与结果状态成对。
        assertThatThrownBy(() -> jdbc.update(
                "insert into flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','NOTIFIED','CONFIRMED','非法迁移',?,?)",
                id(), riskId, userA, T0)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from uav_event where alarm_id=?", Long.class, alarm)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where source_id=?", Long.class, source)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from flight_risk_verification where risk_id=?", Long.class, riskId)).isZero();
    }

    @Test
    void versionedConditionalUpdatesRejectStaleVersionsInDatabaseAndThroughApi() throws Exception {
        // 数据库层：条件更新命中旧版本必须为 0 行，这是服务层“0 行即 VERSION_CONFLICT”的前提。
        assertThat(jdbc.update("update uav_event set state_code='CONFIRMED',updated_at=?,version=version+1 where event_id=? and version=?",
                T0.plusSeconds(5), eventId, 7L)).isZero();
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId)).isZero();
        assertThat(jdbc.update("update flight_risk set state_code='EXCLUDED',updated_at=?,version=version+1 where risk_id=? and version=? and state_code='PENDING_VERIFICATION'",
                T0.plusSeconds(5), riskId, 3L)).isZero();
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, riskId)).isZero();

        // 接口层：成功提交后版本递增一次；再次携带旧版本必须 409，且不得出现第二条历史。
        MvcResult ok = mvc.perform(verifyEvent(sessionA, eventId, "CONFIRMED", "真实库首次核实", 0, "pg-event-" + UUID.randomUUID())).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class, eventId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForList("select version,previous_state,resulting_state,conclusion,actor_id from uav_event_verification where event_id=?", eventId))
                .singleElement().satisfies(row -> {
                    assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
                    assertThat(row.get("previous_state")).isEqualTo("PENDING_VERIFICATION");
                    assertThat(row.get("resulting_state")).isEqualTo("CONFIRMED");
                    assertThat(row.get("conclusion")).isEqualTo("CONFIRMED");
                    assertThat(row.get("actor_id")).isEqualTo(userA);
                });
        MvcResult stale = mvc.perform(verifyEvent(sessionB, eventId, "FALSE_POSITIVE", "持旧版本再次核实", 0, "pg-event-stale-" + UUID.randomUUID())).andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class, eventId)).isEqualTo("CONFIRMED");

        MvcResult riskOk = mvc.perform(verifyRisk(sessionA, riskId, "CONFIRMED", "真实库首次核验", 0, "pg-risk-" + UUID.randomUUID())).andReturn();
        assertThat(riskOk.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, riskId)).isEqualTo("PENDING_NOTIFICATION");
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, riskId)).isEqualTo(1L);
        MvcResult riskStale = mvc.perform(verifyRisk(sessionB, riskId, "EXCLUDED", "持旧版本再次核验", 0, "pg-risk-stale-" + UUID.randomUUID())).andReturn();
        assertThat(riskStale.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(riskStale)).isEqualTo("VERSION_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from flight_risk_verification where risk_id=?", Long.class, riskId)).isEqualTo(1L);
        // 阶段 4 不写 NOTIFIED：核验通过只进入待通知。
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_id=? and state_code='NOTIFIED'", Long.class, riskId)).isZero();
    }

    @Test
    void twoRealConnectionsVerifyingSameUavEventCommitExactlyOneHistory() throws Exception {
        List<MvcResult> results = race(
                verifyEvent(sessionA, eventId, "CONFIRMED", "并发连接 A 判属实", 0, "race-event-a-" + UUID.randomUUID()),
                verifyEvent(sessionB, eventId, "FALSE_POSITIVE", "并发连接 B 判误报", 0, "race-event-b-" + UUID.randomUUID()));
        assertExactlyOneWinner(results);

        // 断言数据库行而不只是 HTTP：版本恰好加一、恰一条历史、恰一条成功审计，终态与赢家结论一致。
        MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        String winnerState = json.readTree(winner.getResponse().getContentAsString()).path("data").path("state").asText();
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class, eventId)).isEqualTo(winnerState);
        List<Map<String, Object>> history = jdbc.queryForList("select version,resulting_state,conclusion from uav_event_verification where event_id=?", eventId);
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
            assertThat(row.get("resulting_state")).isEqualTo(winnerState);
            assertThat(row.get("conclusion")).isEqualTo(winnerState);
        });
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='uav_event_verified' and object_id=? and result='SUCCESS'", Long.class, eventId)).isEqualTo(1L);
        // 输家的事务整体回滚：它的幂等占位不能留下，否则重试会被误判为 replay。
        String loser = winner.getRequest().getHeader("Authorization").endsWith(sessionA) ? userB : userA;
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=?", Long.class, loser)).isZero();
    }

    @Test
    void twoRealConnectionsVerifyingSameRiskCommitExactlyOneHistory() throws Exception {
        List<MvcResult> results = race(
                verifyRisk(sessionA, riskId, "CONFIRMED", "并发连接 A 确认风险", 0, "race-risk-a-" + UUID.randomUUID()),
                verifyRisk(sessionB, riskId, "EXCLUDED", "并发连接 B 排除风险", 0, "race-risk-b-" + UUID.randomUUID()));
        assertExactlyOneWinner(results);

        MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        String winnerState = json.readTree(winner.getResponse().getContentAsString()).path("data").path("state").asText();
        assertThat(winnerState).isIn("PENDING_NOTIFICATION", "EXCLUDED");
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, riskId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, riskId)).isEqualTo(winnerState);
        List<Map<String, Object>> history = jdbc.queryForList("select version,previous_state,resulting_state from flight_risk_verification where risk_id=?", riskId);
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
            assertThat(row.get("previous_state")).isEqualTo("PENDING_VERIFICATION");
            assertThat(row.get("resulting_state")).isEqualTo(winnerState);
        });
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='risk_verified' and object_id=? and result='SUCCESS'", Long.class, riskId)).isEqualTo(1L);
        String loser = winner.getRequest().getHeader("Authorization").endsWith(sessionA) ? userB : userA;
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=?", Long.class, loser)).isZero();
    }

    /** 两个请求各占一个线程，各自从连接池取独立 PostgreSQL 连接；屏障保证它们同时进入 FOR UPDATE 竞争。 */
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

    private void assertExactlyOneWinner(List<MvcResult> results) throws Exception {
        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(200, 409);
        MvcResult rejected = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(code(rejected)).isEqualTo("VERSION_CONFLICT");
    }

    private String code(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.path("error").path("code").asText();
    }

    private static MockHttpServletRequestBuilder verifyEvent(String session, String id, String conclusion, String note, long version, String key) {
        return post("/api/v1/uav-events/" + id + "/verifications").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"" + conclusion + "\",\"note\":\"" + note + "\",\"expected_version\":" + version + "}");
    }

    private static MockHttpServletRequestBuilder verifyRisk(String session, String id, String conclusion, String note, long version, String key) {
        return post("/api/v1/risks/" + id + "/verifications").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"" + conclusion + "\",\"note\":\"" + note + "\",\"expected_version\":" + version + "}");
    }

    private String role() {
        String role = "ROLE-S4-PG-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'alarm:read','READ',false,current_timestamp),(?,'alarm:verify','OP',false,current_timestamp),(?,'risk:read','READ',false,current_timestamp),(?,'risk:verify','OP',false,current_timestamp)",
                role, role, role, role);
        return role;
    }

    /** ASSIGNED 用户配同一 (org,district) 授权元组，走与生产一致的精确范围谓词。 */
    private String session(String user, String roleCode, String account) {
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                user, account, "阶段四核实员", roleCode);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", user, org, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        // 只接受专用验证库，防止误连生产库或日常联调库。
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 4 verification outside a stage456_verify_ database");
        }
        String extension = root.queryForObject("select extversion from pg_extension where extname='postgis'", String.class);
        if (extension == null || extension.isBlank()) {
            throw new IllegalStateException("PostGIS is required for Stage 4 verification");
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) {
            throw new IllegalStateException("Unsafe Stage 4 verification schema");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
