package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class LocalPendingPlanDemoSeederTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalPendingPlanDemoSeeder seeder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void pendingPlansHaveThreeRecentNotifiableSpaceRisksAndNoFakeAssessments() {
        assertThat(count("select count(*) from flight_plan where plan_id like 'seed-pending-demo-%' and status_code='PENDING' and start_at > CURRENT_TIMESTAMP")).isEqualTo(5);
        assertThat(count("select count(*) from flight_risk r join space_risk_fact f on f.risk_id=r.risk_id where r.plan_id like 'seed-pending-demo-%' and r.risk_type='SPACE_OBJECT' and r.state_code='PENDING_NOTIFICATION' and r.source_mode='mock'")).isEqualTo(3);
        assertThat(count("select count(*) from flight_risk_verification v join flight_risk r on r.risk_id=v.risk_id where r.plan_id like 'seed-pending-demo-%' and v.conclusion='CONFIRMED'")).isEqualTo(3);
        assertThat(count("select count(*) from assessment_result where plan_id like 'seed-pending-demo-%'")).isZero();
    }

    @Test
    void notificationPersistsAndReseedingDoesNotResetItOrDuplicateData() throws Exception {
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + json.readTree(login).path("data").path("session_id").asText();
        String risk = jdbc.queryForObject("select risk_id from flight_risk where plan_id='seed-pending-demo-1'", String.class);
        mvc.perform(get("/api/v1/risks").param("plan_id", "seed-pending-demo-1").param("risk_type", "SPACE_OBJECT")
                .header("Authorization", bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source_kind\":\"RISK\",\"source_id\":\"" + risk + "\",\"handoff_type\":\"RISK_NOTICE\",\"expected_version\":1}"))
                .andExpect(status().isCreated());
        seeder.run(null);
        seeder.run(null);
        mvc.perform(get("/api/v1/risks/" + risk).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("NOTIFIED"));
        assertThat(count("select count(*) from flight_plan where plan_id like 'seed-pending-demo-%'")).isEqualTo(5);
        assertThat(count("select count(*) from flight_risk where plan_id like 'seed-pending-demo-%'")).isEqualTo(3);
        assertThat(count("select count(*) from flight_risk_verification v join flight_risk r on r.risk_id=v.risk_id where r.plan_id like 'seed-pending-demo-%'")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from handoff where source_id=?", Integer.class, risk)).isEqualTo(1);
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
}
