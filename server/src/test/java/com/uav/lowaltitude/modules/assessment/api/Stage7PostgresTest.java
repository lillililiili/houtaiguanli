package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.integration.mock.RuleReplayRunner;
import com.uav.lowaltitude.integration.mock.RuleReplayRunner.ReplayReport;

/**
 * 阶段 7 PostgreSQL/PostGIS 专项验证：迁移 041/042 与 R__stage7 在真实库升级、只增触发器、已发布参数不可改、
 * 回放运行的部分唯一索引、assessment_result 继续只增且接受 ABNORMAL、两条真实连接并发复核同一研判一成一 409，
 * 以及契约十个回放场景在 PostGIS 上的 legal_status 与原因码。
 *
 * 沿用 Stage4/5PostgresTest 的条件式、stage456_verify_ 库名限制、随机 stage456_ schema 与仅删该 schema 的清理。
 * 不使用测试级事务：并发复核要求业务事务真正提交或回滚，行锁必须发生在连接池的两条独立连接之间。
 * 因此用例之间通过同一 schema 相互可见，方法顺序显式声明：迁移断言最先（schema 尚未被夹具之外的数据污染），
 * 十场景回放最后（它会把契约种子写入 schema）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，Stage7PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，Stage7PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，Stage7PostgresTest 未在真实 PostgreSQL 上执行")
class Stage7PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final int REPLAY_SUBJECTS = LocalStage7RuleEngineSeeder.SCENARIOS.size() + 1;

    private static boolean schemaCreated;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    /** !production 下注册；run-on-start 关闭，由用例显式调用 replay()。 */
    @Autowired RuleReplayRunner runner;

    /**
     * 契约十个场景在 PostGIS 上的期望（与 RuleReplayRegressionTest 的 H2 决策表同源）：
     * 四态、计划匹配等级、违规原因码（精确）、必须出现的未知原因码、是否评分、告警合并结果（null 表示不告警）。
     * 空间事实这里不打桩：距离、覆盖、边界接触全部由种子几何经 PostgisSpatialFactAdapter 推出。
     */
    record Scenario(String code, String legalStatus, String planMatch, List<String> violationReasons, List<String> unknownReasons, boolean scored, String alarmKind) { }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("legal", "LEGAL", "FULL", List.of(), List.of(), false, null),
            new Scenario("deviation", "ABNORMAL", "FULL", List.of("ROUTE_DEVIATION"), List.of(), true, "CREATED"),
            new Scenario("airspace-limit", "ILLEGAL", "FULL", List.of("AIRSPACE_ALTITUDE_EXCEEDED"), List.of(), true, "CREATED"),
            new Scenario("plan-altitude", "ABNORMAL", "FULL", List.of("PLAN_ALTITUDE_EXCEEDED"), List.of(), true, "CREATED"),
            new Scenario("boundary", "UNDETERMINED", "FULL", List.of(), List.of("BOUNDARY_POLICY_UNKNOWN"), false, null),
            new Scenario("no-plan", "ILLEGAL", "NONE", List.of("NO_AUTHORIZATION"), List.of(), true, "CREATED"),
            new Scenario("degraded", "UNDETERMINED", "FULL", List.of(), List.of("LOW_CONFIDENCE", "TRACK_BRIDGED"), false, null),
            new Scenario("datum-mismatch", "UNDETERMINED", "FULL", List.of(), List.of("ALTITUDE_DATUM_OR_RANGE_UNKNOWN"), false, null),
            new Scenario("merge", "ILLEGAL", "FULL", List.of("TEMPORARY_RESTRICTION_ACTIVE"), List.of(), true, "CREATED"),
            new Scenario("cross-scope", "ILLEGAL", "NONE", List.of("NO_AUTHORIZATION"), List.of(), true, "CREATED"));

    private String suffix;
    private String org;
    private String district;
    private String ruleSetId;
    private String publishedVersionId;
    private String draftVersionId;
    private String c03RuleVersionId;
    private String targetId;
    private String routeId;
    private String routeVersionId;
    private String planId;
    private String runId;
    private String evaluationId;
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
        registry.add("app.rule-engine.enabled", () -> "false");
        registry.add("app.rule-engine.replay.run-on-start", () -> "false");
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
        org = id(); district = id(); ruleSetId = id(); publishedVersionId = id(); draftVersionId = id(); c03RuleVersionId = id();
        targetId = id(); routeId = id(); routeVersionId = id(); planId = id(); runId = id(); evaluationId = id();
        userA = id(); userB = id();

        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "ORG-S7-" + suffix, "阶段七验证机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "DIST-S7-" + suffix, "阶段七验证区域");

        // 规则集：一个已发布（DEMO 参数）版本作为 ACTIVE，一个草稿版本用于证明“未发布参数仍可改”。
        jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at) values (?,?,1,'ACTIVE',?,'mock',?)",
                c03RuleVersionId, "C03-S7-" + suffix, T0, T0);
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,active_version_id,shadow_version_id,previous_active_version_id,version,created_at,updated_at) values (?,?,?,null,null,null,0,?,?)",
                ruleSetId, "LEGALITY-S7-" + suffix, "阶段七验证规则集", T0, T0);
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,valid_to,description,source_mode,created_at,published_at) values (?,?,1,'PUBLISHED','DEMO',?,null,'已发布验证版本','mock',?,?)",
                publishedVersionId, ruleSetId, T0, T0, T0);
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,valid_to,description,source_mode,created_at,published_at) values (?,?,2,'DRAFT','DEMO',?,null,'草稿验证版本','mock',?,null)",
                draftVersionId, ruleSetId, T0, T0);
        jdbc.update("update rule_set set active_version_id=? where rule_set_id=?", publishedVersionId, ruleSetId);
        jdbc.update("insert into rule_set_member (rule_set_version_id,rule_version_id,priority,enabled) values (?,?,30,true)", publishedVersionId, c03RuleVersionId);
        jdbc.update("insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note) values (?,?,'C02-3','tolerance_m','20','NUMBER','m','DEMO','验证参数')",
                id(), publishedVersionId);
        jdbc.update("insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note) values (?,?,'C02-3','tolerance_m','50','NUMBER','m','DEMO','草稿参数')",
                id(), draftVersionId);

        // 目标与计划：研判行自带 (owner_org_id, district_id)，复核接口的范围谓词只看这两列。
        jdbc.update("insert into target (target_id,target_no,object_type_code,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'UAV',?,?,?,'mock',?,?,?,?,0)",
                targetId, "TGT-S7-" + suffix, "SN-S7-" + suffix, T0, T0, org, district, T0, T0);
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,?,?,0)",
                routeId, "ROUTE-S7-" + suffix, "阶段七验证航线", org, district, T0, T0);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) values (?,?,1,ST_GeomFromText('LINESTRING(118.6 37.4,118.7 37.5)',4326),100,?,?)",
                routeVersionId, routeId, T0, T0);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'APPROVED','mock',?,?,?,?,?,?,?,?,0)",
                planId, "PLAN-S7-" + suffix, "SN-S7-" + suffix, T0.minusHours(1), T0.plusHours(1), routeVersionId, org, district, T0, T0);

        // 一次已完成的 ACTIVE 运行与一条待复核研判；复核并发用例围绕它展开。
        insertRun(runId, publishedVersionId, "ACTIVE", "MANUAL", null, "DONE");
        insertEvaluation(evaluationId, runId, publishedVersionId, "ACTIVE", "ABNORMAL", "[\"ROUTE_DEVIATION\"]", "[]");
        jdbc.update("insert into legality_review (evaluation_id,review_state,manual_status,version,owner_org_id,district_id,created_at,updated_at) values (?,'PENDING_REVIEW',null,0,?,?,?,?)",
                evaluationId, org, district, T0, T0);

        String role = role();
        sessionA = session(userA, role, "s7-a-" + suffix);
        sessionB = session(userB, role, "s7-b-" + suffix);
    }

    @Test
    @Order(1)
    void migrations041And042AndStage7RepeatableAreAppliedToIsolatedPostgresSchema() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success=true and version is not null", String.class);
        assertThat(versions).contains("202609050040", "202609050041", "202609050042");
        List<String> repeatables = jdbc.queryForList(
                "select description from flyway_schema_history where success=true and version is null", String.class);
        assertThat(repeatables).anyMatch(d -> d.toLowerCase().contains("stage7"));
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=false", Long.class)).isZero();

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema=? and table_name in ('rule_set','rule_set_version','rule_set_member','rule_param','rule_set_activation','rule_run','rule_evaluation','rule_engine_lease','legality_review','legality_review_history','alarm_merge_group','alarm_merge_member')",
                String.class, SCHEMA);
        assertThat(tables).containsExactlyInAnyOrder("rule_set", "rule_set_version", "rule_set_member", "rule_param", "rule_set_activation",
                "rule_run", "rule_evaluation", "rule_engine_lease", "legality_review", "legality_review_history", "alarm_merge_group", "alarm_merge_member");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.views where table_schema=? and table_name='v_rule_effect_fact'", Long.class, SCHEMA)).isEqualTo(1L);
        for (String column : List.of("violation_reasons", "hit_details", "unknown_reasons", "evidence_references", "input_snapshot")) {
            assertThat(jdbc.queryForObject(
                    "select data_type from information_schema.columns where table_schema=? and table_name='rule_evaluation' and column_name=?",
                    String.class, SCHEMA, column)).as(column).isEqualTo("jsonb");
        }
        List<String> permissions = jdbc.queryForList(
                "select permission_code from app_permission where permission_code in ('rule:read','rule:manage','assessment:evaluate','assessment:revise','assessment:escalate') order by permission_code",
                String.class);
        assertThat(permissions).containsExactly("assessment:escalate", "assessment:evaluate", "assessment:revise", "rule:manage", "rule:read");
        // 迁移只登记引擎来源目录，不插入任何规则集或研判：本 schema 中的规则集只能来自各用例夹具。
        assertThat(jdbc.queryForObject("select count(*) from integration_source where source_id like 'rule-engine-legality-%' and enabled=true and credential_ref is null", Long.class)).isEqualTo(3L);
        // 阶段 9 迁移 062 登记的 SPACE-RISK-DEMO（PUBLISHED+DEMO，未激活）属结构性目录（决策 9-30），不算阶段 7 的意外数据。
        assertThat(jdbc.queryForObject("select count(*) from rule_set where rule_set_code not like 'LEGALITY-S7-%' and rule_set_code<>'SPACE-RISK-DEMO'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from rule_set where rule_set_code='SPACE-RISK-DEMO' and (active_version_id is not null or shadow_version_id is not null)", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assessment_result", Long.class)).isZero();
    }

    @Test
    @Order(2)
    void ruleEvaluationAndRuleRunAreAppendOnlyInPostgres() {
        // 研判与运行记录是规则效果的证据链：UPDATE/DELETE 必须被触发器拒绝，纠错只能追加新行。
        assertThatThrownBy(() -> jdbc.update("update rule_evaluation set legal_status='LEGAL' where evaluation_id=?", evaluationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 连不改变业务含义的时间戳也不允许改：触发器拦的是 UPDATE 本身，不是某几列。
        assertThatThrownBy(() -> jdbc.update("update rule_evaluation set evaluated_at=? where evaluation_id=?", T0.plusMinutes(5), evaluationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from rule_evaluation where evaluation_id=?", evaluationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 唯一例外：C06 在研判行存在之后一次性回填告警关联；回填后同样冻结，不能再改第二次。
        assertThat(jdbc.update("update rule_evaluation set alarm_outcome=cast(? as jsonb) where evaluation_id=?", "{\"kind\":\"SUPPRESSED_SHADOW\"}", evaluationId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update rule_evaluation set alarm_outcome=cast(? as jsonb) where evaluation_id=?", "{\"kind\":\"CREATED\"}", evaluationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update rule_evaluation set alarm_outcome=null where evaluation_id=?", evaluationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select legal_status from rule_evaluation where evaluation_id=?", String.class, evaluationId)).isEqualTo("ABNORMAL");
        assertThat(jdbc.queryForObject("select alarm_outcome->>'kind' from rule_evaluation where evaluation_id=?", String.class, evaluationId)).isEqualTo("SUPPRESSED_SHADOW");

        // 已完成的运行不可改也不可删。
        assertThatThrownBy(() -> jdbc.update("update rule_run set status='FAILED' where run_id=?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update rule_run set evaluated_count=evaluated_count+1 where run_id=?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from rule_run where run_id=?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select status from rule_run where run_id=?", String.class, runId)).isEqualTo("DONE");
        // RUNNING 的运行只能被收尾一次；身份列（版本、触发方式、数据集、as_of）即使在 RUNNING 也不可改。
        String running = id();
        insertRun(running, publishedVersionId, "ACTIVE", "MANUAL", null, "RUNNING");
        assertThatThrownBy(() -> jdbc.update("update rule_run set trigger_kind='SCHEDULED' where run_id=?", running))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update rule_run set rule_set_version_id=? where run_id=?", draftVersionId, running))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("update rule_run set status='DONE', finished_at=?, evaluated_count=3 where run_id=?", T0.plusSeconds(9), running)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update rule_run set evaluated_count=4 where run_id=?", running))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select evaluated_count from rule_run where run_id=?", Integer.class, running)).isEqualTo(3);
    }

    @Test
    @Order(3)
    void publishedRuleParamsAreImmutableWhileDraftParamsRemainEditable() {
        // 已发布版本的参数是历史研判的输入证据：任何原位改写都会让同一研判的含义漂移。
        assertThatThrownBy(() -> jdbc.update("update rule_param set value_text='99' where rule_set_version_id=? and rule_code='C02-3'", publishedVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update rule_param set param_status='CONFIRMED' where rule_set_version_id=? and rule_code='C02-3'", publishedVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from rule_param where rule_set_version_id=?", publishedVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C02-3' and param_key='tolerance_m'", String.class, publishedVersionId)).isEqualTo("20");
        // 草稿版本尚未成为证据，参数可以继续调整。
        assertThat(jdbc.update("update rule_param set value_text='60' where rule_set_version_id=? and rule_code='C02-3'", draftVersionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select value_text from rule_param where rule_set_version_id=? and rule_code='C02-3' and param_key='tolerance_m'", String.class, draftVersionId)).isEqualTo("60");
        // (rule_set_version_id, rule_code, param_key) 唯一：同键不能有两个值。
        assertThatThrownBy(() -> jdbc.update(
                "insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note) values (?,?,'C02-3','tolerance_m','70','NUMBER','m','DEMO','重复键')",
                id(), draftVersionId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(4)
    void assessmentResultStaysAppendOnlyAndAcceptsAbnormalConclusion() {
        String assessmentId = id();
        assertThat(jdbc.update(
                "insert into assessment_result (assessment_id,plan_id,target_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at,evaluation_id,rule_set_version_id) values (?,?,?,?,?,?,'ABNORMAL',cast(? as jsonb),cast('[]' as jsonb),cast('[]' as jsonb),'mock',?,?,?)",
                assessmentId, planId, targetId, routeVersionId, c03RuleVersionId, T0, "[{\"rule_code\":\"C02-3\",\"result_code\":\"FAIL\",\"reason_code\":\"ROUTE_DEVIATION\"}]", T0, evaluationId, publishedVersionId)).isEqualTo(1);
        // 阶段 3 的只增触发器在 040/041 之后仍然生效；新增列也不能成为改写口子。
        assertThatThrownBy(() -> jdbc.update("update assessment_result set conclusion_code='LEGAL' where assessment_id=?", assessmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update assessment_result set supersedes_assessment_id=null, evaluation_id=null where assessment_id=?", assessmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from assessment_result where assessment_id=?", assessmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 结论枚举以外的值仍被检查约束拒绝；evaluation_id / rule_set_version_id 必须指向真实行。
        assertThatThrownBy(() -> jdbc.update(
                "insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at) values (?,?,?,?,?,'SUSPICIOUS',cast('[]' as jsonb),cast('[]' as jsonb),cast('[]' as jsonb),'mock',?)",
                id(), planId, routeVersionId, c03RuleVersionId, T0, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at,rule_set_version_id) values (?,?,?,?,?,'LEGAL',cast('[]' as jsonb),cast('[]' as jsonb),cast('[]' as jsonb),'mock',?,?)",
                id(), planId, routeVersionId, c03RuleVersionId, T0, T0, "missing-version-" + suffix)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at,supersedes_assessment_id) values (?,?,?,?,?,'LEGAL',cast('[]' as jsonb),cast('[]' as jsonb),cast('[]' as jsonb),'mock',?,?)",
                id(), planId, routeVersionId, c03RuleVersionId, T0, T0, "missing-assessment-" + suffix)).isInstanceOf(DataIntegrityViolationException.class);
        // 重算以新行 supersedes 旧行，旧行原样保留。
        String recomputed = id();
        assertThat(jdbc.update(
                "insert into assessment_result (assessment_id,plan_id,target_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,created_at,rule_set_version_id,supersedes_assessment_id) values (?,?,?,?,?,?,'LEGAL',cast('[]' as jsonb),cast('[]' as jsonb),cast('[]' as jsonb),'mock',?,?,?)",
                recomputed, planId, targetId, routeVersionId, c03RuleVersionId, T0.plusMinutes(1), T0.plusMinutes(1), publishedVersionId, assessmentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select conclusion_code from assessment_result where assessment_id=?", String.class, assessmentId)).isEqualTo("ABNORMAL");
        assertThat(jdbc.queryForObject("select count(*) from assessment_result where plan_id=?", Long.class, planId)).isEqualTo(2L);
    }

    @Test
    @Order(5)
    void replayRunIsUniquePerDatasetAndRuleSetVersionOnly() {
        String dataset = "stage7-verify-" + suffix;
        insertRun(id(), publishedVersionId, "ACTIVE", "REPLAY", dataset, "DONE");
        // 同数据集、同版本的第二次回放必须被部分唯一索引拒绝：回放回归是“可重复得到同一结论”，不是再跑一遍写第二份。
        // 拒绝必须来自 R__stage7 的部分唯一索引本身，而不是别的约束碰巧挡住。
        assertThatThrownBy(() -> insertRun(id(), publishedVersionId, "ACTIVE", "REPLAY", dataset, "DONE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_stage7_rule_run_replay_dataset");
        // 部分唯一只约束 REPLAY：其他触发方式与其他版本/数据集不受影响。
        insertRun(id(), draftVersionId, "SHADOW", "REPLAY", dataset, "DONE");
        insertRun(id(), publishedVersionId, "ACTIVE", "REPLAY", dataset + "-b", "DONE");
        insertRun(id(), publishedVersionId, "ACTIVE", "MANUAL", null, "DONE");
        insertRun(id(), publishedVersionId, "ACTIVE", "MANUAL", null, "DONE");
        insertRun(id(), publishedVersionId, "ACTIVE", "SCHEDULED", null, "DONE");
        List<Map<String, Object>> replays = jdbc.queryForList(
                "select run_id,mode,rule_set_version_id,trigger_kind,replay_dataset_code from rule_run where trigger_kind='REPLAY' and replay_dataset_code=? order by run_id", dataset);
        assertThat(replays).as("同数据集的回放运行（每个版本恰一条）: " + replays).hasSize(2);
        assertThat(replays).extracting(row -> (String) row.get("rule_set_version_id")).containsExactlyInAnyOrder(publishedVersionId, draftVersionId);
        assertThat(jdbc.queryForObject("select count(*) from rule_run where trigger_kind='REPLAY' and replay_dataset_code=?", Long.class, dataset + "-b")).isEqualTo(1L);
        // 夹具已有一条 MANUAL 运行（复核用例的研判所属），这里再加两条：非回放触发不受唯一索引约束。
        assertThat(jdbc.queryForObject("select count(*) from rule_run where rule_set_version_id=? and trigger_kind='MANUAL'", Long.class, publishedVersionId)).isEqualTo(3L);
    }

    @Test
    @Order(6)
    void twoRealConnectionsReviewingSameEvaluationCommitExactlyOneRevision() throws Exception {
        List<MvcResult> results = race(
                revise(sessionA, evaluationId, "CONFIRM", null, "并发连接 A 确认研判", 0, "race-review-a-" + UUID.randomUUID()),
                revise(sessionB, evaluationId, "REJECT", null, "并发连接 B 驳回研判", 0, "race-review-b-" + UUID.randomUUID()));
        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses).containsExactly(200, 409);
        MvcResult rejected = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(code(rejected)).isEqualTo("VERSION_CONFLICT");
        MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        String winnerState = json.readTree(winner.getResponse().getContentAsString()).path("data").path("review").path("state").asText();
        assertThat(winnerState).isIn("CONFIRMED", "REJECTED");

        // 断言数据库行而不只是 HTTP：版本恰好加一、恰一条历史、恰一条成功审计；研判行本身不被触碰。
        assertThat(jdbc.queryForObject("select version from legality_review where evaluation_id=?", Long.class, evaluationId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select review_state from legality_review where evaluation_id=?", String.class, evaluationId)).isEqualTo(winnerState);
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version,previous_state,resulting_state,conclusion from legality_review_history where evaluation_id=?", evaluationId);
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
            assertThat(row.get("previous_state")).isEqualTo("PENDING_REVIEW");
            assertThat(row.get("resulting_state")).isEqualTo(winnerState);
            assertThat(row.get("conclusion")).isEqualTo(winnerState.equals("CONFIRMED") ? "CONFIRM" : "REJECT");
        });
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='legality_evaluation_revised' and object_id=? and result='SUCCESS'", Long.class, evaluationId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select legal_status from rule_evaluation where evaluation_id=?", String.class, evaluationId)).isEqualTo("ABNORMAL");
        // 输家的事务整体回滚：它的幂等占位不能留下，否则重试会被误判为 replay。
        String loser = winner.getRequest().getHeader("Authorization").endsWith(sessionA) ? userB : userA;
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=?", Long.class, loser)).isZero();
    }

    @Test
    @Order(7)
    void legalityReviewHistoryIsUniquePerVersionAndAppendOnly() {
        String historyId = id();
        jdbc.update("insert into legality_review_history (history_id,evaluation_id,version,previous_state,resulting_state,conclusion,status_before,status_after,note,actor_id,created_at) values (?,?,1,'PENDING_REVIEW','CONFIRMED','CONFIRM','ABNORMAL','ABNORMAL','首次复核',?,?)",
                historyId, evaluationId, userA, T0);
        // (evaluation_id, version) 唯一：两条同版本历史意味着并发写穿透，数据库必须拒绝。
        assertThatThrownBy(() -> jdbc.update(
                "insert into legality_review_history (history_id,evaluation_id,version,previous_state,resulting_state,conclusion,status_before,status_after,note,actor_id,created_at) values (?,?,1,'PENDING_REVIEW','REJECTED','REJECT','ABNORMAL','ABNORMAL','重复版本',?,?)",
                id(), evaluationId, userA, T0)).isInstanceOf(DataIntegrityViolationException.class);
        // 结论枚举与备注长度由检查约束守住。
        assertThatThrownBy(() -> jdbc.update(
                "insert into legality_review_history (history_id,evaluation_id,version,previous_state,resulting_state,conclusion,status_before,status_after,note,actor_id,created_at) values (?,?,2,'CONFIRMED','CONFIRMED','APPROVE','ABNORMAL','ABNORMAL','非法结论',?,?)",
                id(), evaluationId, userA, T0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into legality_review_history (history_id,evaluation_id,version,previous_state,resulting_state,conclusion,status_before,status_after,note,actor_id,created_at) values (?,?,2,'CONFIRMED','CONFIRMED','CONFIRM','ABNORMAL','ABNORMAL','   ',?,?)",
                id(), evaluationId, userA, T0)).isInstanceOf(DataIntegrityViolationException.class);
        // 契约要求复核历史只增（R__stage7 的 E2 片段）：UPDATE/DELETE 必须被触发器拒绝。
        assertThatThrownBy(() -> jdbc.update("update legality_review_history set note='改写' where history_id=?", historyId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from legality_review_history where history_id=?", historyId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select note from legality_review_history where history_id=?", String.class, historyId)).isEqualTo("首次复核");
    }

    /**
     * 契约十个场景在 PostGIS 上端到端回放：种子几何 → PostgisSpatialFactAdapter（ST_Touches/ST_Covers/geography 距离）→ C01/C02/C03 → C06。
     * 同一用例内顺带覆盖 C06 “同目标两次 ILLEGAL 只一条告警 + MERGED”、OPEN 合并组的部分唯一索引，以及回放的“已有同数据集即跳过、不覆盖人工复核”。
     * 种子类在 postgres-test profile 下不注册（它只在 local/test 生效），这里直接实例化写入本 schema；它是幂等的。
     */
    @Test
    @Order(8)
    void contractScenariosReplayOnPostgisAndMergeSameTargetIntoOneAlarm() {
        new LocalStage7RuleEngineSeeder(jdbc).run(null);
        assertThat(jdbc.queryForObject("select active_version_id from rule_set where rule_set_code=?", String.class, LocalStage7RuleEngineSeeder.RULE_SET_CODE))
                .isEqualTo(LocalStage7RuleEngineSeeder.VERSION_1);
        // 几何事实先于引擎单独核对：偏航目标到中心线 70–150 m 之间（偏航但仍匹配计划）、boundary 目标恰在 P1 边界上。
        BigDecimal distance = jdbc.queryForObject(
                "select ST_Distance(rv.centerline::geography, s.location::geography) from route_version rv, target_latest_state s where rv.route_version_id=? and s.target_id=?",
                BigDecimal.class, "seed-stage7-rv-deviation", LocalStage7RuleEngineSeeder.targetId("deviation"));
        assertThat(distance.doubleValue()).isBetween(70.0, 150.0);
        assertThat(jdbc.queryForObject("select ST_Touches(av.boundary, s.location) from airspace_version av, target_latest_state s where av.airspace_version_id='seed-stage7-av-p1' and s.target_id=?",
                Boolean.class, LocalStage7RuleEngineSeeder.targetId("boundary"))).isTrue();

        ReplayReport report = runner.replay();
        assertThat(report.skipped()).isFalse();
        assertThat(report.runId()).isNotNull();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.evaluatedCount()).isEqualTo(REPLAY_SUBJECTS);
        Map<String, Object> run = jdbc.queryForMap("select status,mode,trigger_kind,replay_dataset_code,rule_set_version_id,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,error_summary from rule_run where run_id=?", report.runId());
        assertThat(run.get("status")).isEqualTo("DONE");
        assertThat(run.get("mode")).isEqualTo("ACTIVE");
        assertThat(run.get("trigger_kind")).isEqualTo("REPLAY");
        assertThat(run.get("replay_dataset_code")).isEqualTo(RuleReplayRunner.DATASET);
        assertThat(run.get("rule_set_version_id")).isEqualTo(LocalStage7RuleEngineSeeder.VERSION_1);
        assertThat(((Number) run.get("subject_count")).intValue()).isEqualTo(REPLAY_SUBJECTS);
        assertThat(((Number) run.get("evaluated_count")).intValue()).isEqualTo(REPLAY_SUBJECTS);
        assertThat(run.get("error_summary")).as("没有任何主体评估失败").isNull();
        assertThat(((Number) run.get("alarm_created_count")).longValue()).isEqualTo(SCENARIOS.stream().filter(s -> "CREATED".equals(s.alarmKind())).count());
        assertThat(((Number) run.get("alarm_merged_count")).longValue()).isEqualTo(1L);

        for (Scenario scenario : SCENARIOS) {
            List<Map<String, Object>> rows = jdbc.queryForList("select evaluation_id,mode,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,unknown_reasons,"
                    + "assessment_id,alarm_id,alarm_outcome->>'kind' as alarm_kind,owner_org_id,district_id from rule_evaluation where run_id=? and target_id=? order by evaluated_at desc,evaluation_id desc",
                    report.runId(), LocalStage7RuleEngineSeeder.targetId(scenario.code()));
            assertThat(rows).as(scenario.code() + " 研判条数").hasSize("merge".equals(scenario.code()) ? 2 : 1);
            for (Map<String, Object> row : rows) assertScenario(scenario, row);
        }

        // C06：merge 目标两次 ILLEGAL 只建一条告警、一条待核实事件、一个 OPEN 合并组（hit_count=2），第二次为 MERGED 成员且不带告警。
        String mergeTarget = LocalStage7RuleEngineSeeder.targetId("merge");
        List<Map<String, Object>> mergeRows = jdbc.queryForList("select evaluation_id,alarm_id,alarm_outcome->>'kind' as alarm_kind,alarm_outcome->>'group_id' as group_id from rule_evaluation where run_id=? and target_id=?", report.runId(), mergeTarget);
        assertThat(mergeRows.stream().map(r -> (String) r.get("alarm_kind")).toList()).containsExactlyInAnyOrder("CREATED", "MERGED");
        Map<String, Object> createdRow = mergeRows.stream().filter(r -> "CREATED".equals(r.get("alarm_kind"))).findFirst().orElseThrow();
        Map<String, Object> mergedRow = mergeRows.stream().filter(r -> "MERGED".equals(r.get("alarm_kind"))).findFirst().orElseThrow();
        List<String> alarms = jdbc.queryForList("select alarm_id from alarm where target_id=? and alarm_type='RULE_LEGALITY'", String.class, mergeTarget);
        assertThat(alarms).hasSize(1);
        assertThat(createdRow.get("alarm_id")).isEqualTo(alarms.get(0));
        assertThat(mergedRow.get("alarm_id")).as("合并不建第二条告警").isNull();
        assertThat(mergedRow.get("group_id")).isEqualTo(createdRow.get("group_id"));
        Map<String, Object> alarm = jdbc.queryForMap("select source_id,source_alarm_id,severity,source_mode,owner_org_id,district_id from alarm where alarm_id=?", alarms.get(0));
        assertThat(alarm.get("source_id")).isEqualTo("rule-engine-legality-mock");
        assertThat(alarm.get("source_mode")).isEqualTo("mock");
        assertThat(alarm.get("source_alarm_id")).isEqualTo("eval:" + createdRow.get("evaluation_id"));
        assertThat(alarm.get("owner_org_id")).isEqualTo(LocalStage7RuleEngineSeeder.ORG);
        assertThat(alarm.get("district_id")).isEqualTo(LocalStage7RuleEngineSeeder.DISTRICT);
        assertThat(jdbc.queryForObject("select count(*) from uav_event where alarm_id=? and state_code='PENDING_VERIFICATION'", Long.class, alarms.get(0))).isEqualTo(1L);
        List<Map<String, Object>> groups = jdbc.queryForList("select group_id,state,hit_count,first_alarm_id,latest_alarm_id,current_severity,owner_org_id,district_id from alarm_merge_group where target_id=? and alarm_type='RULE_LEGALITY'", mergeTarget);
        assertThat(groups).hasSize(1);
        Map<String, Object> group = groups.get(0);
        assertThat(group.get("group_id")).isEqualTo(createdRow.get("group_id"));
        assertThat(group.get("state")).isEqualTo("OPEN");
        assertThat(((Number) group.get("hit_count")).intValue()).isEqualTo(2);
        assertThat(group.get("first_alarm_id")).isEqualTo(alarms.get(0));
        assertThat(group.get("latest_alarm_id")).isEqualTo(alarms.get(0));
        assertThat(group.get("current_severity")).isEqualTo(alarm.get("severity"));
        List<Map<String, Object>> members = jdbc.queryForList("select evaluation_id,alarm_id,member_kind from alarm_merge_member where group_id=?", group.get("group_id"));
        assertThat(members).hasSize(2);
        assertThat(members.stream().filter(m -> "CREATED".equals(m.get("member_kind"))).findFirst().orElseThrow())
                .containsEntry("evaluation_id", createdRow.get("evaluation_id")).containsEntry("alarm_id", alarms.get(0));
        assertThat(members.stream().filter(m -> "MERGED".equals(m.get("member_kind"))).findFirst().orElseThrow())
                .containsEntry("evaluation_id", mergedRow.get("evaluation_id")).containsEntry("alarm_id", null);
        // 同目标同类型只允许一个 OPEN 合并组：R__stage7 的部分唯一索引在数据库层兜底。
        assertThatThrownBy(() -> jdbc.update("insert into alarm_merge_group (group_id,target_id,alarm_type,rule_set_id,state,current_severity,first_alarm_id,latest_alarm_id,hit_count,window_opened_at,window_expires_at,last_hit_at,owner_org_id,district_id,version,created_at,updated_at) values (?,?,'RULE_LEGALITY',?,'OPEN',?,?,?,1,?,?,?,?,?,0,?,?)",
                id(), mergeTarget, LocalStage7RuleEngineSeeder.RULE_SET_ID, alarm.get("severity"), alarms.get(0), alarms.get(0), T0, T0, T0, LocalStage7RuleEngineSeeder.ORG, LocalStage7RuleEngineSeeder.DISTRICT, T0, T0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from alarm_merge_group where target_id=? and state='OPEN'", Long.class, mergeTarget)).isEqualTo(1L);

        // cross-scope：研判与告警都落在另一元组。
        Map<String, Object> cross = jdbc.queryForMap("select owner_org_id,district_id,alarm_id from rule_evaluation where run_id=? and target_id=?", report.runId(), LocalStage7RuleEngineSeeder.targetId("cross-scope"));
        assertThat(cross.get("owner_org_id")).isEqualTo(LocalStage7RuleEngineSeeder.OTHER_ORG);
        assertThat(cross.get("district_id")).isEqualTo(LocalStage7RuleEngineSeeder.OTHER_DISTRICT);
        assertThat(jdbc.queryForObject("select owner_org_id||'/'||district_id from alarm where alarm_id=?", String.class, cross.get("alarm_id")))
                .isEqualTo(LocalStage7RuleEngineSeeder.OTHER_ORG + "/" + LocalStage7RuleEngineSeeder.OTHER_DISTRICT);

        // 第二次回放：同数据集 + 同版本已存在 → 跳过，不新增运行，不覆盖人工复核。
        String legal = jdbc.queryForObject("select evaluation_id from rule_evaluation where run_id=? and target_id=?", String.class, report.runId(), LocalStage7RuleEngineSeeder.targetId("legal"));
        assertThat(jdbc.update("update legality_review set review_state='CONFIRMED',manual_status='LEGAL',version=1 where evaluation_id=?", legal)).isEqualTo(1);
        long runs = jdbc.queryForObject("select count(*) from rule_run", Long.class);
        ReplayReport again = runner.replay();
        assertThat(again.skipped()).isTrue();
        assertThat(again.runId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from rule_run", Long.class)).isEqualTo(runs);
        assertThat(jdbc.queryForObject("select count(*) from rule_run where trigger_kind='REPLAY' and replay_dataset_code=? and rule_set_version_id=?", Long.class, RuleReplayRunner.DATASET, LocalStage7RuleEngineSeeder.VERSION_1)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select review_state||'/'||manual_status||'/'||version from legality_review where evaluation_id=?", String.class, legal)).isEqualTo("CONFIRMED/LEGAL/1");
        // 回放写入的研判在 PostgreSQL 上同样只增。
        assertThatThrownBy(() -> jdbc.update("update rule_evaluation set legal_status='LEGAL' where evaluation_id=?", createdRow.get("evaluation_id")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertScenario(Scenario scenario, Map<String, Object> row) {
        String code = scenario.code();
        String evaluationId = (String) row.get("evaluation_id");
        assertThat(row.get("mode")).as(code).isEqualTo("ACTIVE");
        assertThat(row.get("freshness_code")).as(code + " 回放以观测时刻为评估时点").isEqualTo("REPLAY");
        assertThat(row.get("legal_status")).as(code + " legal_status").isEqualTo(scenario.legalStatus());
        assertThat(row.get("plan_match_code")).as(code + " plan_match_code").isEqualTo(scenario.planMatch());
        assertThat(strings(row.get("violation_reasons"))).as(code + " violation_reasons").containsExactlyInAnyOrderElementsOf(scenario.violationReasons());
        assertThat(strings(row.get("unknown_reasons"))).as(code + " unknown_reasons").containsAll(scenario.unknownReasons());
        if (scenario.scored()) {
            assertThat(row.get("score")).as(code + " 评分").isNotNull();
            assertThat(row.get("grade")).as(code + " 等级").isIn("HIGH", "MEDIUM", "LOW");
        } else {
            assertThat(row.get("score")).as(code + " 非 ILLEGAL/ABNORMAL 不评分").isNull();
            assertThat(row.get("grade")).as(code).isNull();
        }
        boolean projected = "FULL".equals(scenario.planMatch()) || "PARTIAL".equals(scenario.planMatch());
        if (projected) {
            assertThat(row.get("assessment_id")).as(code + " 投影").isNotNull();
            assertThat(jdbc.queryForObject("select conclusion_code from assessment_result where assessment_id=? and evaluation_id=?", String.class, row.get("assessment_id"), evaluationId))
                    .isEqualTo(scenario.legalStatus());
        } else {
            assertThat(row.get("assessment_id")).as(code + " 无计划不投影").isNull();
        }
        assertThat(jdbc.queryForObject("select review_state||'/'||version||'/'||owner_org_id||'/'||district_id from legality_review where evaluation_id=?", String.class, evaluationId))
                .as(code + " 复核行").isEqualTo("PENDING_REVIEW/0/" + row.get("owner_org_id") + "/" + row.get("district_id"));
        if (scenario.alarmKind() == null) {
            assertThat(row.get("alarm_kind")).as(code + " 不告警").isNull();
            assertThat(row.get("alarm_id")).as(code).isNull();
        } else if (!"merge".equals(code)) {
            assertThat(row.get("alarm_kind")).as(code + " 告警结果").isEqualTo(scenario.alarmKind());
            assertThat(row.get("alarm_id")).as(code + " 告警关联").isNotNull();
        }
    }

    private List<String> strings(Object stored) {
        List<String> output = new ArrayList<>();
        if (stored == null) return output;
        try {
            JsonNode node = json.readTree(String.valueOf(stored));
            if (node.isTextual()) node = json.readTree(node.textValue());
            for (JsonNode item : node) output.add(item.asText());
        } catch (Exception ex) {
            throw new AssertionError("JSON 列不可解析: " + stored, ex);
        }
        return output;
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

    private String code(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.path("error").path("code").asText();
    }

    private static MockHttpServletRequestBuilder revise(String session, String evaluationId, String conclusion, String overrideStatus,
            String note, long version, String key) {
        String override = overrideStatus == null ? "" : ",\"override_status\":\"" + overrideStatus + "\"";
        return post("/api/v1/legality-evaluations/" + evaluationId + "/revisions").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"" + conclusion + "\"" + override + ",\"note\":\"" + note + "\",\"expected_version\":" + version + "}");
    }

    private void insertRun(String id, String versionId, String mode, String trigger, String dataset, String status) {
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,replay_dataset_code,triggered_by,as_of,started_at,finished_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,error_summary,source_mode,created_at) values (?,?,?,?,?,?,null,?,?,?,?,1,1,0,0,null,'mock',?)",
                id, ruleSetId, versionId, mode, trigger, dataset, T0, T0, T0.plusSeconds(1), status, T0);
    }

    private void insertEvaluation(String id, String run, String versionId, String mode, String legalStatus, String violations, String unknowns) {
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,track_id,plan_id,route_version_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,supersedes_evaluation_id,assessment_id,alarm_outcome,alarm_id,owner_org_id,district_id,source_mode,created_at) values (?,?,?,?,'TARGET',?,null,?,?,?,?,?,'FRESH','FULL',?,null,null,cast(? as jsonb),cast('[]' as jsonb),cast(? as jsonb),cast('[]' as jsonb),cast('{}' as jsonb),null,null,null,null,?,?,'mock',?)",
                id, run, versionId, mode, targetId, planId, routeVersionId, T0, T0, T0, legalStatus, violations, unknowns, org, district, T0);
    }

    private String role() {
        String role = "ROLE-S7-PG-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'assessment:read','READ',false,current_timestamp),(?,'assessment:revise','OP',false,current_timestamp),(?,'assessment:evaluate','OP',false,current_timestamp),(?,'target:read','READ',false,current_timestamp),(?,'alarm:read','READ',false,current_timestamp),(?,'rule:read','READ',false,current_timestamp)",
                role, role, role, role, role, role);
        return role;
    }

    /** ASSIGNED 用户配同一 (org,district) 授权元组，走与生产一致的精确范围谓词。 */
    private String session(String user, String roleCode, String account) {
        String token = UUID.randomUUID().toString();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                user, account, "阶段七复核员", roleCode);
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
            throw new IllegalStateException("Refusing Stage 7 verification outside a stage456_verify_ database");
        }
        String extension = root.queryForObject("select extversion from pg_extension where extname='postgis'", String.class);
        if (extension == null || extension.isBlank()) {
            throw new IllegalStateException("PostGIS is required for Stage 7 verification");
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
            throw new IllegalStateException("Unsafe Stage 7 verification schema");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
