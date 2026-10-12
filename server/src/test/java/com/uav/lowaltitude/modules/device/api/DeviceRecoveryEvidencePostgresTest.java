package com.uav.lowaltitude.modules.device.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the exact API/provenance/ordering contract against all migrations in a disposable PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_USER",matches=".+")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_PASSWORD",matches=".*")
class DeviceRecoveryEvidencePostgresTest extends LocalQaDeviceStatusApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){DATABASE.springProperties(registry);}
    @AfterAll static void clean(){DATABASE.close();}
}
