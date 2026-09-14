package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import org.flywaydb.core.Flyway;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;

import com.uav.lowaltitude.modules.disposal.application.DisposalExpiryJob;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 阶段 13（处置授权域）PostgreSQL 专项验证。沿用 Stage9/Stage85PostgresTest 已跑通的做法：
 * env 门禁（缺 `POSTGRES_TEST_*` 整类跳过）、`stage456_verify_` 库名硬校验、随机 `stage456_` schema、
 * 手动 Flyway（`db/migration` + `db/postgresql`）、**不使用测试级事务**（到期任务自己开事务，测试再包一层会看不到提交结果）、只删本 schema。
 *
 * <p>本轮包含**不依赖 E1 写接口**的部分：迁移与约束、事件流只增、有效期 CHECK、到期任务的四条行为。
 * 契约要求的三处并发（并发审批一成一 409、并发申请同主体 `ACTIVE_AUTHORIZATION_EXISTS`、编号并发无重复）
 * 都要经 `POST /disposal-authorizations` 与 `/{id}/approve`，接口落地后按文末 TODO 补齐。
 * 现在就写死那些断言只会让本类在接口落地前整体失败，掩盖真正的回归。
 *
 * <p>到期开关在本类里是**打开**的：要验的正是这个任务的行为。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@TestPropertySource(properties = "app.disposal.expiry.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage13PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage13PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage13PostgresTest 未在真实 PostgreSQL 上执行")
