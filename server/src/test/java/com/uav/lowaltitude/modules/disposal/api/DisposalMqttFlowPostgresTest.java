package com.uav.lowaltitude.modules.disposal.api;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class DisposalMqttFlowPostgresTest extends DisposalMqttFlowTest {
    static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.mqtt.enabled", () -> true);
        registry.add("app.outbox.enabled", () -> true);
        registry.add("app.dev-seed.password", () -> "changeme");
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
}
