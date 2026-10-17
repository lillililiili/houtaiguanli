package com.uav.lowaltitude.modules.integrationconfig.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Reuse the real API contract against a fully migrated, isolated PostgreSQL/PostGIS schema. */
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LocalPlanBatchIdentityPostgresTest extends LocalInterfaceSimulatorApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) { DATABASE.springProperties(properties); }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
}
