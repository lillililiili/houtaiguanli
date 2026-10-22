package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class OrganizationDeletionPostgresApiTest extends OrganizationDeletionApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void cleanup() { DATABASE.close(); }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void aConcurrentCommittedChildReferencePreventsDeletion() throws Exception {
        String child = UUID.randomUUID().toString();
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO app_org(org_id,parent_id,org_code,name,enabled,created_at,updated_at,version)
                    VALUES(?,?,?, '并发新增下级单位',true,0,0,0)
                    """)) {
                insert.setString(1, child); insert.setString(2, org); insert.setString(3, child); insert.executeUpdate();
            }
            var deleting = pool.submit(() -> remove(platform, org, 0).andReturn().getResponse());
            connection.commit();
            var result = deleting.get(15, TimeUnit.SECONDS);
            assertThat(result.getStatus()).isEqualTo(409);
            assertThat(result.getContentAsString()).contains("ORG_IN_USE");
            assertThat(countOrg(org)).isEqualTo(1);
            assertThat(countOrg(child)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void anInFlightWriteWithoutOrganizationForeignKeyFailsDeletionFast() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("LOCK TABLE prediction_episode IN ROW EXCLUSIVE MODE");
            }
            remove(platform, org, 0).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ORG_DELETE_BUSY"));
            assertThat(countOrg(org)).isEqualTo(1);
            connection.rollback();
        }
        remove(platform, org, 0).andExpect(status().isOk());
        assertThat(countOrg(org)).isZero();
    }
}
