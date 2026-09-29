package com.uav.lowaltitude.modules.device.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Reuses the existing guarded disposable PostgreSQL/PostGIS fixture lifecycle. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class QaVideoStreamPostgresTest extends QaVideoStreamApiTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        TargetVideoPostgresTest.database(properties);
    }
    @AfterAll static void cleanSchema(@Autowired ConfigurableApplicationContext context) {
        TargetVideoPostgresTest.cleanSchema(context);
    }
}
