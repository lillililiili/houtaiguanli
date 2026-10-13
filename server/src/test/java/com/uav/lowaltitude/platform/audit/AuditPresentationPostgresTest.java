package com.uav.lowaltitude.platform.audit;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/audit_verify_[a-z0-9_]+")
class AuditPresentationPostgresTest extends AuditPresentationApiTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/audit_verify_[a-z0-9_]+"))
            throw new IllegalArgumentException("仅允许独立审计测试库");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
    }
}
