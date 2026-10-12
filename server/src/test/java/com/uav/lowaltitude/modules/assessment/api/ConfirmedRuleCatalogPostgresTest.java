package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.uav.lowaltitude.integration.mock.LocalQaRuleCatalog;
import com.uav.lowaltitude.modules.assessment.engine.RuleParamLoader;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Configuration publication is seed-independent, historical versions stay immutable, and activation remains an audited action. */
@SpringBootTest(properties = "app.bootstrap-admin.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class ConfirmedRuleCatalogPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    private static final String LEGALITY = "legality-confirmed-20261008";
    private static final String REVISION = "legality-confirmed-20261008-r2";
    private static final String SPACE = "space-risk-confirmed-20261008";
    private static final String CURRENT_LEGALITY = "legality-current-20261009";
    private static final String CURRENT_SPACE = "space-current-20261009";
    private static final String HASH = "b6e07da1a820843ea2b3634fa64fe2cc08a043c3f4d9c7f8274c685506877566";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.enabled", () -> false);
        registry.add("app.rule-engine.allow-demo-active", () -> false);
    }

    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @Test void currentParametersAreConfirmedWithoutChangingValuesOrPublishedHistory() {
        new LocalQaRuleCatalog(jdbc).run(new DefaultApplicationArguments(new String[0]));
        for (var pair : Map.of("seed-stage7-rsv-1", "legality-current-20261009",
                "space-risk-demo-v1", "space-current-20261009").entrySet()) {
            String fields = "rule_code,param_key,value_text,value_type,unit";
            var original = jdbc.queryForList("SELECT " + fields + " FROM rule_param WHERE rule_set_version_id=? ORDER BY rule_code,param_key", pair.getKey());
            var confirmed = jdbc.queryForList("SELECT " + fields + " FROM rule_param WHERE rule_set_version_id=? ORDER BY rule_code,param_key", pair.getValue());
            assertThat(original).isNotEmpty();
            assertThat(confirmed).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT param_status FROM rule_set_version WHERE rule_set_version_id=?", String.class, pair.getKey())).isEqualTo("DEMO");
            assertThat(jdbc.queryForObject("SELECT param_status FROM rule_set_version WHERE rule_set_version_id=?", String.class, pair.getValue())).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_param WHERE rule_set_version_id=? AND param_status<>'CONFIRMED'", Long.class, pair.getValue())).isZero();
            assertThat(jdbc.queryForList("SELECT v.rule_code,m.priority,m.enabled FROM rule_set_member m JOIN rule_version v USING(rule_version_id) WHERE m.rule_set_version_id=? ORDER BY v.rule_code", pair.getValue()))
                    .isEqualTo(jdbc.queryForList("SELECT v.rule_code,m.priority,m.enabled FROM rule_set_member m JOIN rule_version v USING(rule_version_id) WHERE m.rule_set_version_id=? ORDER BY v.rule_code", pair.getKey()));
            assertThat(jdbc.queryForObject("SELECT description FROM rule_set_version WHERE rule_set_version_id=?", String.class, pair.getValue()))
                    .contains("2026-10-09", "4cec49368ba934683290bef91686d6227b6a4d94444f3e4aa194f653a34c5dd5", "不改变");
            assertThatThrownBy(() -> jdbc.update("UPDATE rule_param SET value_text='999' WHERE rule_set_version_id=?", pair.getValue()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject("SELECT value_text FROM rule_param WHERE rule_set_version_id='legality-current-20261009' AND rule_code='C03' AND param_key='no_plan_status'", String.class)).isEqualTo("ILLEGAL");
    }

    @Test void cleanInstallationPublishesOnlyConfigurationAndActivatesThroughExistingApi() throws Exception {
        for (String table : new String[]{"target", "flight_plan", "alarm", "flight_risk"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_set WHERE active_version_id IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_set_activation", Long.class)).isZero();
        assertPublishedCatalog(jdbc);
        String actor = UUID.randomUUID().toString();
        String token = manager(actor);
        mvc.perform(get("/api/v1/rule-sets/LEGALITY-DEMO/versions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.rule_set_version_id=='" + LEGALITY + "')].activation_allowed").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.data.items[?(@.rule_set_version_id=='" + LEGALITY + "')].activation_block_reason").value(org.hamcrest.Matchers.contains("版本已撤回，不能启用")));
        mvc.perform(post("/api/v1/rule-sets/LEGALITY-DEMO/activate")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rule_set_version_id\":\"" + LEGALITY + "\",\"note\":\"已撤回版本应阻断\",\"expected_version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RULE_VERSION_NOT_PUBLISHED"));
        for (var version : Map.of("LEGALITY-DEMO", CURRENT_LEGALITY, "SPACE-RISK-DEMO", CURRENT_SPACE).entrySet()) {
            mvc.perform(get("/api/v1/rule-set-versions/" + version.getValue()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.param_status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.is_active").value(false));
            mvc.perform(post("/api/v1/rule-sets/" + version.getKey() + "/activate")
                            .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rule_set_version_id\":\"" + version.getValue() + "\",\"note\":\"确认书第二、四部分：隔离验收\",\"expected_version\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.active_version_id").value(version.getValue()));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_set_activation WHERE actor_id=? AND kind='ACTIVATE'", Long.class, actor)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE user_id=? AND action='rule_set_activated' AND result='SUCCESS'", Long.class, actor)).isEqualTo(2);
        for (String version : new String[]{LEGALITY, REVISION, SPACE}) {
            assertThat(jdbc.queryForObject("SELECT source_mode FROM rule_set_version WHERE rule_set_version_id=?", String.class, version)).isEqualTo("live");
        }
        // A confirmed configuration does not rewrite the provenance of observations or plans.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM target", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"合法性研判演示规则集", "客户自定义研判名称"})
    void upgradePreservesOldVersionsAndActivePointers(String previousName) {
        String schema = "confirmed_rules_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.matches("confirmed_rules_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
        DataSource rootSource = source(required("POSTGRES_TEST_URL"));
        JdbcTemplate root = new JdbcTemplate(rootSource);
        assertThat(root.queryForObject("SELECT current_database()", String.class)).matches("stage456_verify_[a-z0-9_]+");
        root.execute("CREATE SCHEMA " + schema);
        try {
            migration(rootSource, schema, "202610079101").migrate();
            JdbcTemplate old = new JdbcTemplate(source(required("POSTGRES_TEST_URL") + "?currentSchema=" + schema + ",public"));
            String set = UUID.randomUUID().toString();
            old.update("INSERT INTO rule_set(rule_set_id,rule_set_code,name,version,created_at,updated_at) VALUES(?,'LEGALITY-DEMO',?,6,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", set, previousName);
            String member = UUID.randomUUID().toString();
            old.update("INSERT INTO rule_version(rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at) VALUES(?,'C01',1,'ACTIVE',CURRENT_TIMESTAMP,'mock',CURRENT_TIMESTAMP)", member);
            String[] versions = {UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()};
            for (int i = 0; i < versions.length; i++) {
                old.update("INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at) VALUES(?,?,?,'PUBLISHED','DEMO',CURRENT_TIMESTAMP,'既有演示版本','mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", versions[i], set, i + 1);
                old.update("INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled) VALUES(?,?,100,TRUE)", versions[i], member);
                old.update("INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,param_status,note) VALUES(?,?,'C02-3','tolerance_m',?,'NUMBER','DEMO','不可回写的旧参数')", UUID.randomUUID().toString(), versions[i], i == 1 ? "50" : "20");
            }
            old.update("UPDATE rule_set SET active_version_id=?,shadow_version_id=?,previous_active_version_id=? WHERE rule_set_id=?", versions[0], versions[2], versions[1], set);
            var beforeHead = new LinkedHashMap<>(old.queryForMap("SELECT * FROM rule_set WHERE rule_set_id=?", set));
            var oldVersions = old.queryForList("SELECT * FROM rule_set_version ORDER BY rule_set_version_id");
            var oldParams = old.queryForList("SELECT * FROM rule_param ORDER BY rule_param_id");
            var oldMembers = old.queryForList("SELECT * FROM rule_set_member ORDER BY rule_set_version_id,rule_version_id");
            var oldDefinitions = old.queryForList("SELECT * FROM rule_version ORDER BY rule_version_id");
            var migrate = migration(rootSource, schema, null);
            migrate.migrate();
            assertPublishedCatalog(old);
            if (previousName.equals("合法性研判演示规则集")) beforeHead.put("name", "合法性研判规则集");
            assertThat(old.queryForMap("SELECT * FROM rule_set WHERE rule_set_id=?", set)).isEqualTo(beforeHead);
            assertThat(old.queryForList("SELECT * FROM rule_set_version WHERE rule_set_version_id NOT IN (?,?,?,?,?) ORDER BY rule_set_version_id", LEGALITY, REVISION, SPACE, CURRENT_LEGALITY, CURRENT_SPACE)).isEqualTo(oldVersions);
            assertThat(old.queryForList("SELECT * FROM rule_param WHERE rule_set_version_id NOT IN (?,?,?,?,?) ORDER BY rule_param_id", LEGALITY, REVISION, SPACE, CURRENT_LEGALITY, CURRENT_SPACE)).isEqualTo(oldParams);
            assertThat(old.queryForList("SELECT * FROM rule_set_member WHERE rule_set_version_id NOT IN (?,?,?,?,?) ORDER BY rule_set_version_id,rule_version_id", LEGALITY, REVISION, SPACE, CURRENT_LEGALITY, CURRENT_SPACE)).isEqualTo(oldMembers);
            assertThat(old.queryForList("SELECT * FROM rule_version WHERE rule_version_id NOT LIKE 'confirmed-20261008-%' ORDER BY rule_version_id")).isEqualTo(oldDefinitions);
            assertThat(old.queryForObject("SELECT version_no FROM rule_set_version WHERE rule_set_version_id=?", Integer.class, LEGALITY)).isEqualTo(4);
            assertThat(old.queryForObject("SELECT COUNT(*) FROM rule_set_activation", Long.class)).isZero();
            assertThat(migrate.migrate().migrationsExecuted).isZero();
        } finally { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Test void publishedInputsCannotBeChangedOrDeletedAndUnlistedCoefficientsRemainExplicit() {
        for (String version : new String[]{LEGALITY, REVISION, SPACE}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE rule_param SET value_text='999' WHERE rule_set_version_id=?", version))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM rule_param WHERE rule_set_version_id=?", version))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        String note = jdbc.queryForObject("SELECT note FROM rule_param WHERE rule_set_version_id=? AND rule_code='C03' AND param_key='w.violation'", String.class, LEGALITY);
        assertThat(note).contains("客户确认业务方法", "数值沿用既有实现系数，未单独确认", "不表示客户逐值确认");
        var params = new RuleParamLoader(jdbc).load(LEGALITY);
        assertThat(params.number("C03", "w.violation")).isEqualByComparingTo("0.40");
        assertThat(params.string("C01", "distance_basis")).isEqualTo("ROUTE_CENTERLINE");
        assertThat(params.bool("C01", "time_window_end_inclusive")).isTrue();
        assertThat(params.bool("C02-4", "grace_end_inclusive")).isTrue();
        assertThat(params.string("C03", "own_plan_time_mismatch_policy")).isEqualTo("OVERRUN");
        assertThat(params.string("C03", "quality_window_basis")).isEqualTo("AS_OF");
        assertThat(params.string("C06", "auto_close_basis")).isEqualTo("LAST_HIT");
        assertThat(params.string("C06", "upgrade_window_basis")).isEqualTo("FALSE_POSITIVE_AT");
    }

    @Test void optionalLocalQaCatalogCanStillPublishItsSeparateDemoVersionsWithoutOverwritingConfirmedInputs() {
        var before = jdbc.queryForList("SELECT * FROM rule_param WHERE rule_set_version_id IN (?,?,?) ORDER BY rule_param_id", LEGALITY, REVISION, SPACE);
        var heads = jdbc.queryForList("SELECT * FROM rule_set ORDER BY rule_set_id");
        new LocalQaRuleCatalog(jdbc).run(new DefaultApplicationArguments(new String[0]));
        assertThat(jdbc.queryForList("SELECT * FROM rule_param WHERE rule_set_version_id IN (?,?,?) ORDER BY rule_param_id", LEGALITY, REVISION, SPACE)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM rule_set ORDER BY rule_set_id")).isEqualTo(heads);
        assertThat(jdbc.queryForObject("SELECT param_status FROM rule_set_version WHERE rule_set_version_id='seed-stage7-rsv-1'", String.class)).isEqualTo("DEMO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM target", Long.class)).isZero();
    }

    @Test void revisionChangesOnlyTheWithdrawnNoPlanPolicyAndPreservesOriginalInputs() {
        assertRevisionCopy(jdbc);
        assertThat(jdbc.queryForObject("SELECT value_text FROM rule_param WHERE rule_set_version_id=? AND rule_code='C03' AND param_key='no_plan_status'", String.class, LEGALITY))
                .isEqualTo("ILLEGAL");
        assertThat(jdbc.queryForObject("SELECT description FROM rule_set_version WHERE rule_set_version_id=?", String.class, REVISION))
                .contains("本会话最新确认", "2-2", "2-1", "既有告警流程及历史不变", HASH);
        assertThat(jdbc.queryForObject("SELECT version_no FROM rule_set_version WHERE rule_set_version_id=?", Integer.class, REVISION))
                .isGreaterThan(jdbc.queryForObject("SELECT version_no FROM rule_set_version WHERE rule_set_version_id=?", Integer.class, LEGALITY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "historical-run"})
    void revisionDoesNotRetireAnAlreadyUsedVersionOrRewriteItsHistory(String use) {
        String schema = "confirmed_rules_" + UUID.randomUUID().toString().replace("-", "");
        DataSource rootSource = source(required("POSTGRES_TEST_URL"));
        JdbcTemplate root = new JdbcTemplate(rootSource);
        assertThat(root.queryForObject("SELECT current_database()", String.class)).matches("stage456_verify_[a-z0-9_]+");
        root.execute("CREATE SCHEMA " + schema);
        try {
            migration(rootSource, schema, "202610080001").migrate();
            JdbcTemplate before = new JdbcTemplate(source(required("POSTGRES_TEST_URL") + "?currentSchema=" + schema + ",public"));
            String set = before.queryForObject("SELECT rule_set_id FROM rule_set_version WHERE rule_set_version_id=?", String.class, LEGALITY);
            if (use.equals("active")) {
                before.update("UPDATE rule_set SET active_version_id=?,version=version+1 WHERE rule_set_id=?", LEGALITY, set);
            } else {
                before.update("INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,finished_at,status,source_mode,created_at)"
                        + " VALUES(?,?,?,'ACTIVE','SCHEDULED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'DONE','live',CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), set, LEGALITY);
            }
            var originalVersion = before.queryForMap("SELECT * FROM rule_set_version WHERE rule_set_version_id=?", LEGALITY);
            var originalHead = before.queryForMap("SELECT * FROM rule_set WHERE rule_set_id=?", set);
            var originalParams = before.queryForList("SELECT * FROM rule_param WHERE rule_set_version_id=? ORDER BY rule_param_id", LEGALITY);
            var originalRuns = before.queryForList("SELECT * FROM rule_run ORDER BY run_id");
            migration(rootSource, schema, null).migrate();
            assertThat(before.queryForMap("SELECT * FROM rule_set_version WHERE rule_set_version_id=?", LEGALITY)).isEqualTo(originalVersion);
            assertThat(before.queryForMap("SELECT * FROM rule_set WHERE rule_set_id=?", set)).isEqualTo(originalHead);
            assertThat(before.queryForList("SELECT * FROM rule_param WHERE rule_set_version_id=? ORDER BY rule_param_id", LEGALITY)).isEqualTo(originalParams);
            assertThat(before.queryForList("SELECT * FROM rule_run ORDER BY run_id")).isEqualTo(originalRuns);
            assertRevisionCopy(before);
        } finally { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    private static void assertRevisionCopy(JdbcTemplate database) {
        String projection = "rule_code,param_key,value_text,value_type,unit,param_status,note";
        var original = database.queryForList("SELECT " + projection + " FROM rule_param WHERE rule_set_version_id=? ORDER BY rule_code,param_key", LEGALITY);
        var revision = database.queryForList("SELECT " + projection + " FROM rule_param WHERE rule_set_version_id=? ORDER BY rule_code,param_key", REVISION);
        assertThat(revision).hasSameSizeAs(original);
        int changed = 0;
        for (int i = 0; i < original.size(); i++) {
            Map<String, Object> expected = new LinkedHashMap<>(original.get(i));
            if ("C03".equals(expected.get("rule_code")) && "no_plan_status".equals(expected.get("param_key"))) {
                changed++;
                assertThat(expected.get("value_text")).isEqualTo("ILLEGAL");
                expected.put("value_text", "LEGAL");
                String note = (String) revision.get(i).get("note");
                assertThat(note).contains("本会话最新确认", "撤回原确认书2-2", "本身不构成违规", "告警核实、通知、反制和历史记录不变");
                expected.put("note", note);
            }
            assertThat(revision.get(i)).isEqualTo(expected);
        }
        assertThat(changed).isEqualTo(1);
        assertThat(database.queryForList("SELECT rule_version_id,priority,enabled FROM rule_set_member WHERE rule_set_version_id=? ORDER BY priority,rule_version_id", REVISION))
                .isEqualTo(database.queryForList("SELECT rule_version_id,priority,enabled FROM rule_set_member WHERE rule_set_version_id=? ORDER BY priority,rule_version_id", LEGALITY));
    }

    private static void assertPublishedCatalog(JdbcTemplate database) {
        assertThat(database.queryForObject("SELECT COUNT(*) FROM rule_set_version WHERE rule_set_version_id IN (?,?) AND status_code='PUBLISHED' AND param_status='CONFIRMED'", Long.class, REVISION, SPACE)).isEqualTo(2);
        assertThat(database.queryForObject("SELECT status_code FROM rule_set_version WHERE rule_set_version_id=?", String.class, LEGALITY)).isEqualTo("RETIRED");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM rule_param WHERE rule_set_version_id IN (?,?,?) AND param_status<>'CONFIRMED'", Long.class, LEGALITY, REVISION, SPACE)).isZero();
        assertThat(database.queryForList("SELECT r.rule_code FROM rule_set_member m JOIN rule_version r ON r.rule_version_id=m.rule_version_id WHERE m.rule_set_version_id=? AND m.enabled ORDER BY m.priority", String.class, LEGALITY))
                .containsExactly("C01", "C02-1", "C02-2", "C02-3", "C02-4", "C02-5", "C02-6", "C02-7", "C02-8", "C03", "C06");
        assertThat(database.queryForList("SELECT r.rule_code FROM rule_set_member m JOIN rule_version r ON r.rule_version_id=m.rule_version_id WHERE m.rule_set_version_id=? AND m.enabled ORDER BY m.priority", String.class, SPACE))
                .containsExactly("C04", "C05");
        assertThat(database.queryForObject("SELECT CAST(source_snapshot AS VARCHAR) FROM rule_version WHERE rule_version_id='confirmed-20261008-C01'", String.class)).contains(HASH, "II:2-1..2-13", "IV:4-1..4-6");
        var loader = new RuleParamLoader(database);
        var legal = loader.load(LEGALITY);
        assertThat(legal.number("C01", "corridor_tolerance_m")).isEqualByComparingTo("100");
        assertThat(legal.number("C02-3", "tolerance_m")).isEqualByComparingTo("20");
        assertThat(legal.number("C02-6", "vlos_m")).isEqualByComparingTo("500");
        assertThat(legal.integer("C03", "fresh_seconds")).isEqualTo(120);
        assertThat(legal.number("C03", "conf_min")).isEqualByComparingTo("0.75");
        var risk = loader.load(SPACE);
        assertThat(risk.number("C04", "corridor_near_m")).isEqualByComparingTo("300");
        assertThat(risk.integer("C04", "flock_count_threshold")).isEqualTo(20);
        assertThat(risk.number("C05", "procedure_buffer_m")).isEqualByComparingTo("500");
        assertThat(risk.number("C05", "protected_target_pad_m")).isEqualByComparingTo("200");
    }

    private String manager(String user) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String token = UUID.randomUUID().toString();
        // 规则启用属于后台管理；自定义前台角色即使带 rule:manage 也不能代替后台身份。
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,'ROLE-BACKEND','ACTIVE','unused',0,'ALL',0,0,0,0)", user, "confirmed-" + suffix, "确认规则测试");
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private static Flyway migration(DataSource source, String schema, String target) {
        var configuration = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql");
        if (target != null) configuration.target(target);
        return configuration.load();
    }
    private static DataSource source(String url) {
        if (!url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+(?:\\?currentSchema=confirmed_rules_[a-f0-9]{32},public)?"))
            throw new IllegalStateException("Confirmed catalog test requires an isolated stage456_verify_ database");
        return new DriverManagerDataSource(url, required("POSTGRES_TEST_USER"), required("POSTGRES_TEST_PASSWORD"));
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null) throw new IllegalStateException(key + " is required");
        return value;
    }
}
