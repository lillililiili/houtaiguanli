package com.uav.lowaltitude.modules.fusion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.testsupport.SourceTypeCatalogFixture;

/**
 * 阶段 8 PostgreSQL/PostGIS 专项验证：迁移 050–054 与 R__stage8 在真实库升级；source_observation.location 的 WGS-84 检查；
 * track 分层与 link_id 的互斥；fusion_config 单 ACTIVE 部分唯一；target_lineage / fusion_event / target_classification_revision 只增；
 * 视图 fusion_effect_daily 在 PG 上可查并经 GET /fusion/metrics/daily 读出。
 *
 * 沿用 Stage7PostgresTest：env 门禁、stage456_verify_ 库名限制、随机 stage456_ schema、手动 Flyway 含 db/postgresql、
 * 不用测试级事务（并发用例要求真正提交/回滚）、只删本 schema。用例顺序显式声明：迁移断言最先，回放场景最后。
 *
 * 待 E1/E2 落地后补齐：跑 LocalStage8FusionReplaySeeder 六场景后 GET /targets 每真值目标只有一个 unified 目标、/targets/{id}/tracks 含 FUSED 层、
 * 点默认不含 PRED；两连接并发 POST /targets/merge 同一 survivor 一成一 VERSION_CONFLICT。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage8PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage8PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage8PostgresTest 未在真实 PostgreSQL 上执行")
class Stage8PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneOffset.UTC);

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired FusionInboxRepository inbox;

    private String suffix, org, district, sourceId, sourceCode, targetId, linkId, session;

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
        org = id(); district = id(); sourceId = id(); sourceCode = "s8pg-radar-" + suffix; targetId = id(); linkId = id();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", org, "ORG-S8-" + suffix, "阶段八验证机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-S8-" + suffix, "阶段八验证区域");
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version) values (?,?,?,true,'replay','RADAR',?,?,0)",
                sourceId, sourceCode, "阶段八验证雷达", T0, T0);
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'replay',?,?,?,?,0)",
                targetId, "MB-S8-" + suffix, org, district, T0, T0);
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,?,?,?,?)",
                linkId, targetId, sourceId, "ds-" + suffix, "R-1", T0);
        session = session(role());
    }

    @Test
    @Order(1)
    void migrations050To054AndStage8RepeatableAreAppliedToIsolatedPostgresSchema() {
        List<String> versions = jdbc.queryForList("select version from flyway_schema_history where success=true and version is not null", String.class);
        assertThat(versions).contains("202609050050", "202609050051", "202609050052", "202609050053", "202609050054");
        List<String> repeatables = jdbc.queryForList("select description from flyway_schema_history where success=true and version is null", String.class);
        assertThat(repeatables).anyMatch(d -> d.toLowerCase().contains("stage8"));
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema=? and table_name in ('source_type_catalog','fusion_config','source_observation','association_pending','target_track_status','target_lineage','target_current_alias','target_attribute_selection','target_degradation','target_classification_revision','fusion_event','replay_ground_truth')",
                String.class, SCHEMA);
        assertThat(tables).containsExactlyInAnyOrder("source_type_catalog", "fusion_config", "source_observation", "association_pending", "target_track_status",
                "target_lineage", "target_current_alias", "target_attribute_selection", "target_degradation", "target_classification_revision", "fusion_event", "replay_ground_truth");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.views where table_schema=? and table_name='fusion_effect_daily'", Long.class, SCHEMA)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema=? and table_name='track' and column_name='layer'", String.class, SCHEMA)).isEqualTo("character varying");
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=? and table_name='track' and column_name='link_id'", String.class, SCHEMA)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema=? and table_name='track_point' and column_name='point_kind'", String.class, SCHEMA)).isEqualTo("character varying");
        List<String> permissions = jdbc.queryForList("select permission_code from app_permission where permission_code in ('fusion:read','fusion:revise','fusion:manage') order by permission_code", String.class);
        assertThat(permissions).containsExactly("fusion:manage", "fusion:read", "fusion:revise");
        // 阶段 8.5 迁移 070 增 AOA/DCD/RID（决策 8.5-26）；行数与状态口径统一在夹具里（阶段 10.3）。
        assertThat(jdbc.queryForObject("select count(*) from source_type_catalog", Long.class))
                .isEqualTo((long) SourceTypeCatalogFixture.EXPECTED_TYPES.size());
        SourceTypeCatalogFixture.assertCatalog(jdbc);
        assertThat(jdbc.queryForObject("select count(*) from fusion_config where status='ACTIVE' and config_version='demo-v1'", Long.class)).isEqualTo(1L);
        // 迁移不写任何观测、目标或真值：本 schema 里的目标只能来自各用例夹具。
        assertThat(jdbc.queryForObject("select count(*) from source_observation", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from replay_ground_truth", Long.class)).isZero();
    }

    @Test
    @Order(2)
    void sourceObservationLocationMustBeValidWgs84() {
        // 非 4326 的点：PostGIS 类型修饰符与 R__stage8 的 SRID 检查都不放行。
        assertThatThrownBy(() -> insertObservation(id(), "R-1", T0, "SRID=3857;POINT(13200000 4470000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 越界经纬度：不是合法的 WGS-84 坐标，不能进原始观测层。
        assertThatThrownBy(() -> insertObservation(id(), "R-1", T0, "SRID=4326;POINT(200 37.4)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation(id(), "R-1", T0, "SRID=4326;POINT(118.6 95)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        String observation = id();
        insertObservation(observation, "R-1", T0, "SRID=4326;POINT(118.6 37.4)");
        assertThat(jdbc.queryForObject("select ST_SRID(location) from source_observation where observation_id=?", Integer.class, observation)).isEqualTo(4326);
        // 位置可空：只有方位的观测没有点，不能写 (0,0) 冒充。
        String noPosition = id();
        insertObservation(noPosition, "R-1", T0.plusSeconds(1), null);
        assertThat(jdbc.queryForObject("select count(*) from source_observation where observation_id=? and location is null", Long.class, noPosition)).isEqualTo(1L);
        // (source_id, source_session_key, external_target_id, observed_at) 唯一：同一来源同一时刻同一目标只能有一条观测。
        assertThatThrownBy(() -> insertObservation(id(), "R-1", T0, "SRID=4326;POINT(118.6 37.4)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(3)
    void trackLayerAndLinkIdAreMutuallyConstrained() {
        // FUSED 层不属于任何来源 link；RAW 层必须挂在 link 上。
        assertThatThrownBy(() -> insertTrack(id(), linkId, "FUSED", "fused:" + targetId + ":1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTrack(id(), null, "RAW", "R-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTrack(id(), linkId, "MIXED", "R-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        String raw = id(), fused = id();
        insertTrack(raw, linkId, "RAW", "R-1");
        insertTrack(fused, null, "FUSED", "fused:" + targetId + ":" + T0.toInstant().toEpochMilli());
        assertThat(jdbc.queryForObject("select layer from track where track_id=?", String.class, raw)).isEqualTo("RAW");
        assertThat(jdbc.queryForObject("select link_id from track where track_id=?", String.class, fused)).isNull();
        // 点种类只允许 MEAS/BRIDGE/PRED。
        assertThatThrownBy(() -> insertPoint(id(), fused, 1, T0, "GUESS", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertPoint(id(), fused, 1, T0, "PRED", null);
        assertThat(jdbc.queryForObject("select point_kind from track_point where track_id=?", String.class, fused)).isEqualTo("PRED");
    }

    @Test
    @Order(4)
    void fusionConfigAllowsExactlyOneActiveVersion() {
        // demo-v1 已 ACTIVE：第二个 ACTIVE 必须被 R__stage8 的部分唯一索引拒绝，草稿与退役版本不受影响。
        assertThatThrownBy(() -> jdbc.update(
                "insert into fusion_config (config_version,status,schema_status,params,note,created_at,activated_at,version) values (?,'ACTIVE','DEMO',cast('{}' as json),'并发激活反例',?,?,0)",
                "s8-active-" + suffix, T0, T0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_stage8_fusion_config_single_active");
        jdbc.update("insert into fusion_config (config_version,status,schema_status,params,note,created_at,version) values (?,'DRAFT','DEMO',cast('{}' as json),'草稿',?,0)", "s8-draft-" + suffix, T0);
        jdbc.update("insert into fusion_config (config_version,status,schema_status,params,note,created_at,version) values (?,'RETIRED','DEMO',cast('{}' as json),'退役',?,0)", "s8-retired-" + suffix, T0);
        assertThat(jdbc.queryForObject("select count(*) from fusion_config where status='ACTIVE'", Long.class)).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update("update fusion_config set status='ACTIVE' where config_version=?", "s8-draft-" + suffix))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(5)
    void lineageEventsAndClassificationRevisionsAreAppendOnly() {
        String lineage = id();
        jdbc.update("insert into target_lineage (lineage_id,op,occurred_at,survivor_target_id,origin_target_id,member_target_ids,source_target_ids,basis,algo_version,config_version,operator_kind,snapshots,created_at) values (?,'CREATE',?,?,null,cast('[]' as jsonb),cast('[]' as jsonb),cast('{}' as jsonb),'test','demo-v1','SYSTEM',cast('{}' as jsonb),?)",
                lineage, T0, targetId, T0);
        assertThatThrownBy(() -> jdbc.update("update target_lineage set op='MERGE' where lineage_id=?", lineage)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update target_lineage set note='改写' where lineage_id=?", lineage)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from target_lineage where lineage_id=?", lineage)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select op from target_lineage where lineage_id=?", String.class, lineage)).isEqualTo("CREATE");

        String event = id();
        jdbc.update("insert into fusion_event (event_id,event_type,target_id,payload,occurred_at,created_at) values (?,'STATUS_STABLE',?,cast('{}' as json),?,?)", event, targetId, T0, T0);
        assertThatThrownBy(() -> jdbc.update("update fusion_event set event_type='MERGED' where event_id=?", event)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from fusion_event where event_id=?", event)).isInstanceOf(DataIntegrityViolationException.class);

        String revision = id();
        jdbc.update("insert into target_classification_revision (revision_id,target_id,previous_class_code,new_class_code,note,actor_id,target_version,created_at) values (?,?,'UNKNOWN','UAV','人工修订',?,1,?)",
                revision, targetId, userId(session), T0);
        assertThatThrownBy(() -> jdbc.update("update target_classification_revision set new_class_code='BIRD' where revision_id=?", revision)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from target_classification_revision where revision_id=?", revision)).isInstanceOf(DataIntegrityViolationException.class);
        // (target_id, target_version) 唯一：同一版本不能有两条修订。
        assertThatThrownBy(() -> jdbc.update("insert into target_classification_revision (revision_id,target_id,previous_class_code,new_class_code,note,actor_id,target_version,created_at) values (?,?,'UAV','BIRD','重复版本',?,1,?)",
                id(), targetId, userId(session), T0)).isInstanceOf(DataIntegrityViolationException.class);
        // 被并目标不得指向自己。
        assertThatThrownBy(() -> jdbc.update("insert into target_current_alias (historical_target_id,current_target_id,lineage_id,updated_at) values (?,?,?,?)", targetId, targetId, lineage, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(6)
    void effectViewIsQueryableOnPostgresAndReadableThroughMetricsApi() throws Exception {
        String fused = id();
        insertTrack(fused, null, "FUSED", "fused:" + targetId + ":" + T0.plusMinutes(5).toInstant().toEpochMilli());
        insertPoint(id(), fused, 1, T0.plusMinutes(5), "MEAS", null);
        insertPoint(id(), fused, 2, T0.plusMinutes(5).plusSeconds(1), "MEAS", null);
        insertPoint(id(), fused, 3, T0.plusMinutes(5).plusSeconds(2), "PRED", null);
        insertPoint(id(), fused, 4, T0.plusMinutes(5).plusSeconds(3), "BRIDGE", null);
        jdbc.update("insert into target_lineage (lineage_id,op,occurred_at,survivor_target_id,origin_target_id,member_target_ids,source_target_ids,basis,algo_version,config_version,operator_kind,snapshots,created_at) values (?,'SWITCH',?,?,null,cast('[]' as jsonb),cast('[]' as jsonb),cast('{}' as jsonb),'test','demo-v1','SYSTEM',cast('{}' as jsonb),?)",
                id(), T0.plusMinutes(5), targetId, T0.plusMinutes(5));
        // 真值：两条观测都落到同一目标 → 无重复、关联全部正确。
        String raw = id();
        insertTrack(raw, linkId, "RAW", "R-1-" + suffix);
        for (int i = 0; i < 2; i++) {
            OffsetDateTime at = T0.plusMinutes(5).plusSeconds(i);
            String observation = id();
            insertObservation(observation, "R-1", at, "SRID=4326;POINT(118.6 37.4)");
            jdbc.update("insert into replay_ground_truth (dataset_id,scenario,record_no,true_target_key,source_code,external_target_id,observed_at,created_at) values (?,?,?,?,?,?,?,?)",
                    "ds-" + suffix, "S1", i, "K-" + suffix, sourceCode, "R-1", at, at);
            insertPoint(id(), raw, i, at, "MEAS", observation);
        }
        Map<String, Object> row = jdbc.queryForMap("select tracked_targets,id_switch_count,short_lost_frames,total_frames,interrupt_rate,duplicate_targets,truth_targets,duplicate_target_rate,correct_associations,total_associations,association_accuracy from fusion_effect_daily where fusion_domain_key=? and \"day\"=cast(? as date)",
                "replay|" + org + "|" + district, "2026-09-05");
        assertThat(((Number) row.get("tracked_targets")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("id_switch_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("short_lost_frames")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("total_frames")).longValue()).isEqualTo(4L);
        assertThat(((Number) row.get("interrupt_rate")).doubleValue()).isEqualTo(0.25);
        assertThat(((Number) row.get("duplicate_targets")).longValue()).isZero();
        assertThat(((Number) row.get("truth_targets")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("duplicate_target_rate")).doubleValue()).isZero();
        assertThat(((Number) row.get("correct_associations")).longValue()).isEqualTo(2L);
        assertThat(((Number) row.get("total_associations")).longValue()).isEqualTo(2L);
        assertThat(((Number) row.get("association_accuracy")).doubleValue()).isEqualTo(1.0);

        String window = "from=" + T0.minusHours(1).toInstant().toEpochMilli() + "&to=" + T0.plusHours(1).toInstant().toEpochMilli();
        mvc.perform(get("/api/v1/fusion/metrics/daily?" + window + "&domain=replay|" + org + "|" + district).header("Authorization", "Bearer " + session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].day").value("2026-09-05"))
                .andExpect(jsonPath("$.data.items[0].total_frames").value(4))
                .andExpect(jsonPath("$.data.items[0].interrupt_rate.value").value(0.25))
                .andExpect(jsonPath("$.data.items[0].duplicate_target_rate.value").value(0.0))
                .andExpect(jsonPath("$.data.items[0].association_accuracy.value").value(1.0))
                .andExpect(jsonPath("$.data.items[0].association_accuracy.availability").value("AVAILABLE"));
    }

    /**
     * 两条真实连接并发 claim 同一批 inbox 行：条件更新必须让每行只被一个租约拿到，不能重复投递同一帧。
     * 不用测试事务，两个线程各自从连接池取独立连接，屏障保证它们同时进入 UPDATE 竞争。
     */
    @Test
    @Order(7)
    void twoRealConnectionsClaimingTheSameInboxBatchNeverOverlap() throws Exception {
        int rows = 8;
        for (int i = 0; i < rows; i++) {
            jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status) values (?,?,?,?,?,?,cast(? as jsonb),'RECEIVED')",
                    id(), "replay:" + sourceCode + ":ds-" + suffix, "msg-" + suffix + "-" + i, T0.toInstant().toEpochMilli() + i, sourceId,
                    sha256(suffix + ":" + i), "{\"frame\":" + i + "}");
        }
        long now = T0.toInstant().toEpochMilli() + 1_000;
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<List<String>> claimed;
        try {
            Future<List<String>> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return inbox.claim(now, rows, 30_000).stream().map(InboxRow::inboxId).toList(); });
            Future<List<String>> b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return inbox.claim(now, rows, 30_000).stream().map(InboxRow::inboxId).toList(); });
            claimed = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        List<String> first = claimed.get(0), second = claimed.get(1);
        // 允许一方空手而归（另一方先拿到整批）；关键是两次领取没有交集，同一帧不会被投递两次。
        if (!second.isEmpty()) assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(first.size() + second.size()).isLessThanOrEqualTo(rows);
        assertThat(java.util.stream.Stream.concat(first.stream(), second.stream()).distinct().count()).isEqualTo(first.size() + second.size());
        // 每行至多一个租约令牌；被领取的行都进入 PROCESSING 且带租约。
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like ? and status='PROCESSING' and lease_token is null", Long.class,
                "replay:" + sourceCode + ":%")).isZero();
        assertThat(jdbc.queryForObject("select count(distinct lease_token) from inbox_message where source like ? and lease_token is not null", Long.class,
                "replay:" + sourceCode + ":%")).isLessThanOrEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like ? and status='PROCESSING'", Long.class,
                "replay:" + sourceCode + ":%")).isEqualTo((long) (first.size() + second.size()));
        // 租约未过期时再 claim 拿不到已领取的行（哪一方先拿到整批是竞态，因此用两次领取的并集断言；AssertJ 不接受空集合作为排除项）。
        List<String> alreadyClaimed = java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
        List<String> again = inbox.claim(now, rows, 30_000).stream().map(InboxRow::inboxId).toList();
        if (!alreadyClaimed.isEmpty()) assertThat(again).doesNotContainAnyElementsOf(alreadyClaimed);
        // 领导集成补充（决策 8-17）：领取计数随每次领取加一，耗尽后即使租约过期也不再被领取，而是由 failExhausted 置 FAILED。
        assertThat(jdbc.queryForObject("select min(fusion_attempts) from inbox_message where source like ? and status='PROCESSING'", Integer.class,
                "replay:" + sourceCode + ":%")).isGreaterThanOrEqualTo(1);
        long expired = now + 60_000;
        jdbc.update("update inbox_message set fusion_attempts=5 where source like ?", "replay:" + sourceCode + ":%");
        assertThat(inbox.claim(expired, rows, 30_000, 5)).isEmpty();
        assertThat(inbox.failExhausted(expired, 5)).isEqualTo((long) alreadyClaimed.size());
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like ? and status='FAILED' and processed_at is not null", Long.class,
                "replay:" + sourceCode + ":%")).isEqualTo((long) alreadyClaimed.size());
    }

    // TODO(8.3，待 E1 落地 LocalStage8FusionReplaySeeder)：种子六场景后 GET /targets 每真值目标只有一个 unified 目标；/targets/{id}/tracks 含 FUSED 层；点默认不含 PRED。
    // TODO(8.3，待 E2 落地 POST /targets/merge)：两条真实连接并发合并同一 survivor，一成一 VERSION_CONFLICT，members 只并一次。

    private void insertObservation(String observationId, String external, OffsetDateTime at, String ewkt) {
        if (ewkt == null) {
            jdbc.update("insert into source_observation (observation_id,source_id,source_type,source_session_key,external_target_id,observed_at,received_at,location,quality,source_mode,owner_org_id,district_id,created_at) values (?,?,'RADAR',?,?,?,?,null,cast('{}' as jsonb),'replay',?,?,?)",
                    observationId, sourceId, "ds-" + suffix, external, at, at, org, district, at);
            return;
        }
        jdbc.update("insert into source_observation (observation_id,source_id,source_type,source_session_key,external_target_id,observed_at,received_at,location,quality,source_mode,owner_org_id,district_id,created_at) values (?,?,'RADAR',?,?,?,?,ST_GeomFromEWKT(?),cast('{}' as jsonb),'replay',?,?,?)",
                observationId, sourceId, "ds-" + suffix, external, at, at, ewkt, org, district, at);
    }

    private void insertTrack(String trackId, String link, String layer, String external) {
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,layer,started_at,created_at) values (?,?,?,?,?,?,?)",
                trackId, targetId, link, external, layer, T0, T0);
    }

    private void insertPoint(String pointId, String trackId, long seq, OffsetDateTime at, String kind, String observationId) {
        jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,point_kind,observation_id,created_at) values (?,?,?,?,?,ST_GeomFromEWKT('SRID=4326;POINT(118.6 37.4)'),?,?,?)",
                pointId, trackId, seq, at, at, kind, observationId, at);
    }

    private String role() {
        String role = "ROLE-S8-PG-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'target:read','READ',false,current_timestamp),(?,'fusion:read','READ',false,current_timestamp),(?,'fusion:revise','OP',false,current_timestamp)",
                role, role, role);
        return role;
    }

    /** ASSIGNED 用户配同一 (org,district) 授权元组，走与生产一致的精确范围谓词。 */
    private String session(String roleCode) {
        String user = id(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                user, "s8-pg-" + suffix, "阶段八验证员", roleCode);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", user, org, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private String userId(String token) { return jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, token); }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        // 只接受专用验证库，防止误连生产库或日常联调库。
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing Stage 8 verification outside a stage456_verify_ database");
        }
        String extension = root.queryForObject("select extversion from pg_extension where extname='postgis'", String.class);
        if (extension == null || extension.isBlank()) throw new IllegalStateException("PostGIS is required for Stage 8 verification");
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
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe Stage 8 verification schema");
    }

    private static String id() { return UUID.randomUUID().toString(); }

    /** inbox_message.payload_hash 有 ^[0-9a-f]{64}$ 检查约束：夹具必须给真实 SHA-256，不能用占位串。 */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
