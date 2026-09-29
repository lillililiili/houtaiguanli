package com.uav.lowaltitude.modules.device.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class DeviceEventsPostgresApiTest extends DeviceEventsApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
    }

    @AfterAll
    static void cleanup() {
        DATABASE.close();
    }
}
