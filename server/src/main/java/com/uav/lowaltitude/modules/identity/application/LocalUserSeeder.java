package com.uav.lowaltitude.modules.identity.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.platform.config.AppProperties;

@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(10)
public class LocalUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalUserSeeder.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public LocalUserSeeder(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            AppProperties appProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = appProperties.getDevSeed().getPassword();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("app.dev-seed.password must be set when development seed is enabled");
        }
        String hash = passwordEncoder.encode(password);
        long now = System.currentTimeMillis();
        String orgId = stableId("demo-org:platform");
        String districtId = stableId("demo-district:dongying");
        if (count("SELECT COUNT(*) FROM app_org WHERE org_id = ?", orgId) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO app_org (org_id, parent_id, org_code, name, enabled, created_at, updated_at, version)
                    VALUES (?, NULL, 'ORG-DEV', '开发测试组织', TRUE, ?, ?, 0)
                    """, orgId, now, now);
        }
        if (count("SELECT COUNT(*) FROM app_district WHERE district_id = ?", districtId) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO app_district (district_id, district_code, name, enabled, created_at, updated_at, version)
                    VALUES (?, 'DIST-DEV', '开发测试区域', TRUE, ?, ?, 0)
                    """, districtId, now, now);
        }
        if (count("SELECT COUNT(*) FROM app_user WHERE account = ?", "admin1") == 0) {
            jdbcTemplate.update("""
                INSERT INTO app_user
                  (user_id, account, name, role_code, status, password_hash, fail_count, org_id,
                   scope_mode, must_change_password, permission_version, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, 0, ?, ?, FALSE, 0, ?, ?, 0)
                """,
                row("admin1", "超级管理员", "ROLE-ADMIN", "ALL", hash, orgId, now));
        }
        jdbcTemplate.update("""
                UPDATE app_user SET role_code = 'ROLE-ADMIN', status = 'ACTIVE', org_id = ?, scope_mode = 'ALL',
                    updated_at = ?
                WHERE account = 'admin1'
                  AND (role_code <> 'ROLE-ADMIN' OR status <> 'ACTIVE' OR COALESCE(org_id, '') <> ? OR scope_mode <> 'ALL')
                """, orgId, now, orgId);
        log.info("seeded the synthetic admin1 super administrator for an isolated development environment");
    }

    private static Object[] row(String account, String name, String role, String scopeMode,
            String hash, String orgId, long now) {
        return new Object[] {stableId("demo-user:" + account), account, name, role, hash, orgId, scopeMode, now, now};
    }

    private int count(String sql, Object value) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, value);
        return result == null ? 0 : result;
    }

    private static String stableId(String value) {
        UUID id = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        return id.toString();
    }
}
