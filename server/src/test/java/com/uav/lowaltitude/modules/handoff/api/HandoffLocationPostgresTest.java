package com.uav.lowaltitude.modules.handoff.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Frozen notification material also round-trips through PostgreSQL JSON and PostGIS geometry. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class HandoffLocationPostgresTest extends HandoffApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) { DATABASE.springProperties(properties); }
    @AfterAll static void close() { DATABASE.close(); }
    @Override void cleanupSpaceFacts() {
        // PostgreSQL facts are append-only, including in tests. The disposable schema owns their cleanup.
    }
}
