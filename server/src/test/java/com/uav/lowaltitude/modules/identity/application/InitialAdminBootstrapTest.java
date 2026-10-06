package com.uav.lowaltitude.modules.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.uav.lowaltitude.Application;

/** 按 deploy/compose.yml 传给 api 的同名变量启动 production：空库得到可登录的唯一超级管理员。 */
class InitialAdminBootstrapTest {
    private static final String TEMPORARY = "Deploy#Init9";

    @Test
    void composeVariablesCreateOneSuperAdminOnAnEmptyDatabaseAndLaterStartsLeaveItAlone() {
        String database = "compose_bootstrap_" + UUID.randomUUID();
        try (ConfigurableApplicationContext context = start(database, TEMPORARY)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            List<Map<String, Object>> users = jdbc.queryForList(
                    "SELECT account, role_code, status, scope_mode, must_change_password, password_hash FROM app_user");
            assertThat(users).hasSize(1);
            Map<String, Object> admin = users.get(0);
            assertThat(admin.get("account")).isEqualTo("admin1");
            assertThat(admin.get("role_code")).isEqualTo("ROLE-ADMIN");
            assertThat(admin.get("status")).isEqualTo("ACTIVE");
            assertThat(admin.get("scope_mode")).isEqualTo("ALL");
            assertThat(admin.get("must_change_password")).isEqualTo(true);
            assertThat(context.getBean(PasswordEncoder.class).matches(TEMPORARY, (String) admin.get("password_hash"))).isTrue();
        }
        // 改密后按说明删掉初始密码再重启：不再创建、不改动已有管理员
        try (ConfigurableApplicationContext context = start(database, "")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE account='admin1' AND role_code='ROLE-ADMIN'",
                    Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void emptyDatabaseWithoutInitialPasswordRefusesToStartInsteadOfRunningWithoutAdmin() {
        assertThatThrownBy(() -> {
            try (var ignored = start("compose_bootstrap_missing_" + UUID.randomUUID(), "")) { }
        }).hasStackTraceContaining("APP_SUPER_ADMIN_PASSWORD must be configured");
    }

    private static ConfigurableApplicationContext start(String database, String password) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=production",
                "--spring.datasource.url=jdbc:h2:mem:" + database
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration",
                "--app.source-mode=live", "--app.dev-seed.enabled=false", "--app.live-device.enabled=false",
                // deploy/compose.yml 的默认值
                "--APP_BOOTSTRAP_ADMIN_ENABLED=true",
                "--APP_SUPER_ADMIN_ACCOUNT=admin1",
                "--APP_SUPER_ADMIN_NAME=超级管理员",
                "--APP_SUPER_ADMIN_PASSWORD=" + password));
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(args.toArray(String[]::new));
    }
}
