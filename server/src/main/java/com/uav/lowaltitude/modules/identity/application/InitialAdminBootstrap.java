package com.uav.lowaltitude.modules.identity.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.platform.config.AppProperties;

@Component
@ConditionalOnProperty(prefix = "app.bootstrap-admin", name = "enabled", havingValue = "true")
@Order(20)
public class InitialAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);

    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AppProperties properties;

    public InitialAdminBootstrap(UserMapper userMapper, JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy, AppProperties properties) {
        this.userMapper = userMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userMapper.count() > 0) return;
        AppProperties.BootstrapAdmin config = properties.getBootstrapAdmin();
        String account = required(config.getAccount(), "APP_SUPER_ADMIN_ACCOUNT");
        validateAccount(account);
        String password = required(config.getPassword(), "APP_SUPER_ADMIN_PASSWORD");
        passwordPolicy.validateTemporary(password, account);
        long now = System.currentTimeMillis();
        insert(account, required(config.getName(), "APP_SUPER_ADMIN_NAME"), password, now);
        log.info("initialized the one-time super administrator account; password change is required at first login");
    }

    private void insert(String account, String name, String password, long now) {
        jdbcTemplate.update("""
                INSERT INTO app_user
                  (user_id, account, name, role_code, status, password_hash, fail_count, scope_mode,
                   must_change_password, permission_version, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ROLE-ADMIN', 'ACTIVE', ?, 0, 'ALL', TRUE, 0, ?, ?, 0)
                """, stableId(account), account, name, passwordEncoder.encode(password), now, now);
    }

    private static void validateAccount(String account) {
        if (!account.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new IllegalStateException("bootstrap administrator account format is invalid");
        }
    }

    private static String required(String value, String environmentName) {
        if (value == null || value.isBlank()) throw new IllegalStateException(environmentName + " must be configured");
        return value.trim();
    }

    private static String stableId(String account) {
        return UUID.nameUUIDFromBytes(("bootstrap-admin:" + account).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
