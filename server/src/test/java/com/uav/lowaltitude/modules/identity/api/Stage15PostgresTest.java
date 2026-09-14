package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

/**
 * 阶段 15（小接线）PostgreSQL 专项验证。harness 沿用 Stage9/13/14PostgresTest 已跑通的做法：
 * env 门禁、`stage456_verify_` 库名硬校验、随机 `stage456_` schema、手动 Flyway
 * （`db/migration` + `db/postgresql`）、**不用测试级事务**（并发用例要真提交）、只删本 schema。
 * 库用 `stage456_verify_s15`。
 *
 * <p>本阶段改动分散在好几个模块的边缘，最容易出的不是"功能不通"，而是**接缝对不上**，
 * 而这几种接缝恰好都是 H2 盖不住的：
 * <ul>
 *   <li>动作行整组替换 + 乐观锁：H2 测不了两条真事务同时提交；</li>
 *   <li>导出上限：只有真库上造够 5001 行才知道拒在哪一侧；</li>
 *   <li>分页稳定次序：PG 不带 ORDER BY 兜底键时的返回顺序会随物理位置变，H2 常常"碰巧"按插入序；</li>
 *   <li>JSONB：H2 把 jsonb 当字符串，`quality LIKE '%…%'` 在 H2 上正常、在 PG 上直接报
 *       `operator does not exist: jsonb ~~ unknown`。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage15PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage15PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage15PostgresTest 未在真实 PostgreSQL 上执行")
class Stage15PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 8, 12, 0, 0, 0, ZoneOffset.UTC);
    /** 契约 §3 / 决策 15-7 的导出上限。写死在这里而不是引用 CsvExport.MAX_ROWS：
        用例要独立声明"契约说的是 5000"，引用产品常量的话，谁把它改成 50 用例照样绿。 */
    private static final int EXPORT_CAP = 5000;
    /** 本类自己的超级管理员账号（见 postgresProperties）。 */
    private static final String SUPER_ADMIN_ACCOUNT = "s15-super-admin";
    /** 中文化那一版：升级路径用例的第一站，也是"归一被抹掉"那次启动所在的版本。 */
    private static final String ROUND8_TAIL = "202609080104";

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired com.uav.lowaltitude.modules.identity.application.SuperAdminIntegrityInitializer superAdminIntegrity;
    @Autowired com.uav.lowaltitude.platform.config.AppProperties appProperties;

    private String suffix, org, district, source;

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
        // 并发用例要让两个请求同时在库里：默认池会让它们在栅栏后互相等连接，
        // 那测出来的是连接池大小而不是乐观锁（决策 13-28）。
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        // 超级管理员完整性检查用例要按名字找到那个账号；固定成本用例自己的账号，
        // 免得跟着部署环境的 APP_SUPER_ADMIN_ACCOUNT 变。启动时 app_user 还是空的，
        // 初始化器会直接返回，不会因为找不到账号而让整个上下文起不来。
        registry.add("app.super-admin.account", () -> SUPER_ADMIN_ACCOUNT);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (!schemaCreated) return;
        assertSafeSchema();
        try (Connection connection = rootDataSource().getConnection(); Statement statement = connection.createStatement()) {
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
        org = id(); district = id(); source = id();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "ORG-S15-" + suffix, "阶段十五验证机构 " + suffix);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-S15-" + suffix, "阶段十五验证区域 " + suffix);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " values (?,?,?,true,'mock',?,?,0)", source, "SRC-S15-" + suffix, "阶段十五验证来源 " + suffix, T0, T0);
    }

    /**
     * 基线：迁移无失败，且**动作目录与 `PermissionCode` 枚举逐一对上**。
     *
     * <p>直接引用枚举而不是抄一份字符串常量：抄一份的话，改了枚举、没改目录，两边各自"自洽"、
     * 用例照样绿，而真实后果是 `access.require` 永远拿不到那条权限，接口在生产上**静默 403**
     * （阶段 9 出过这类 P0）。除数量与集合外还断言 `route_key IS NULL`——动作码不是菜单项，
     * 带上菜单键会让它出现在导航里。
     */
    @Test
    @Order(1)
    void actionCatalogMatchesThePermissionCodeEnumOnPostgres() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class))
                .as("有失败迁移时后续断言全部没有意义").isZero();

        List<String> expected = java.util.Arrays.stream(PermissionCode.values())
                .map(PermissionCode::value).sorted().toList();
        List<String> catalog = jdbc.queryForList(
                "select permission_code from app_permission where permission_kind='ACTION' order by permission_code",
                String.class);
        assertThat(catalog).as("目录行必须与枚举逐一对上，多一个少一个都不行").containsExactlyElementsOf(expected);
        // 集合相等还不够：枚举和目录同时少掉同一个码时 containsExactly 仍然全绿。
        assertThat(catalog).as("动作码总数（枚举与目录一致时才有意义）").hasSize(expected.size());
        assertThat(jdbc.queryForObject(
                "select count(*) from app_permission where permission_kind='ACTION' and route_key is not null", Long.class))
                .as("动作权限不得带菜单键").isZero();
        // 迁移只登记不授权：授权矩阵是部署时的决定。
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission p join app_permission c"
                + " on c.permission_code=p.permission_code where c.permission_kind='ACTION' and p.role_code<>'ROLE-ADMIN'", Long.class))
                .as("除内置超级管理员外，迁移不得预先授予任何动作权限").isZero();
    }

    /**
     * 两路同时改同一个角色的动作权限：恰一条 200、一条 409 `VERSION_CONFLICT`，
     * 且动作行**恰为胜者提交的那一组**。
     *
     * <p>判据不是"行数对"：两路各落一半时行数照样对得上，而那意味着这个角色最终拿到的是
     * 一组谁也没提交过的权限组合——事后没人能解释"他凭什么能批"。所以逐码比对。
     * `permission_version` 只许涨 1：涨 2 说明落败方也提交了一次。
     */
    @Test
    @Order(2)
    void concurrentActionUpdatesLeaveExactlyTheWinnersActionRows() throws Exception {
        String admin = session("roles", "AUTH");
        String role = customRole();
        // permission_version 记在 **app_user** 行上，所以这个角色底下必须先有个人：
        // 没有人的角色前后都读到 0，断言看起来跑过了，其实什么也没验。
        sessionFor(role);
        int version = jdbc.queryForObject("select version from app_role where role_code=?", Integer.class, role);
        long versionBefore = jdbc.queryForObject(
                "select coalesce(max(permission_version),0) from app_user where role_code=?", Long.class, role);

        List<String> groupA = List.of(PermissionCode.ALARM_READ.value(), PermissionCode.TARGET_READ.value());
        List<String> groupB = List.of(PermissionCode.EVIDENCE_READ.value());
        List<MvcResult> results = race(List.of(
                updateActions(admin, role, version, groupA),
                updateActions(admin, role, version, groupB)));

        List<Integer> codes = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(codes).as("两路同时改：必须恰一成一败，响应体=" + bodies(results)).containsExactly(200, 409);
        MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(errorCode(loser)).as("落败方必须是版本冲突，而不是别的 409").isEqualTo("VERSION_CONFLICT");

        MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        List<String> winning = body(winner).path("actions").findValuesAsText("permission_code")
                .stream().sorted().toList();
        List<String> stored = jdbc.queryForList("select p.permission_code from app_role_permission p"
                + " join app_permission c on c.permission_code=p.permission_code"
                + " where p.role_code=? and c.permission_kind='ACTION' order by p.permission_code", String.class, role);
        assertThat(stored).as("库里的动作行必须恰为胜者提交的那一组，不能是两路各落一半").isEqualTo(winning);
        assertThat(winning).as("胜者只可能是 A 组或 B 组之一")
                .isIn(groupA.stream().sorted().toList(), groupB.stream().sorted().toList());

        long versionAfter = jdbc.queryForObject(
                "select coalesce(max(permission_version),0) from app_user where role_code=?", Long.class, role);
        assertThat(versionAfter - versionBefore).as("permission_version 只许涨一次：涨两次说明落败方也提交了").isEqualTo(1);
    }

    /**
     * 改完角色权限后，**旧会话必须立刻失效**。
     *
     * <p>这条守的是"权限收回要当场生效"：如果旧会话还能继续用，被撤销权限的人在会话到期前
     * 仍然能做那件事——而撤销通常正是因为不该再让他做了。契约写的是 403，实现走的是会话失效，
     * 所以这里断言的是"不再是 200"并把实际状态码带进断言消息，两种都算拦住了。
     */
    @Test
    @Order(3)
    void permissionChangeInvalidatesTheOldSessionImmediately() throws Exception {
        String admin = session("roles", "AUTH");
        String role = customRole();
        String victim = sessionForRole(role, PermissionCode.ALARM_READ.value());
        mvc.perform(get("/api/v1/alarms?size=1").header("Authorization", "Bearer " + victim))
                .andExpect(status().isOk());

        int version = jdbc.queryForObject("select version from app_role where role_code=?", Integer.class, role);
        MvcResult updated = mvc.perform(updateActions(admin, role, version, List.of(PermissionCode.EVIDENCE_READ.value()))).andReturn();
        assertThat(updated.getResponse().getStatus()).as("前置改权限必须成功，否则这条用例什么也没验，体=" + responseBody(updated)).isEqualTo(200);

        int after = mvc.perform(get("/api/v1/alarms?size=1").header("Authorization", "Bearer " + victim))
                .andReturn().getResponse().getStatus();
        assertThat(after).as("权限改过之后旧会话必须立刻失效（实际 " + after + "）").isNotEqualTo(200);
    }

    /**
     * 导出上限**两侧都钉**：恰 5000 条必须放行，5001 条必须 400 `EXPORT_TOO_LARGE`。
     *
     * <p>只测超限那一侧的话，把阈值写成 4999 就拒也照样绿——而那会让一份本该导得出的表
     * 在用户面前变成报错。放行那一侧还要验 BOM 与文件名：不带 BOM 的中文列头在 Excel 里是乱码，
     * 这是导出功能最常被投诉的一件事。
     */
    @Test
    @Order(4)
    void alarmExportRejectsAboveTheCapAndAcceptsExactlyAtIt() throws Exception {
        String reader = session("alarms", "READ", PermissionCode.ALARM_READ.value());
        jdbc.update("delete from alarm");   // 前面用例留下的行会让边界算错（阶段 13 的教训：先排空）
        bulkAlarms(EXPORT_CAP);
        assertThat(jdbc.queryForObject("select count(*) from alarm", Long.class)).isEqualTo((long) EXPORT_CAP);

        MvcResult ok = mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", "Bearer " + reader)).andReturn();
        assertThat(ok.getResponse().getStatus()).as("恰在上限上必须放行，体=" + responseBody(ok)).isEqualTo(200);
        byte[] csv = ok.getResponse().getContentAsByteArray();
        assertThat(new byte[] {csv[0], csv[1], csv[2]})
                .as("没有 BOM 时 Excel 会按本地代码页解释 UTF-8，中文列头直接是乱码")
                .isEqualTo(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        assertThat(ok.getResponse().getHeader("Content-Disposition")).contains("attachment").contains("alarms-");
        long lines = new String(csv, StandardCharsets.UTF_8).lines().count();
        assertThat(lines).as("表头 + 5000 行数据").isEqualTo(EXPORT_CAP + 1L);

        bulkAlarms(1);
        MvcResult tooLarge = mvc.perform(get("/api/v1/alarms/export.csv").header("Authorization", "Bearer " + reader)).andReturn();
        assertThat(tooLarge.getResponse().getStatus()).as("超过上限必须拒绝而不是悄悄截断").isEqualTo(400);
        assertThat(errorCode(tooLarge)).isEqualTo("EXPORT_TOO_LARGE");
    }

    /**
     * 相同 `received_at` 下的分页次序必须稳定：两页**无重叠、无遗漏、并集恰为全集**（契约 §3 的"次序键后恒附 id"）。
     *
     * <p>关键在于取完第一页之后**故意 UPDATE 掉一部分行**，让 PG 的物理位置与插入次序错开：
     * 不这么做，即使 ORDER BY 少了 `id` 兜底，PG 也很可能碰巧按插入序返回，用例就成了摆设。
     * 少了兜底键的真实后果是翻页时同一条告警出现两次、另一条一次也不出现——
     * 值班的人照着列表逐条处置，漏掉的那条谁也不知道。
     */
    @Test
    @Order(5)
    void alarmPagingStaysStableWhenReceivedAtTies() throws Exception {
        String reader = session("alarms", "READ", PermissionCode.ALARM_READ.value());
        jdbc.update("delete from alarm");
        bulkAlarms(40);     // 全部同一个 received_at：次序完全由兜底键决定

        List<String> firstPage = pageIds(reader, 1, 20);
        // 让物理次序与插入次序错开：PG 的 UPDATE 是"标记旧行 + 追加新行"，被改过的行会跑到堆的末尾。
        jdbc.update("update alarm set detail=cast('{\"touched\":true}' as jsonb)"
                + " where alarm_id in (select alarm_id from alarm order by alarm_id asc limit 15)");
        jdbc.execute("analyze alarm");
        List<String> secondPage = pageIds(reader, 2, 20);

        assertThat(firstPage).as("第一页应满页").hasSize(20);
        assertThat(secondPage).as("第二页应满页").hasSize(20);
        assertThat(firstPage).as("同一页内不得有重复").doesNotHaveDuplicates();
        assertThat(secondPage).doesNotHaveDuplicates();
        assertThat(firstPage).as("两页不得重叠——重叠意味着有告警被看了两次").doesNotContainAnyElementsOf(secondPage);
        List<String> all = new ArrayList<>(firstPage);
        all.addAll(secondPage);
        assertThat(all).as("两页并集必须恰为全部 40 条——遗漏的那条值班的人永远不会看到")
                .containsExactlyInAnyOrderElementsOf(
                        jdbc.queryForList("select alarm_id from alarm", String.class));
    }

    /**
     * `bearing_deg` 从 **JSONB** `quality` 里读出来（决策 15-5）。
     *
     * <p>这条只有在真 PostgreSQL 上才有意义：`source_observation.quality` 在迁移
     * `V202609050051` 里声明的是 JSONB，而 H2（PostgreSQL 模式）把它当字符串，
     * 于是任何直接把它当文本用的写法在 H2 上都正常、在 PG 上直接报
     * `operator does not exist: jsonb ~~ unknown`，整个 `GET /targets` 500。
     * 另一半同样要钉：**目标自身有位置时不给方位**——同一个目标既画点又画方向线，
     * 读图的人不知道该信哪个。
     */
    @Test
    @Order(6)
    void bearingIsReadFromTheJsonbQualityColumnOnPostgres() throws Exception {
        String reader = session("sensing", "READ", PermissionCode.TARGET_READ.value());
        String target = target();
        bearingObservation(target);

        JsonNode state = detail(reader, target).path("latest_state");
        assertThat(state.path("bearing_deg").decimalValue())
                .as("无位置但带方位的观测必须透出方位角（JSONB 读路径）").isEqualByComparingTo("123.5");
        assertThat(state.path("bearing_device_id").asText()).as("方位线要知道从哪台设备画起").isNotBlank();

        // 列表与详情同形（契约 §2）：只在详情里透出会让态势页的悬浮卡拿不到。
        JsonNode listed = listOne(reader, target);
        assertThat(listed.path("latest_state").path("bearing_deg").decimalValue()).isEqualByComparingTo("123.5");

        jdbc.update("update target_latest_state set location=cast(? as geometry) where target_id=?",
                "SRID=4326;POINT(118.4 37.3)", target);
        assertThat(detail(reader, target).path("latest_state").has("bearing_deg"))
                .as("目标自身有位置时不再给方位——点和方向线同时出现，读图的人不知道信哪个").isFalse();
    }

    /**
     * 三段摘要"取最新一条"在 `occurred_at` 为空时也必须是**确定的一条**（决策 15-21）。
     *
     * <p>`flight_risk.occurred_at` 是可空列，而判据是元组比较
     * `(n.occurred_at, n.risk_id) > (r.occurred_at, r.risk_id)`。两行都为 NULL 时元组比较返回 NULL、
     * 两行**都**满足 `NOT EXISTS`，于是留在结果里的是任意一条——悬浮卡上的风险等级会在两次刷新之间跳变，
     * 而看的人以为自己看到的是"现在的情况"。修法是排序键取 `COALESCE(occurred_at, received_at)`。
     *
     * <p>这条用例的三层，按"根本 → 表象"排：
     * <ol>
     *   <li><b>夹具确实能触发缺陷</b>：先用**退休前那个谓词**在库里数一遍——它必须匹配到 <b>2 行</b>。
     *       不先证明这一点，后面两层全绿也可能只是因为夹具压根没造出那个情形。</li>
     *   <li><b>缺陷本体是"谓词命中两行"</b>，"谁最终胜出"只是随结果集顺序变化的表象
     *       （E1 的第一版用例就栽在这里：H2 的返回顺序碰巧让正确答案胜出，回退实现照样绿）。
     *       所以现行谓词必须**只命中一行**。</li>
     *   <li>最后才是接口层的表象断言：拿到的是 `received_at` 较新的那条。
     *       为让错误实现<b>必然</b>选错，夹具**先插较新、后插较旧**——两行都匹配时，
     *       后扫到的那条会覆盖掉先前的，于是错误实现只能给出较旧的那条。</li>
     * </ol>
     *
     * <p>另一半同样钉：有 `occurred_at` 时它仍然说了算，**`occurred_at` 较新但 `received_at` 较旧的那条要赢**。
     * 只验前一半的话，把排序键整个换成 `received_at` 也照样绿，而那会让一条早就发生、
     * 刚刚才补录进来的风险盖掉真正最新的那条。
     */
    @Test
    @Order(7)
    void latestRiskSummaryStaysDeterministicWhenOccurredAtIsNull() throws Exception {
        String reader = session("sensing", "READ", PermissionCode.TARGET_READ.value());
        String plan = flightPlan();

        // 先插较新、后插较旧：两行都匹配时后扫到的会覆盖先前的，错误实现只能给出较旧的那条。
        String nullTarget = target();
        String newerByReceipt = risk(plan, nullTarget, null, T0.plusMinutes(20), "CRITICAL");
        risk(plan, nullTarget, null, T0.plusMinutes(10), "LOW");

        // ① 夹具自证：退休前那个谓词在这份夹具上确实会匹配到两行。
        assertThat(latestRowCount(nullTarget, "(n.occurred_at, n.risk_id) > (r.occurred_at, r.risk_id)"))
                .as("夹具必须真的触发那个缺陷：旧谓词对两条 occurred_at 为 NULL 的风险应当两行都匹配。"
                        + "数不到 2 的话，后面全绿也只说明这份夹具没造出那个情形")
                .isEqualTo(2);
        // ② 缺陷本体：现行谓词只应命中一行。"谁胜出"只是表象，"命中几行"才是问题本身。
        assertThat(latestRowCount(nullTarget,
                "(COALESCE(n.occurred_at, n.received_at), n.risk_id) > (COALESCE(r.occurred_at, r.received_at), r.risk_id)"))
                .as("取最新一条的谓词必须恰好命中一行——命中两行时选中哪条只看扫描顺序").isEqualTo(1);
        // ③ 表象：接口给出的确实是接收时刻较新的那条。
        JsonNode nullSummary = detail(reader, nullTarget).path("risk_summary");
        assertThat(nullSummary.path("risk_id").asText())
                .as("两条都没有发生时刻时，按接收时刻取较新的那条").isEqualTo(newerByReceipt);
        assertThat(nullSummary.path("severity").asText()).isEqualTo("CRITICAL");

        // 另一半：occurred_at 在场时仍然由它说了算。
        String mixedTarget = target();
        String occurredNewer = risk(plan, mixedTarget, T0.plusMinutes(50), T0.plusMinutes(10), "CRITICAL");
        risk(plan, mixedTarget, T0.plusMinutes(30), T0.plusMinutes(90), "LOW");
        assertThat(detail(reader, mixedTarget).path("risk_summary").path("risk_id").asText())
                .as("补录进来的旧风险不得盖掉真正最新的那条：occurred_at 较新者赢，哪怕它更早被接收")
                .isEqualTo(occurredNewer);
    }

    /**
     * 某个目标下，给定"更新"判据时有几行会被当成"最新一条"。
     *
     * <p>谓词以字符串传进来是有意的：这里要能同时问"**退休前**那个写法会命中几行"和
     * "**现行**写法会命中几行"。前者是夹具的自证（证明这份数据确实触发过缺陷），
     * 后者是缺陷本体的断言。两个问题都不该去引用产品代码里那段 SQL——
     * 引用的话，谓词被改坏时这条用例会跟着一起改坏，然后照样绿。
     */
    private int latestRowCount(String targetId, String newerPredicate) {
        return jdbc.queryForObject("select count(*) from flight_risk r where r.target_id=?"
                + " and not exists (select 1 from flight_risk n where n.target_id=r.target_id and "
                + newerPredicate + ")", Integer.class, targetId);
    }

    /**
     * 超级管理员完整性检查**不得把动作行的等级刷成 AUTH**，而且**再跑一次仍然不许**（决策 15-25）。
     *
     * <p>动作等级只有 `NONE|READ|OP`（决策 15-2），`AUTH` 是菜单模块才有的档。
     * `SuperAdminIntegrityInitializer` 原先对 `ROLE-ADMIN` 的**全部**授权行无差别地
     * `SET permission_level = 'AUTH'`，动作行跟着一起被刷——升级库 `uav_stage10_verify` 上
     * 42 条动作行全是 AUTH 就是这么来的。
     *
     * <p><b>重点在"再跑一次"</b>：这是个 `ApplicationRunner`，**每次启动都会执行**。
     * 只用迁移把存量数据归一，下一次重启它又会刷回去——那样"修好了"只在重启之间成立，
     * 而演示或部署恰恰总在重启之后。所以这条用例直接把初始化器再调一遍，
     * 断言的是"它自己不再制造这种行"，不是"迁移把历史数据洗干净了"。
     *
     * <p>另一半同样钉：**模块行仍须被置为 AUTH**。把范围收得过窄、让初始化器什么都不干，
     * 同样是缺陷——超级管理员的模块权限就是靠它兜底的。
     */
    @Test
    @Order(8)
    void superAdminIntegrityMustNotStampAuthOntoActionRowsEvenOnASecondStartup() {
        // 部署/种子给超级管理员的动作行是 READ 档；本 schema 里 dev-seed 是关的，按真实形态补上。
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " select 'ROLE-ADMIN', c.permission_code, 'READ', false, current_timestamp from app_permission c"
                + " where c.permission_kind='ACTION' and not exists (select 1 from app_role_permission p"
                + "   where p.role_code='ROLE-ADMIN' and p.permission_code=c.permission_code)");
        int actionGrants = jdbc.queryForObject("select count(*) from app_role_permission p"
                + " join app_permission c on c.permission_code=p.permission_code"
                + " where p.role_code='ROLE-ADMIN' and c.permission_kind='ACTION'", Integer.class);
        assertThat(actionGrants).as("夹具自证：超级管理员必须真的持有动作行，否则下面断言的是一个空集合").isPositive();

        // 初始化器要按账号找到唯一的超级管理员，并且要求全系统恰好一个 ROLE-ADMIN 持有者。
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?, 'ROLE-ADMIN','ACTIVE','unused',0,'ALL',0,0,0,0)",
                id(), SUPER_ADMIN_ACCOUNT, "阶段十五超级管理员");

        assertActionRowsCarryNoAuthLevel("首次");
        // **每次启动都会跑**：只靠迁移洗历史数据的话，下一次重启就又被刷回去了。
        superAdminIntegrity.run(null);
        assertActionRowsCarryNoAuthLevel("再次启动后");

        // 允许的那一半：模块行仍须被置为 AUTH，否则超级管理员的模块权限没人兜底了。
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission p"
                + " join app_permission c on c.permission_code=p.permission_code"
                + " where p.role_code='ROLE-ADMIN' and c.permission_kind<>'ACTION' and p.permission_level<>'AUTH'", Integer.class))
                .as("把范围收得过窄、让初始化器什么都不干，与刷错等级一样是缺陷").isZero();
    }

    private void assertActionRowsCarryNoAuthLevel(String phase) {
        assertThat(jdbc.queryForList("select p.permission_code from app_role_permission p"
                + " join app_permission c on c.permission_code=p.permission_code"
                + " where c.permission_kind='ACTION' and p.permission_level='AUTH' order by p.permission_code", String.class))
                .as(phase + "：动作等级只有 NONE|READ|OP，AUTH 是菜单模块才有的档（决策 15-2 / 15-25）").isEmpty();
    }

    /**
     * 分两步升级：**先修成因、再洗存量**，两件事在升级路径上都要成立（决策 15-27）。
     *
     * <p>背景是升级库 `uav_stage10_verify` 上真实发生过的一次"净效果为零"：
     * Flyway 跑 `0104` 把 42 条动作行归一成 `OP`，**同一次启动**里 `SuperAdminIntegrityInitializer`
     * （`@Order(30)`，在 Flyway 之后）又把它们刷回 `AUTH`。迁移的版本号已经记进
     * `flyway_schema_history`，不会再跑第二遍——所以光修成因，存量那 42 条会永远停在
     * 一个动作行不该有的等级上（决策 15-2：动作只有 `NONE|READ|OP`）。
     *
     * <p>三步走的正是升级路径上真正会发生的事：
     * <ol>
     *   <li>迁到 `0104` 为止；</li>
     *   <li>照升级库的实测形态铺出脏状态（`ROLE-ADMIN` 的动作行等级为 `AUTH`），并**自证铺出来了**——
     *       不先证明脏状态真的存在，第 3 步的"没有 AUTH"可能只是因为压根没有那种行；</li>
     *   <li>再迁到最新，断言动作行**一条 AUTH 都不剩**。</li>
     * </ol>
     *
     * <p>脏状态是直接写出来的，**不是再调一次初始化器造出来的**——成因已经修好（15-25 把那条
     * UPDATE 限定到 `permission_kind = 'MODULE'`），现役代码再也产不出这种行。
     * 这条用例守的是**历史**：已经这样的库升级上来之后必须干净。
     * "现役代码不再制造它"由第 8 例用真的初始化器去守，两条各管一头，别混为一谈。
     */
    @Test
    @Order(9)
    void upgradePathClearsAuthActionRowsThatTheInitializerHadStampedBack() {
        String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        root.execute("create schema " + schema);
        try {
            flywayFor(schema, ROUND8_TAIL).migrate();
            JdbcTemplate scratch = scratchTemplate(schema);

            // 直接写出脏状态，而不是再调一次初始化器把它造出来——**因为成因已经修好了**
            // （决策 15-25：那条 UPDATE 现在限定 `permission_kind = 'MODULE'`），
            // 现役代码再也产不出这种行。但升级库上那批行是**历史事实**：`uav_stage10_verify` 上
            // 实测就是 42 条 ACTION 全为 AUTH。这条用例要守的正是历史，所以照历史的样子铺。
            scratch.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " select 'ROLE-ADMIN', c.permission_code, 'AUTH', false, current_timestamp from app_permission c"
                    + " where c.permission_kind='ACTION' and not exists (select 1 from app_role_permission p"
                    + "   where p.role_code='ROLE-ADMIN' and p.permission_code=c.permission_code)");
            assertThat(authActionRows(scratch))
                    .as("夹具自证：这一步必须真的造出 AUTH 动作行，否则第 3 步的『没有 AUTH』什么也没证明")
                    .isPositive();

            flywayForAll(schema).migrate();
            assertThat(scratch.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class))
                    .as("有失败迁移时后面的断言没有意义").isZero();
            assertThat(authActionRows(scratch))
                    .as("升级到最新之后，初始化器当初刷上去的 AUTH 动作行必须被洗干净（决策 15-27）——"
                            + "只修成因不洗存量的话，升级库上那批行会永远停在一个不合法的等级")
                    .isZero();
        } finally {
            root.execute("drop schema " + schema + " cascade");
        }
    }

    private static int authActionRows(JdbcTemplate template) {
        return template.queryForObject("select count(*) from app_role_permission p"
                + " join app_permission c on c.permission_code=p.permission_code"
                + " where c.permission_kind='ACTION' and p.permission_level='AUTH'", Integer.class);
    }

    /* ---------------------------------------------------------------- 夹具 */

    /**
     * 一条计划 + 它的航线版本。`flight_risk` 的必填面比看上去宽：计划与航线版本是复合外键，
     * 缺一条整行插不进去，所以夹具照真实形状铺一遍，不图省事去改产品的约束。
     */
    private String flightPlan() {
        String route = id(), routeVersion = id(), plan = id();
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,true,'mock',?,?,?,?,0)", route, "RT-S15-" + suffix, "阶段十五验证航线 " + suffix, org, district, T0, T0);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,valid_from,created_at)"
                + " values (?,?,1,cast(? as geometry),?,?)", routeVersion, route,
                "SRID=4326;LINESTRING(118.40 37.30,118.50 37.40)", T0, T0);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,route_version_id,"
                + "owner_org_id,district_id,created_at,updated_at,version) values (?,?,'APPROVED',?,'mock',?,?,?,?,?,0)",
                plan, "FP-S15-" + suffix, source, routeVersion, org, district, T0, T0);
        return plan;
    }

    /** 一条风险；`occurredAt` 传 null 就是"没有发生时刻"，正是 15-21 要盯的那种行。返回 risk_id。 */
    private String risk(String planId, String targetId, OffsetDateTime occurredAt, OffsetDateTime receivedAt, String severity) {
        String riskId = id();
        String routeVersion = jdbc.queryForObject("select route_version_id from flight_plan where plan_id=?", String.class, planId);
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,"
                + "severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,"
                + "owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,'AIRSPACE',?,'PENDING_VERIFICATION','PROHIBITED_AIRSPACE_OVERLAP','阶段十五验证：进入禁飞空域',"
                + "?,?,'UNKNOWN','mock',?,?,?,?,0)",
                riskId, source, "S15-RISK-" + riskId.substring(0, 8), planId, routeVersion, targetId, severity,
                occurredAt, receivedAt, org, district, T0, T0);
        return riskId;
    }

    private JsonNode detail(String token, String targetId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/targets/{id}", targetId).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as("目标详情必须 200，体=" + responseBody(result)).isEqualTo(200);
        return body(result);
    }

    private JsonNode listOne(String token, String targetId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/targets?size=100").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as("目标列表必须 200，体=" + responseBody(result)).isEqualTo(200);
        for (JsonNode item : body(result).path("items")) {
            if (targetId.equals(item.path("target_id").asText())) return item;
        }
        throw new AssertionError("目标列表里没有夹具目标 " + targetId);
    }

    private String target() {
        String targetId = id();
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version,unified)"
                + " values (?,?,'mock',?,?,?,?,0,false)", targetId, "T-S15-" + suffix + "-" + targetId.substring(0, 6), org, district, T0, T0);
        jdbc.update("insert into target_latest_state (target_id,observed_at,received_at,created_at,updated_at)"
                + " values (?,?,?,?,?)", targetId, T0, T0, T0, T0);
        return targetId;
    }

    /**
     * 一条无位置、`quality` 里带 `bearing_deg` 的观测，并把它链到目标上。
     *
     * <p>观测必须挂在一台**真实存在的设备**上：`source_observation.device_id` 有指向 `device` 的外键，
     * 而方位线要从设备所在的位置画起——没有设备号的方位角在图上落不了笔，
     * 所以夹具照真实形态造，不图省事把 `device_id` 留空。
     */
    private void bearingObservation(String targetId) {
        String external = "ext-" + targetId.substring(0, 8);
        String device = id();
        jdbc.update("insert into device (device_id,device_no,name,enabled,source_mode,created_at,updated_at,version)"
                + " values (?,?,?,true,'mock',?,?,0)", device, "DEV-S15-" + suffix, "阶段十五验证测向设备", T0, T0);
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,device_id,source_session_key,external_target_id,created_at)"
                + " values (?,?,?,?,?,?,?)", id(), targetId, source, device, "s15-session", external, T0);
        jdbc.update("insert into source_observation (observation_id,source_id,device_id,source_type,source_session_key,external_target_id,"
                + "observed_at,received_at,location,identity_confidence,quality,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,?,?,'AOA','s15-session',?,?,?,null,0.80000,cast(? as jsonb),'mock',?,?,?)",
                id(), source, device, external, T0, T0, "{\"bearing_deg\":123.5}", org, district, T0);
    }

    /** 一批 `received_at` 完全相同的告警：排序稳定性与导出上限共用。 */
    private void bulkAlarms(int count) {
        jdbc.update("insert into alarm (alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,"
                + "detail,source_mode,owner_org_id,district_id,created_at)"
                + " select gen_random_uuid()::varchar, ?, 'S15-' || ?::varchar || '-' || g::varchar, 'INTRUSION', 'HIGH', ?, ?,"
                + "  cast('{}' as jsonb), 'mock', ?, ?, ?"
                + " from generate_series(1, ?) g",
                source, UUID.randomUUID().toString().substring(0, 8), T0, T0, org, district, T0, count);
    }

    private List<String> pageIds(String token, int page, int size) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/alarms?page={p}&size={s}&sort=received_at&order=desc", page, size)
                .header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as("分页请求必须 200，体=" + responseBody(result)).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        for (JsonNode item : body(result).path("items")) ids.add(item.path("alarm_id").asText());
        return ids;
    }

    private MockHttpServletRequestBuilder updateActions(String token, String roleCode, int expectedVersion, List<String> actions) {
        StringBuilder actionJson = new StringBuilder("[");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) actionJson.append(',');
            actionJson.append("{\"permission_code\":\"").append(actions.get(i)).append("\",\"level\":\"READ\"}");
        }
        actionJson.append(']');
        return put("/api/v1/roles/{code}/permissions", roleCode)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "s15-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_version\":" + expectedVersion + ",\"reason\":\"阶段十五并发验证\","
                        + "\"permissions\":" + fullMatrixJson() + ",\"actions\":" + actionJson + "}");
    }

    /** MODULE 矩阵要求整组提交（`PERMISSION_SET_INCOMPLETE`），这里按目录整组给 NONE。 */
    private String fullMatrixJson() {
        List<String> modules = jdbc.queryForList(
                "select permission_code from app_permission where permission_kind<>'ACTION' order by permission_code", String.class);
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < modules.size(); i++) {
            if (i > 0) text.append(',');
            text.append("{\"permission_code\":\"").append(modules.get(i))
                    .append("\",\"level\":\"NONE\",\"menu_enabled\":false}");
        }
        return text.append(']').toString();
    }

    private String customRole() {
        String role = "ROLE-S15-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, "阶段十五自定义角色 " + role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " select ?,permission_code,'NONE',false,current_timestamp from app_permission where permission_kind<>'ACTION'", role);
        return role;
    }

    /** 建一个只带指定模块权限（外加若干动作码）的会话。 */
    private String session(String moduleCode, String level, String... actionCodes) {
        String role = "ROLE-S15S-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, "阶段十五会话 " + role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " values (?,?,?,true,current_timestamp)", role, moduleCode, level);
        for (String action : actionCodes) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'OP',false,current_timestamp)", role, action);
        }
        return sessionFor(role);
    }

    /** 给一个既有角色开一个会话（改权限使旧会话失效的用例要用）。 */
    private String sessionForRole(String roleCode, String... actionCodes) {
        for (String action : actionCodes) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'OP',false,current_timestamp)", roleCode, action);
        }
        return sessionFor(roleCode);
    }

    private String sessionFor(String roleCode) {
        String user = id(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)",
                user, "s15u-" + user.substring(0, 8), "阶段十五操作员", roleCode);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    /** 用栅栏把 N 个请求对齐到同一时刻发出：不对齐的话先到的早就提交完了，测不到并发。 */
    private List<MvcResult> race(List<MockHttpServletRequestBuilder> requests) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(requests.size());
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (MockHttpServletRequestBuilder request : requests) {
                futures.add(pool.submit(() -> { barrier.await(20, TimeUnit.SECONDS); return mvc.perform(request).andReturn(); }));
            }
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String errorCode(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText();
    }

    private static String bodies(List<MvcResult> results) {
        return results.stream().map(Stage15PostgresTest::responseBody).toList().toString();
    }

    /** 断言失败时把响应体带出来：只报"期望 200 得到 409"排查起来要多跑一趟。 */
    private static String responseBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception unreadable) {
            return "<响应体不可读: " + unreadable + ">";
        }
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 15 verification outside a stage456_verify_ database");
        }
        root.execute("create schema " + SCHEMA);
        schemaCreated = true;
        try {
            Flyway.configure().dataSource(rootDataSource()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false)
                    .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
        } catch (RuntimeException exception) {
            try { root.execute("drop schema " + SCHEMA + " cascade"); } finally { schemaCreated = false; }
            throw exception;
        }
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe Stage 15 verification schema");
    }
}
