package com.uav.lowaltitude.modules.handoff.api;

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
 * 阶段 5 PostgreSQL/PostGIS 专项验证：迁移 030/031 在真实库升级、device_business_scope 三个外键、
 * handoff 逻辑唯一、快照以 jsonb 对象落库，以及两条真实连接并发向同一接收方提交同一风险。
 *
 * 沿用 Stage4PostgresTest 的条件式、stage456_verify_ 库名限制、随机 stage456_ schema 与仅删该 schema 的清理。
 * 不使用测试级事务：并发提交要求业务事务真正提交或回滚，行锁必须发生在连接池的两条独立连接之间。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage5PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage5PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage5PostgresTest 未在真实 PostgreSQL 上执行")
class Stage5PostgresTest {

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
    private String routeId;
    private String routeVersionId;
    private String planId;
    private String riskId;
    private String recipientA;
    private String recipientB;
    private String opsDeviceId;
    private String userA;
    private String userB;
    private String sessionA;
    private String sessionB;

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
        org = id(); district = id(); source = id(); routeId = id(); routeVersionId = id(); planId = id(); riskId = id();
        recipientA = id(); recipientB = id(); opsDeviceId = id(); userA = id(); userB = id();

        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "ORG-S5-" + suffix, "阶段五验证机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-S5-" + suffix, "阶段五验证区域");
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,0)",
                source, "SRC-S5-" + suffix, "阶段五验证来源", T0, T0);
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,?,?,0)",
                routeId, "ROUTE-S5-" + suffix, "阶段五验证航线", org, district, T0, T0);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,ST_GeomFromText('LINESTRING(118.6 37.4,118.7 37.5)',4326),100,?,?)",
                routeVersionId, routeId, T0, T0);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING','mock',?,?,?,?,?,0)",
                planId, "PLAN-S5-" + suffix, routeVersionId, org, district, T0, T0);
        // 已核验风险：PENDING_NOTIFICATION、version=1，带一条核验历史；提交将风险推进已通知，快照保留这两个原值。
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,?,?,'FLIGHT_OPERATION','HIGH','PENDING_NOTIFICATION','ROUTE_DEVIATION','阶段五验证风险',?,'UNKNOWN','mock',?,?,?,?,1)",
                riskId, source, "RISK-S5-" + suffix, planId, routeVersionId, T0.plusSeconds(2), org, district, T0, T0.plusSeconds(10));
        jdbc.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values (?,?,'RISK_NOTICE',true,?,?),(?,?,'RISK_NOTICE',true,?,?)",
                recipientA, "验证接收方 A", T0, T0, recipientB, "验证接收方 B", T0, T0);
        jdbc.update("insert into ops_device (device_id,device_no,name,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) values (?,?,?,'雷达','融合感知箱',true,'mock',true,0,0,0)",
                opsDeviceId, "DEV-S5-" + suffix, "阶段五验证设备");

        String role = role();
        sessionA = session(userA, role, "s5-a-" + suffix);
        sessionB = session(userB, role, "s5-b-" + suffix);
        jdbc.update("insert into flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','PENDING_NOTIFICATION','CONFIRMED','人工复核后确认',?,?)",
                id(), riskId, userA, T0.plusSeconds(10));
    }

    @Test
    void migrations030And031AreAppliedToIsolatedPostgresSchema() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success=true and version is not null", String.class);
        assertThat(versions).contains("202609050030", "202609050031");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema=? and table_name in ('device_business_scope','handoff_recipient','handoff','handoff_material_snapshot','handoff_delivery')",
                String.class, SCHEMA);
        assertThat(tables).containsExactlyInAnyOrder("device_business_scope", "handoff_recipient", "handoff", "handoff_material_snapshot", "handoff_delivery");
        assertThat(jdbc.queryForObject(
                "select data_type from information_schema.columns where table_schema=? and table_name='handoff_material_snapshot' and column_name='snapshot'",
                String.class, SCHEMA)).isEqualTo("jsonb");
        List<String> permissions = jdbc.queryForList(
                "select permission_code from app_permission where permission_code in ('workbench:read','handoff:read','handoff:create') order by permission_code",
                String.class);
        assertThat(permissions).containsExactly("handoff:create", "handoff:read", "workbench:read");
        // 迁移不得自动插入任何接收方：本 schema 内只允许各用例夹具写入的“验证接收方”，没有其他来源的行。
        assertThat(jdbc.queryForObject("select count(*) from handoff_recipient where display_name not like '验证接收方%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from handoff_recipient where recipient_id in (?,?)", Long.class, recipientA, recipientB)).isEqualTo(2L);
    }

    @Test
    void deviceScopeForeignKeysAndHandoffLogicalUniqueAreEnforcedByPostgres() {
        // device_business_scope 三个外键：设备、组织、区域缺任一都不能入库；映射只能指向真实存在的目录。
        assertThatThrownBy(() -> jdbc.update(
                "insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,?,?)",
                "missing-device-" + suffix, org, district, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,?,?)",
                opsDeviceId, "missing-org-" + suffix, district, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,?,?)",
                opsDeviceId, org, "missing-district-" + suffix, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(
                "insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,?,?)",
                opsDeviceId, org, district, T0, T0)).isEqualTo(1);
        // 一台设备只能有一个归属：主键阻止第二条映射。
        assertThatThrownBy(() -> jdbc.update(
                "insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,?,?)",
                opsDeviceId, org, district, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from device_business_scope where ops_device_id=?", Long.class, opsDeviceId)).isEqualTo(1L);

        // handoff 逻辑唯一 (source_kind,source_id,handoff_type,recipient_id)：不同 handoff_id、不同提交人也只能落一份。
        String first = id();
        assertThat(jdbc.update(
                "insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) values (?,'RISK',?,?,null,'RISK_NOTICE',?,1,?,?,'mock',?,?)",
                first, riskId, riskId, recipientA, org, district, userA, T0)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) values (?,'RISK',?,?,null,'RISK_NOTICE',?,1,?,?,'mock',?,?)",
                id(), riskId, riskId, recipientA, org, district, userB, T0)).isInstanceOf(DataIntegrityViolationException.class);
        // source_id 必须等于对应 kind 的外键列，避免同一 source_id 在不同 kind 下被当成同一事项。
        assertThatThrownBy(() -> jdbc.update(
                "insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) values (?,'RISK',?,?,null,'RISK_NOTICE',?,1,?,?,'mock',?,?)",
                id(), "other-source-" + suffix, riskId, recipientB, org, district, userA, T0)).isInstanceOf(DataIntegrityViolationException.class);
        // 接收方外键：不存在的 recipient_id 不能入库。
        assertThatThrownBy(() -> jdbc.update(
                "insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,owner_org_id,district_id,source_mode,submitted_by,created_at) values (?,'RISK',?,?,null,'RISK_NOTICE',?,1,?,?,'mock',?,?)",
                id(), riskId, riskId, "missing-recipient-" + suffix, org, district, userA, T0)).isInstanceOf(DataIntegrityViolationException.class);
        // 投递尝试 (handoff_id,attempt_no) 唯一。
        jdbc.update("insert into handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) values (?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','CHANNEL_NOT_CONNECTED',?)",
                id(), first, T0);
        assertThatThrownBy(() -> jdbc.update(
                "insert into handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,created_at) values (?,?,1,'PENDING_DELIVERY','NOT_EXPECTED','CHANNEL_NOT_CONNECTED',?)",
                id(), first, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from handoff where source_kind='RISK' and source_id=?", Long.class, riskId)).isEqualTo(1L);
    }

    @Test
    void snapshotIsStoredAsJsonbObjectWithSchemaVersionOne() throws Exception {
        MvcResult created = mvc.perform(create(sessionA, riskId, recipientA, 1, "pg-snapshot-" + UUID.randomUUID())).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String handoffId = json.readTree(created.getResponse().getContentAsString()).path("data").path("handoff_id").asText();
        assertThat(handoffId).isNotBlank();

        // 真实库里快照必须是 jsonb 对象而不是被包成文本的字符串：jsonb 操作符能直接取出 schema_version。
        assertThat(jdbc.queryForObject("select pg_typeof(snapshot)::text from handoff_material_snapshot where handoff_id=?", String.class, handoffId)).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject("select jsonb_typeof(snapshot) from handoff_material_snapshot where handoff_id=?", String.class, handoffId)).isEqualTo("object");
        assertThat(jdbc.queryForObject("select snapshot->>'schema_version' from handoff_material_snapshot where handoff_id=?", String.class, handoffId)).isEqualTo("1");
        assertThat(jdbc.queryForObject("select schema_version from handoff_material_snapshot where handoff_id=?", Integer.class, handoffId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select snapshot->'risk'->>'risk_id' from handoff_material_snapshot where handoff_id=?", String.class, handoffId)).isEqualTo(riskId);
        assertThat(jdbc.queryForObject("select snapshot->'risk'->>'state' from handoff_material_snapshot where handoff_id=?", String.class, handoffId)).isEqualTo("PENDING_NOTIFICATION");
        assertThat(jdbc.queryForObject("select jsonb_array_length(snapshot->'verifications') from handoff_material_snapshot where handoff_id=?", Integer.class, handoffId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select jsonb_exists(snapshot,'files') from handoff_material_snapshot where handoff_id=?", Boolean.class, handoffId)).isFalse();
        // 首投记录：attempt_no=1、待投递、无回执、渠道未接通；提交不等于送达。
        assertThat(jdbc.queryForObject(
                "select count(*) from handoff_delivery where handoff_id=? and attempt_no=1 and delivery_status='PENDING_DELIVERY' and receipt_status='NOT_EXPECTED' and blocked_reason='CHANNEL_NOT_CONNECTED' and submitted_at is null and delivered_at is null and acknowledged_at is null",
                Long.class, handoffId)).isEqualTo(1L);
        assertRiskNotified();
    }

    @Test
    void twoRealConnectionsSubmittingSameRiskToSameRecipientCommitExactlyOneHandoff() throws Exception {
        List<MvcResult> results = race(
                create(sessionA, riskId, recipientA, 1, "race-handoff-a-" + UUID.randomUUID()),
                create(sessionB, riskId, recipientA, 1, "race-handoff-b-" + UUID.randomUUID()));
        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(201, 409);
        MvcResult rejected = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        JsonNode error = json.readTree(rejected.getResponse().getContentAsString()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(error.has("handoff_id")).isFalse();
        MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 201).findFirst().orElseThrow();
        String handoffId = json.readTree(winner.getResponse().getContentAsString()).path("data").path("handoff_id").asText();

        // 断言数据库行：恰一份交接、恰一条首投记录、恰一份快照、恰一条成功审计；输家的幂等占位已随事务回滚。
        List<Map<String, Object>> handoffs = jdbc.queryForList(
                "select handoff_id,submitted_by,source_version from handoff where source_kind='RISK' and source_id=? and handoff_type='RISK_NOTICE' and recipient_id=?",
                riskId, recipientA);
        assertThat(handoffs).singleElement().satisfies(row -> {
            assertThat(row.get("handoff_id")).isEqualTo(handoffId);
            assertThat(((Number) row.get("source_version")).longValue()).isEqualTo(1L);
        });
        String winnerUser = (String) handoffs.get(0).get("submitted_by");
        assertThat(winnerUser).isIn(userA, userB);
        String loserUser = winnerUser.equals(userA) ? userB : userA;
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=? and attempt_no=1 and delivery_status='PENDING_DELIVERY'", Long.class, handoffId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery d join handoff h on h.handoff_id=d.handoff_id where h.source_id=?", Long.class, riskId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from handoff_material_snapshot where handoff_id=?", Long.class, handoffId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='handoff_created' and object_type='handoff' and result='SUCCESS' and object_id in (select handoff_id from handoff where source_id=?)", Long.class, riskId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=?", Long.class, winnerUser)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=?", Long.class, loserUser)).isZero();
        assertRiskNotified();
    }

    @Test
    void differentRecipientsEachGetTheirOwnHandoffWithVersionRetry() throws Exception {
        List<MvcResult> results = race(
                create(sessionA, riskId, recipientA, 1, "pair-handoff-a-" + UUID.randomUUID()),
                create(sessionB, riskId, recipientB, 1, "pair-handoff-b-" + UUID.randomUUID()));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201, 409);
        int loser = results.get(0).getResponse().getStatus() == 409 ? 0 : 1;
        assertThat(json.readTree(results.get(loser).getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(mvc.perform(create(loser == 0 ? sessionA : sessionB, riskId, loser == 0 ? recipientA : recipientB, 2,
                "retry-version-" + UUID.randomUUID())).andReturn().getResponse().getStatus()).isEqualTo(201);
        List<String> recipients = jdbc.queryForList(
                "select recipient_id from handoff where source_kind='RISK' and source_id=? and handoff_type='RISK_NOTICE' order by recipient_id", String.class, riskId);
        assertThat(recipients).containsExactlyInAnyOrder(recipientA, recipientB);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery d join handoff h on h.handoff_id=d.handoff_id where h.source_id=? and d.attempt_no=1", Long.class, riskId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from handoff_material_snapshot s join handoff h on h.handoff_id=s.handoff_id where h.source_id=?", Long.class, riskId)).isEqualTo(2L);
        assertRiskNotified();
    }

    /** 提交推进已通知及版本，核验历史保持不变。 */
    private void assertRiskNotified() {
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, riskId)).isEqualTo("NOTIFIED");
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, riskId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from flight_risk_verification where risk_id=?", Long.class, riskId)).isEqualTo(1L);
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

    private static MockHttpServletRequestBuilder create(String session, String riskId, String recipientId, long version, String key) {
        return post("/api/v1/handoffs").header("Authorization", "Bearer " + session).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source_kind\":\"RISK\",\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\",\"recipient_id\":\""
                        + recipientId + "\",\"expected_version\":" + version + "}");
    }

    private String role() {
        String role = "ROLE-S5-PG-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'risk:read','READ',false,current_timestamp),(?,'handoff:read','READ',false,current_timestamp),(?,'handoff:create','OP',false,current_timestamp)",
                role, role, role);
        return role;
    }

    /** ASSIGNED 用户配同一 (org,district) 授权元组，走与生产一致的精确范围谓词。 */
    private String session(String user, String roleCode, String account) {
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                user, account, "阶段五交接员", roleCode);
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
            throw new IllegalStateException("Refusing Stage 5 verification outside a stage456_verify_ database");
        }
        String extension = root.queryForObject("select extversion from pg_extension where extname='postgis'", String.class);
        if (extension == null || extension.isBlank()) {
            throw new IllegalStateException("PostGIS is required for Stage 5 verification");
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
            throw new IllegalStateException("Unsafe Stage 5 verification schema");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
