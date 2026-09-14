package com.uav.lowaltitude.modules.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.uav.lowaltitude.platform.config.AppProperties;

class SingleSuperAdminMigrationTest {

    @Test
    void existingMultiAdminDatabaseIsMigratedWithoutDeletingReferencedUsersOrHistory() {
        String url = "jdbc:h2:mem:upgrade_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("202609030003")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long now = 1_000L;
        jdbc.update("""
                insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version)
                values ('org-upgrade','ORG-UPGRADE','升级组织',true,?,?,0)
                """, now, now);
        insertUser(jdbc, "admin-1", "admin1", "ROLE-ADMIN", now);
        insertUser(jdbc, "admin-2", "admin2", "ROLE-ADMIN", now);
        insertUser(jdbc, "duty-1", "duty1", "ROLE-DUTY", now);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values ('old-session','admin-2',999999,'',0)");
        jdbc.update("""
                insert into access_change_request(change_id,change_type,subject_type,subject_id,requester_id,
                    before_snapshot,after_snapshot,reason,subject_version,status,requested_at,version)
                values ('pending-upgrade','USER_CREATE','USER','new-user','duty-1','{}','{}','旧申请',-1,'PENDING',?,0)
                """, now);
        jdbc.update("""
                insert into pending_user_registration(change_id,account,name,org_id,role_code,scope_mode,scope_grants,password_hash)
                values ('pending-upgrade','never-created','待创建','org-upgrade','ROLE-DUTY','NONE','[]','secret-hash')
                """);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("select name from app_role where role_code='ROLE-ADMIN'", String.class))
                .isEqualTo("超级管理员");
        assertThat(jdbc.queryForObject("select builtin from app_role where role_code='ROLE-DUTY'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("select count(*) from app_role where role_code='ROLE-AUTH'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select status from access_change_request where change_id='pending-upgrade'", String.class))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from pending_user_registration", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from app_role_permission
                where role_code='ROLE-DUTY' and permission_code in ('users','roles','audit','countermeasure')
                  and (permission_level<>'NONE' or menu_enabled=true)
                """, Integer.class)).isZero();

        AppProperties properties = new AppProperties();
        properties.getSuperAdmin().setAccount("admin1");
        new SuperAdminIntegrityInitializer(jdbc, properties).run(null);
        assertThat(jdbc.queryForObject("select count(*) from app_user where role_code='ROLE-ADMIN'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select role_code from app_user where account='admin2'", String.class))
                .isEqualTo("ROLE-MIGRATED-ADMIN");
        assertThat(jdbc.queryForObject("select status from app_user where account='admin2'", String.class))
                .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("select expire_at from app_session where session_id='old-session'", Long.class))
                .isZero();
    }

    private void insertUser(JdbcTemplate jdbc, String id, String account, String role, long now) {
        jdbc.update("""
                insert into app_user(user_id,account,name,org_id,role_code,status,password_hash,fail_count,
                    scope_mode,must_change_password,permission_version,created_at,updated_at,version)
                values (?,?,'升级用户','org-upgrade',?,'ACTIVE','hash',0,'ALL',false,0,?,?,0)
                """, id, account, role, now, now);
    }
}
