package com.uav.lowaltitude.modules.punishment.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 阶段 14（处罚案件域）PostgreSQL 专项验证。harness 沿用 Stage9/Stage13PostgresTest 已跑通的做法：
 * env 门禁（缺 `POSTGRES_TEST_*` 整类跳过）、`stage456_verify_` 库名硬校验、随机 `stage456_` schema、
 * 手动 Flyway（`db/migration` + `db/postgresql`）、**不使用测试级事务**（并发用例要真提交）、只删本 schema。
 * 库用 `stage456_verify_s14`。
 *
 * <p>这个域的每一步都可能进真实的行政处罚卷宗，所以本类钉的重点是**"哪些能改、哪些不能改"**：
 * 事件流与复核只增；已出具的决定书内容冻结但**允许作废**；已确认的裁量数字冻结但**允许被 SUPERSEDED**。
 * 只堵不放和只放不堵一样危险——前者逼业务去"删了重开"，后者让已经发出去的文书能被悄悄改掉金额。
 *
 * <p>本轮是第一批（不依赖 E1 的写接口）。契约要求的四处 HTTP 并发与材料包 v2 都要经接口，
 * 落地后按文末 TODO 补齐；现在写死只会红在"接口不存在"上，掩盖真正要验的东西。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage14PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage14PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage14PostgresTest 未在真实 PostgreSQL 上执行")
