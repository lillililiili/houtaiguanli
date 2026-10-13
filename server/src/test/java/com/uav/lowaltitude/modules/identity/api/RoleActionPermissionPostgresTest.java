package com.uav.lowaltitude.modules.identity.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/role_actions_[a-z0-9_]+")
class RoleActionPermissionPostgresTest extends RoleActionPermissionApiTest {
    @Override
    @AfterEach
    void cleanup() {
        // PG 的处置事件只追加，不能沿用 H2 删除历史的收尾。每例使用独立 UUID，
        // 事件及关联账号留在专用测试库，整轮结束后由测试环境销毁该库。
        com.uav.lowaltitude.platform.security.AuthContext.clear();
        jdbc.update("delete from app_permission where permission_code='audit:purge'");
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/role_actions_[a-z0-9_]+")) {
            throw new IllegalArgumentException("仅允许隔离的角色动作测试库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
    }
}
