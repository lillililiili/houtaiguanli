package com.uav.lowaltitude.modules.flight.api;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 只允许显式指定的独立测试库；与 H2 执行相同的持久化与接口断言。 */
@EnabledIfEnvironmentVariable(named="FLIGHT_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/stage_flight_verify_[a-z0-9_]+")
class FlightVerificationPostgresTest extends FlightVerificationApiTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        String url=System.getenv("FLIGHT_TEST_PG_URL");
        if(url==null || !url.matches("jdbc:postgresql://[^/]+/stage_flight_verify_[a-z0-9_]+"))throw new IllegalArgumentException("只允许独立飞行测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->System.getenv("FLIGHT_TEST_PG_USER"));
        registry.add("spring.datasource.password",()->System.getenv("FLIGHT_TEST_PG_PASSWORD"));
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
    }
}
