package com.uav.lowaltitude.modules.fusion.api;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.application.LiveRadarSourceObservationPort;
import com.uav.lowaltitude.integration.mock.LocalStage8FusionReplaySeeder;
import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator;
import com.uav.lowaltitude.integration.replay.FusionReplayRunner;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.fusion.application.FusionPipeline;
import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationReadRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.testsupport.SourceTypeCatalogFixture;

/**
 * 阶段 8.5（设备直连切片）PostgreSQL/PostGIS 专项验证。沿用 Stage8/Stage9PostgresTest 已跑通的做法：
 * env 门禁（缺 `POSTGRES_TEST_*` 整类跳过，不影响他人全量跑）、`stage456_verify_` 库名硬校验、随机 `stage456_` schema、
 * 手动 Flyway（`db/migration` + `db/postgresql`）、**不使用测试级事务**（并发领取用例要求真正提交）、只删本 schema。
 *
 * 本轮（8.5.3 起草）只包含**不依赖执行者类**的用例：迁移基线、来源类型目录（迁移 070）、融合参数 JSON 在 PG 上的回读。
 * 契约要求的 `pilot_location` 几何 CHECK/GIST、四前缀并发领取、`live-radar:` 开关闸门、凌云 v2 数据集端到端，
 * 都依赖 E1 的迁移 071 + `R__stage85_direct_access.sql` 与 `InboxSourceRouter`/`LiveRadarFrameMapper`、E2 的迁移 072；
 * 它们落地后按文末 TODO 补齐并重跑。现在就写死这些断言只会让本类在迁移落地前整体失败，掩盖真正的回归。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage85PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage85PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage85PostgresTest 未在真实 PostgreSQL 上执行")
class Stage85PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 7, 12, 0, 0, 0, ZoneOffset.UTC);

    /** 契约 §2 的来源类型码表与 070 新增三行：口径统一在 {@link SourceTypeCatalogFixture}（阶段 10.3）。 */
    private static final List<String> SOURCE_TYPES = SourceTypeCatalogFixture.EXPECTED_TYPES;
    private static final List<String> STAGE85_SOURCE_TYPES = SourceTypeCatalogFixture.STAGE85_TYPES;
    private static final String LINGYUN_SPEC = "设备资料/凌云协议/协议A-设备数据及感知数据接入协议v8.6.pdf";

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired FusionInboxRepository inbox;
    @Autowired AppClock clock;
    @Autowired FusedTrackRepository fusedTracks;
    @Autowired RuleEngineRepository ruleEngine;
    @Autowired FusionReplayRunner replayRunner;
    @Autowired FusionPipeline pipeline;
    @Autowired FusionReplayDatasetGenerator datasetGenerator;
    @Autowired FusionProperties fusionProperties;
    @Autowired ObservationReadRepository observationReads;
    @Autowired TargetReadRepository targetReads;

    private String suffix;

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
        // 摄取 Worker 不按节拍跑：并发领取用例要自己控制时机，调度器插一脚会让断言不可复现。
        registry.add("app.fusion.enabled", () -> "false");
        registry.add("app.fusion.replay.run-on-start", () -> "false");
        // 实测提升默认关：开关打开的分支由专门的用例用独立上下文验证（见文末 TODO）。
        registry.add("app.fusion.live-promotion.enabled", () -> "false");
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
    }

    /** 迁移基线：070 已应用、无失败迁移、PostGIS 可用。后面所有断言都以此为前提。 */
    @Test
    @Order(1)
    void stage85MigrationsAreAppliedToIsolatedPostgresSchema() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class))
                .as("有失败迁移时后续断言全部没有意义").isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version=?", Long.class, "202609050070"))
                .as("迁移 070（阶段 8.5 目录扩展）必须已应用").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select extversion from pg_extension where extname='postgis'", String.class)).isNotBlank();
        // 阶段 8 的只增触发器在 8.5 迁移之后仍在位：新迁移不得顺手放开历史证据的改写。
        for (String trigger : List.of("trg_stage8_lineage_append_only", "trg_stage7_rule_evaluation_append_only")) {
            assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgname=? and not tgisinternal", Long.class, trigger))
                    .as(trigger + " 必须仍然存在").isEqualTo(1L);
        }
    }

    /**
     * 迁移 070 的来源类型目录。三行新来源（AOA/DCD/RID）**必须仍是 DEMO**：凌云协议给了字段但没联调，
     * 标成 CONFIRMED 等于宣称这三路已经验证过。`spec_ref` 指向协议 A，让页面上的"演示值"能追到出处。
     */
    @Test
    @Order(4)
    void sourceTypeCatalogCarriesTheThreeLingyunSourcesAsDemo() {
        // 八行目录、只有雷达 CONFIRMED、其余 DEMO：在真实 PG 上按同一夹具口径断言。
        SourceTypeCatalogFixture.assertCatalog(jdbc);
        for (String type : STAGE85_SOURCE_TYPES) {
            Map<String, Object> row = jdbc.queryForMap("select schema_status,spec_ref,display_name from source_type_catalog where source_type=?", type);
            assertThat(row).as(type + " 未经联调，不得标 CONFIRMED").containsEntry("schema_status", "DEMO");
            assertThat((String) row.get("spec_ref")).as(type + " 的字段出处必须可追溯").isEqualTo(LINGYUN_SPEC);
            assertThat((String) row.get("display_name")).isNotBlank();
        }
        // 070 顺带把四个既有来源的 spec_ref 指向协议 A：只补空值，不覆盖雷达已确认的出处。
        assertThat(jdbc.queryForObject("select spec_ref from source_type_catalog where source_type='RADAR'", String.class))
                .as("雷达的出处是雷达协议，070 不得覆盖").doesNotContain("凌云");
        assertThat(jdbc.queryForList("select source_type from source_type_catalog where spec_ref=? order by source_type", String.class, LINGYUN_SPEC))
                .containsExactly("AOA", "DCD", "EO", "FIVE_G_A", "FUSION_BOX", "RID", "TDOA");
        assertThat(jdbc.queryForObject("select count(*) from source_type_catalog where spec_ref is null", Long.class))
                .as("每种来源都要能追到协议出处").isZero();
    }

    /**
     * 迁移 070 重写了 `fusion_config demo-v1` 的整块 JSON（H2/PG 没有共同的 JSON 局部更新语法）。
     * 整块重写最容易出的事故是"改了三项、丢了三十项"，所以这里逐项核对而不是只看新增的三个。
     * 另外阶段 8 踩过 H2/PG 的 JSON 回读差异（H2 回带引号的字符串），PG 侧必须能直接 `cast(... as text)` 解析。
     */
    @Test
    @Order(5)
    void fusionConfigParametersSurviveTheWholeJsonRewriteOnPostgres() throws Exception {
        Map<String, Object> config = jdbc.queryForMap("select status,schema_status,note,cast(params as text) as params_text"
                + " from fusion_config where config_version='demo-v1'");
        assertThat(config).containsEntry("status", "ACTIVE").containsEntry("schema_status", "DEMO");
        assertThat((String) config.get("note")).as("阈值未经算法方确认，必须自述为 DEMO").contains("DEMO");

        JsonNode params = json.readTree((String) config.get("params_text"));
        assertThat(params.isObject()).as("PG 上 JSON 列按文本回读即可解析，不像 H2 会包一层字符串").isTrue();
        JsonNode accuracy = params.path("filter").path("accuracy_default_m");
        JsonNode weights = params.path("weights");
        for (String type : SOURCE_TYPES) {
            assertThat(accuracy.has(type)).as(type + " 缺省精度缺失会让融合退回裸阈值").isTrue();
            assertThat(accuracy.path(type).asDouble()).as(type + " 缺省精度").isPositive();
            assertThat(weights.has(type)).as(type + " 权重缺失").isTrue();
            for (String dimension : List.of("position", "motion", "class", "identity")) {
                assertThat(weights.path(type).has(dimension)).as(type + "." + dimension).isTrue();
            }
        }
        // AOA 只给方位不给位置：位置权重必须是 0，否则一个没有位置的来源会把融合位置往自己身上拉。
        assertThat(weights.path("AOA").path("position").asDouble()).as("AOA 不产出位置，位置权重必须为 0").isZero();
        assertThat(weights.path("AOA").path("identity").asDouble()).as("AOA 的价值在身份维度").isPositive();
        // 070 之前就存在的四组阈值不得在整块重写里丢失（抽三处代表：关联门限、身份升级、降级口径）。
        assertThat(params.path("association").path("gate_sigma").asDouble()).isEqualTo(3.0);
        assertThat(params.path("identity").path("tentative_to_stable_hits").asInt()).isEqualTo(3);
        assertThat(params.path("degradation").path("three_source_min").asInt()).isEqualTo(3);
        assertThat(params.path("quality").path("anomaly_zscore").asDouble()).isEqualTo(4.0);
    }

    /**
     * `inbox_message` 是直连切片的唯一入口，四个前缀共用一张表。这里先钉住**表级前提**：
     * `(source, source_msg_id)` 唯一（契约 §2 的去重键）、`payload_hash` 的十六进制 CHECK、
     * `processed_at` 与终态的成对 CHECK。前缀白名单与并发领取等 E1 的 `claim` 改动落地后再补（见文末 TODO）。
     */
    @Test
    @Order(6)
    void inboxEnvelopeConstraintsHoldForTheFourDirectAccessPrefixes() {
        String deviceId = "dev-" + suffix;
        for (String source : List.of("replay:" + deviceId, "lingyun:radar:" + deviceId, "eo-edge:" + deviceId, "live-radar:" + deviceId)) {
            insertInbox(source, "msg-1", "{\"probe\":1}");
            // 同 (source, source_msg_id) 再来一条即重复报文：契约要求丢弃而不是入库两份。
            assertThat(catching(() -> insertInbox(source, "msg-1", "{\"probe\":2}")))
                    .as(source + " 的去重键必须是 (source, source_msg_id)").isNotNull();
            // 同前缀不同 msg_id 是新报文，必须能进。
            insertInbox(source, "msg-2", "{\"probe\":3}");
        }
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like ?", Long.class, "%" + deviceId)).isEqualTo(8L);

        // 探针行用完即收尾：它们是只为验唯一键造的假报文（payload 是 {"probe":n}），
        // 不收尾的话后面的端到端用例会把它们一起领走，然后因为"这不是一条真报文"而失败——
        // 那种红是夹具噪声，不是缺陷，会掩盖真正的映射问题。
        for (String inboxId : jdbc.queryForList("select inbox_id from inbox_message where source like ?", String.class, "%" + deviceId)) {
            inbox.done(inboxId, T0.toInstant().toEpochMilli());
        }

        // payload_hash 必须是 64 位小写十六进制：契约要求 SHA-256，写别的等于让去重键失去意义。
        assertThat(catching(() -> jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,source_id,payload,payload_hash,status,received_at)"
                + " values (?,?,?,?,cast(? as jsonb),?,'RECEIVED',?)", id(), "live-radar:" + deviceId, "bad-hash", sourceId(),
                "{\"probe\":4}", "NOT-A-SHA256", T0.toInstant().toEpochMilli())))
                .as("payload_hash 走 ck_stage2_inbox_payload_hash").isNotNull();
    }

    /**
     * 实测雷达提升端口的写入行为。端口只产出 inbox 信封，所以在 PG 上验的正是信封的三个键与去重语义；
     * 语义映射属于 `LiveRadarFrameMapper`（E1），不在这里重复。
     *
     * 直接 new 而不是注入：本类的上下文按 `live-promotion.enabled=false` 起（并发用例不想被调度器插一脚），
     * 端口那时不注册。开关两侧的 Bean 互斥性由 `ProductionStage85SeedIsolationTest` 单独钉。
     */
    @Test
    @Order(7)
    void liveRadarPortWritesEnvelopesOnlyAndRejectsUnregisteredDevices() {
        String deviceCode = "RADAR-S85-" + suffix;
        String registered = registerDevice(deviceCode);
        LiveRadarSourceObservationPort port = new LiveRadarSourceObservationPort(inbox, jdbc, clock, json);

        long observationsBefore = jdbc.queryForObject("select count(*) from source_observation", Long.class);
        long targetsBefore = jdbc.queryForObject("select count(*) from target", Long.class);
        port.accept(List.of(radarFrame(deviceCode, 1_000L, 7)));

        Map<String, Object> envelope = jdbc.queryForMap("select source,source_msg_id,source_id,status,payload_hash,"
                + "cast(payload as text) as payload_text from inbox_message where source=?", "live-radar:" + deviceCode);
        assertThat(envelope).containsEntry("source_msg_id", "1000:7").containsEntry("source_id", registered)
                .containsEntry("status", "RECEIVED");
        assertThat((String) envelope.get("payload_hash")).as("契约要求 SHA-256 十六进制").matches("^[0-9a-f]{64}$");
        assertThat((String) envelope.get("payload_text")).as("payload 必须原样透传，端口不替设备改写任何字段")
                .contains("\"external_track_id\"").contains("\"z_m\"");
        // 端口**只写信封**：业务表一行都不能多。映射与写入是管线领取之后的事。
        assertThat(jdbc.queryForObject("select count(*) from source_observation", Long.class)).isEqualTo(observationsBefore);
        assertThat(jdbc.queryForObject("select count(*) from target", Long.class)).isEqualTo(targetsBefore);

        // 同键同哈希重复上报：丢弃，不入第二条，也不抛给 A。
        port.accept(List.of(radarFrame(deviceCode, 1_000L, 7)));
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=?", Long.class, "live-radar:" + deviceCode))
                .as("重复帧不是新事实").isEqualTo(1L);
        // 同设备不同帧号是新报文。
        port.accept(List.of(radarFrame(deviceCode, 1_000L, 8)));
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=?", Long.class, "live-radar:" + deviceCode)).isEqualTo(2L);

        // 未注册设备：拒收，且**不自动建来源行**——凭一条上报凭空建来源等于让未注册设备混进统一目标库。
        port.accept(List.of(radarFrame("RADAR-UNKNOWN-" + suffix, 2_000L, 1)));
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like ?", Long.class,
                "live-radar:RADAR-UNKNOWN-%")).as("未注册设备的帧不得入库").isZero();
        assertThat(jdbc.queryForObject("select count(*) from integration_source where source_code like ?", Long.class,
                "RADAR-UNKNOWN-%")).as("端口不得自动建来源行").isZero();

        // 已停用的设备同样拒收：停用是运行期的明确决定，不能被一条上报绕过。
        String disabled = registerDevice("RADAR-OFF-" + suffix);
        jdbc.update("update integration_source set enabled=false where source_id=?", disabled);
        port.accept(List.of(radarFrame("RADAR-OFF-" + suffix, 3_000L, 1)));
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=?", Long.class,
                "live-radar:RADAR-OFF-" + suffix)).isZero();

        // 信封级校验：缺 device_id / frame_id、items 不是数组，一律拒收且不影响同批其他帧。
        long before = jdbc.queryForObject("select count(*) from inbox_message", Long.class);
        port.accept(List.of(
                Map.of("boot_micros", 4_000L, "frame_id", 1, "items", List.of()),                       // 缺 device_id
                Map.of("device_id", deviceCode, "boot_micros", 4_000L, "items", List.of()),             // 缺 frame_id
                Map.of("device_id", deviceCode, "boot_micros", 4_000L, "frame_id", 2, "items", "none"), // items 不是数组
                radarFrame(deviceCode, 4_000L, 3)));                                                     // 这条合规
        assertThat(jdbc.queryForObject("select count(*) from inbox_message", Long.class))
                .as("三条不合规被拒、一条合规入库；一帧不合规不该让整批上报失败").isEqualTo(before + 1);

        // 空 items 是合法的"本帧无目标"，不是不合规：拒了它等于让雷达没法上报"这一帧我什么都没看到"。
        port.accept(List.of(radarFrame(deviceCode, 5_000L, 1, List.of())));
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=? and source_msg_id='5000:1'",
                Long.class, "live-radar:" + deviceCode)).isEqualTo(1L);
    }

    /**
     * E1 迁移 071 + `R__stage85_direct_access.sql`：飞手位置两列的 WGS-84 约束与 GIST。
     *
     * **按机制分别断言**，不只看"抛没抛错"：列类型是 `GEOMETRY(POINT,4326)`，所以 SRID 不符与几何种类不符
     * 由**列的 typmod** 挡（报 `does not match column SRID` / `does not match column type`），
     * 经纬越界才轮到 `ck_stage85_*_pilot_location_wgs84` 挡。阶段 9 的教训：混在一条 assertThatThrownBy 里，
     * 将来谁把 CHECK 删了也照样"通过"——因为 typmod 仍然会抛。
     */
    @Test
    @Order(8)
    void pilotLocationGeometryConstraintsAndIndexesHoldOnPostgis() {
        String targetId = insertTarget();
        // 合法点：写得进、取得回，坐标不被改写。
        jdbc.update("update target_latest_state set pilot_location=ST_GeomFromEWKT(?) where target_id=?",
                "SRID=4326;POINT (100.6199544 20.4200215)", targetId);
        assertThat(jdbc.queryForObject("select ST_X(pilot_location) from target_latest_state where target_id=?", Double.class, targetId))
                .isEqualTo(100.6199544);
        assertThat(jdbc.queryForObject("select ST_Y(pilot_location) from target_latest_state where target_id=?", Double.class, targetId))
                .isEqualTo(20.4200215);
        assertThat(jdbc.queryForObject("select ST_SRID(pilot_location) from target_latest_state where target_id=?", Integer.class, targetId))
                .isEqualTo(4326);
        // 飞手位置与目标位置是**两个不同的点**：C02-6 要拿它们算大圆距离，挤在一列就没法判了。
        jdbc.update("update target_latest_state set location=ST_GeomFromEWKT(?) where target_id=?",
                "SRID=4326;POINT (100.7 20.5)", targetId);
        assertThat(jdbc.queryForObject("select ST_Equals(location, pilot_location) from target_latest_state where target_id=?",
                Boolean.class, targetId)).as("两列各存各的点").isFalse();

        // ① SRID 不符：由列 typmod 挡，报错文案里点名 SRID。
        assertThat(catching(() -> jdbc.update("update target_latest_state set pilot_location=ST_SetSRID(ST_MakePoint(100.6,20.4),3857) where target_id=?", targetId)))
                .as("SRID 3857 应由列 typmod 拒").isNotNull()
                .hasMessageContaining("does not match column SRID");
        // ② 几何种类不符：同样是 typmod，不是 CHECK。
        assertThat(catching(() -> jdbc.update("update target_latest_state set pilot_location=ST_GeomFromEWKT('SRID=4326;LINESTRING(100.6 20.4,100.7 20.5)') where target_id=?", targetId)))
                .as("LINESTRING 应由列 typmod 拒").isNotNull()
                .hasMessageContaining("does not match column type");
        // ③ 经纬越界：这才是 R__stage85 的 CHECK 该管的事。
        assertThat(catching(() -> jdbc.update("update target_latest_state set pilot_location=ST_SetSRID(ST_MakePoint(200,20.4),4326) where target_id=?", targetId)))
                .as("经度 200 应由 ck_stage85_target_state_pilot_location_wgs84 拒").isNotNull()
                .hasMessageContaining("ck_stage85_target_state_pilot_location_wgs84");
        assertThat(catching(() -> jdbc.update("update target_latest_state set pilot_location=ST_SetSRID(ST_MakePoint(100.6,95),4326) where target_id=?", targetId)))
                .as("纬度 95 应由 CHECK 拒").isNotNull()
                .hasMessageContaining("ck_stage85_target_state_pilot_location_wgs84");
        // 被拒之后原值不变：坏点一次都不能落地。
        assertThat(jdbc.queryForObject("select ST_X(pilot_location) from target_latest_state where target_id=?", Double.class, targetId))
                .isEqualTo(100.6199544);

        // 观测层同一把尺子，但**只能用 INSERT 验**：`source_observation` 是只增表（阶段 8 的证据底座），
        // UPDATE/DELETE 被触发器拒。用 UPDATE 试约束会先撞上只增触发器，测到的就不是几何约束了。
        String observationId = insertObservation("no-pilot", null, null);
        assertThat(jdbc.queryForObject("select count(*) from source_observation where observation_id=? and pilot_location is null",
                Long.class, observationId)).as("没有飞手信息就留空，不补 (0,0)").isEqualTo(1L);
        String withPilot = insertObservation("with-pilot", "SRID=4326;POINT (100.60 20.40)", "SENSE_DATA");
        assertThat(jdbc.queryForObject("select ST_X(pilot_location) from source_observation where observation_id=?", Double.class, withPilot))
                .isEqualTo(100.60);
        assertThat(catching(() -> insertObservation("bad-srid", "SRID=3857;POINT (118.6 37.4)", null)))
                .isNotNull().hasMessageContaining("does not match column SRID");
        assertThat(catching(() -> insertObservation("out-of-range", "SRID=4326;POINT (-181 37.4)", null)))
                .isNotNull().hasMessageContaining("ck_stage85_observation_pilot_location_wgs84");

        // class_source 的字典：四个取值可写，字典外一律拒（它决定这个类别结论有多可信），空值合法。
        for (String valid : List.of("SENSE_DATA", "EO_TRACKING", "RADAR", "MANUAL")) {
            assertThat(insertObservation("cs-" + valid, null, valid)).isNotBlank();
        }
        assertThat(catching(() -> insertObservation("cs-bad", null, "GUESSED")))
                .as("字典外的类别来源必须被拒").isNotNull().hasMessageContaining("ck_stage85_observation_class_source");

        // 只增：飞手位置与类别来源写错了也只能新增一条修正观测，不能回头改历史证据。
        assertThat(catching(() -> jdbc.update("update source_observation set class_source='MANUAL' where observation_id=?", withPilot)))
                .as("source_observation 是只增表").isNotNull();
        assertThat(catching(() -> jdbc.update("delete from source_observation where observation_id=?", withPilot))).isNotNull();

        // 两个 GIST 索引：飞手位置会被按范围检索（"这一带有哪些飞手"），没有索引就是全表扫。
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname=? and indexname in "
                + "('idx_stage85_observation_pilot_location_gist','idx_stage85_target_state_pilot_location_gist') order by indexname",
                String.class, SCHEMA))
                .containsExactly("idx_stage85_observation_pilot_location_gist", "idx_stage85_target_state_pilot_location_gist");
    }

    /**
     * E2 的飞手位置写入与读取，在真实 PostGIS 上跑**它自己的代码**（此前只在临时 schema 里验过 SQL 语义）。
     * 覆盖 `FusedTrackRepository.upsertLatestState` 的 INSERT 与 UPDATE 两条路径、写回 NULL，
     * 以及 `RuleEngineRepository.latestState` 的 PG 分支把两个几何列分别取回。
     *
     * <p><b>顺带钉住一个静默失效点</b>：三份 `locationColumns` 都靠构造时的 `postgis` 布尔选分支，
     * H2 分支靠解析 `CAST(几何列 AS VARCHAR)`。而 PG 上那个表达式回的是 <b>EWKB 十六进制</b>（决策 8-23）。
     * 所以标志一旦在 PG 上误判为 false，坐标解析不出来 → 飞手位置**静默变成"缺失"**，不报错、日志不留痕，
     * 只表现为"C02-6 老是 UNDETERMINED"。本用例同时断言"原始文本是十六进制"与"E2 的读取仍拿得到坐标"，
     * 两条一起才说明走的确实是 PostGIS 分支。
     */
    @Test
    @Order(9)
    void pilotLocationSurvivesTheFusedWriterAndTheRuleEngineReaderOnPostgis() {
        // INSERT 路径：新目标还没有 target_latest_state 行。
        String targetId = "s85-target-fused-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,created_at,updated_at,version)"
                + " values (?,?,'UAV',?,?,'live',?,?,0)", targetId, "目标-S85F-" + suffix, T0, T0, T0, T0);
        fusedTracks.upsertLatestState(latestState(targetId, 100.7, 20.5, 100.6199544, 20.4200215));
        assertThat(jdbc.queryForObject("select count(*) from target_latest_state where target_id=?", Long.class, targetId))
                .as("INSERT 路径应建出最新状态行").isEqualTo(1L);

        RuleEngineRepository.StateRow inserted = ruleEngine.latestState(targetId);
        assertThat(inserted).isNotNull();
        assertThat(inserted.pilotLongitude()).as("规则引擎读到的飞手经度").isEqualByComparingTo("100.6199544");
        assertThat(inserted.pilotLatitude()).isEqualByComparingTo("20.4200215");
        assertThat(inserted.longitude()).as("目标位置与飞手位置各归各列，互不串台").isEqualByComparingTo("100.7");
        assertThat(inserted.latitude()).isEqualByComparingTo("20.5");

        // 这两条一起证明走的是 PostGIS 分支：原始文本形态是 EWKB 十六进制，而读取仍拿得到坐标。
        assertThat(jdbc.queryForObject("select cast(pilot_location as varchar) from target_latest_state where target_id=?", String.class, targetId))
                .as("PG 上几何列的文本形态是 EWKB 十六进制，不是 EWKT——H2 分支在这里会静默解析失败")
                .matches("^[0-9A-F]+$").doesNotContain("POINT");

        // UPDATE 路径：同一目标再写一次，坐标随之更新。
        fusedTracks.upsertLatestState(latestState(targetId, 100.71, 20.51, 100.61, 20.41));
        RuleEngineRepository.StateRow updated = ruleEngine.latestState(targetId);
        assertThat(updated.pilotLongitude()).isEqualByComparingTo("100.61");
        assertThat(updated.pilotLatitude()).isEqualByComparingTo("20.41");

        // 写回 NULL：飞手位置每帧重写，身份主源这一帧没给就必须清空。
        // 留着上一帧的旧值会让 C02-6 拿过期位置判超视距——那比判不出来更危险，因为它会给出一个看起来确定的结论。
        fusedTracks.upsertLatestState(latestState(targetId, 100.72, 20.52, null, null));
        assertThat(jdbc.queryForObject("select count(*) from target_latest_state where target_id=? and pilot_location is null",
                Long.class, targetId)).as("没有飞手位置就写回 NULL，不留上一帧的值").isEqualTo(1L);
        RuleEngineRepository.StateRow cleared = ruleEngine.latestState(targetId);
        assertThat(cleared.pilotLongitude()).as("读侧看到的是缺失，不是 0，也不是过期坐标").isNull();
        assertThat(cleared.pilotLatitude()).isNull();
        assertThat(cleared.longitude()).as("清空飞手位置不该影响目标位置").isEqualByComparingTo("100.72");
    }

    private FusedTrackRepository.LatestState latestState(String targetId, Double lon, Double lat, Double pilotLon, Double pilotLat) {
        return new FusedTrackRepository.LatestState(targetId, lon, lat, null, null, null, null, null, null,
                T0, T0, "[]", T0, pilotLon, pilotLat);
    }

    /**
     * 凌云 v2 数据集在真实 PostGIS 上跑完整条管线（E1 的 `InboxSourceRouter` + 四个映射器 + 阶段 8 融合）。
     * 这是本类唯一一条端到端用例：前面的用例证明"每块砖是好的"，这条证明"垒起来的墙站得住"。
     */
    @Test
    @Order(2)
    void lingyunDatasetV2FlowsThroughTheWholePipelineOnPostgis() throws Exception {
        seedDirectAccessSources();
        replayRunner.loadV2();
        drainWithDiagnostics();

        // 四个直连前缀的信封都进了库，且没有失败帧——有 FAILED 就说明映射炸了，后面的断言都没意义。
        String v2Sources = inClause(FusionReplayDatasetGenerator.INBOX_SOURCES.values());
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source in " + v2Sources, Long.class))
                .as("v2 数据集应已落库").isPositive();
        assertThat(jdbc.queryForList("select source,source_msg_id,last_error from inbox_message where status='FAILED'"))
                .as("有失败帧就说明映射炸了").isEmpty();

        // 决策 8.5-23：每行 payload_hash 必须等于 payload 的 SHA-256。
        // **不能在 SQL 里算**：payload 列是 jsonb，PG 会重排键（按长度再按字节，不是字典序）并在冒号后加空格，
        // 例如 {"bbb":1,"a":2,"cc":3} 存进去回读是 {"a": 2, "cc": 3, "bbb": 1}；而哈希是对写入前那份
        // 「字典序、无空格」的规范化 JSON 算的。所以在 Java 侧按同一规范化重算，逐行比对。
        Map<String, String> expected = expectedV2Hashes();
        assertThat(expected).as("数据集里应有 v2 记录").isNotEmpty();
        List<Map<String, Object>> stored = jdbc.queryForList("select source,source_msg_id,payload_hash from inbox_message"
                + " where source in " + v2Sources);
        assertThat(stored).as("落库行数应与数据集记录数一致").hasSize(expected.size());
        for (Map<String, Object> row : stored) {
            String key = row.get("source") + "#" + row.get("source_msg_id");
            assertThat(row.get("payload_hash")).as("payload_hash 与 payload 不一致：" + key).isEqualTo(expected.get(key));
        }

        // 重跑数据集不重复入库：哈希的用处正在于此（同键同哈希幂等）。
        long before = jdbc.queryForObject("select count(*) from inbox_message", Long.class);
        replayRunner.loadV2();
        assertThat(jdbc.queryForObject("select count(*) from inbox_message", Long.class)).as("重跑不得写入第二份").isEqualTo(before);

        // 一个物理目标只产出一个 unified 目标：关联做不好就会一物多目标，这是融合的第一条底线。
        List<Map<String, Object>> perExternal = jdbc.queryForList("select o.external_target_id, count(distinct l.target_id) as targets"
                + " from source_observation o join target_source_link l on l.source_id=o.source_id and l.external_target_id=o.external_target_id"
                + " group by o.external_target_id order by o.external_target_id");
        assertThat(perExternal).isNotEmpty();
        assertThat(perExternal).allSatisfy(row -> assertThat(((Number) row.get("targets")).intValue())
                .as("外部目标 " + row.get("external_target_id") + " 被拆成了多个统一目标").isEqualTo(1));

        // TDOA 的 tdoa-pilot 场景带 pilotLon/pilotLat：必须落到 source_observation.pilot_location。
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='D-PILOT' and pilot_location is not null",
                Long.class)).as("tdoa-pilot 场景的飞手位置必须落库（C02-6 的输入）").isPositive();

        // AOA 只给方位不给位置：位置必须为空，方位进 quality.bearing_deg。
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='A-BEARING' and location is not null",
                Long.class)).as("AOA 不产出位置，补一个坐标等于凭空造证据").isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='A-BEARING'"
                + " and quality ? 'bearing_deg'", Long.class)).as("AOA 的方位必须留在 quality 里").isPositive();

        // 255 = 识别中：class_code 必须为空并在 quality 记 identifying，而不是塞一个 UNKNOWN 冒充结论。
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='R-IDENT' and class_code is not null",
                Long.class)).as("objectType=255 是“还在识别”，不是一个类别").isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where external_target_id='R-IDENT'"
                + " and cast(quality->>'identifying' as boolean)", Long.class)).isPositive();

        // 光电只在 BeginTracking 期间有观测：EndTracking / HeartBeat 不产观测（但帧仍 DONE）。
        long eoObservations = jdbc.queryForObject("select count(*) from source_observation where external_target_id='T-EO-TRACK'", Long.class);
        long eoFrames = jdbc.queryForObject("select count(*) from inbox_message where source=?", Long.class,
                FusionReplayDatasetGenerator.INBOX_SOURCES.get(FusionReplayDatasetGenerator.EO));
        assertThat(eoObservations).as("跟踪期间应有光电观测").isPositive();
        assertThat(eoObservations).as("光电只在跟踪期间有观测，心跳与结束事件不产观测").isLessThan(eoFrames);
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=? and status<>'DONE'", Long.class,
                FusionReplayDatasetGenerator.INBOX_SOURCES.get(FusionReplayDatasetGenerator.EO)))
                .as("不产观测不等于失败：这些帧仍要正常收尾").isZero();

        // E1 先验清单 ③：quality 的结构化字段在 jsonb 上读回是对象/数值，不是被转义的字符串。
        assertThat(jdbc.queryForObject("select count(*) from source_observation where jsonb_typeof(quality)<>'object'", Long.class))
                .as("quality 整体必须是 jsonb 对象").isZero();
        for (String key : List.of("speed_xyz")) {
            List<String> types = jdbc.queryForList("select distinct jsonb_typeof(quality->'" + key + "') from source_observation"
                    + " where quality ? '" + key + "'", String.class);
            if (!types.isEmpty()) {
                assertThat(types).as(key + " 应是结构化对象，不是被转义的字符串").doesNotContain("string");
            }
        }
        List<String> bearingTypes = jdbc.queryForList("select distinct jsonb_typeof(quality->'bearing_deg') from source_observation"
                + " where quality ? 'bearing_deg'", String.class);
        assertThat(bearingTypes).as("方位角是数值，不是字符串").containsExactly("number");
    }

    /**
     * 自己铺来源与设备，然后 `loadV2()` + 逐帧处理。**不复用种子的 `run()`**：它是 @Transactional 且失败即整体回滚，
     * 一旦某一帧映射不了，事务回滚会把现场一并抹掉，只留下一句异常，定位不到是哪条报文。
     * 这里逐帧 try/catch 并把失败的 (source, msg_id, 原因, payload 片段) 一并带进断言消息。
     */
    private void drainWithDiagnostics() {
        List<String> failures = new java.util.ArrayList<>();
        for (int round = 0; round < 1000; round++) {
            List<FusionInboxRepository.InboxRow> rows = inbox.claim(System.currentTimeMillis(), 50, 30_000L);
            if (rows.isEmpty()) break;
            for (FusionInboxRepository.InboxRow row : rows) {
                try {
                    pipeline.processFrame(row);
                    inbox.done(row.inboxId(), System.currentTimeMillis());
                } catch (RuntimeException failure) {
                    inbox.fail(row.inboxId(), System.currentTimeMillis(), String.valueOf(failure.getMessage()));
                    String payload = row.payloadJson() == null ? "<null>" : row.payloadJson();
                    failures.add(row.source() + "#" + row.sourceMsgId() + " → " + failure.getMessage()
                            + " | payload=" + payload.substring(0, Math.min(400, payload.length())));
                }
            }
        }
        assertThat(failures).as("以下直连报文没能通过映射：\n" + String.join("\n", failures)).isEmpty();
    }

    private void seedDirectAccessSources() {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version)"
                + " select ?,?,?,true,0,0,0 where not exists (select 1 from app_org where org_id=?)",
                LocalStage8FusionReplaySeeder.ORG, "DEMO-PLATFORM", "演示平台机构", LocalStage8FusionReplaySeeder.ORG);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version)"
                + " select ?,?,?,true,0,0,0 where not exists (select 1 from app_district where district_id=?)",
                LocalStage8FusionReplaySeeder.DISTRICT, "DEMO-DONGYING", "东营演示区域", LocalStage8FusionReplaySeeder.DISTRICT);
        for (LocalStage8FusionReplaySeeder.SourceSeed seed : LocalStage8FusionReplaySeeder.SOURCES) {
            jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version)"
                    + " select ?,?,?,true,'replay',?,?,?,0 where not exists (select 1 from integration_source where source_id=?)",
                    seed.sourceId(), seed.sourceCode(), seed.name(), seed.sourceType(), T0, T0, seed.sourceId());
            jdbc.update("insert into device (device_id,source_id,external_device_id,device_no,name,device_type_code,enabled,source_mode,"
                    + "owner_org_id,district_id,created_at,updated_at,version)"
                    + " select ?,?,?,?,?,?,true,'replay',?,?,?,?,0 where not exists (select 1 from device where device_id=?)",
                    seed.deviceId(), seed.sourceId(), "external-" + seed.sourceCode(), seed.deviceNo(), seed.name(), seed.sourceType(),
                    LocalStage8FusionReplaySeeder.ORG, LocalStage8FusionReplaySeeder.DISTRICT, T0, T0, seed.deviceId());
        }
    }

    /** 按 `FusionReplayRunner.payloadJson` 的同一规范化（键字典序、无空格）重算 v2 每条记录的 SHA-256。 */
    private Map<String, String> expectedV2Hashes() throws Exception {
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        for (var record : datasetGenerator.generateV2().records()) {
            String payload = json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(new java.util.TreeMap<>(record.payload()));
            expected.put(record.inboxSource() + "#" + record.recordNo(), sha256(payload));
        }
        return expected;
    }

    /**
     * 三份 `locationColumns` 的 PG 分支必须**全都**走 PostGIS 路径（领导交办）。用例 7 已钉住 `RuleEngineRepository`，
     * 这一条补上另外两份：`ObservationReadRepository` 与 `TargetReadRepository`。
     *
     * <p>判据同样是"两条一起"：先确认同一行几何列的文本形态是 EWKB 十六进制（H2 分支在这里必然解析失败），
     * 再确认这两份仓库仍然回出坐标。只断言后者的话，`postgis` 布尔一旦误判为 false，
     * 坐标会静默变成"缺失"——不报错、日志不留痕，只表现为页面不画点、C02-6 老是 UNDETERMINED。
     */
    @Test
    @Order(3)
    void allThreeReadRepositoriesTakeThePostgisBranchAndReturnCoordinates() {
        seedDirectAccessSources();
        replayRunner.loadV2();
        drainWithDiagnostics();

        // 找一条既有观测位置又有飞手位置的目标：tdoa-pilot 场景就是为此造的。
        String targetId = jdbc.queryForObject("select l.target_id from source_observation o"
                + " join target_source_link l on l.source_id=o.source_id and l.external_target_id=o.external_target_id"
                + " where o.external_target_id='D-PILOT' and o.pilot_location is not null limit 1", String.class);
        assertThat(targetId).as("tdoa-pilot 场景应产出带飞手位置的观测").isNotBlank();

        // 前提：这一行的几何列文本形态确实是 EWKB 十六进制（不是 EWKT），H2 分支在 PG 上必然拿不到坐标。
        assertThat(jdbc.queryForObject("select cast(pilot_location as varchar) from source_observation"
                + " where external_target_id='D-PILOT' and pilot_location is not null limit 1", String.class))
                .matches("^[0-9A-F]+$").doesNotContain("POINT");

        // ① ObservationReadRepository：观测位置与飞手位置都要回出数值。
        List<ObservationReadRepository.ObservationRow> observations = observationReads.listObservations(
                targetId, new ObservationReadRepository.ObservationQuery(null, null, null), 0, 200);
        assertThat(observations).as("目标应有观测").isNotEmpty();
        assertThat(observations).anySatisfy(row -> {
            assertThat(row.pilotLongitude()).as("飞手经度必须是数值，不是 null——若为 null 说明走了 H2 分支").isNotNull();
            assertThat(row.pilotLatitude()).isNotNull();
        });
        assertThat(observations).anySatisfy(row -> assertThat(row.longitude()).as("观测位置也要回出坐标").isNotNull());
        // 类别来源随观测一起下发：页面要能解释"这个类别是谁给的"。
        assertThat(observations.stream().map(ObservationReadRepository.ObservationRow::classSource).filter(java.util.Objects::nonNull).toList())
                .as("v2 数据集的观测应带 class_source").isNotEmpty();

        // ② TargetReadRepository：最新状态里的目标位置与飞手位置同理。
        TargetReadRepository.TargetRow target = targetReads.findTarget(targetId, new AccessDecision("s85-reader-" + suffix, ScopeMode.ALL));
        assertThat(target).isNotNull();
        assertThat(target.location()).as("目标位置必须回出坐标").isNotNull();
        assertThat(target.location().longitude()).isNotNull();
        // 最新状态里的飞手位置：这里**自己写入**再读，而不是依赖融合选源的结果。
        // 这条用例要回答的是"读侧在 PG 上取不取得到坐标"，把它绑到选源的产出上会让两件事互相掩盖：
        // 选源一变（决策 8.5-27 之后就变过一次），这条读路径用例就会跟着红，而问题根本不在读侧。
        // 所以用 E2 自己的写入方法把状态摆好再读——写入路径另有用例 9 与 14 覆盖。
        BigDecimal observationPilotLon = jdbc.queryForObject("select ST_X(pilot_location) from source_observation"
                + " where external_target_id='D-PILOT' and pilot_location is not null limit 1", BigDecimal.class);
        BigDecimal observationPilotLat = jdbc.queryForObject("select ST_Y(pilot_location) from source_observation"
                + " where external_target_id='D-PILOT' and pilot_location is not null limit 1", BigDecimal.class);
        fusedTracks.upsertLatestState(new FusedTrackRepository.LatestState(targetId, 118.62, 37.42, null, null, null, null,
                null, null, T0, T0, "[]", T0, observationPilotLon.doubleValue(), observationPilotLat.doubleValue()));
        assertThat(jdbc.queryForObject("select count(*) from target_latest_state where target_id=? and pilot_location is not null",
                Long.class, targetId)).isEqualTo(1L);

        // 写完之后重新读一次：上面那次 findTarget 发生在写入之前，拿不到飞手位置是理所当然的。
        TargetReadRepository.TargetRow withPilotState = targetReads.findTarget(targetId, new AccessDecision("s85-reader-" + suffix, ScopeMode.ALL));
        assertThat(withPilotState.pilotLocation()).as("读侧必须能取到飞手位置，否则 C02-6 永远 UNDETERMINED").isNotNull();
        assertThat(withPilotState.pilotLocation().longitude()).isNotNull();
        assertThat(withPilotState.pilotLocation().latitude()).isNotNull();
        // 目标位置与飞手位置是两个点，不能串台。
        assertThat(withPilotState.pilotLocation().longitude()).isNotEqualByComparingTo(withPilotState.location().longitude());
    }

    /**
     * 实测雷达的第二道闸（E1 已落地 `claimablePrefixes()`）：开关关闭时 `live-radar:` 行**连领都不领**。
     *
     * <p>关键不只是"不处理"，而是 `fusion_attempts` 必须**一次都不加**。领了再丢会把重试次数推到上限，
     * 之后 `failExhausted` 会把这批本来完好的帧永久判成毒帧——等有人真把开关打开，数据已经废了。
     * 一个纯配置开关不该有不可逆的数据后果，所以这条断言的是次数不动，而不只是"没被处理"。
     */
    @Test
    @Order(10)
    void liveRadarRowsAreOnlyClaimableWhileThePromotionSwitchIsOn() {
        String device = "gate-" + suffix;
        String source = "live-radar:" + device;
        for (int i = 1; i <= 3; i++) insertInbox(source, "gate-" + i, "{\"frame\":" + i + "}");

        // 关：领不到，且重试次数保持 0。
        assertThat(fusionProperties.getLivePromotion().isEnabled()).as("本类默认开关是关的").isFalse();
        assertThat(inbox.claim(System.currentTimeMillis(), 50, 30_000L).stream().map(FusionInboxRepository.InboxRow::source).toList())
                .as("开关关闭时不得领取 live-radar: 行").doesNotContain(source);
        assertThat(jdbc.queryForObject("select coalesce(max(fusion_attempts),0) from inbox_message where source=?", Integer.class, source))
                .as("关着的通道不得消耗重试次数——否则开关打开时这批帧已被判成毒帧").isZero();
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=? and status='RECEIVED'", Long.class, source))
                .isEqualTo(3L);

        // 开：同一批行立刻可领。开关只影响"领不领"，不改变已落库的信封。
        fusionProperties.getLivePromotion().setEnabled(true);
        try {
            List<FusionInboxRepository.InboxRow> claimed = inbox.claim(System.currentTimeMillis(), 50, 30_000L);
            assertThat(claimed.stream().map(FusionInboxRepository.InboxRow::source).toList())
                    .as("开关打开后同一批行必须可领").contains(source);
            assertThat(claimed.stream().filter(row -> source.equals(row.source())).count()).isEqualTo(3L);
            for (FusionInboxRepository.InboxRow row : claimed) inbox.done(row.inboxId(), System.currentTimeMillis());
        } finally {
            fusionProperties.getLivePromotion().setEnabled(false);
        }
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=? and status='DONE'", Long.class, source)).isEqualTo(3L);
    }

    /**
     * 两条真实连接同时领取四个前缀的待处理帧：结果集**无交集**、总数等于可领行数、每行恰好被领一次。
     * 这条只有 PostgreSQL 能钉——H2 的行锁与可见性语义不同，并发重叠在 H2 上测不出来。
     */
    @Test
    @Order(11)
    void twoRealConnectionsClaimingTheFourPrefixesNeverOverlap() throws Exception {
        String device = "race-" + suffix;
        List<String> sources = List.of("replay:" + device, "lingyun:radar:" + device, "eo-edge:" + device, "live-radar:" + device);
        int perSource = 10;
        for (String source : sources) {
            for (int i = 1; i <= perSource; i++) insertInbox(source, "race-" + i, "{\"n\":" + i + "}");
        }
        long claimable = jdbc.queryForObject("select count(*) from inbox_message where status='RECEIVED' and source_id is not null and payload is not null",
                Long.class);
        assertThat(claimable).as("四个前缀共 40 行待领").isGreaterThanOrEqualTo(40L);

        // 四前缀全开，才谈得上"四前缀并发领取"。
        fusionProperties.getLivePromotion().setEnabled(true);
        // 每人领一半：可领行数 >= 2*batch，所以两人各领满一批是**确定的**结果，不是碰巧。
        int perClaim = Math.max(1, (int) claimable / 2);
        List<List<FusionInboxRepository.InboxRow>> results;
        try {
            results = raceClaims(perClaim);
        } finally {
            fusionProperties.getLivePromotion().setEnabled(false);
        }
        List<String> first = results.get(0).stream().map(FusionInboxRepository.InboxRow::inboxId).toList();
        List<String> second = results.get(1).stream().map(FusionInboxRepository.InboxRow::inboxId).toList();
        assertThat(first).as("两个领取者都应拿到行，否则并发性没被真正验证").isNotEmpty();
        assertThat(second).isNotEmpty();
        assertThat(first).as("同一行被两个领取者同时拿到，就会被处理两次").doesNotContainAnyElementsOf(second);
        assertThat(first).doesNotHaveDuplicates();
        assertThat(second).doesNotHaveDuplicates();
        // 合计**恰好**等于两批之和：只断言"不超过"的话，一个领取者少领、甚至领到 0 也照样通过——
        // 而"后到的那个领到 0"正是 15-38/15-40 两轮要消除的症状本身。
        assertThat(first.size() + second.size())
                .as("两人各领满一批才算并发领取真的成立（claimable=" + claimable + " perClaim=" + perClaim
                        + " first=" + first.size() + " second=" + second.size() + "）")
                .isEqualTo(2 * perClaim);
        assertThat(first.size() + second.size()).as("合计不得超过可领行数（超了就说明有行被领了两次）")
                .isLessThanOrEqualTo((int) claimable);
        assertThat(jdbc.queryForObject("select coalesce(max(fusion_attempts),0) from inbox_message where source like ?", Integer.class, "%" + device))
                .as("没有任何一行被领两次").isLessThanOrEqualTo(1);
        // 收尾，别把 PROCESSING 行留给后面的用例。
        for (List<FusionInboxRepository.InboxRow> batch : results) {
            for (FusionInboxRepository.InboxRow row : batch) inbox.done(row.inboxId(), System.currentTimeMillis());
        }
    }

    /**
     * `source_observation` 只增（E1 在 `R__stage85_direct_access.sql` 加的 `trg_stage85_source_observation_append_only`）。
     * 观测是研判的原始证据：写错了只能新增一条修正观测，不能回头改。错误码固定为 23514，读侧据此把它翻成"不可改写"而不是 500。
     */
    @Test
    @Order(12)
    void sourceObservationIsAppendOnlyOnPostgres() {
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='source_observation'::regclass"
                + " and tgname='trg_stage85_source_observation_append_only'", Long.class)).isEqualTo(1L);
        String observationId = insertObservation("append-only", "SRID=4326;POINT (100.60 20.40)", "RADAR");
        assertThat(sqlState(catching(() -> jdbc.update("update source_observation set class_source='MANUAL' where observation_id=?", observationId))))
                .as("UPDATE 必须以 23514 被拒").isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("update source_observation set pilot_location=null where observation_id=?", observationId))))
                .isEqualTo("23514");
        assertThat(sqlState(catching(() -> jdbc.update("delete from source_observation where observation_id=?", observationId))))
                .as("DELETE 必须以 23514 被拒").isEqualTo("23514");
        // 被拒之后原行原封不动。
        assertThat(jdbc.queryForObject("select class_source from source_observation where observation_id=?", String.class, observationId))
                .isEqualTo("RADAR");
        assertThat(jdbc.queryForObject("select ST_X(pilot_location) from source_observation where observation_id=?", Double.class, observationId))
                .isEqualTo(100.60);
    }

    /** 两条真实连接同时领取；每人最多领 batch 行，用栅栏对齐起跑时刻。 */
    private List<List<FusionInboxRepository.InboxRow>> raceClaims(int batch) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long now = System.currentTimeMillis();
            Future<List<FusionInboxRepository.InboxRow>> a = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS); return inbox.claim(now, batch, 60_000L);
            });
            Future<List<FusionInboxRepository.InboxRow>> b = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS); return inbox.claim(now, batch, 60_000L);
            });
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 取最内层 PostgreSQL 异常的 SQLSTATE：只断言"抛了"会让触发器换成别的机制时照样通过。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return failure == null ? null : "<no SQLException: " + failure + ">";
    }

    /**
     * 提交后跟进的两支迁移（决策 8.5-28 / 8.5-29）在真实库上的形态。
     * `ingest_seq` 是领取排序的稳定键，它自己必须先是单调且唯一的，否则"顺序可复现"无从谈起。
     */
    @Test
    @Order(13)
    void followUpMigrations072And073AreAppliedWithUsableColumnShapes() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();
        for (String version : List.of("202609050072", "202609050073")) {
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version=?", Long.class, version))
                    .as("迁移 " + version + " 必须已应用").isEqualTo(1L);
        }
        // 072：飞手位置的观测时刻，可空（多数来源根本不上报飞手，缺失是真实结论）。
        Map<String, Object> pilotAt = jdbc.queryForMap("select data_type,is_nullable from information_schema.columns"
                + " where table_schema=? and table_name='target_latest_state' and column_name='pilot_observed_at'", SCHEMA);
        assertThat(pilotAt).containsEntry("data_type", "timestamp with time zone").containsEntry("is_nullable", "YES");
        // 073：写入序列，非空且由数据库生成——由调用方填就失去"写入先后"的意义了。
        Map<String, Object> ingestSeq = jdbc.queryForMap("select data_type,is_nullable,is_identity from information_schema.columns"
                + " where table_schema=? and table_name='inbox_message' and column_name='ingest_seq'", SCHEMA);
        assertThat(ingestSeq).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO").containsEntry("is_identity", "YES");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname=? and indexname='idx_stage85_inbox_claim_order'",
                String.class, SCHEMA)).as("排序键要有索引跟着").hasSize(1);

        // 单调且唯一：三条同一毫秒写入的信封，序号必须严格递增。
        String source = "replay:seq-" + suffix;
        for (int i = 1; i <= 3; i++) insertInbox(source, "seq-" + i, "{\"n\":" + i + "}");
        List<Long> seqs = jdbc.queryForList("select ingest_seq from inbox_message where source=? order by ingest_seq", Long.class, source);
        assertThat(seqs).hasSize(3).doesNotHaveDuplicates().isSorted();
        assertThat(seqs.get(2)).isGreaterThan(seqs.get(0));
        for (String inboxId : jdbc.queryForList("select inbox_id from inbox_message where source=?", String.class, source)) {
            inbox.done(inboxId, T0.toInstant().toEpochMilli());
        }
    }

    /**
     * 决策 8.5-27/8.5-28：飞手位置只由**携带身份主源的帧**改写；不表态的帧连同观测时刻一起保持原值。
     *
     * <p>三条路径分开验，因为它们的失败后果完全不同：
     * 表态且有位置 → 两列同写；不表态 → 两列同留（**这条最要紧**，原先每帧重写会让一条没有飞手的光电帧
     * 把 TDOA 刚写进去的飞手位置抹掉）；表态且位置为空 → 两列同清（明写就是"我确认现在没有"，不能当成不表态）。
     *
     * <p>注意"同写同留"是**写入方的保证，不是数据库约束**：`LatestState` 的 15 参夹具构造器就允许写位置不写时刻。
     * 所以这里断言的是 `upsertLatestState` 的行为，不是全库不变式——把它写成全库不变式会因为夹具而假红。
     */
    @Test
    @Order(14)
    void pilotLocationAndItsObservedAtAreWrittenKeptAndClearedTogether() {
        String targetId = "s85-pilot-pair-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,created_at,updated_at,version)"
                + " values (?,?,'UAV',?,?,'live',?,?,0)", targetId, "目标-S85P-" + suffix, T0, T0, T0, T0);

        // ① 表态且有位置：两列同写。
        OffsetDateTime pilotAt = T0.plusMinutes(1);
        fusedTracks.upsertLatestState(pilotState(targetId, 100.61, 20.41, pilotAt, true));
        Map<String, Object> written = jdbc.queryForMap("select ST_X(pilot_location) as lon, pilot_observed_at from target_latest_state where target_id=?", targetId);
        assertThat((Double) written.get("lon")).isEqualTo(100.61);
        assertThat(written.get("pilot_observed_at")).as("有飞手位置就必须有它的观测时刻，否则没法判断这个位置有多旧").isNotNull();

        // ② 不表态：两列同留。这条是 8.5-27 的正题——原先每帧重写，一条没有飞手的帧就能把上一帧的飞手位置抹掉。
        fusedTracks.upsertLatestState(pilotState(targetId, null, null, null, false));
        Map<String, Object> kept = jdbc.queryForMap("select ST_X(pilot_location) as lon, pilot_observed_at from target_latest_state where target_id=?", targetId);
        assertThat((Double) kept.get("lon")).as("不表态的帧不得抹掉已知的飞手位置").isEqualTo(100.61);
        assertThat(kept.get("pilot_observed_at")).as("位置留下了，它的时刻也必须留下——否则会变成一个不知多旧的位置").isNotNull();
        assertThat(kept.get("pilot_observed_at")).isEqualTo(written.get("pilot_observed_at"));

        // ③ 表态且位置为空：两列同清。明写 NULL 是"我确认现在没有"，与"不表态"是两件事。
        fusedTracks.upsertLatestState(pilotState(targetId, null, null, null, true));
        assertThat(jdbc.queryForObject("select count(*) from target_latest_state where target_id=?"
                + " and pilot_location is null and pilot_observed_at is null", Long.class, targetId))
                .as("显式清空必须两列一起清，不能留一个孤儿时刻").isEqualTo(1L);

        // ④ 再表态一次，时刻随之更新：位置变了而时刻不变，等于用新位置冒充旧观测。
        fusedTracks.upsertLatestState(pilotState(targetId, 100.62, 20.42, pilotAt.plusMinutes(5), true));
        assertThat(jdbc.queryForObject("select pilot_observed_at from target_latest_state where target_id=?", OffsetDateTime.class, targetId))
                .isEqualTo(pilotAt.plusMinutes(5));
    }

    /**
     * 决策 8.5-29：领取顺序按 `(received_at, ingest_seq)`，同一份数据灌两次得到同一个顺序。
     *
     * <p>断言的是"顺序等于按 `(received_at, ingest_seq)` 排出来的顺序"，而不只是"两次相同"——
     * 两次都错成同一个顺序也满足"两次相同"，那样的用例挡不住任何东西。
     * 用同一毫秒、交替前缀的行来构造：只有排序键真的生效，顺序才会等于写入顺序；
     * 若还按随机 `inbox_id` 排，交替前缀会被打乱。
     */
    @Test
    @Order(15)
    void claimOrderFollowsIngestSequenceAndIsReproducible() {
        String tag = "order-" + suffix;
        List<String> prefixes = List.of("replay:", "lingyun:radar:", "eo-edge:");
        List<String> insertionOrder = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String source = prefixes.get(i % prefixes.size()) + tag;
            insertInbox(source, "ord-" + i, "{\"n\":" + i + "}");   // 同一 received_at（T0），只有 ingest_seq 不同
            insertionOrder.add(source + "#ord-" + i);
        }
        List<String> byOrderingKey = jdbc.queryForList("select source||'#'||source_msg_id from inbox_message"
                + " where source like ? order by received_at asc, ingest_seq asc", String.class, "%" + tag);
        assertThat(byOrderingKey).as("同毫秒写入时，排序键顺序就是写入顺序").containsExactlyElementsOf(insertionOrder);

        List<FusionInboxRepository.InboxRow> claimed = inbox.claim(System.currentTimeMillis(), 12, 60_000L);
        List<String> claimedOrder = claimed.stream().filter(row -> row.source().endsWith(tag))
                .map(row -> row.source() + "#" + row.sourceMsgId()).toList();
        assertThat(claimedOrder).as("领取顺序必须与 (received_at, ingest_seq) 一致").containsExactlyElementsOf(insertionOrder);
        for (FusionInboxRepository.InboxRow row : claimed) inbox.done(row.inboxId(), System.currentTimeMillis());

        // 再灌一批同样的数据：相对顺序不变（序号继续单调，不会因为随机 id 而重排）。
        String secondTag = "order2-" + suffix;
        List<String> secondInsertion = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String source = prefixes.get(i % prefixes.size()) + secondTag;
            insertInbox(source, "ord-" + i, "{\"n\":" + i + "}");
            secondInsertion.add(source + "#ord-" + i);
        }
        List<FusionInboxRepository.InboxRow> second = inbox.claim(System.currentTimeMillis(), 12, 60_000L);
        assertThat(second.stream().filter(row -> row.source().endsWith(secondTag))
                .map(row -> row.source() + "#" + row.sourceMsgId()).toList())
                .as("同一份数据第二次灌库仍得到同一个顺序").containsExactlyElementsOf(secondInsertion);
        for (FusionInboxRepository.InboxRow row : second) inbox.done(row.inboxId(), System.currentTimeMillis());
    }

    private FusedTrackRepository.LatestState pilotState(String targetId, Double pilotLon, Double pilotLat,
            OffsetDateTime pilotObservedAt, boolean decided) {
        return new FusedTrackRepository.LatestState(targetId, 100.70, 20.50, null, null, null, null, null, null,
                T0, T0, "[]", T0, pilotLon, pilotLat, pilotObservedAt, decided);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // TODO(8.5.3)：两条 HTTP 读路径（GET /targets/{id}、GET /targets/{id}/observations）在 PG 上的 pilot_location 下发。
    //   仓库层的 PostGIS 分支已由用例 3 钉住；HTTP 层还需要本类目前没有的会话/角色夹具，留待 8.5.9 集成时补。
    // ---------------------------------------------------------------------------------------------------------------

    /** 造一个目标与它的最新状态：飞手位置挂在 target_latest_state 上，规则引擎读的就是这张表。 */
    private String insertTarget() {
        String targetId = "s85-target-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,created_at,updated_at,version)"
                + " values (?,?,'UAV',?,?,'live',?,?,0)", targetId, "目标-S85-" + suffix, T0, T0, T0, T0);
        jdbc.update("insert into target_latest_state (target_id,observed_at,received_at,unknown_fields,created_at,updated_at,version)"
                + " values (?,?,?,cast('[]' as jsonb),?,?,0)", targetId, T0, T0, T0, T0);
        return targetId;
    }

    /**
     * 造一条观测。飞手位置传 null 是常态：多数来源根本不上报飞手。
     * `key` 用于区分 `uk_stage8_observation_identity`（source_id + session + external_target_id + observed_at）。
     */
    private String insertObservation(String key, String pilotEwkt, String classSource) {
        String observationId = id();
        jdbc.update("insert into source_observation (observation_id,source_id,source_type,source_session_key,external_target_id,"
                + "observed_at,received_at,pilot_location,class_source,quality,source_mode,created_at)"
                + " values (?,?,'RADAR',?,?,?,?," + (pilotEwkt == null ? "null" : "ST_GeomFromEWKT(?)") + ",?,cast('{}' as jsonb),'live',?)",
                pilotEwkt == null
                        ? new Object[] { observationId, sourceId(), "sess-" + suffix, "ext-" + key + "-" + suffix, T0, T0, classSource, T0 }
                        : new Object[] { observationId, sourceId(), "sess-" + suffix, "ext-" + key + "-" + suffix, T0, T0, pilotEwkt, classSource, T0 });
        return observationId;
    }

    /** 注册一台设备（契约 §2：A 注册设备时建 integration_source），返回它的 source_id。 */
    private String registerDevice(String deviceCode) {
        String sourceId = id();
        jdbc.update("insert into integration_source (source_id,source_code,source_type,name,source_mode,enabled,credential_ref,created_at,updated_at,version)"
                + " values (?,?,'RADAR',?,'live',true,null,?,?,0)", sourceId, deviceCode, "阶段8.5实测雷达 " + deviceCode, T0, T0);
        return sourceId;
    }

    private static Map<String, Object> radarFrame(String deviceCode, long bootMicros, int frameId) {
        return radarFrame(deviceCode, bootMicros, frameId, List.of(Map.of("external_track_id", "trk-1",
                "longitude", 118.62, "latitude", 37.42, "z_m", 120.0, "velocity_x_mps", 3.0, "velocity_y_mps", -1.0,
                "velocity_z_mps", 0.0, "snr_db", 18.0, "rcs_m2", 0.4, "classification", "UAV")));
    }

    private static Map<String, Object> radarFrame(String deviceCode, long bootMicros, int frameId, List<Map<String, Object>> items) {
        return Map.of("device_id", deviceCode, "boot_micros", bootMicros, "frame_id", frameId, "items", items);
    }

    /** 写一行 inbox 信封。payload_hash 用真实 SHA-256：ck_stage2_inbox_payload_hash 要求 `^[0-9a-f]{64}$`。 */
    private void insertInbox(String source, String sourceMsgId, String payload) {
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,source_id,payload,payload_hash,status,received_at)"
                + " values (?,?,?,?,cast(? as jsonb),?,'RECEIVED',?)",
                id(), source, sourceMsgId, sourceId(), payload, sha256(payload), T0.toInstant().toEpochMilli());
    }

    /** 直连切片的 inbox 行必须挂在一个已注册的 integration_source 上（契约 §2：A 注册设备时建）。 */
    private String sourceId() {
        if (cachedSourceId == null) {
            cachedSourceId = "s85-src-" + suffix;
            jdbc.update("insert into integration_source (source_id,source_code,source_type,name,source_mode,enabled,credential_ref,created_at,updated_at,version)"
                    + " values (?,?,?,?,'live',true,null,?,?,0)", cachedSourceId, "SRC-S85-" + suffix, "RADAR", "阶段8.5验证来源", T0, T0);
        }
        return cachedSourceId;
    }

    private String cachedSourceId;

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 捕获期望中的写入失败：返回异常本身（null 表示写入意外成功），让调用处能对失败原因再断言。 */
    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException expected) {
            return expected;
        }
    }

    private String id() { return UUID.randomUUID().toString(); }

    /** 把来源列表拼成 SQL 的 IN 子句（值来自代码常量，不是外部输入）。 */
    private static String inClause(java.util.Collection<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        // 只接受专用验证库，防止误连生产库或日常联调库。
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 8.5 verification outside a stage456_verify_ database");
        }
        String extension = root.queryForObject("select extversion from pg_extension where extname='postgis'", String.class);
        if (extension == null || extension.isBlank()) throw new IllegalStateException("PostGIS is required for Stage 8.5 verification");
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe Stage 8.5 verification schema");
    }
}
