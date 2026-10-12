package com.uav.lowaltitude.modules.handoff.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** 与 HTTP 检证相同的坐标点与冻结 JSON，在独立 PostgreSQL/PostGIS schema 中回读。 */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.transaction.annotation.Transactional
class HandoffFrozenEvidencePostgresTest extends HandoffPunishmentMaterialsApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) { DATABASE.springProperties(properties); }
    @AfterAll static void close() { DATABASE.close(); }
    @Override @org.junit.jupiter.api.AfterEach void cleanup() {
        // Each test rolls back; append-only evidence must never be deleted as row-level cleanup.
        // The dedicated schema is dropped only after the whole class has finished.
    }
}
