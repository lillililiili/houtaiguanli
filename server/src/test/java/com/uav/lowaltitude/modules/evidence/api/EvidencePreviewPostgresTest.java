package com.uav.lowaltitude.modules.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 在明确隔离数据库的独立 schema 上验证预览权限、拒绝审计及升级路径。 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class EvidencePreviewPostgresTest extends EvidencePreviewApiTest {
    private static final String SCHEMA = "evidence_preview_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean created;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        initialize();
        p.add("spring.datasource.url", () -> System.getenv("POSTGRES_TEST_URL") + "?currentSchema=" + SCHEMA + ",public");
        p.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        p.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.enabled", () -> false);
        p.add("app.rule-engine.enabled", () -> false);
        p.add("app.rule-engine.replay.run-on-start", () -> false);
        p.add("app.rule-engine.c04.enabled", () -> false);
        p.add("app.fusion.enabled", () -> false);
        p.add("app.fusion.replay.run-on-start", () -> false);
        p.add("app.disposal.expiry.enabled", () -> false);
    }
    static synchronized void initialize() {
        if (created) return;
        var jdbc = new JdbcTemplate(root());
        if (!jdbc.queryForObject("select current_database()", String.class).matches("^advisory_verify_[a-z0-9_]+$")) {
            throw new IllegalStateException("Requires isolated advisory_verify_ database");
        }
        jdbc.execute("create schema " + SCHEMA); created = true;
        migration(SCHEMA).load().migrate();
    }
    @Test void upgradePreservesExistingAuditActionsAndDoesNotGrantRoles() {
        String schema = "evidence_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(root()); jdbc.execute("create schema " + schema);
        try {
            migration(schema).target("202609160002").load().migrate();
            var upgraded = migration(schema).load();
            assertThat(upgraded.migrate().migrationsExecuted).isGreaterThanOrEqualTo(2);
            upgraded.validate(); assertThat(upgraded.migrate().migrationsExecuted).isZero();
            String constraint = jdbc.queryForObject("select pg_get_constraintdef(c.oid) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname=? and c.conname='ck_evidence_access_action'", String.class, schema);
            assertThat(constraint).contains("DESTROY", "PREVIEW", "THUMBNAIL");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".app_role_permission where permission_code='evidence:preview'", Long.class)).isZero();
        } finally { jdbc.execute("drop schema " + schema + " cascade"); }
    }
    private static org.flywaydb.core.api.configuration.FluentConfiguration migration(String schema) {
        return Flyway.configure().dataSource(root()).schemas(schema).defaultSchema(schema).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql");
    }
    @AfterAll static void cleanSchema(@org.springframework.beans.factory.annotation.Autowired org.springframework.context.ConfigurableApplicationContext context) {
        context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).values()
                .forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::shutdown);
        if (created) { new JdbcTemplate(root()).execute("drop schema " + SCHEMA + " cascade"); created = false; }
    }
    static DataSource root() { return new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"), System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD")); }
}
