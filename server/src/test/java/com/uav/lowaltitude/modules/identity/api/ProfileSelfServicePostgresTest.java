package com.uav.lowaltitude.modules.identity.api;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 改密失败计数走独立事务，必须在真实 PostgreSQL 上确认业务回滚后计数和审计仍然落库。 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = UserDataScopePostgresTest.DATABASE)
class ProfileSelfServicePostgresTest extends ProfileSelfServiceApiTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        UserDataScopePostgresTest.database(registry);
    }
}
