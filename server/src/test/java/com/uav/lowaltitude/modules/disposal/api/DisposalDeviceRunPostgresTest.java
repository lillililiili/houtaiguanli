package com.uav.lowaltitude.modules.disposal.api;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same no-successor and historical safe-stop checks in a disposable PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class DisposalDeviceRunPostgresTest extends DisposalDeviceRunApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void cleanup() { DATABASE.close(); }

    @org.junit.jupiter.api.Test void databaseRejectsInvalidModeWithoutChangingExistingAuthorization() throws Exception {
        var body = java.util.Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                "channel", "COUNTERMEASURE_4CH", "device_id", device, "countermeasure_mode", "DRIVE_AWAY");
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/disposal-authorizations/direct-execute")
                .header("Authorization", "Bearer " + actor).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).path("data").path("authorization_id").asText();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update disposal_authorization set countermeasure_mode='UNKNOWN' where authorization_id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update disposal_authorization set action_type='JAMMING' where authorization_id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select countermeasure_mode from disposal_authorization where authorization_id=?", String.class, id)).isEqualTo("DRIVE_AWAY");
    }
}
