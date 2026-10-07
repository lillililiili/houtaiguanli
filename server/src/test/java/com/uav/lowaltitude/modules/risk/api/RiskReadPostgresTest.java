package com.uav.lowaltitude.modules.risk.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Read/filter contracts also run against a disposable PostgreSQL/PostGIS schema. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RiskReadPostgresTest extends RiskReadApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        DATABASE.springProperties(properties);
    }

    @AfterAll static void close() { DATABASE.close(); }

    @Override String weatherPolygonJsonLiteral() {
        return "CAST('[[118,37],[119,37],[119,38],[118,37]]' AS JSON)";
    }
}
