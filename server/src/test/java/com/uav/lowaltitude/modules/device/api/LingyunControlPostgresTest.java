package com.uav.lowaltitude.modules.device.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same protocol and receipt contracts on a guarded disposable PostgreSQL/PostGIS schema. */
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class LingyunControlPostgresTest extends LingyunControlMqttTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
        // Inherited timeout case calls poll explicitly; scheduled callbacks remain disabled.
        registry.add("app.outbox.enabled", () -> true);
    }

    @AfterAll static void closeDatabase() { DATABASE.close(); }
}
