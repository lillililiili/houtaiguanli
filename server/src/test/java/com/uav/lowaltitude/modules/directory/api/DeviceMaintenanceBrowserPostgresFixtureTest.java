package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in, disposable PostGIS fixture for real cross-application device checks. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/maintenance_browser_verify_[a-z0-9_]+")
class DeviceMaintenanceBrowserPostgresFixtureTest extends DeviceMaintenanceBrowserFixtureTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/maintenance_browser_verify_[a-z0-9_]+")) {
            throw new IllegalArgumentException("只允许独立浏览器运维测试库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
    }

    @Override void verifyBrowserDatabase(String url) {
        assertThat(url).matches("jdbc:postgresql://[^/]+/maintenance_browser_verify_[a-z0-9_]+(?:\\?.*)?");
    }

    @Override String browserDatabaseLabel() { return "isolated PostgreSQL/PostGIS"; }

    @org.junit.jupiter.api.AfterEach
    @Override void cleanCommittedFixture() {
        if (createdTaskIds.isEmpty() && setting != null) {
            jdbc.update("DELETE FROM notification_setting WHERE setting_id=?", setting);
        }
        // Browser runs keep their evidence in this dedicated disposable database.
    }

    @Override void prepareBrowserChecks() {
        // Move only the synthetic fixture device onto its seeded route; no business DB is allowed.
        jdbc.update("UPDATE ops_device SET longitude=ST_X(ST_StartPoint(rv.centerline)),latitude=ST_Y(ST_StartPoint(rv.centerline)),coordinate_system='WGS-84' FROM route_version rv JOIN flight_plan fp ON fp.route_version_id=rv.route_version_id WHERE ops_device.device_id=? AND fp.plan_id=?", device, plan);
        doCallRealMethod().when(checks).read(plan);
        sqlSession.clearCache();
    }

    @Test void realDeviceCheckReflectsFixtureRecovery() throws Exception {
        prepareBrowserChecks();
        healthy();
        var healthyCheck = data(auth(get("/api/v1/flight-plans/" + plan + "/verifications/device-check")));
        var healthyRow = java.util.stream.StreamSupport.stream(healthyCheck.path("rows").spliterator(), false)
                .filter(row -> device.equals(row.path("device_id").asText())).findFirst().orElseThrow();
        assertThat(healthyRow.path("health_code").asText()).isEqualTo("GOOD");
        assertThat(healthyRow.path("abnormal").asBoolean()).isFalse();
        jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE',health_code='BAD' WHERE device_id=?", device);
        sqlSession.clearCache();
        var failedCheck = data(auth(get("/api/v1/flight-plans/" + plan + "/verifications/device-check")));
        var failedRow = java.util.stream.StreamSupport.stream(failedCheck.path("rows").spliterator(), false)
                .filter(row -> device.equals(row.path("device_id").asText())).findFirst().orElseThrow();
        assertThat(failedRow.path("connectivity").asText()).isEqualTo("OFFLINE");
        assertThat(failedRow.path("abnormal").asBoolean()).isTrue();
    }
}
