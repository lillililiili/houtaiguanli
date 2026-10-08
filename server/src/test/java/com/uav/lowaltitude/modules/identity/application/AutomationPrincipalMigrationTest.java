package com.uav.lowaltitude.modules.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;

/** 自动规则发起人随安装建好（V202610089001）：不开开发种子的库里自动反制也有发起人，种子建过的库不重复、不改。 */
class AutomationPrincipalMigrationTest {
    private static final String BEFORE = "202610079101";

    @Test
    void freshDatabaseGetsADisabledPrincipalWithoutAnyPermission() {
        DriverManagerDataSource dataSource = database();
        migrate(dataSource, null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Map<String, Object> user = jdbc.queryForMap("""
                select account, name, role_code, status, scope_mode, scope_org_rule, must_change_password, password_hash
                from app_user where user_id = ?
                """, AutomationPrincipal.USER_ID);
        assertThat(user).containsEntry("account", AutomationPrincipal.ACCOUNT).containsEntry("name", "自动规则")
                .containsEntry("role_code", AutomationPrincipal.ROLE).containsEntry("status", "DISABLED")
                .containsEntry("scope_mode", "ALL").containsEntry("must_change_password", false);
        assertThat(user.get("scope_org_rule")).isNull();
        // 随机口令生成后即丢弃：哈希格式有效，常见口令都对不上。
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        for (String guess : new String[] {"", "changeme", AutomationPrincipal.ACCOUNT, AutomationPrincipal.USER_ID}) {
            assertThat(bcrypt.matches(guess, (String) user.get("password_hash"))).isFalse();
        }
        assertThat(jdbc.queryForObject("select name from app_role where role_code = ?", String.class,
                AutomationPrincipal.ROLE)).isEqualTo("自动规则");
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission where role_code = ?", Integer.class,
                AutomationPrincipal.ROLE)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_user_data_scope where user_id = ?", Integer.class,
                AutomationPrincipal.USER_ID)).isZero();
    }

    @Test
    void principalAlreadyCreatedByTheDevSeedIsKeptAsIs() {
        DriverManagerDataSource dataSource = database();
        migrate(dataSource, BEFORE);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values ('org-seed','ORG-SEED','种子单位',true,1,1,0)");
        jdbc.update("""
                insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)
                values (?, '自动规则', '种子建的', FALSE, TRUE, 5, 5, 0, FALSE)
                """, AutomationPrincipal.ROLE);
        jdbc.update("""
                insert into app_user (user_id, account, name, role_code, status, password_hash, fail_count, org_id,
                    scope_mode, must_change_password, permission_version, created_at, updated_at, version)
                values (?, ?, '自动规则', ?, 'DISABLED', 'seed-hash', 0, 'org-seed', 'ALL', FALSE, 0, 5, 5, 0)
                """, AutomationPrincipal.USER_ID, AutomationPrincipal.ACCOUNT, AutomationPrincipal.ROLE);

        migrate(dataSource, null);

        assertThat(jdbc.queryForObject("select count(*) from app_user where user_id = ?", Integer.class,
                AutomationPrincipal.USER_ID)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select org_id, password_hash, created_at from app_user where user_id = ?",
                AutomationPrincipal.USER_ID)).containsEntry("org_id", "org-seed").containsEntry("password_hash", "seed-hash")
                .containsEntry("created_at", 5L);
        assertThat(jdbc.queryForObject("select description from app_role where role_code = ?", String.class,
                AutomationPrincipal.ROLE)).isEqualTo("种子建的");
    }

    @Test
    void roleNameOrAccountTakenByOtherRecordsDoesNotStopTheMigration() {
        DriverManagerDataSource dataSource = database();
        migrate(dataSource, BEFORE);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)
                values ('ROLE-CUSTOM', '自动规则', '管理员自建', FALSE, TRUE, 1, 1, 0, FALSE)
                """);
        jdbc.update("""
                insert into app_user (user_id, account, name, role_code, status, password_hash, fail_count,
                    scope_mode, must_change_password, permission_version, created_at, updated_at, version)
                values ('someone-else', ?, '同名账号', 'ROLE-CUSTOM', 'ACTIVE', 'hash', 0, 'NONE', FALSE, 0, 1, 1, 0)
                """, AutomationPrincipal.ACCOUNT);

        migrate(dataSource, null);

        assertThat(jdbc.queryForObject("select name from app_role where role_code = ?", String.class,
                AutomationPrincipal.ROLE)).isEqualTo("自动规则（系统）");
        assertThat(jdbc.queryForMap("select account, role_code, status from app_user where user_id = ?",
                AutomationPrincipal.USER_ID)).containsEntry("account", "automation-rule-runner")
                .containsEntry("role_code", AutomationPrincipal.ROLE).containsEntry("status", "DISABLED");
        assertThat(jdbc.queryForObject("select account from app_user where user_id = 'someone-else'", String.class))
                .isEqualTo(AutomationPrincipal.ACCOUNT);
    }

    private static DriverManagerDataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:automation_principal_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void migrate(DriverManagerDataSource dataSource, String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        config.load().migrate();
    }
}
