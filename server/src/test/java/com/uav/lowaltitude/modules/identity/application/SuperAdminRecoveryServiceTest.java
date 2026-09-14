package com.uav.lowaltitude.modules.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.super-admin-recovery.password=Recovered#2026Admin")
@ActiveProfiles("test")
class SuperAdminRecoveryServiceTest {

    @Autowired SuperAdminRecoveryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void recoveryResetsOnlySuperAdminRevokesSessionsForcesPasswordChangeAndAuditsWithoutSecret() {
        String userId = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        String originalHash = jdbc.queryForObject("select password_hash from app_user where user_id=?", String.class, userId);
        Boolean originalMustChange = jdbc.queryForObject("select must_change_password from app_user where user_id=?", Boolean.class, userId);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values ('recovery-session',?,9999999999999,'',0)", userId);
        try {
            service.recover();
            String recoveredHash = jdbc.queryForObject("select password_hash from app_user where user_id=?", String.class, userId);
            assertThat(encoder.matches("Recovered#2026Admin", recoveredHash)).isTrue();
            assertThat(jdbc.queryForObject("select must_change_password from app_user where user_id=?", Boolean.class, userId)).isTrue();
            assertThat(jdbc.queryForObject("select expire_at from app_session where session_id='recovery-session'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from audit_log where action='super_admin_recovered'", Integer.class))
                    .isGreaterThan(0);
            assertThat(jdbc.queryForObject("select count(*) from audit_log where detail like '%Recovered#2026Admin%'", Integer.class))
                    .isZero();
        } finally {
            jdbc.update("delete from app_session where session_id='recovery-session'");
            jdbc.update("delete from audit_log where action='super_admin_recovered'");
            jdbc.update("update app_user set password_hash=?, must_change_password=?, fail_count=0, locked_until=null where user_id=?",
                    originalHash, originalMustChange, userId);
        }
    }
}
