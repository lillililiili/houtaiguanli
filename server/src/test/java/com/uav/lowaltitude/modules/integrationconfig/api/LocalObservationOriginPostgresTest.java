package com.uav.lowaltitude.modules.integrationconfig.api;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+")
class LocalObservationOriginPostgresTest extends LocalObservationOriginApiTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+"))
            throw new IllegalArgumentException("仅允许隔离规则测试库");
        r.add("spring.datasource.url",()->url);
        r.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));
        r.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
    }
}
