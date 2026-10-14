package com.uav.lowaltitude.modules.device.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeviceRetirementPostgresTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository devices;
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
    }
    @AfterAll static void cleanup() { DATABASE.close(); }
    private DeviceOperationsApiTest contract() {
        var test=new DeviceOperationsApiTest();
        test.mvc=mvc; test.objectMapper=json; test.jdbc=jdbc; test.deviceRepository=devices;
        return test;
    }
    @Test void preservesMockSnapshotsAndKeepsDispatchGuards() throws Exception {
        contract().mockRebootSnapshotsWithoutDispatchDoNotPermanentlyBlockCatalogRetirement();
    }
    @Test void retirementKeepsPermissionsVersionsHistoryAndSharedCatalog() throws Exception {
        contract().deleteRequiresDisabledDeviceAndVersionAndPreservesHistory();
    }
}
