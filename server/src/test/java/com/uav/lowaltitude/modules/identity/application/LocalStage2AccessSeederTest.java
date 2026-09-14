package com.uav.lowaltitude.modules.identity.application;

import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.Application;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stage4_access_seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class LocalStage2AccessSeederTest {

    @Autowired
    LocalStage2AccessSeeder seeder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccessService legacyAccessService;

    @Autowired
    AuthService authService;

    @Test
    void localSeedExplicitlyGrantsOnlyTheSyntheticAdministrator() {
        // 期望值直接从 PermissionCode 目录生成：新增动作时种子必须自动补齐，测试不再手抄清单而过期。
        assertThat(actionGrants(jdbc)).containsExactlyElementsOf(expectedAdminGrants());
        // 本地/测试种子另造了几个演示角色（15-3 的复核员，阶段 18 的四个业务角色），它们是仅有的被允许
        // 持动作码的非管理员角色。**逐个列出名字**：再冒出第六个持码角色仍然要红——
        // 换成"凡是 ROLE-DEMO- 开头的都放行"就等于把这条守卫拆了，谁新造一个演示角色都不会有人知道。
        assertThat(jdbc.queryForList("select distinct role_code from app_role_permission where permission_code like '%:%'"
                + " and role_code<>'ROLE-ADMIN' order by role_code", String.class))
                .as("non-admin roles holding action codes")
                .containsExactly("ROLE-DEMO-AUDIT", "ROLE-DEMO-AUTH", "ROLE-DEMO-DUTY",
                        "ROLE-DEMO-OPS", "ROLE-DEMO-REVIEWER");
        assertThat(jdbc.queryForObject(
                "select scope_mode from app_user where account='admin1'", String.class))
                .isEqualTo("ALL");
        // 决策 16-4 起 permission_codes 里**有意**混着两种码：模块码带 .read|op|auth 后缀，动作码给原文。
        // 原来这里断言"不含冒号"，那是 16-4 之前的口径。现在要守的是两者**不许串味**——
        // 出现 disposal:approve.read 这种双重编码，前端两边都认不出来。
        for (String code : legacyAccessService.permissionCodes("ROLE-ADMIN")) {
            if (code.contains(":")) {
                assertThat(code).as("动作码给原文").doesNotContain(".read", ".op", ".auth");
            } else {
                assertThat(code).as("模块码带等级后缀").matches(".+\\.(read|op|auth)$");
            }
        }

        seeder.run(new DefaultApplicationArguments(new String[0]));
        seeder.run(new DefaultApplicationArguments(new String[0]));
        assertThat(actionGrants(jdbc)).hasSize(PermissionCode.values().length);
    }

    @Test
    void addingNewActionsInvalidatesSessionsCreatedWithTheOldPermissionVersion() {
        String sessionId = UUID.randomUUID().toString();
        String refreshedSessionId = null;
        jdbc.update("delete from app_role_permission where role_code='ROLE-ADMIN' and permission_code in (?, ?, ?)",
                "alarm:verify", "risk:read", "risk:verify");
        long oldVersion = jdbc.queryForObject(
                "select permission_version from app_user where account='admin1'", Long.class);
        String userId = jdbc.queryForObject(
                "select user_id from app_user where account='admin1'", String.class);
        jdbc.update("""
                insert into app_session(session_id,user_id,expire_at,ip,permission_version)
                values (?, ?, ?, '', ?)
                """, sessionId, userId, Long.MAX_VALUE, oldVersion);

        try {
            seeder.run(new DefaultApplicationArguments(new String[0]));

            assertThat(jdbc.queryForObject(
                    "select permission_version from app_user where account='admin1'", Long.class))
                    .isEqualTo(oldVersion + 1);
            assertThat(authService.resolve(sessionId)).isNull();

            var refreshedLogin = authService.login("admin1", "changeme", "127.0.0.1", "stage4-seed-test");
            refreshedSessionId = refreshedLogin.sessionId();
            assertThat(authService.resolve(refreshedSessionId)).isNotNull();
            assertThat(jdbc.queryForObject(
                    "select permission_version from app_session where session_id=?",
                    Long.class,
                    refreshedSessionId)).isEqualTo(oldVersion + 1);
        } finally {
            jdbc.update("delete from app_session where session_id=?", sessionId);
            if (refreshedSessionId != null) {
                jdbc.update("delete from app_session where session_id=?", refreshedSessionId);
            }
        }
    }

    @Test
    void disabledSeedLeavesActionPermissionsUnassigned() {
        String database = "stage2_seed_disabled_" + UUID.randomUUID();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.profiles.active=test",
                        "--spring.datasource.url=jdbc:h2:mem:" + database
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "--app.dev-seed.enabled=false")) {
            assertThat(context.getBeansOfType(LocalStage2AccessSeeder.class)).isEmpty();
            assertThat(actionGrants(context.getBean(JdbcTemplate.class))).isEmpty();
        }
    }

    @Test
    void productionProfileCannotEnableTheStage2AccessSeeder() {
        String database = "stage2_seed_production_" + UUID.randomUUID();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.profiles.active=production",
                        "--spring.datasource.url=jdbc:h2:mem:" + database
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.flyway.locations=classpath:db/migration",
                        "--app.dev-seed.enabled=true",
                        "--app.dev-seed.password=Stage2ProductionGuard-9!")) {
            assertThat(context.getBeansOfType(LocalStage2AccessSeeder.class)).isEmpty();
            assertThat(actionGrants(context.getBean(JdbcTemplate.class))).isEmpty();
        }
    }

    @Test
    void productionAndLocalProfilesTogetherStillCannotEnableTheAccessSeeder() {
        String database = "stage4_seed_production_local_" + UUID.randomUUID();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.profiles.active=production,local",
                        "--spring.datasource.url=jdbc:h2:mem:" + database
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.flyway.locations=classpath:db/migration",
                        "--app.dev-seed.enabled=true",
                        "--app.dev-seed.password=Stage4ProductionGuard-9!")) {
            assertThat(context.getBeansOfType(LocalStage2AccessSeeder.class)).isEmpty();
            assertThat(actionGrants(context.getBean(JdbcTemplate.class))).isEmpty();
        }
    }

    private static List<String> actionGrants(JdbcTemplate template) {
        return template.queryForList("""
                select role_code || ':' || permission_code
                from app_role_permission
                where permission_code like '%:%'
                  and role_code = 'ROLE-ADMIN'
                order by role_code, permission_code
                """, String.class);
    }

    private static java.util.List<String> expectedAdminGrants() {
        return java.util.Arrays.stream(PermissionCode.values())
                .map(code -> "ROLE-ADMIN:" + code.value())
                .sorted()
                .toList();
    }
}
