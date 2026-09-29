package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;

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

    @org.junit.jupiter.api.BeforeEach
    @Override void fixture() throws Exception {
        String resume=System.getProperty("qa.maintenance.resume-task");
        if(resume==null) { super.fixture(); return; }
        java.util.UUID.fromString(resume);
        try(var connection=jdbc.getDataSource().getConnection()) {
            verifyBrowserDatabase(connection.getMetaData().getURL());
        }
        var row=jdbc.queryForMap("SELECT t.device_id,t.plan_id,t.simulated FROM ops_device_maintenance_task t WHERE t.task_id=?",resume);
        assertThat(row.get("plan_id")).isEqualTo(plan);
        assertThat(row.get("simulated")).isEqualTo(true);
        taskId=resume; device=(String)row.get("device_id"); createdTaskIds.add(resume);
        now=System.currentTimeMillis(); clock.setNow(java.time.Instant.ofEpochMilli(now));
        session=data(post("/api/v1/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).path("session_id").asText();
        org=jdbc.queryForObject("SELECT org_id FROM app_org WHERE org_code='ORG-DEV'",String.class);
        setting=jdbc.queryForObject("SELECT setting_id FROM notification_setting WHERE purpose='DEVICE_MAINTENANCE' AND routing_key=?",String.class,"DEVICE_MAINTENANCE:"+org);
        doReturn(true).when(channel).simulated();
        doAnswer(call -> {
            var at=((com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();
            return new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome("DELIVERED","PENDING",null,null,at,at,null);
        }).when(channel).deliver(any());
    }

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

    @Override void prepareDuePlan() {
        // Scope is the guarded disposable database. Keep one same-source sensor for an unambiguous matrix.
        jdbc.update("UPDATE ops_device SET source_mode='replay' WHERE device_id<>? AND source_mode='mock'", device);
        jdbc.update("UPDATE flight_plan SET status_code='EXECUTING',start_at=?,end_at=? WHERE plan_id=?",
                java.time.Instant.ofEpochMilli(now-60000).atOffset(java.time.ZoneOffset.UTC),
                java.time.Instant.ofEpochMilli(now+3600000).atOffset(java.time.ZoneOffset.UTC), plan);
        jdbc.update("UPDATE device_incident SET stage='RECOVERED',closed_at=? WHERE device_id=?", now-60001, device);
        healthy();
        // Keep seed history outside the tested plan window.
        jdbc.update("UPDATE device_incident SET closed_at=? WHERE device_id=?", now-60001, device);
        prepareBrowserChecks();
    }

    @Test void duePlanDistinguishesNormalAbnormalAndUnknown() throws Exception {
        prepareDuePlan();
        healthy(); // Later current-state changes must not move already closed incidents into this plan.
        String path="/api/v1/flight-plans/"+plan+"/verifications/device-check";
        var normal=data(auth(get(path)));
        assertThat(normal.path("conclusion").asText()).isEqualTo("SUSPECTED_NOT_TAKEN_OFF");
        assertThat(normal.path("rows")).hasSize(1);
        jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE',health_code='BAD' WHERE device_id=?",device);
        assertThat(data(auth(get(path))).path("conclusion").asText()).isEqualTo("AUTO_DEVICE_ABNORMAL");
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code='UNKNOWN' WHERE device_id=?",device);
        assertThat(data(auth(get(path))).path("conclusion").asText()).isEqualTo("CHECK_INCOMPLETE");
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
