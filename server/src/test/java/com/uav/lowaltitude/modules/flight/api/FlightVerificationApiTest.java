package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.handoff.channel=none", "app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FlightVerificationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    String session, planId;

    @BeforeEach void setup() throws Exception {
        session = json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        String template = jdbc.queryForObject("SELECT plan_id FROM flight_plan WHERE source_id IS NOT NULL ORDER BY plan_id FETCH FIRST 1 ROWS ONLY", String.class);
        planId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_id,source_mode,route_version_id,owner_org_id,district_id,start_at,end_at,created_at,updated_at,version)"
                + " SELECT ?,?,'COMPLETED',source_id,source_mode,route_version_id,owner_org_id,district_id,?,?,created_at,updated_at,0 FROM flight_plan WHERE plan_id=?",
                planId,"TEST-"+planId,Timestamp.from(Instant.now().minusSeconds(7200)),Timestamp.from(Instant.now().minusSeconds(3600)),template);
        // H2 covers unknown device locations; PostgreSQL subclasses retain the real spatial path.
        try (var connection = jdbc.getDataSource().getConnection()) {
            if ("H2".equals(connection.getMetaData().getDatabaseProductName()))
                jdbc.update("UPDATE ops_device SET longitude=NULL,latitude=NULL");
        }
    }

    @Test void automaticCheckKeepsTakeoffUnknownAndFeedbackDoesNotCreateAlarm() throws Exception {
        long alarms = jdbc.queryForObject("SELECT COUNT(*) FROM uav_event", Long.class);
        JsonNode created = automatic();
        assertThat(created.path("conclusion").asText()).isIn("AUTO_DEVICE_ABNORMAL", "CHECK_INCOMPLETE", "SUSPECTED_NOT_TAKEN_OFF");
        assertThat(created.path("evidence").asText()).startsWith("系统检查时间：");
        assertThat(created.path("takeoff_status").asText()).isEqualTo("UNKNOWN");
        assertThat(created.path("handled_by_name").asText()).isNotBlank();
        assertThat(created.path("handled_at").asLong()).isPositive();
        String verification = created.path("verification_id").asText();
        String sourceId = jdbc.queryForObject("SELECT source_id FROM flight_plan WHERE plan_id=?", String.class, planId);
        mvc.perform(post(base()+"/feedback").header("Authorization", "Bearer "+session)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("verification_id",verification,"recipient_id",sourceId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.delivery_status").value("PENDING_DELIVERY"))
                .andExpect(jsonPath("$.data.receipt_status").value("NOT_EXPECTED"))
                .andExpect(jsonPath("$.data.processing_result").doesNotExist());
        mvc.perform(get(base()).header("Authorization", "Bearer "+session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifications[0].verification_id").value(verification))
                .andExpect(jsonPath("$.data.feedback[0].verification_id").value(verification));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event", Long.class)).isEqualTo(alarms);
        assertThat(jdbc.queryForObject("SELECT status_code FROM flight_plan WHERE plan_id=?", String.class, planId)).isEqualTo("COMPLETED");
    }

    @Test void manualVerdictsAreDisabledAndAutomaticCheckRequiresRevisionAndDuePlan() throws Exception {
        mvc.perform(post(base()).header("Authorization", "Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"conclusion\":\"NOT_TAKEN_OFF\",\"evidence\":\" \",\"note\":\"确认未起飞\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MANUAL_VERIFICATION_DISABLED"));
        mvc.perform(post(base()+"/automatic").header("Authorization", "Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE flight_plan SET start_at=?,end_at=? WHERE plan_id=?",Timestamp.from(Instant.now().plusSeconds(3600)),Timestamp.from(Instant.now().plusSeconds(7200)),planId);
        mvc.perform(post(base()+"/automatic").header("Authorization", "Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_revision\":0}"))
                .andExpect(status().isConflict());
    }

    @Test void feedbackRequiresVerificationAndItsOwnRecipient() throws Exception {
        JsonNode created=automatic();
        assertThat(created.path("takeoff_status").asText()).isEqualTo("UNKNOWN");
        mvc.perform(post(base()+"/feedback").header("Authorization", "Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("verification_id",created.path("verification_id").asText(),"recipient_id","unrelated-risk-recipient"))))
                .andExpect(status().isConflict());
    }

    @Test void staleRevisionAndDuplicateFeedbackCannotCreateMoreRecords() throws Exception {
        JsonNode created=automatic();
        mvc.perform(post(base()+"/automatic").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_revision\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        String sourceId=jdbc.queryForObject("SELECT source_id FROM flight_plan WHERE plan_id=?",String.class,planId);
        String body=json.writeValueAsString(java.util.Map.of("verification_id",created.path("verification_id").asText(),"recipient_id",sourceId));
        mvc.perform(post(base()+"/feedback").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post(base()+"/feedback").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_verification WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_feedback WHERE plan_id=?",Long.class,planId)).isEqualTo(1);
    }

    @Test void pendingPlanDoesNotReturnActualTrajectory() throws Exception {
        jdbc.update("UPDATE flight_plan SET status_code='APPROVED' WHERE plan_id=?",planId);
        mvc.perform(get("/api/v1/flight-plans/"+planId+"/trajectory").header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.availability").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.data.points").isEmpty());
    }

    private JsonNode automatic() throws Exception {
        return json.readTree(mvc.perform(post(base()+"/automatic").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_revision\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String base(){return "/api/v1/flight-plans/"+planId+"/verifications";}
}