class Stage14PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 8, 12, 0, 0, 0, ZoneOffset.UTC);
    /** 阶段 13 末尾的版本：分两步升级用例的第一站。 */
    private static final String STAGE13_TAIL = "202609070103";
    private static final String SHA256 = "0".repeat(64);

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    private String suffix, org, district, officer, reviewer;

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
        registry.add("app.disposal.expiry.enabled", () -> "false");
        // 并发用例（案件编号 20 路）要让请求同时在库里：默认池 10 会让它们在栅栏后互相等连接，
        // 那测出来的是连接池大小而不是编号的并发安全（决策 13-28）。
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
        org = id(); district = id();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "ORG-S14-" + suffix, "阶段十四验证机构 " + suffix);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-S14-" + suffix, "阶段十四验证区域 " + suffix);
        // 承办人与复核人必须是两个人：契约里"复核人 ≠ 承办人"是 409 REVIEW_SELF_NOT_ALLOWED 的前提，
        // 夹具照真实形态造，别让用例里出现自己复核自己。
        officer = user("承办人");
        reviewer = user("复核人");
    }

    /** 迁移与档位目录基线。后面所有断言都以此为前提。 */
    @Test
    @Order(1)
    void stage14MigrationsAndPenaltyRuleCatalogAreAppliedToIsolatedPostgresSchema() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class))
                .as("有失败迁移时后续断言全部没有意义").isZero();
        for (String version : List.of("202609080101", "202609080102", "202609080103")) {
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version=?", Long.class, version))
                    .as("迁移 " + version + " 必须已应用").isEqualTo(1L);
        }
        // 档位表是**配置**不是演示数据：它必须在，且必须自述为 DEMO（条款号待法制岗核定）。
        assertThat(jdbc.queryForObject("select count(*) from penalty_rule", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from penalty_rule where schema_status<>'DEMO'", Long.class))
                .as("条款未经法制岗核定前不得标 CONFIRMED").isZero();
        assertThat(jdbc.queryForObject("select count(*) from penalty_rule where fine_min > fine_max", Long.class))
                .as("档位区间倒挂会让任何金额都判不出合规").isZero();
        // 迁移不写业务数据：本 schema 里的案件只能来自各用例夹具。
        for (String table : List.of("punishment_case", "punishment_case_event", "punishment_case_lead",
                "penalty_discretion", "penalty_decision_document", "punishment_review", "punishment_no_counter")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class))
                    .as(table + " 迁移不得预置业务数据").isZero();
        }
    }

    /**
     * 只增与冻结的四条规则，全部**按行为断言**（写一次、删一次），不查 `pg_trigger` 有没有同名对象——
     * 阶段 13 的教训：同名存在性证明不了它拦得住写入，而缺陷恰恰是"该建没建"。
     *
     * <p>四条各有各的"允许"，这才是重点：
     * 事件流与复核**全禁**；决定书**只许作废**；已确认的裁量**只许被 SUPERSEDED**。
     * 把"允许"的那一半也钉住，是为了防止有人把规则收得过紧——那样业务只能"删了重开"，
     * 卷宗里就会出现一份凭空消失的文书。
     */
    @Test
    @Order(2)
    void appendOnlyAndFrozenRulesHoldOnPostgres() {
        String caseId = punishmentCase("INVESTIGATING");
        String caseEventId = caseEvent(caseId, "FILE");
        String reviewId = review(caseId, "UPHELD");
        String discretionId = discretion(caseId, 1, "CONFIRMED", 50000);
        String documentId = document(caseId, discretionId);

        // ① 案件事件流：改不动、删不掉。办案过程改一行等于改卷宗。
        assertThat(sqlState(catching(() -> jdbc.update("update punishment_case_event set note='改口供' where event_id=?", caseEventId))))
                .as("事件 UPDATE 必须以 23514 被拒").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("delete from punishment_case_event where event_id=?", caseEventId))))
                .isEqualTo("23514");
        assertThat(jdbc.queryForObject("select event_kind from punishment_case_event where event_id=?", String.class, caseEventId))
                .isEqualTo("FILE");

        // ② 复核记录：同样全禁。复核结论是定性依据，改了就说不清当初是谁怎么定的。
        assertThat(sqlState(catching(() -> jdbc.update("update punishment_review set conclusion='REVISED' where review_id=?", reviewId))))
                .isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("delete from punishment_review where review_id=?", reviewId))))
                .isEqualTo("23514");

        // ③ 决定书：内容冻结，但**作废必须放行**。
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_decision_document set rendered_sha256=? where document_id=?",
                "1".repeat(64), documentId)))).as("改正文摘要必须被拒").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_decision_document set fields=cast('{\"tampered\":true}' as json) where document_id=?",
                documentId)))).as("改字段必须被拒").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("delete from penalty_decision_document where document_id=?", documentId))))
                .isEqualTo("23514");
        assertThat(jdbc.update("update penalty_decision_document set status='REVOKED', revoked_at=?, revoke_reason='适用条款有误',"
                + " updated_at=? where document_id=?", T0.plusHours(1), T0.plusHours(1), documentId))
                .as("作废是合法的新事实，必须放行——堵死它等于逼业务删了重开").isEqualTo(1);
        assertThat(jdbc.queryForObject("select rendered_sha256 from penalty_decision_document where document_id=?", String.class, documentId))
                .as("作废不得顺带改动正文摘要").isEqualTo(SHA256);
        // 决策 14-23：作废是**单向**的。把已作废的文书"复活"成 ISSUED、或抹掉作废时刻/理由，
        // 等于让一份已经宣布无效的处罚决定重新生效，而外面那张纸的效力谁也说不清。
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_decision_document set status='ISSUED', revoked_at=null,"
                + " revoke_reason=null where document_id=?", documentId))))
                .as("已作废的文书不得复活").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_decision_document set revoked_at=null where document_id=?", documentId))))
                .as("作废时刻不得被抹掉").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_decision_document set revoke_reason='' where document_id=?", documentId))))
                .as("作废理由不得被清空——没有理由的作废事后无法交代").isEqualTo("23514");
        // 决策 14-30：作废痕迹一经写下就不可再动——**不只是不能清空，改成别的值也不行**。
        // 这比清空更隐蔽：卷宗里仍然有一条作废记录，只是时刻和理由已经不是当初那份了，
        // 事后对账时看不出被动过手脚。所以这里既断错误码，也在之后复查两列的值确实没变。
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_decision_document set revoked_at=? where document_id=?",
                T0.plusDays(1), documentId)))).as("作废时刻不得被改成另一个时刻").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_decision_document set revoke_reason='换一个说法' where document_id=?",
                documentId)))).as("作废理由不得被改成另一句话").isEqualTo("23514");
        Map<String, Object> revoked = jdbc.queryForMap("select status, revoke_reason,"
                + " cast(revoked_at as varchar) as revoked_at from penalty_decision_document where document_id=?", documentId);
        assertThat(revoked).containsEntry("status", "REVOKED").containsEntry("revoke_reason", "适用条款有误");
        assertThat((String) revoked.get("revoked_at")).as("五次被拒之后作废痕迹与当初写下的完全一致").isNotNull();

        // ④ 已确认的裁量：数字冻结，但**允许被 SUPERSEDED**（复核要求重做时）。
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_discretion set fine_amount=1 where discretion_id=?", discretionId))))
                .as("已确认裁量的金额必须冻结").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("update penalty_discretion set status='DRAFT' where discretion_id=?", discretionId))))
                .as("已确认的裁量不能退回草稿——那等于把定过的性悄悄改掉").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("delete from penalty_discretion where discretion_id=?", discretionId))))
                .isEqualTo("23514");
        assertThat(jdbc.update("update penalty_discretion set status='SUPERSEDED' where discretion_id=?", discretionId))
                .as("复核要求重做时必须能置为 SUPERSEDED").isEqualTo(1);
    }

    /**
     * 金额的**库层**边界：`fine_amount < 0` 撞 `ck_stage14_discretion_amount`；
     * `WARNING` 却带金额撞 `ck_stage14_discretion_warning_amount`。
     *
     * <p>应用层的区间校验（400 `FINE_OUT_OF_RANGE`，按 `penalty_rule` 的 `fine_min/fine_max`）是另一回事，
     * 归 HTTP 那批（见文末 TODO）。**两层各管一段**：库层管"这个数在任何情况下都不合法"，
     * 应用层管"这个数超出了本条款的档位"。只测一层会漏——比如库层允许 999999999，而它早就超出了 PR-01 的上限。
     */
    @Test
    @Order(3)
    void discretionAmountBoundsAreCheckedByTheDatabase() {
        String caseId = punishmentCase("INVESTIGATING");
        Throwable negative = catching(() -> discretion(caseId, 1, "DRAFT", -1));
        assertThat(sqlState(negative)).isEqualTo("23514");
        assertThat(negative).hasMessageContaining("ck_stage14_discretion_amount");

        Throwable warningWithFine = catching(() -> discretionOf(caseId, 2, "DRAFT", "WARNING", 10000));
        assertThat(sqlState(warningWithFine)).as("只警告却带罚款金额，文书上会自相矛盾").isEqualTo("23514");
        assertThat(warningWithFine).hasMessageContaining("ck_stage14_discretion_warning_amount");

        // 边界另一侧：0 元的警告与正数的罚款都必须能进，别把校验收得过紧。
        assertThat(catching(() -> discretionOf(caseId, 3, "DRAFT", "WARNING", 0))).isNull();
        assertThat(catching(() -> discretionOf(caseId, 4, "DRAFT", "FINE", 20000))).isNull();
    }

    /**
     * 分两步升级：随机 schema 先迁到阶段 13 末的 `202609070103`（此时阶段 14 的表还没建），再迁到最新，
     * 然后重跑"只增/冻结"的**行为**断言。
     *
     * <p>这是 13-32 那条教训的延续：PG 专属的触发器若放在 `R__` 里靠守卫，
     * 先停在中间版本再前进的库会把它记为已应用而不再补——迁移全绿、保护静默缺失。
     * 阶段 14 的触发器放在版本化的 `V202609080103`，本用例就是钉住"分两步升级也真的装上了"。
     * 同样**不查 `pg_trigger`**：要证明的是它拦得住写入，不是有个同名对象。
     */
    @Test
    @Order(4)
    void stagedUpgradeStillInstallsPunishmentTriggers() {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe scratch schema");
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        root.execute("create schema " + schema);
        try {
            flywayFor(schema, STAGE13_TAIL).migrate();
            JdbcTemplate scratch = scratchTemplate(schema);
            assertThat(scratch.queryForObject("select to_regclass(?)::text", String.class, schema + ".punishment_case_event"))
                    .as("停在阶段 13 末时阶段 14 的表还不该存在").isNull();

            flywayForAll(schema).migrate();
            assertThat(scratch.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();

            Fixture fixture = seedCaseOn(scratch, schema);
            assertThat(sqlState(catching(() -> scratch.update(
                    "update punishment_case_event set note='x' where event_id=?", fixture.caseEventId()))))
                    .as("分两步升级的库上，案件事件仍必须只增").isEqualTo("23514");
            assertThat(sqlState(catching(() -> scratch.update(
                    "delete from punishment_review where review_id=?", fixture.reviewId())))).isEqualTo("23514");
            assertThat(sqlState(catching(() -> scratch.update(
                    "update penalty_decision_document set rendered_sha256=? where document_id=?",
                    "2".repeat(64), fixture.documentId())))).isEqualTo("23514");
            // 允许的那一半同样要在：升级路径不能只装上"禁止"而漏掉"放行"。
            assertThat(scratch.update("update penalty_decision_document set status='REVOKED', revoked_at=?, revoke_reason='升级路径验证',"
                    + " updated_at=? where document_id=?", T0, T0, fixture.documentId())).isEqualTo(1);
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    /**
     * 一事件一案的并发保证：两路同时立案，恰一条 201、一条 409 `CASE_ALREADY_EXISTS`。
     *
     * <p><b>判据看事件不只看状态（13-28）</b>：`punishment_case_event` 必须恰一条 `FILE`。
     * 只看"案件表里有一条"是不够的——落败方若在回滚前写了一条 FILE 事件，卷宗里就永远留着
     * "这个案子被立过两次"，而案件表看起来完全正常。状态可被覆盖，只增的事件流不能。
     */
    @Test
    @Order(5)
    void twoRealConnectionsFilingSameHandoffCommitExactlyOne() throws Exception {
        String filer = session("punishment:file", "punishment:read", "handoff:read", "alarm:read");
        Scope scope = scopeOn(jdbc, suffix + "-f" + nextNo());

        List<MvcResult> results = race(
                fileCaseRequest(filer, scope.handoffId(), "file-a-" + UUID.randomUUID()),
                fileCaseRequest(filer, scope.handoffId(), "file-b-" + UUID.randomUUID()));

        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList())
                .as("同一交接并发立案必须一成一败").containsExactly(201, 409);
        MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(json.readTree(loser.getResponse().getContentAsString()).path("error").path("code").asText())
                .isEqualTo("CASE_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where event_id=?", Long.class, scope.eventId()))
                .as("一事件一案").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from punishment_case_event e join punishment_case c"
                + " on c.case_id=e.case_id where c.event_id=? and e.event_kind='FILE'", Long.class, scope.eventId()))
                .as("落败方不得也写一条 FILE——只增事件流里多一条就永远留着").isEqualTo(1L);
    }

    /**
     * 案件编号并发 20 路：`case_no` 互不相同、格式一致、序号连续无洞。
     *
     * <p><b>"无重复"必须用唯一性断言，不能用 count</b>：20 条里两条重号、另有一条多出来，count 一样是 20。
     * 序号连续也要验——计数表若退化成"读了再写"，并发下会跳号，
     * 那意味着某次立案拿到的号其实来自另一次的读，只是恰好没撞上。
     */
    @Test
    @Order(6)
    void concurrentFilingsNeverProduceDuplicateCaseNumbers() throws Exception {
        int parallelism = 20;
        String filer = session("punishment:file", "punishment:read", "handoff:read", "alarm:read");
        List<MockHttpServletRequestBuilder> requests = new java.util.ArrayList<>();
        for (int i = 0; i < parallelism; i++) {
            Scope scope = scopeOn(jdbc, suffix + "-n" + nextNo());
            requests.add(fileCaseRequest(filer, scope.handoffId(), "no-" + i + "-" + UUID.randomUUID()));
        }
        // 用例 5 已经通过接口立过一条，所以按**增量**断言，不要求起点为 0。
        long apiCasesBefore = jdbc.queryForObject("select count(*) from punishment_case where case_no like 'CASE-%'", Long.class);

        List<MvcResult> results = race(requests);

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus())
                .as("各自交接互不冲突，全部应成功：" + responseBody(r)).isEqualTo(201));
        List<String> generated = jdbc.queryForList("select case_no from punishment_case where case_no like 'CASE-%'"
                + " order by case_no", String.class);
        assertThat(generated).as("接口生成的编号（CASE- 前缀）应增加 20 条；夹具案件用 FIX- 前缀，不会混进来")
                .hasSize((int) apiCasesBefore + parallelism);
        assertThat(generated).as("编号不得重复").doesNotHaveDuplicates();
        assertThat(generated).allSatisfy(no -> assertThat(no).matches("^CASE-\\d{8}-\\d{4}$"));
        assertThat(generated.stream().map(no -> no.substring(5, 13)).distinct().toList())
                .as("同一次运行只跨一个日期段").hasSize(1);
        List<Integer> sequences = generated.stream().map(no -> Integer.parseInt(no.substring(14))).sorted().toList();
        assertThat(sequences.get(sequences.size() - 1) - sequences.get(0))
                .as("序号必须连续无洞：跳号说明计数器的读与写不在同一把锁里").isEqualTo(sequences.size() - 1);
    }

    /**
     * 复核并发：两路同时复核同一案件，恰一条 200、一条 409，`punishment_review` 恰一行。
     * 复核是定性动作，两条并存等于同一案件有两个互相独立的定性结论——事后无法说清依据的是哪一个。
     */
    @Test
    @Order(7)
    void twoRealConnectionsReviewingSameCaseCommitExactlyOne() throws Exception {
        String filer = session("punishment:file", "punishment:read", "punishment:decide", "handoff:read", "alarm:read");
        String reviewerSession = session("punishment:review", "punishment:read");
        String caseId = caseUnderReview(filer);

        List<MvcResult> results = race(
                reviewRequest(reviewerSession, caseId, "review-a-" + UUID.randomUUID()),
                reviewRequest(reviewerSession, caseId, "review-b-" + UUID.randomUUID()));

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).as("并发复核必须一成一败：" + results.stream().map(Stage14PostgresTest::responseBody).toList())
                .containsExactly(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from punishment_review where case_id=?", Long.class, caseId))
                .as("复核记录只增，两条并存说不清依据的是哪一个").isEqualTo(1L);
    }

    /**
     * 金额的**应用层**边界：超出 `penalty_rule` 的档位区间 → 400 `FINE_OUT_OF_RANGE`。
     * 与用例 3 的库层 CHECK 分工明确：库层管"这个数在任何情况下都不合法"（负数、警告带金额），
     * 应用层管"这个数超出了本条款的档位"。只测一层会漏——库层放行 999999999，而它早就超出 PR-01 的上限。
     */
    @Test
    @Order(8)
    void fineOutsideTheRuleRangeIsRejectedByTheApplicationLayer() throws Exception {
        String filer = session("punishment:file", "punishment:read", "punishment:decide", "handoff:read", "alarm:read");
        String caseId = investigatingCase(filer);
        Map<String, Object> rule = fineRule();
        long max = ((Number) rule.get("fine_max")).longValue();
        long min = ((Number) rule.get("fine_min")).longValue();

        MvcResult tooHigh = mvc.perform(discretionRequest(filer, caseId, (String) rule.get("rule_code"), "FINE", max + 1)).andReturn();
        assertThat(tooHigh.getResponse().getStatus()).as("超上限（档位 " + rule + "）：" + responseBody(tooHigh)).isEqualTo(400);
        assertThat(json.readTree(tooHigh.getResponse().getContentAsString()).path("error").path("code").asText())
                .as("档位 " + rule).isEqualTo("FINE_OUT_OF_RANGE");
        if (min > 0) {
            MvcResult tooLow = mvc.perform(discretionRequest(filer, caseId, (String) rule.get("rule_code"), "FINE", min - 1)).andReturn();
            assertThat(tooLow.getResponse().getStatus()).as("低于下限同样越界：" + responseBody(tooLow)).isEqualTo(400);
            assertThat(json.readTree(tooLow.getResponse().getContentAsString()).path("error").path("code").asText())
                    .isEqualTo("FINE_OUT_OF_RANGE");
        }
        // 区间内必须放行：把校验收得过紧和放得过松一样是缺陷。
        MvcResult accepted = mvc.perform(discretionRequest(filer, caseId, (String) rule.get("rule_code"), "FINE", min)).andReturn();
        assertThat(accepted.getResponse().getStatus()).as("区间下限必须放行：" + responseBody(accepted)).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from penalty_discretion where case_id=?", Long.class, caseId))
                .as("被拒的两次不得留下草稿").isEqualTo(1L);
    }

    /**
     * 处罚交接的材料包 v2（契约 §1）：`handoff_material_snapshot.schema_version=2`，四段齐全，
     * 且 `disposals` 里至少一条 `status='COMPLETED'`——那是 13-6 定的立案前提，快照里必须能自证。
     *
     * <p><b>用 jsonb 路径查询，不是把整段当字符串 contains。</b>后者在字段改名、嵌套层级变化、
     * 甚至只是把 `COMPLETED` 写进某段自由文本时都会照样绿；路径查询问的是"结构里确实有这一条"。
     */
    @Test
    @Order(9)
    void punishmentHandoffWritesMaterialSnapshotV2OnPostgres() throws Exception {
        String submitter = session("handoff:create", "handoff:read", "alarm:read", "disposal:read");
        Scope scope = scopeWithoutHandoff(jdbc, suffix + "-m" + nextNo());
        completedDisposal(scope);   // 13-6：没有 COMPLETED 授权就不允许提交处罚交接

        MvcResult submitted = mvc.perform(post("/api/v1/handoffs").header("Authorization", "Bearer " + submitter)
                .header("Idempotency-Key", "handoff-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source_kind\":\"UAV_EVENT\",\"source_id\":\"" + scope.eventId() + "\","
                        + "\"handoff_type\":\"UAV_PUNISHMENT\",\"recipient_id\":\"" + scope.recipientId() + "\","
                        + "\"expected_version\":1}")).andReturn();
        assertThat(submitted.getResponse().getStatus()).as("提交处罚交接应成功：" + responseBody(submitted)).isEqualTo(201);
        String handoffId = json.readTree(submitted.getResponse().getContentAsString()).path("data").path("handoff_id").asText();

        assertThat(jdbc.queryForObject("select schema_version from handoff_material_snapshot where handoff_id=?",
                Integer.class, handoffId)).as("处罚交接的快照必须是 v2").isEqualTo(2);
        // 四段齐全：用 jsonb 的键存在判断，不是字符串包含。
        // 用 jsonb_exists(...) 而不是 `jsonb ? key`：`?` 会被 JDBC 当成占位符，语句直接报"参数没给值"。
        for (String section : List.of("event", "verifications", "disposals")) {
            // 参数还要显式 cast 成 text：未定型的 `?` 让 PG 找不到 jsonb_exists 的重载。
            assertThat(jdbc.queryForObject("select jsonb_exists(snapshot, cast(? as text))"
                    + " from handoff_material_snapshot where handoff_id=?", Boolean.class, section, handoffId))
                    .as("快照缺少 " + section + " 段").isTrue();
        }
        // disposals 里至少一条 COMPLETED：这是立案前提在材料里的自证。
        assertThat(jdbc.queryForObject("select count(*) from handoff_material_snapshot s,"
                + " jsonb_array_elements(s.snapshot->'disposals') d"
                + " where s.handoff_id=? and d->>'status'='COMPLETED'", Long.class, handoffId))
                .as("材料里必须有已完成的处置授权，否则立案前提无从查证").isPositive();
    }

    /** 与 {@link #scopeOn} 相同，但**不建交接**：材料包用例要走接口自己提交。 */
    private Scope scopeWithoutHandoff(JdbcTemplate scratch, String tag) {
        return scopeOn(scratch, tag, false);
    }

    /** 给该事件造一条 COMPLETED 处置授权（13-6 的立案/交接前提）。 */
    private void completedDisposal(Scope scope) {
        jdbc.update("insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,result_code,"
                + "policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " values (?,?,'COUNTERMEASURE','UAV_EVENT',?,'MANUAL','阶段十四材料包验证',?,?,?,?,?,?,'COMPLETED','SUCCEEDED',"
                + "'demo-v1',?,?,'mock',0,?,?)",
                id(), "AUTH-S14-" + nextNo(), scope.eventId(), scope.officer(), T0, scope.reviewer(), T0, T0, T0.plusMinutes(30),
                scope.orgId(), scope.districtId(), T0, T0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // TODO(14.3，等 progress.md 行首出现 "14.1 api landed" 或 modules/punishment/api 下出现 Controller)：
    //   ① 一事件一案并发：两路同时 POST /punishment-cases → 恰一条 201、一条 409 CASE_ALREADY_EXISTS，
    //      且 punishment_case_event 恰一条 FILE（判据看事件不只看状态，13-28），落败方不留半条事件；
    //   ② 案件编号并发 20 路：case_no 唯一（唯一性断言而非 count）、匹配 CASE-\d{8}-\d{4}、日期段一致、序号连续无洞；
    //   ③ 复核并发：两路同时 POST /{id}/reviews → 恰一条 200、一条 409，punishment_review 恰一行；
    //   ④ 金额越界的应用层面：400 FINE_OUT_OF_RANGE（与用例 3 的库层 CHECK 分开验，两层各管一段）；
    //   ⑤ 材料包 v2：提交处罚交接后 handoff_material_snapshot.schema_version=2，
    //      且 jsonb 路径查询 snapshot->'disposals' 里至少一条 status='COMPLETED'（用 jsonb 路径，不是整段字符串 contains）。
    // ---------------------------------------------------------------------------------------------------------------

    /* ---- HTTP 夹具 ---- */

    private MockHttpServletRequestBuilder fileCaseRequest(String sessionId, String handoffId, String idempotencyKey) {
        return post("/api/v1/punishment-cases").header("Authorization", "Bearer " + sessionId)
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handoff_id\":\"" + handoffId + "\",\"party_type\":\"PERSON\",\"party_name\":\"某当事人\","
                        + "\"note\":\"阶段十四并发验证\"}");
    }

    private MockHttpServletRequestBuilder reviewRequest(String sessionId, String caseId, String idempotencyKey) {
        return post("/api/v1/punishment-cases/{id}/reviews", caseId).header("Authorization", "Bearer " + sessionId)
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"UPHELD\",\"note\":\"阶段十四并发复核\",\"expected_version\":" + version(caseId) + "}");
    }

    private MockHttpServletRequestBuilder discretionRequest(String sessionId, String caseId, String ruleCode,
            String penaltyType, long fineAmount) {
        return post("/api/v1/punishment-cases/{id}/discretions", caseId).header("Authorization", "Bearer " + sessionId)
                .header("Idempotency-Key", "disc-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_code\":\"" + ruleCode + "\",\"penalty_type\":\"" + penaltyType + "\",\"fine_amount\":" + fineAmount
                        + ",\"factors\":[],\"basis_text\":\"阶段十四金额区间验证\",\"expected_version\":" + version(caseId) + "}");
    }

    /** 走接口立案并指派承办人，返回处于 INVESTIGATING 的案件 id。 */
    private String investigatingCase(String sessionId) throws Exception {
        Scope scope = scopeOn(jdbc, suffix + "-i" + nextNo());
        MvcResult filed = mvc.perform(fileCaseRequest(sessionId, scope.handoffId(), "file-" + UUID.randomUUID())).andReturn();
        assertThat(filed.getResponse().getStatus()).as("立案应成功：" + responseBody(filed)).isEqualTo(201);
        String caseId = json.readTree(filed.getResponse().getContentAsString()).path("data").path("case_id").asText();
        MvcResult assigned = mvc.perform(post("/api/v1/punishment-cases/{id}/assign", caseId)
                .header("Authorization", "Bearer " + sessionId).header("Idempotency-Key", "assign-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"officer_id\":\"" + scope.officer() + "\",\"expected_version\":" + version(caseId) + "}")).andReturn();
        assertThat(assigned.getResponse().getStatus()).as("指派应成功：" + responseBody(assigned)).isEqualTo(200);
        return caseId;
    }

    /** 立案 → 指派 → 拟裁量 → 确认，返回处于 UNDER_REVIEW 的案件 id。 */
    private String caseUnderReview(String sessionId) throws Exception {
        String caseId = investigatingCase(sessionId);
        Map<String, Object> rule = fineRule();
        MvcResult drafted = mvc.perform(discretionRequest(sessionId, caseId, (String) rule.get("rule_code"), "FINE",
                ((Number) rule.get("fine_min")).longValue())).andReturn();
        // 接口返回的是动作结果（case_id/status/version），不含 discretion_id——从库里取最新草稿。
        assertThat(drafted.getResponse().getStatus()).as("拟裁量应成功：" + responseBody(drafted)).isEqualTo(200);
        String discretionId = jdbc.queryForObject("select discretion_id from penalty_discretion where case_id=? and status='DRAFT'"
                + " order by version_no desc limit 1", String.class, caseId);
        MvcResult confirmed = mvc.perform(post("/api/v1/punishment-cases/{id}/discretions/{did}/confirm", caseId, discretionId)
                .header("Authorization", "Bearer " + sessionId).header("Idempotency-Key", "confirm-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":" + version(caseId) + "}")).andReturn();
        assertThat(confirmed.getResponse().getStatus()).as("确认裁量应成功：" + responseBody(confirmed)).isEqualTo(200);
        assertThat(status(caseId)).as("确认后案件应进入待复核").isEqualTo("UNDER_REVIEW");
        return caseId;
    }

    /**
     * 取一条**支持 FINE 处罚种类**的启用档位。不能直接 `order by rule_code limit 1`——
     * 档位的 `penalty_types` 各不相同，撞上一条不含 FINE 的会先被"该档位不支持这种处罚种类"挡掉，
     * 于是金额区间那条断言根本没跑到，却显示成 VALIDATION_ERROR，容易被误判成产品缺陷。
     * `penalty_types` 存的是 JSON 数组（`["WARNING","FINE",...]`），所以按 JSON 解析逐项比对——
     * 用 `like '%FINE%'` 会把 `WARNING_AND_FINE` 也算成 FINE，用逗号切又会切出带引号和方括号的碎片。
     */
    private Map<String, Object> fineRule() {
        for (Map<String, Object> row : jdbc.queryForList(
                "select rule_code, fine_min, fine_max, penalty_types from penalty_rule where enabled=true order by rule_code")) {
            try {
                for (com.fasterxml.jackson.databind.JsonNode type : json.readTree(String.valueOf(row.get("penalty_types")))) {
                    if ("FINE".equals(type.asText())) return row;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                throw new IllegalStateException("档位 " + row.get("rule_code") + " 的 penalty_types 不是合法 JSON 数组", malformed);
            }
        }
        throw new IllegalStateException("档位表里没有支持 FINE 的启用档位，金额区间用例无从验起");
    }

    private long version(String caseId) {
        return jdbc.queryForObject("select version from punishment_case where case_id=?", Long.class, caseId);
    }

    private String status(String caseId) {
        return jdbc.queryForObject("select status from punishment_case where case_id=?", String.class, caseId);
    }

    /** 建一个带指定权限、范围为 ALL 的会话，返回 session id。 */
    private String session(String... permissions) {
        String user = id(), token = UUID.randomUUID().toString(), role = "ROLE-S14S-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, "阶段十四会话 " + role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", user, "s14u-" + user.substring(0, 8), "阶段十四操作员", role);
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

    /** 断言失败时把响应体带出来：只报"期望 201 得到 409"排查起来要多跑一趟。 */
    private static String responseBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception unreadable) {
            return "<响应体不可读: " + unreadable + ">";
        }
    }

    private record Fixture(String caseId, String caseEventId, String reviewId, String discretionId, String documentId) { }

    /** 在指定 schema 上铺一整条案件夹具（升级用例用）。 */
    private Fixture seedCaseOn(JdbcTemplate scratch, String schema) {
        String tag = schema.substring(SCHEMA_PREFIX.length(), SCHEMA_PREFIX.length() + 8);
        Scope scope = scopeOn(scratch, tag);
        String caseId = caseOn(scratch, scope, "INVESTIGATING", tag);
        String eventId = id(), reviewId = id(), discretionId = id(), documentId = id();
        scratch.update("insert into punishment_case_event (event_id,case_id,event_kind,actor_id,actor_name,note,snapshot,occurred_at)"
                + " values (?,?,'FILE',?,?,?,cast('{}' as json),?)", eventId, caseId, scope.officer(), "承办人", "分步升级验证", T0);
        scratch.update("insert into punishment_review (review_id,case_id,reviewer_id,reviewer_name,conclusion,note,missing_leads,created_at)"
                + " values (?,?,?,?,'UPHELD',?,cast('[]' as json),?)", reviewId, caseId, scope.reviewer(), "复核人", "分步升级验证", T0);
        scratch.update("insert into penalty_discretion (discretion_id,case_id,version_no,status,violation_code,rule_code,penalty_type,"
                + "fine_amount,factors,basis_text,drafted_by,drafted_at,decided_by,decided_at)"
                + " values (?,?,1,'CONFIRMED','NO_AUTHORIZATION','PR-01','FINE',50000,cast('[]' as json),?,?,?,?,?)",
                discretionId, caseId, "分步升级验证", scope.officer(), T0, scope.reviewer(), T0);
        scratch.update("insert into penalty_decision_document (document_id,document_no,case_id,discretion_id,template_version,status,"
                + "fields,rendered_sha256,issued_by,issued_by_name,issued_at,version,updated_at)"
                + " values (?,?,?,?,'demo-v1','ISSUED',cast('{}' as json),?,?,?,?,0,?)",
                documentId, "CASE-UP-" + tag + "-DEC-01", caseId, discretionId, SHA256, scope.officer(), "承办人", T0, T0);
        return new Fixture(caseId, eventId, reviewId, discretionId, documentId);
    }

    private record Scope(String orgId, String districtId, String officer, String reviewer, String eventId,
            String handoffId, String recipientId) { }

    /** 在指定 schema 上铺出案件所需的整条前置链：机构/区域/用户 → 告警 → 已核实事件 → 接收方 → 交接。 */
    private Scope scopeOn(JdbcTemplate scratch, String tag) { return scopeOn(scratch, tag, true); }

    private Scope scopeOn(JdbcTemplate scratch, String tag, boolean withHandoff) {
        String orgId = id(), districtId = id(), roleCode = "ROLE-S14-" + tag, officerId = id(), reviewerId = id();
        scratch.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                orgId, "ORG-" + tag, "阶段十四 " + tag);
        scratch.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                districtId, "DIST-" + tag, "阶段十四区域 " + tag);
        scratch.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", roleCode, "阶段十四角色 " + tag);
        for (String[] person : new String[][] { { officerId, "off-" + tag, "承办人" }, { reviewerId, "rev-" + tag, "复核人" } }) {
            scratch.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                    + " values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", person[0], person[1], person[2], roleCode);
        }
        String sourceId = "src-" + tag, alarmId = id(), eventId = id(), recipientId = id(), handoffId = id();
        scratch.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " values (?,?,?,true,'mock',?,?,0)", sourceId, "SRC-" + tag, "阶段十四来源", T0, T0);
        scratch.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)", alarmId, sourceId, "告警-" + tag, T0, T0, orgId, districtId, T0);
        scratch.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'CONFIRMED',?,?,?,?,1)", eventId, alarmId, orgId, districtId, T0, T0);
        // 核实记录要跟着状态一起造：真实世界里事件不可能"是 CONFIRMED 但没人核实过"。
        // 少了它，材料包 v2 的 verifications 段会是空的，用例测到的就不是"快照带全了四段"，
        // 而是"空段被省略"——两件事看起来一样，但只有前者是我们要保证的。
        scratch.update("insert into uav_event_verification (history_id,event_id,version,previous_state,resulting_state,"
                + "conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED',?,?,?)",
                id(), eventId, "阶段十四夹具：核实属实", officerId, T0);
        scratch.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at)"
                + " values (?,?,'UAV_PUNISHMENT',true,?,?)", recipientId, "阶段十四接收方 " + tag, T0, T0);
        if (withHandoff) {
            scratch.update("insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,"
                    + "source_version,owner_org_id,district_id,source_mode,submitted_by,created_at)"
                    + " values (?,'UAV_EVENT',?,null,?,'UAV_PUNISHMENT',?,1,?,?,'mock',?,?)",
                    handoffId, eventId, eventId, recipientId, orgId, districtId, officerId, T0);
        }
        return new Scope(orgId, districtId, officerId, reviewerId, eventId, withHandoff ? handoffId : null, recipientId);
    }

    /** 夹具案件的编号用 `FIX-` 前缀：接口生成的是 `CASE-`，两者混在一起会让编号并发用例数错。 */
    private String caseOn(JdbcTemplate scratch, Scope scope, String status, String tag) {
        String caseId = id();
        scratch.update("insert into punishment_case (case_id,case_no,event_id,handoff_id,status,party_type,party_name,officer_id,"
                + "officer_name,filed_by,filed_by_name,filed_at,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " values (?,?,?,?,?,'PERSON',?,?,?,?,?,?,?,?,'mock',0,?,?)",
                caseId, "FIX-" + tag + "-" + nextNo(), scope.eventId(), scope.handoffId(), status, "某当事人",
                scope.officer(), "承办人", scope.officer(), "承办人", T0, scope.orgId(), scope.districtId(), T0, T0);
        return caseId;
    }

    /* ---- 主 schema 上的夹具 ---- */

    private String punishmentCase(String status) {
        Scope scope = scopeOn(jdbc, suffix + "-" + nextNo());
        return caseOn(jdbc, scope, status, suffix.substring(0, 4));
    }

    private String caseEvent(String caseId, String kind) {
        String eventId = id();
        jdbc.update("insert into punishment_case_event (event_id,case_id,event_kind,actor_id,actor_name,note,snapshot,occurred_at)"
                + " values (?,?,?,?,?,?,cast('{}' as json),?)", eventId, caseId, kind, officer, "承办人", "阶段十四验证", T0);
        return eventId;
    }

    private String review(String caseId, String conclusion) {
        String reviewId = id();
        jdbc.update("insert into punishment_review (review_id,case_id,reviewer_id,reviewer_name,conclusion,note,missing_leads,created_at)"
                + " values (?,?,?,?,?,?,cast('[]' as json),?)", reviewId, caseId, reviewer, "复核人", conclusion, "阶段十四验证", T0);
        return reviewId;
    }

    private String discretion(String caseId, int versionNo, String status, long fineAmount) {
        return discretionOf(caseId, versionNo, status, "FINE", fineAmount);
    }

    private String discretionOf(String caseId, int versionNo, String status, String penaltyType, long fineAmount) {
        String discretionId = id();
        boolean confirmed = "CONFIRMED".equals(status);
        jdbc.update("insert into penalty_discretion (discretion_id,case_id,version_no,status,violation_code,rule_code,penalty_type,"
                + "fine_amount,factors,basis_text,drafted_by,drafted_at,decided_by,decided_at)"
                + " values (?,?,?,?,'NO_AUTHORIZATION','PR-01',?,?,cast('[]' as json),?,?,?,?,?)",
                discretionId, caseId, versionNo, status, penaltyType, fineAmount, "阶段十四验证", officer, T0,
                confirmed ? reviewer : null, confirmed ? T0 : null);
        return discretionId;
    }

    private String document(String caseId, String discretionId) {
        String documentId = id();
        jdbc.update("insert into penalty_decision_document (document_id,document_no,case_id,discretion_id,template_version,status,"
                + "fields,rendered_sha256,issued_by,issued_by_name,issued_at,version,updated_at)"
                + " values (?,?,?,?,'demo-v1','ISSUED',cast('{\"amount\":50000}' as json),?,?,?,?,0,?)",
                documentId, "DOC-" + suffix + "-" + nextNo(), caseId, discretionId, SHA256, officer, "承办人", T0, T0);
        return documentId;
    }

    private String user(String name) {
        String userId = id(), roleCode = "ROLE-S14-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", roleCode, name + " " + roleCode);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", userId, "s14-" + userId.substring(0, 8), name, roleCode);
        return userId;
    }

    /** 类级计数：`case_no` / `document_no` 都是全局唯一键，每个用例各自从 1 开始会撞号。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    private static String nextNo() { return String.format("%04d", SEQ.incrementAndGet()); }

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

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        // 只接受专用验证库，防止误连生产库或日常联调库。
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 14 verification outside a stage456_verify_ database");
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe Stage 14 verification schema");
    }
}
