package com.uav.lowaltitude.modules.identity.api;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 在专门建立的隔离测试库上跑同一组数据范围用例（迁移约束、FOR UPDATE、按单位展开元组），不连接业务库。 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = UserDataScopePostgresTest.DATABASE)
class UserDataScopePostgresTest extends UserDataScopeApiTest {

    static final String DATABASE = "jdbc:postgresql://[^/]+/account_scope_[a-z0-9_]+";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches(DATABASE)) throw new IllegalArgumentException("仅允许显式隔离的账号范围测试库");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
    }
}
