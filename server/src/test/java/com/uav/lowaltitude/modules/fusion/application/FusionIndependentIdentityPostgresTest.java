package com.uav.lowaltitude.modules.fusion.application;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Reuses the real pipeline identity scenarios in a disposable PostgreSQL/PostGIS schema. */
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class FusionIndependentIdentityPostgresTest extends FusionIdentityClassAndClockTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) { DATABASE.springProperties(properties); }
    @AfterAll
    static void closeDatabase() { DATABASE.close(); }
}
