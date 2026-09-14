package com.uav.lowaltitude.modules.fusion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.uav.lowaltitude.modules.fusion.infrastructure.AssociationPendingRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;

/**
 * 阶段 16（自动合并/分裂）PostgreSQL 专项验证。harness 沿用 Stage15PostgresTest 已跑通的做法：
 * env 门禁、`stage456_verify_` 库名硬校验、随机 `stage456_` schema、手动 Flyway
 * （`db/migration` + `db/postgresql`）、**不用测试级事务**（并发用例要真提交）、只删本 schema。
 * 库用 `stage456_verify_s16`。
 *
 * <p>这个域里**只有在 PostgreSQL 上才看得见**的东西有两类，正是本类的重点：
 * <ul>
 *   <li>`target_lineage` 的只增触发器 `trg_stage8_lineage_append_only` **只建在 PostgreSQL 上**
 *       （`db/postgresql` 的可重复迁移里），H2 压根没有它——所以"改写血缘会被拒"这件事在 H2 上
 *       怎么测都是绿的，而它守的是目标身份的证据链；</li>
 *   <li>同一条 pending 被两个真实连接同时落定：H2 测不了两条真事务的行锁竞争。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage16PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage16PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage16PostgresTest 未在真实 PostgreSQL 上执行")
class Stage16PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 9, 12, 0, 0, 0, ZoneOffset.UTC);

    private static boolean schemaCreated;

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired LineageRepository lineages;
    @Autowired AssociationPendingRepository pendings;

    private String suffix, org, district, configVersion;

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
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
        org = id(); district = id();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "ORG-S16-" + suffix, "阶段十六验证机构 " + suffix);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-S16-" + suffix, "阶段十六验证区域 " + suffix);
        // 血缘的 config_version 是外键，用迁移预置的那一条，不自己编一个不存在的版本。
        configVersion = jdbc.queryForObject("select config_version from fusion_config order by created_at asc limit 1", String.class);
    }

    /** 基线：迁移无失败，且 PostgreSQL 专属的只增触发器**按行为**生效。 */
    @Test
    @Order(1)
    void migrationsAndPostgresOnlyLineageTriggerAreInPlace() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class))
                .as("有失败迁移时后续断言全部没有意义").isZero();
        assertThat(configVersion).as("血缘要挂的融合参数版本必须由迁移预置").isNotBlank();

        // 按行为断言，不查 pg_trigger 里有没有同名对象（阶段 13 的教训：同名存在性证明不了它拦得住写入，
        // 而缺陷恰恰是"该建没建"）。
        String target = target("基线目标");
        String lineageId = createLineage(target);
        assertThat(sqlState(catching(() -> jdbc.update("update target_lineage set note='改写' where lineage_id=?", lineageId))))
                .as("血缘是目标身份的证据链，改写必须以 23514 被拒").isEqualTo("23514");
    }

    /**
     * 自动合并落库的三件事：血缘一行 `MERGE/SYSTEM`、别名能解析到存活目标、**只有被并掉的那个**状态写 MERGE。
     *
     * <p>最后半句是重点：存活目标的状态也被写成 MERGE 的话，它在列表里会跟着被当成"已合并"而从
     * 默认视图里消失——一次正确的合并反而让两个目标都看不见了。
     */
    @Test
    @Order(2)
    void mergeWritesOneSystemLineageAliasResolvesAndOnlyTheLoserIsMarked() {
        String survivor = target("存活目标");
        String loser = target("被并目标");
        trackStatus(survivor, "STABLE");
        trackStatus(loser, "STABLE");

        String lineageId = merge(survivor, loser);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select op, operator_kind, survivor_target_id from target_lineage where lineage_id=?", lineageId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("op", "MERGE").containsEntry("operator_kind", "SYSTEM")
                .containsEntry("survivor_target_id", survivor);

        assertThat(jdbc.queryForObject("select current_target_id from target_current_alias where historical_target_id=?",
                String.class, loser)).as("老目标号必须能解析到存活目标，否则旧书签与旧证据指向一个查不到的 id")
                .isEqualTo(survivor);
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, loser))
                .isEqualTo("MERGE");
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, survivor))
                .as("存活目标不得被写成 MERGE——否则一次正确的合并会让两个目标都从默认视图里消失")
                .isEqualTo("STABLE");
    }

    /**
     * 别名链必须跟着往前指：A 已并入 B，B 再并入 C 时，A 也要改指到 C。
     *
     * <p>不跟着改的话，拿 A 去解析得到 B，而 B 自己已经是"历史目标"了——深链查出来是一条
     * 已经不存在于默认视图的记录，用的人只会以为数据丢了。
     */
    @Test
    @Order(3)
    void aliasChainIsRedirectedWhenTheSurvivorIsMergedAgain() {
        String a = target("一代目标");
        String b = target("二代目标");
        String c = target("三代目标");
        trackStatus(a, "STABLE"); trackStatus(b, "STABLE"); trackStatus(c, "STABLE");

        merge(b, a);
        assertThat(jdbc.queryForObject("select current_target_id from target_current_alias where historical_target_id=?",
                String.class, a)).isEqualTo(b);

        merge(c, b);

        assertThat(jdbc.queryForObject("select current_target_id from target_current_alias where historical_target_id=?",
                String.class, b)).isEqualTo(c);
        assertThat(jdbc.queryForObject("select current_target_id from target_current_alias where historical_target_id=?",
                String.class, a)).as("二次合并必须把更早的别名一起往前指，否则 A 解析出来的是一个已成历史的目标")
                .isEqualTo(c);
    }

    /**
     * 血缘只增，**但追加必须放行**。
     *
     * <p>两半都钉：只堵不放会逼业务"删了重开"，证据链里就会出现一段凭空消失的历史；
     * 只放不堵则等于合并记录可以被事后改写，那条链就不再是证据。
     */
    @Test
    @Order(4)
    void lineageIsAppendOnlyYetStillAcceptsNewRows() {
        String target = target("只增验证目标");
        String first = createLineage(target);

        assertThat(sqlState(catching(() -> jdbc.update("update target_lineage set op='SPLIT' where lineage_id=?", first))))
                .as("改写血缘必须被拒").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("delete from target_lineage where lineage_id=?", first))))
                .as("删除血缘必须被拒").isEqualTo("23514");

        String second = createLineage(target);
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where lineage_id in (?,?)", Long.class, first, second))
                .as("纠错只能追加新行，所以追加必须放行——堵死它等于逼人去删了重开").isEqualTo(2L);
        assertThat(jdbc.queryForObject("select op from target_lineage where lineage_id=?", String.class, first))
                .as("两次被拒之后原行必须和写下时一模一样").isEqualTo("CREATE");
    }

    /**
     * 分裂：**追加一行 `SPLIT`，创建时那行 `CREATE` 原样保留，原目标的状态不写 SPLIT**。
     *
     * <p>三件事缺一不可。改写 CREATE 行的话，新目标的来历就被抹掉了——事后只看得到它"一直存在"；
     * 而把原目标状态写成 SPLIT，它会跟着从默认视图消失，而分裂之后**原目标仍然是在飞的那一个**，
     * 让它消失比多出一个目标更难解释。
     */
    @Test
    @Order(5)
    void splitAppendsALineageRowKeepsCreateAndLeavesTheOriginStatusAlone() {
        String origin = target("原目标");
        String child = target("分裂出的目标");
        trackStatus(origin, "STABLE");
        trackStatus(child, "TENTATIVE");
        String createRow = createLineage(child);

        String splitRow = id();
        lineages.insertLineage(new LineageRepository.LineageInsert(splitRow, "SPLIT", T0.plusMinutes(1), null, origin,
                write(List.of(child)), write(List.of(origin)), "{}", "stage16", configVersion, "SYSTEM", null, null, "{}",
                T0.plusMinutes(1)));

        assertThat(jdbc.queryForList("select op from target_lineage where survivor_target_id=? or origin_target_id=? or "
                + "member_target_ids @> cast(? as jsonb)", String.class, child, child, "[\"" + child + "\"]"))
                .as("分裂是追加一行 SPLIT，创建时那行 CREATE 必须原样留着——改写它等于抹掉新目标的来历")
                .containsExactlyInAnyOrder("CREATE", "SPLIT");
        assertThat(jdbc.queryForObject("select op from target_lineage where lineage_id=?", String.class, createRow))
                .isEqualTo("CREATE");
        assertThat(jdbc.queryForObject("select origin_target_id from target_lineage where lineage_id=?", String.class, splitRow))
                .isEqualTo(origin);
        assertThat(jdbc.queryForObject("select status from target_track_status where target_id=?", String.class, origin))
                .as("分裂之后原目标仍然是在飞的那一个，状态不得被写成 SPLIT")
                .isEqualTo("STABLE");
    }

    /**
     * 同一条 pending 被两条真实连接同时落定：**只能生效一次**，且最终 `resolution` 是胜者写下的那个。
     *
     * <p>`resolve` 的 `AND resolved_at IS NULL` 是唯一的防线，而它只有在真事务的行锁下才成立——
     * H2 测不了两条连接的锁竞争。落定两次的后果是同一次歧义在证据里有两个互相矛盾的结论
     * （一次 MERGED、一次 SPLIT），事后没人说得清当时到底按哪个算的。
     */
    @Test
    @Order(6)
    void onlyOneOfTwoConcurrentResolutionsTakesEffect() throws Exception {
        String pendingId = pendings.insert("domain-" + suffix, null, "[]", "GATE_AMBIGUOUS",
                "key-" + suffix, T0.toInstant());
        assertThat(jdbc.queryForObject("select resolution from association_pending where pending_id=?", String.class, pendingId))
                .as("夹具自证：落定前 resolution 必须是空的，否则下面比的是一个早就写好的值").isNull();

        // ① 顺序的那一半才是真正钉住 `AND resolved_at IS NULL` 的：第二次落定必须**改不动**第一次的结论。
        //    并发那一半单独看是证明不了这件事的——两条连接被行锁排开之后，后到的那个照样可能覆盖掉前一个，
        //    而最终值"是两者之一"这句话在覆盖发生时同样成立。
        pendings.resolve(pendingId, "MERGED", T0.toInstant());
        pendings.resolve(pendingId, "SPLIT", T0.plusMinutes(1).toInstant());
        assertThat(jdbc.queryForObject("select resolution from association_pending where pending_id=?", String.class, pendingId))
                .as("已落定的歧义不得被改成另一个结论——改得动就等于证据里的结论可以事后翻案").isEqualTo("MERGED");

        // ② 并发那一半：换一条新的 pending，两条真实连接同时落定，结论必须仍然只有一个。
        String raced = pendings.insert("domain-" + suffix, null, "[]", "GATE_AMBIGUOUS", "key-race-" + suffix, T0.toInstant());
        race(raced, "MERGED", "SPLIT");
        Map<String, Object> row = jdbc.queryForMap(
                "select resolution, resolved_at from association_pending where pending_id=?", raced);
        assertThat(row.get("resolved_at")).as("落定必须落时刻；CHECK 要求 resolved_at 与 resolution 成对").isNotNull();
        assertThat((String) row.get("resolution")).isIn("MERGED", "SPLIT");
        // 并发之后再补一次顺序落定：改不动，说明那道防线在竞争之后依然完好，而不是被某一路绕过了。
        String afterRace = (String) row.get("resolution");
        pendings.resolve(raced, "EXPIRED", T0.plusMinutes(2).toInstant());
        assertThat(jdbc.queryForObject("select resolution from association_pending where pending_id=?", String.class, raced))
                .as("竞争之后那道 resolved_at IS NULL 的防线必须依然拦得住后来的写入").isEqualTo(afterRace);
    }

    /* ---------------------------------------------------------------- 夹具 */

    /** 两条真实连接同时落定同一条 pending；各自用独立线程，Hikari 会给每个线程自己的连接。 */
    private List<String> race(String pendingId, String... resolutions) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(resolutions.length);
        ExecutorService pool = Executors.newFixedThreadPool(resolutions.length);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (String resolution : resolutions) {
                futures.add(pool.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    pendings.resolve(pendingId, resolution, Instant.now());
                    return resolution;
                }));
            }
            List<String> done = new ArrayList<>();
            for (Future<String> future : futures) done.add(future.get(60, TimeUnit.SECONDS));
            return done;
        } finally {
            pool.shutdownNow();
        }
    }

    private String merge(String survivor, String loser) {
        String lineageId = id();
        lineages.insertLineage(new LineageRepository.LineageInsert(lineageId, "MERGE", T0, survivor, null,
                write(List.of(loser)), write(List.of(survivor, loser)), "{}", "stage16", configVersion, "SYSTEM", null,
                null, "{}", T0));
        lineages.upsertAlias(loser, survivor, lineageId, T0);
        lineages.redirectAliases(loser, survivor, lineageId, T0);
        lineages.upsertTrackStatus(loser, "MERGE", T0);
        return lineageId;
    }

    private String createLineage(String target) {
        String lineageId = id();
        lineages.insertLineage(new LineageRepository.LineageInsert(lineageId, "CREATE", T0, target, null,
                write(List.of(target)), "[]", "{}", "stage16", configVersion, "SYSTEM", null, null, "{}", T0));
        return lineageId;
    }

    private String target(String name) {
        String targetId = id();
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version,unified)"
                + " values (?,?,'mock',?,?,?,?,0,false)", targetId,
                "T-S16-" + suffix + "-" + targetId.substring(0, 6), org, district, T0, T0);
        return targetId;
    }

    private void trackStatus(String targetId, String status) {
        jdbc.update("insert into target_track_status (target_id,status,since,confirm_hits,miss_frames,last_observed_at,updated_at,version)"
                + " values (?,?,?,0,0,null,?,0)", targetId, status, T0, T0);
    }

    private static String write(List<String> ids) {
        return "[" + ids.stream().map(value -> "\"" + value + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
    }

    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException expected) {
            return expected;
        }
    }

    private static String sqlState(Throwable thrown) {
        for (Throwable cursor = thrown; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return thrown == null ? "<写入意外成功>" : "<无 SQLState: " + thrown + ">";
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 16 verification outside a stage456_verify_ database");
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe Stage 16 verification schema");
    }
}