class Stage13PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AppClock clock;
    @Autowired DisposalExpiryJob expiryJob;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    private String suffix, org, district, requester, approver;

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
        registry.add("app.rule-engine.c04.enabled", () -> "false");
        registry.add("app.fusion.enabled", () -> "false");
        registry.add("app.fusion.replay.run-on-start", () -> "false");
        // 调度器不按节拍跑：到期用例要自己控制触发时机，后台线程插一脚会让断言不可复现。
        registry.add("app.disposal.expiry.poll-millis", () -> "3600000");
        // 编号并发用例要 20 个请求同时在库里，默认连接池只有 10 个，会在栅栏后互相等连接直到超时——
        // 那测出来的是连接池大小，不是编号的并发安全。把池调到并发度之上，让锁竞争真的发生在计数表上。
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "28");
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
        org = id(); district = id(); requester = id(); approver = id();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "ORG-S13-" + suffix, "阶段十三验证机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-S13-" + suffix, "阶段十三验证区域");
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " select ?,?,'',false,true,0,0,0,false where not exists (select 1 from app_role where role_code=?)",
                "ROLE-S13-" + suffix, "阶段十三验证角色 " + suffix, "ROLE-S13-" + suffix);   // app_role.name 也有唯一约束
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", requester, "s13-" + suffix, "阶段十三申请人", "ROLE-S13-" + suffix);
        // 审批人与申请人分开：两人规则（决策 13-2）是应用层的事，但夹具照着真实形态造，别让用例里出现自己批自己。
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", approver, "s13a-" + suffix, "阶段十三审批人", "ROLE-S13-" + suffix);
    }

    /** 迁移基线：0101/0102 与 `R__stage13_disposal` 已应用，策略与权限目录就位。 */
    @Test
    @Order(1)
    void stage13MigrationsAndPolicyCatalogAreAppliedToIsolatedPostgresSchema() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class))
                .as("有失败迁移时后续断言全部没有意义").isZero();
        for (String version : List.of("202609070101", "202609070102")) {
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version=?", Long.class, version))
                    .as("迁移 " + version + " 必须已应用").isEqualTo(1L);
        }
        // 策略是 DEMO：客户 Q5 未答复前不得标 CONFIRMED（决策 13-1）。
        Map<String, Object> policy = jdbc.queryForMap("select schema_status,status,cast(params as text) as params from disposal_policy where policy_code='demo-v1'");
        assertThat(policy).containsEntry("schema_status", "DEMO").containsEntry("status", "ACTIVE");
        assertThat((String) policy.get("params")).as("阈值全部走策略，代码里不得有裸阈值（决策 13-2）")
                .contains("two_person_rule").contains("time_limit_min").contains("requires_confirmed_event").contains("max_active_per_subject");
        // 迁移不写业务数据：本 schema 里的授权只能来自各用例夹具。
        for (String table : List.of("disposal_authorization", "disposal_authorization_event", "disposal_no_counter")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table + " 迁移不得预置业务数据").isZero();
        }
    }

    /**
     * 事件流只增（`trg_stage13_disposal_event_append_only`）。
     * 一次反制的全过程是有法律后果的事实链——谁批的、几点执行的、设备回了什么，都可能进处罚案件的证据。
     * 断言 SQLSTATE **23514**：只断言"抛了"，将来触发器换成任何别的机制照样绿。
     */
    @Test
    @Order(2)
    void disposalEventStreamIsAppendOnlyOnPostgres() {
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='disposal_authorization_event'::regclass"
                + " and tgname='trg_stage13_disposal_event_append_only'", Long.class)).isEqualTo(1L);

        String authorizationId = insertAuthorization("APPROVED", T0, T0.plusMinutes(30));
        String eventId = insertEvent(authorizationId, "APPROVE", "批准反制");

        assertThat(sqlState(catching(() -> jdbc.update("update disposal_authorization_event set note='改口供' where event_id=?", eventId))))
                .as("事件 UPDATE 必须以 23514 被拒").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("update disposal_authorization_event set event_kind='CANCEL' where event_id=?", eventId))))
                .isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("delete from disposal_authorization_event where event_id=?", eventId))))
                .as("事件 DELETE 必须以 23514 被拒").isEqualTo("23514");
        // 被拒之后原行原封不动。
        Map<String, Object> row = jdbc.queryForMap("select event_kind,note from disposal_authorization_event where event_id=?", eventId);
        assertThat(row).containsEntry("event_kind", "APPROVE").containsEntry("note", "批准反制");
    }

    /**
     * 审批四件套与有效期窗口这两条 CHECK 是配套的，必须一起验：
     *
     * <p>`ck_stage13_authorization_approval`：`approved_by / approved_at / valid_from / valid_until` **要么全空、要么全有**。
     * 批过就得同时留下批的人、批的时刻和有效期——少任何一项都说不清"这次动手凭什么"。
     * <p>`ck_stage13_authorization_window`：全有时还要求 `valid_until > valid_from`。零长度或倒挂的窗口
     * 对一个"允许动手"的授权来说是说不清的边界。
     *
     * <p>**两条按各自的约束名分别断言**：只断言"抛了 23514"的话，删掉其中一条、让另一条顺带挡住，用例照样绿。
     */
    @Test
    @Order(3)
    void approvalQuartetAndValidityWindowAreCheckedTogetherOnPostgres() {
        // 未审批：四列全空，合法。
        assertThat(catching(() -> insertAuthorization("REQUESTED", null, null))).as("尚未审批是合法状态").isNull();
        // 已审批且窗口正常：合法。
        assertThat(catching(() -> insertAuthorization("APPROVED", T0, T0.plusMinutes(30)))).isNull();

        // 窗口零长度 / 倒挂：走 window 约束。
        Throwable zeroLength = catching(() -> insertAuthorization("APPROVED", T0, T0));
        assertThat(sqlState(zeroLength)).isEqualTo("23514");
        assertThat(zeroLength).hasMessageContaining("ck_stage13_authorization_window");
        Throwable inverted = catching(() -> insertAuthorization("APPROVED", T0, T0.minusMinutes(1)));
        assertThat(sqlState(inverted)).isEqualTo("23514");
        assertThat(inverted).hasMessageContaining("ck_stage13_authorization_window");

        // 四件套只填一半：走 approval 约束。注意 R__ 的 window 约束**放行**单边（它只在两端都有值时比较），
        // 真正挡住单边的是 0102 的 approval 约束——两条各管一段，缺哪条都会漏。
        Throwable halfApproved = catching(() -> insertAuthorizationRaw("APPROVED", approver, T0, T0, null));
        assertThat(sqlState(halfApproved)).isEqualTo("23514");
        assertThat(halfApproved).as("只有 valid_from 没有 valid_until 必须被 approval 约束挡住")
                .hasMessageContaining("ck_stage13_authorization_approval");
        Throwable noApprover = catching(() -> insertAuthorizationRaw("APPROVED", null, null, T0, T0.plusMinutes(30)));
        assertThat(sqlState(noApprover)).isEqualTo("23514");
        assertThat(noApprover).as("有有效期却没有审批人，说不清是谁批的")
                .hasMessageContaining("ck_stage13_authorization_approval");
    }

    /**
     * 到期任务的四条行为（契约 §2 末段）。这条用例的重点在第三、四条：
     * **过期的 EXECUTING 不能被碰**——它对应设备上正在发生的动作，后台把它改成"已过期"会让页面显示与现场不符；
     * **重复运行不产生第二条 EXPIRE 事件**——事件流是只增的，多一条就永远消不掉。
     */
    @Test
    @Order(4)
    void expiryJobExpiresOnlyOverdueApprovedAuthorizationsAndIsIdempotent() {
        // 先把前面用例留下的、恰好也已过期的夹具行清干净：本类共用一个 schema，
        // 不清的话下面那句"本次只应改掉一条"会把别人的行也算进来——那种红是夹具串扰，不是缺陷。
        expiryJob.expireOnce();

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        String overdue = insertAuthorization("APPROVED", now.minusHours(2), now.minusHours(1));
        String stillValid = insertAuthorization("APPROVED", now.minusMinutes(5), now.plusHours(1));
        String executing = insertAuthorization("EXECUTING", now.minusHours(2), now.minusHours(1));

        int expired = expiryJob.expireOnce();
        assertThat(expired).as("本次只应改掉那条已过期的 APPROVED").isEqualTo(1);

        assertThat(status(overdue)).as("过期的 APPROVED 置为 EXPIRED").isEqualTo("EXPIRED");
        assertThat(status(stillValid)).as("未到期的不得被碰").isEqualTo("APPROVED");
        assertThat(status(executing)).as("执行中的授权即便过期也只能由回执或人工停止收尾，后台不得改").isEqualTo("EXECUTING");

        assertThat(eventCount(overdue, "EXPIRE")).as("状态翻转必须留下一条 EXPIRE 事件").isEqualTo(1L);
        assertThat(eventCount(stillValid, "EXPIRE")).isZero();
        assertThat(eventCount(executing, "EXPIRE")).as("没改状态就不该有事件").isZero();

        // 版本递增，便于读侧的 expected_version 发现状态已变。
        assertThat(jdbc.queryForObject("select version from disposal_authorization where authorization_id=?", Long.class, overdue))
                .isPositive();

        // 幂等：再跑一次不改任何行，也不追加第二条 EXPIRE 事件。
        assertThat(expiryJob.expireOnce()).as("已经过期的行不该被重复处理").isZero();
        assertThat(eventCount(overdue, "EXPIRE")).as("只增事件流里多一条就永远消不掉").isEqualTo(1L);
        assertThat(status(executing)).isEqualTo("EXECUTING");
    }

    /**
     * 两条真实连接并发审批同一申请：一个 200、一个 409，且**落败方不留半条痕迹**。
     *
     * <p>为什么"恰一条 APPROVE 事件"比"状态对"更要紧：状态是可以被后一次写覆盖的单一值，
     * 而事件流是只增的——落败方若也写了一条 APPROVE，库里就永远留着"批了两次、两个人批的"，
     * 这在处罚案件里是解释不清的。
     */
    @Test
    @Order(5)
    void twoRealConnectionsApprovingSameAuthorizationCommitExactlyOne() throws Exception {
        String requesterSession = session("disposal:request", "alarm:read");
        String approverSession = session("disposal:approve", "alarm:read");
        String authorizationId = createAuthorization(requesterSession, confirmedEvent());

        List<MvcResult> results = race(
                post("/api/v1/disposal-authorizations/{id}/approve", authorizationId)
                        .header("Authorization", "Bearer " + approverSession)
                        .header("Idempotency-Key", "approve-a-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0}"),
                post("/api/v1/disposal-authorizations/{id}/approve", authorizationId)
                        .header("Authorization", "Bearer " + approverSession)
                        .header("Idempotency-Key", "approve-b-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0}"));

        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                .as("同一申请并发审批必须一成一败").containsExactly(200, 409);
        assertThat(status(authorizationId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("select distinct approved_by from disposal_authorization where authorization_id=?",
                String.class, authorizationId)).as("审批人只能有一个").hasSize(1);
        assertThat(eventCount(authorizationId, "APPROVE"))
                .as("落败方不得也写一条 APPROVE——只增事件流里多一条就永远留着").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select version from disposal_authorization where authorization_id=?", Long.class, authorizationId))
                .as("只成功了一次，版本只加一次").isEqualTo(1L);
    }

    /**
     * 并发申请同主体同类型：一个 201、一个 409 `ACTIVE_AUTHORIZATION_EXISTS`。
     * 上限预检（`activeCount < max_active_per_subject`）单靠一次 SELECT 是挡不住并发的——
     * 真正让它可靠的是申请路径先对事件行加锁（`events.lock`）把同一主体的申请串行化。
     * 这条用例验的正是那把锁在真实 PG 上确实生效，而不是"预检看起来写了就行"。
     */
    @Test
    @Order(6)
    void twoRealConnectionsRequestingSameSubjectCommitExactlyOne() throws Exception {
        String requesterSession = session("disposal:request", "alarm:read");
        String eventId = confirmedEvent();

        List<MvcResult> results = race(
                createRequest(requesterSession, eventId, "create-a-" + UUID.randomUUID()),
                createRequest(requesterSession, eventId, "create-b-" + UUID.randomUUID()));

        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                .as("同主体同类型并发申请必须一成一败").containsExactly(201, 409);
        MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(json.readTree(loser.getResponse().getContentAsString()).path("error").path("code").asText())
                .isEqualTo("ACTIVE_AUTHORIZATION_EXISTS");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Long.class, eventId))
                .as("落败方整体回滚，不留半条授权").isEqualTo(1L);
    }

    /**
     * 编号并发：20 个各自主体的申请同时打进来，`authorization_no` 必须互不相同、格式一致、序号连续无洞。
     *
     * <p>**"无重复"必须用唯一性断言，不能用 count**：20 条里有两条重号、另有一条多出来，count 一样是 20。
     * 序号连续也要验——`disposal_no_counter` 行锁若退化成"读了再写"，并发下会出现跳号，
     * 那意味着某次申请拿到的编号其实来自另一次的读，只是恰好没撞上。
     */
    @Test
    @Order(7)
    void concurrentRequestsNeverProduceDuplicateAuthorizationNumbers() throws Exception {
        int parallelism = 20;
        String requesterSession = session("disposal:request", "alarm:read");
        List<MockHttpServletRequestBuilder> requests = new java.util.ArrayList<>();
        for (int i = 0; i < parallelism; i++) {
            requests.add(createRequest(requesterSession, confirmedEvent(), "no-" + i + "-" + UUID.randomUUID()));
        }
        long before = jdbc.queryForObject("select count(*) from disposal_authorization", Long.class);

        List<MvcResult> results = race(requests);

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus())
                .as("各自主体互不冲突，全部应成功").isEqualTo(201));
        List<String> numbers = jdbc.queryForList("select authorization_no from disposal_authorization"
                + " order by authorization_no", String.class);
        assertThat(numbers).hasSize((int) before + parallelism);
        assertThat(numbers).as("编号不得重复").doesNotHaveDuplicates();

        // 只看本轮新出的那 20 个：格式、日期段、序号连续。
        List<String> fresh = numbers.stream().filter(no -> !no.startsWith("AUTH-20260907-")).toList();
        List<String> generated = fresh.isEmpty() ? numbers : fresh;
        assertThat(generated).allSatisfy(no -> assertThat(no).matches("^AUTH-\\d{8}-\\d{4}$"));
        assertThat(generated.stream().map(no -> no.substring(5, 13)).distinct().toList())
                .as("同一次运行只跨一个日期段").hasSize(1);
        List<Integer> sequences = generated.stream().map(no -> Integer.parseInt(no.substring(14))).sorted().toList();
        assertThat(sequences).doesNotHaveDuplicates();
        assertThat(sequences.get(sequences.size() - 1) - sequences.get(0))
                .as("序号必须连续无洞：跳号说明计数器的读与写不在同一把锁里").isEqualTo(sequences.size() - 1);
    }

    /**
     * 到期任务的多实例并发：两条线程同时 `expireOnce()`，同一批过期授权只能被处理一次。
     * 两人合计改掉的条数等于应过期条数，且**每条恰有一条 EXPIRE 事件**——
     * 这正是"状态翻转与事件绑在同一个条件更新的胜负上"要保证的东西，单进程的幂等验不出来。
     */
    @Test
    @Order(8)
    void twoConcurrentExpiryRunsNeverDoubleExpireOrDoubleRecord() throws Exception {
        expiryJob.expireOnce();   // 先清掉前面用例遗留的过期行，本轮只看自己造的这批
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        int overdueCount = 8;
        List<String> overdue = new java.util.ArrayList<>();
        for (int i = 0; i < overdueCount; i++) {
            overdue.add(insertAuthorization("APPROVED", now.minusHours(2), now.minusHours(1)));
        }

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int first, second;
        try {
            Future<Integer> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return expiryJob.expireOnce(); });
            Future<Integer> b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return expiryJob.expireOnce(); });
            first = a.get(60, TimeUnit.SECONDS);
            second = b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(first + second).as("两人合计恰好处理这一批，多一条就说明有行被改了两次").isEqualTo(overdueCount);
        for (String authorizationId : overdue) {
            assertThat(status(authorizationId)).isEqualTo("EXPIRED");
            assertThat(eventCount(authorizationId, "EXPIRE"))
                    .as("每条恰一条 EXPIRE 事件：这是条件更新与事件绑在一起的直接证据").isEqualTo(1L);
        }
    }

    /**
     * 分两步升级（决策 13-32 修订，审查第 13 轮 P1-1）：先停在阶段 10 的 074，再前进到最新。
     *
     * <p><b>这条用例存在的理由</b>：只增触发器原先放在 `R__stage13_disposal.sql` 里。R__ 只在校验和变化时重跑——
     * 一个先停在"`disposal_authorization_event` 还没建"的版本、随后再前进的库，会在第一次 migrate 时
     * 把该 R__ 记为已应用（守卫让它什么都没做），之后**永远不再补触发器**。
     * 结果是：库看起来迁移全绿、事件表却没有只增保护，而这件事在一次性建库的测试里怎么也测不出来。
     * 现在触发器与 CHECK 移到版本化的 `V202609070103`，本用例就是钉住"分两步升级也真的装上了"。
     *
     * <p>断言落在**行为**上而不是"触发器存在"：查 `pg_trigger` 只能证明有个同名对象，
     * 证明不了它真的拦得住写入。这里直接写一次、删一次，要求都以 23514 被拒。
     */
    @Test
    @Order(10)
    void stagedUpgradeStillInstallsAppendOnlyTriggerAndWindowCheck() {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe scratch schema");
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        root.execute("create schema " + schema);
        try {
            // 第一步：停在阶段 10 的 074——此时阶段 13 的表都还没建。
            flywayFor(schema, "202609050074").migrate();
            JdbcTemplate scratch = scratchTemplate(schema);
            assertThat(scratch.queryForObject("select to_regclass(?)::text", String.class, schema + ".disposal_authorization_event"))
                    .as("停在 074 时阶段 13 的表还不该存在").isNull();

            // 第二步：前进到最新。
            flywayForAll(schema).migrate();
            assertThat(scratch.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();

            // 行为断言：事件写得进，改不动、删不掉。
            String eventId = seedAuthorizationEvent(scratch, schema);
            assertThat(sqlState(catching(() -> scratch.update(
                    "update disposal_authorization_event set note='x' where event_id=?", eventId))))
                    .as("分两步升级的库上，事件 UPDATE 仍必须以 23514 被拒").isEqualTo("23514");
            assertThat(sqlState(catching(() -> scratch.update(
                    "delete from disposal_authorization_event where event_id=?", eventId))))
                    .as("DELETE 同样").isEqualTo("23514");
            assertThat(scratch.queryForObject("select note from disposal_authorization_event where event_id=?", String.class, eventId))
                    .isEqualTo("分两步升级验证");

            // 有效期 CHECK 也必须在（它和触发器一起从 R__ 搬到了 0103）。
            assertThat(scratch.queryForObject("select count(*) from pg_constraint"
                    + " where conrelid=(quote_ident(?)||'.disposal_authorization')::regclass"
                    + " and conname='ck_stage13_authorization_window'", Long.class, schema)).isEqualTo(1L);
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    /** 在指定 schema 上造出一条授权与一条事件，返回事件 id。夹具链：角色→用户→机构/区域→授权→事件。 */
    private String seedAuthorizationEvent(JdbcTemplate scratch, String schema) {
        String tag = schema.substring(SCHEMA_PREFIX.length(), SCHEMA_PREFIX.length() + 8);
        String orgId = id(), districtId = id(), roleCode = "ROLE-UP-" + tag, userId = id(),
                authorizationId = id(), eventId = id();
        scratch.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                orgId, "ORG-UP-" + tag, "分步升级验证机构 " + tag);
        scratch.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                districtId, "DIST-UP-" + tag, "分步升级验证区域 " + tag);
        scratch.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", roleCode, "分步升级验证角色 " + tag);
        scratch.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", userId, "up-" + tag, "分步升级验证人", roleCode);
        scratch.update("insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " values (?,?,'COUNTERMEASURE','UAV_EVENT',?,'MANUAL','分两步升级验证',?,?,'REQUESTED','demo-v1',?,?,'live',0,?,?)",
                authorizationId, "AUTH-20260908-" + tag.substring(0, 4), "subject-" + tag, userId, T0, orgId, districtId, T0, T0);
        scratch.update("insert into disposal_authorization_event (event_id,authorization_id,event_kind,actor_id,note,snapshot,occurred_at)"
                + " values (?,?,'REQUEST',?,?,cast('{}' as json),?)", eventId, authorizationId, userId, "分两步升级验证", T0);
        return eventId;
    }

    private static Flyway flywayFor(String schema, String targetVersion) {
        return Flyway.configure().dataSource(rootDataSource()).schemas(schema).defaultSchema(schema).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql")
                .target(MigrationVersion.fromVersion(targetVersion)).load();
    }

    private static Flyway flywayForAll(String schema) {
        return Flyway.configure().dataSource(rootDataSource()).schemas(schema).defaultSchema(schema).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load();
    }

    private static JdbcTemplate scratchTemplate(String schema) {
        String base = requiredEnvironment("POSTGRES_TEST_URL");
        return new JdbcTemplate(new DriverManagerDataSource(base + (base.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public", requiredEnvironment("POSTGRES_TEST_USER"),
                requiredEnvironment("POSTGRES_TEST_PASSWORD")));
    }

    /** 造一条已核实的无人机事件（申请路径只支持 UAV_EVENT，且策略要求 CONFIRMED）。 */
    private String confirmedEvent() {
        String alarmId = id(), eventId = id();
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select ?,?,?,true,'mock',?,?,0 where not exists (select 1 from integration_source where source_id=?)",
                "s13-src-" + suffix, "S13-SRC-" + suffix, "阶段十三验证来源", T0, T0, "s13-src-" + suffix);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarmId, "s13-src-" + suffix, "告警-" + alarmId, T0, T0, org, district, T0);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'CONFIRMED',?,?,?,?,1)", eventId, alarmId, org, district, T0, T0);
        return eventId;
    }

    private MockHttpServletRequestBuilder createRequest(String sessionId, String eventId, String idempotencyKey) {
        return post("/api/v1/disposal-authorizations").header("Authorization", "Bearer " + sessionId)
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action_type\":\"COUNTERMEASURE\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\"" + eventId
                        + "\",\"channel\":\"MANUAL\",\"reason\":\"阶段十三并发验证\"}");
    }

    private String createAuthorization(String sessionId, String eventId) throws Exception {
        MvcResult result = mvc.perform(createRequest(sessionId, eventId, "seed-" + UUID.randomUUID())).andReturn();
        assertThat(result.getResponse().getStatus()).as("夹具申请必须成功：" + result.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("authorization_id").asText();
    }

    /** 建一个带指定权限、范围为 ALL 的会话，返回 session id。 */
    private String session(String... permissions) {
        String user = id(), token = UUID.randomUUID().toString(), role = "ROLE-S13X-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, "阶段十三 " + role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", user, "s13u-" + user.substring(0, 8), "阶段十三会话", role);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private List<MvcResult> race(MockHttpServletRequestBuilder... requests) throws Exception {
        return race(List.of(requests));
    }

    /** 用栅栏把 N 个请求对齐到同一时刻发出：不对齐的话先到的早就提交完了，测不到并发。 */
    private List<MvcResult> race(List<MockHttpServletRequestBuilder> requests) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(requests.size());
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<MvcResult>> futures = new java.util.ArrayList<>();
            for (MockHttpServletRequestBuilder request : requests) {
                futures.add(pool.submit(() -> { barrier.await(20, TimeUnit.SECONDS); return mvc.perform(request).andReturn(); }));
            }
            List<MvcResult> results = new java.util.ArrayList<>();
            for (Future<MvcResult> future : futures) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 执行受阻的四分（决策 13-14 / 13-22）。四个 `execution_block_reason` 各对应**一个不同的补救方**：
     * 等厂家开协议 / 运维补登记 / 现场处理设备 / 换设备通道。页面必须分开说，
     * 否则运维会把自己能修的事（补一条 MQTT 绑定）当成厂家的事一直挂着。
     *
     * <p><b>为什么 ① 要临时改策略</b>：60003 已映射 ifr，真实策略会越过「未开通」去看绑定/在线。
     * 用协议标明未有真实设备的 50000 才能单独打到 PROTOCOL_NOT_OPENED。③④ 用真实 60003 即可，
     * 不再把码换成 10000。用例结束时把策略改回去。
     */
    @Test
    @Order(9)
    void executionBlockedReasonsSplitFourWaysOnPostgres() throws Exception {
        String requesterSession = session("disposal:request", "alarm:read");
        String approverSession = session("disposal:approve", "alarm:read", "disposal:read");
        // 设备控制面的权限码 devices.op 是由目录行 devices 在 OP 级别派生出来的，不是独立的目录行（决策 13-9）。
        String executorSession = session("disposal:execute", "disposal:read", "alarm:read", "devices");

        String deviceId = boundOpsDevice("radar");
        // ② 四通道反制选了雷达设备（补救方＝换设备）：不是四通道协议，事件 DEVICE_CONTROL_UNAVAILABLE。
        String fourChannel = approvedAuthorization(requesterSession, approverSession, "JAMMING", "COUNTERMEASURE_4CH", deviceId);
        assertThat(executeExpectingConflict(executorSession, fourChannel)).isEqualTo("DEVICE_CONTROL_UNAVAILABLE");
        assertThat(eventCount(fourChannel, "DEVICE_CONTROL_UNAVAILABLE")).isEqualTo(1L);
        assertThat(blockReason(approverSession, fourChannel)).isEqualTo("DEVICE_CAPABILITY");

        String originalParams = jdbc.queryForObject(
                "select cast(params as text) from disposal_policy where policy_code='demo-v1'", String.class);
        String protocolBlocked;
        try {
            // ① 指令码未开通（50000 未映射，补救方＝厂家）。
            usePolicyCommand(50000);
            protocolBlocked = approvedAuthorization(requesterSession, approverSession, "COUNTERMEASURE", "LINGYUN_B", deviceId);
            assertThat(executeExpectingConflict(executorSession, protocolBlocked)).isEqualTo("DEVICE_CONTROL_UNAVAILABLE");
            assertThat(eventCount(protocolBlocked, "PROTOCOL_NOT_OPENED")).isEqualTo(1L);
            assertThat(blockReason(approverSession, protocolBlocked)).isEqualTo("PROTOCOL_NOT_OPENED");
            assertThat(status(protocolBlocked)).as("受阻不推进状态：授权仍是 APPROVED，等厂家开通后可再执行").isEqualTo("APPROVED");
        } finally {
            jdbc.update("update disposal_policy set params=cast(? as json) where policy_code='demo-v1'", originalParams);
        }

        // ③ 未登记 MQTT 绑定（补救方＝运维）。60003 已开通，不再需要把码换成 10000。
        String unboundDevice = opsDevice(true);   // 有设备行、没有绑定行
        String notBound = approvedAuthorization(requesterSession, approverSession, "COUNTERMEASURE", "LINGYUN_B", unboundDevice);
        assertThat(executeExpectingConflict(executorSession, notBound)).isEqualTo("DEVICE_NOT_BOUND");
        assertThat(eventCount(notBound, "DEVICE_NOT_BOUND")).isEqualTo(1L);
        assertThat(blockReason(approverSession, notBound)).isEqualTo("NOT_BOUND");

        // ④ 已登记但设备未启用/不在线（补救方＝现场）。
        // 有绑定、但没有 ops_device_state 行 → connectivity 不是 ONLINE → 走"不在线"那一支。
        String offlineDevice = boundOpsDevice("radar");
        String offline = approvedAuthorization(requesterSession, approverSession, "COUNTERMEASURE", "LINGYUN_B", offlineDevice);
        assertThat(executeExpectingConflict(executorSession, offline)).isEqualTo("DEVICE_OFFLINE");
        assertThat(eventCount(offline, "DEVICE_OFFLINE")).isEqualTo(1L);
        assertThat(blockReason(approverSession, offline)).isEqualTo("DEVICE_OFFLINE");

        // 四个取值互不相同——这正是"四个补救方"能被页面分开说的前提。
        assertThat(List.of(blockReason(approverSession, protocolBlocked), blockReason(approverSession, fourChannel),
                blockReason(approverSession, notBound), blockReason(approverSession, offline)))
                .containsExactlyInAnyOrder("PROTOCOL_NOT_OPENED", "DEVICE_CAPABILITY", "NOT_BOUND", "DEVICE_OFFLINE");
    }

    /** 把 demo-v1 里 COUNTERMEASURE 的 operation_cmd 换成指定值，其余参数原样保留。 */
    private void usePolicyCommand(int operationCmd) throws Exception {
        String params = jdbc.queryForObject("select cast(params as text) from disposal_policy where policy_code='demo-v1'", String.class);
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(params);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("command_map").path("COUNTERMEASURE"))
                .put("operation_cmd", operationCmd);
        jdbc.update("update disposal_policy set params=cast(? as json) where policy_code='demo-v1'", json.writeValueAsString(root));
    }

    /**
     * 授权里的 `device_id` 用的是**协作者 A 的 ops 设备 id**：A 的控制面按 `ops_device_id` 查绑定、
     * 按 `ops_device` 判启用与在线（`MqttRepository.binding` / `DeviceRepository.find` 都是这么写的），
     * 阶段 13 的种子也是从 `ops_device` 取的。用业务侧 `device.device_id` 会让 `bound()` 永远查不到绑定，
     * 于是"已绑定但离线"这一支被误报成"未绑定"——夹具走错 id 空间，测出来的分流就是假的。
     */
    private String opsDevice(boolean enabled) {
        String opsDeviceId = id(), tag = opsDeviceId.substring(0, 8);
        long now = T0.toInstant().toEpochMilli();
        jdbc.update("insert into ops_integration_source (source_id,source_code,name,source_mode,enabled,simulated,created_at,updated_at)"
                + " values (?,?,?,'live',true,false,?,?)", "ops-src-" + tag, "OPS-" + tag, "阶段十三 ops 来源", now, now);
        jdbc.update("insert into ops_device (device_id,source_id,external_device_id,device_no,name,device_type_name,channel,"
                + "enabled,source_mode,simulated,version,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,'live',false,0,?,?)", opsDeviceId, "ops-src-" + tag, "ext-" + tag,
                "OPSDEV-" + tag, "阶段十三 ops 设备", "雷达", "mqtt", enabled, now, now);
        // 绑定查询会 JOIN device_business_scope（`MqttRepository.BINDING_SELECT`）：缺这一行，
        // 绑定存在也查不出来，"已绑定但离线"就会被误报成"未绑定"。
        jdbc.update("insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at)"
                + " values (?,?,?,?,?)", opsDeviceId, org, district, T0, T0);
        return opsDeviceId;
    }

    /**
     * 在 ops 设备之上再登记一条凌云 MQTT 绑定。绑定表同时引用 ops 侧与业务侧的来源与设备，
     * 四个外键都要先有行，少一个就插不进去。
     */
    private String boundOpsDevice(String deviceTypeAbbr) {
        String opsDeviceId = opsDevice(true), tag = opsDeviceId.substring(0, 8);
        long now = T0.toInstant().toEpochMilli();
        String broker = "broker-" + tag, businessSource = "src-" + tag, businessDevice = id();
        jdbc.update("insert into mqtt_broker (broker_id,name,host,port,tls,client_id,allowed_cidrs,source_mode,"
                + "owner_org_id,district_id,enabled,version,created_at,updated_at)"
                + " values (?,?,?,1883,false,?,'127.0.0.1/32','live',?,?,true,0,?,?)", broker, "阶段十三 broker " + tag,
                "127.0.0.1", "client-" + tag, org, district, now, now);
        jdbc.update("insert into integration_source (source_id,source_code,source_type,name,source_mode,enabled,created_at,updated_at,version)"
                + " values (?,?,'RADAR',?,'live',true,?,?,0)", businessSource, "SRC-" + tag, "阶段十三业务来源", T0, T0);
        jdbc.update("insert into device (device_id,source_id,external_device_id,device_no,name,device_type_code,enabled,"
                + "source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,true,'live',?,?,?,?,0)", businessDevice, businessSource, "ext-" + tag,
                "DEV-S13-" + tag, "阶段十三业务设备", "RADAR", org, district, T0, T0);
        jdbc.update("insert into mqtt_device_binding (ops_device_id,device_id,ops_source_id,source_id,broker_id,provider_code,"
                + "device_type_abbr,external_device_id,source_mode,subscribed)"
                + " values (?,?,?,?,?,?,?,?,'live',true)", opsDeviceId, businessDevice, "ops-src-" + tag, businessSource,
                broker, "lingyun", deviceTypeAbbr, "ext-" + tag);
        return opsDeviceId;
    }

    /** 申请→审批，返回处于 APPROVED 的授权 id。 */
    private String approvedAuthorization(String requesterSession, String approverSession, String actionType,
            String channel, String deviceId) throws Exception {
        String eventId = confirmedEvent();
        MvcResult created = mvc.perform(post("/api/v1/disposal-authorizations")
                .header("Authorization", "Bearer " + requesterSession).header("Idempotency-Key", "exec-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action_type\":\"" + actionType + "\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\"" + eventId
                        + "\",\"channel\":\"" + channel + "\",\"device_id\":\"" + deviceId + "\",\"reason\":\"阶段十三执行分流验证\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("申请应成功：" + created.getResponse().getContentAsString()).isEqualTo(201);
        String authorizationId = json.readTree(created.getResponse().getContentAsString()).path("data").path("authorization_id").asText();
        MvcResult approved = mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", authorizationId)
                .header("Authorization", "Bearer " + approverSession).header("Idempotency-Key", "exec-ap-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0}")).andReturn();
        assertThat(approved.getResponse().getStatus()).as("审批应成功：" + approved.getResponse().getContentAsString()).isEqualTo(200);
        return authorizationId;
    }

    /** 执行并期待 409，返回错误码。 */
    private String executeExpectingConflict(String executorSession, String authorizationId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/disposal-authorizations/{id}/execute", authorizationId)
                .header("Authorization", "Bearer " + executorSession).header("Idempotency-Key", "exec-do-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":1}")).andReturn();
        assertThat(result.getResponse().getStatus()).as("受阻应为 409：" + result.getResponse().getContentAsString()).isEqualTo(409);
        return json.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText();
    }

    /** 从详情接口读派生值：要验的正是接口给出来的那个，不是自己从事件流推的。 */
    private String blockReason(String readerSession, String authorizationId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/disposal-authorizations/{id}", authorizationId)
                .header("Authorization", "Bearer " + readerSession)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode node =
                json.readTree(result.getResponse().getContentAsString()).path("data").path("execution_block_reason");
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }


    /** 审批四件套是全有或全无（`ck_stage13_authorization_approval`）：给了有效期就自动带上审批人与审批时刻。 */
    private String insertAuthorization(String status, OffsetDateTime validFrom, OffsetDateTime validUntil) {
        boolean approved = validFrom != null || validUntil != null;
        return insertAuthorizationRaw(status, approved ? approver : null, approved ? T0 : null, validFrom, validUntil);
    }

    /** 逐列可控的版本：用来构造"四件套只填一半"这类必须被约束挡住的形态。 */
    private String insertAuthorizationRaw(String status, String approvedBy, OffsetDateTime approvedAt,
            OffsetDateTime validFrom, OffsetDateTime validUntil) {
        String authorizationId = id();
        jdbc.update("insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,policy_version,"
                + "owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " values (?,?,'COUNTERMEASURE','TARGET',?,'MANUAL','阶段十三验证',?,?,?,?,?,?,?,'demo-v1',?,?,'live',0,?,?)",
                authorizationId, "AUTH-20260907-" + nextNo(), "subject-" + suffix + "-" + nextSubject(), requester, T0,
                approvedBy, approvedAt, validFrom, validUntil, status, org, district, T0, T0);
        return authorizationId;
    }

    private String insertEvent(String authorizationId, String kind, String note) {
        String eventId = id();
        jdbc.update("insert into disposal_authorization_event (event_id,authorization_id,event_kind,actor_id,note,snapshot,occurred_at)"
                + " values (?,?,?,?,?,cast('{}' as json),?)", eventId, authorizationId, kind, requester, note, T0);
        return eventId;
    }

    private String status(String authorizationId) {
        return jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, authorizationId);
    }

    private long eventCount(String authorizationId, String kind) {
        return jdbc.queryForObject("select count(*) from disposal_authorization_event where authorization_id=? and event_kind=?",
                Long.class, authorizationId, kind);
    }

    /** 类级计数：`authorization_no` 是全局唯一键，每个用例各自从 1 开始会撞号。 */
    private static final java.util.concurrent.atomic.AtomicInteger NO_SEQ = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger SUBJECT_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** 夹具自己的编号：真正的编号生成（`disposal_no_counter` 行锁）归 E1，其并发性见文末 TODO。 */
    private static String nextNo() { return String.format("%04d", NO_SEQ.incrementAndGet()); }
    private static String nextSubject() { return String.format("%03d", SUBJECT_SEQ.incrementAndGet()); }

    /** 捕获期望中的写入失败：返回异常本身（null 表示写入意外成功）。 */
    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException expected) {
            return expected;
        }
    }

    /** 取最内层 PostgreSQL 异常的 SQLSTATE：只断言"抛了"会让约束换成别的机制时照样通过。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return failure == null ? "<未抛异常>" : "<无 SQLException: " + failure + ">";
    }

    private String id() { return UUID.randomUUID().toString(); }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        // 只接受专用验证库，防止误连生产库或日常联调库。
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 13 verification outside a stage456_verify_ database");
        }
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
        return new DriverManagerDataSource(requiredEnvironment("POSTGRES_TEST_URL"), requiredEnvironment("POSTGRES_TEST_USER"),
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe Stage 13 verification schema");
    }
}
