package com.uav.lowaltitude.modules.reporting.api;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class ReportingFormalFactsPostgresTest extends ReportingFormalFactsApiTest {
    private static final DeviceMonitoringPostgresFixture DB = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DB.springProperties(registry); }
    @AfterAll static void closeDatabase() { DB.close(); }
}
