package com.uav.lowaltitude.modules.disposal.application;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same dispatch eligibility checks against a disposable PostgreSQL schema; no device transport. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class DirectDisposalBackgroundPostgresTest extends DirectDisposalBackgroundTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void cleanup() { DATABASE.close(); }
}
