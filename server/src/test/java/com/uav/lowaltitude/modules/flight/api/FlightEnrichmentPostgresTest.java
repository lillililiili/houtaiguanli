package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mock.LocalFlightPlanEnrichmentSeeder;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.infrastructure.WeatherRiskRepository;
import com.uav.lowaltitude.platform.time.AppClock;

@SpringBootTest(properties={"app.flight.status-advance.enabled=false","app.handoff.channel=none"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@EnabledIfEnvironmentVariable(named="FLIGHT_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/stage_flight_verify_[a-z0-9_]+")
class FlightEnrichmentPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){FlightVerificationPostgresTest.database(registry);}
    @Autowired JdbcTemplate jdbc; @Autowired AppClock clock; @Autowired RuleRunService runs;
    @Autowired RiskIngestionService risks; @Autowired WeatherRiskRepository weather;
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Test void planDetailsExposeFilingFactsWithoutInventingMissingFields() throws Exception {
        String session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        String endpoint="/api/v1/flight-plans/seed-stage3-plan-legal";
        var before=json.readTree(mvc.perform(get(endpoint).header("Authorization","Bearer "+session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(before.path("filing").isObject()).isTrue();
        assertThat(before.path("filing").path("pilot_name").asText("")).isEmpty();
        jdbc.update("UPDATE flight_plan SET pilot_name='演示飞手（模拟）',operator_name='演示单位（模拟）',takeoff_site_name='模拟起飞点',landing_site_name='模拟降落点',takeoff_longitude=118.02,takeoff_latitude=37.02,landing_longitude=118.03,landing_latitude=37.03 WHERE plan_id='seed-stage3-plan-legal'");
        var data=json.readTree(mvc.perform(get(endpoint).header("Authorization","Bearer "+session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(data.path("filing").path("pilot_name").asText()).isEqualTo("演示飞手（模拟）");
        assertThat(data.path("filing").path("operator_name").asText()).isEqualTo("演示单位（模拟）");
        assertThat(data.path("filing").path("takeoff_longitude").asDouble()).isEqualTo(118.02);
        assertThat(data.path("filing").path("landing_latitude").asDouble()).isEqualTo(37.03);
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/flight-plans/nonexistent-plan").header("Authorization","Bearer "+session)).andExpect(status().isNotFound());
    }
    @Test void enrichmentPersistsFivePreflightRisksAndOneReadableTrajectoryWithoutResettingHistory() throws Exception {
        String prefix="seed-refill-991231-235959";
        Instant now=clock.now();
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,created_at,updated_at,version) SELECT 'seed-weather-demo','WEATHER-DEMO','气象预警演示源','WEATHER_DEMO','1.0',true,'mock',?,?,0 WHERE NOT EXISTS(SELECT 1 FROM integration_source WHERE source_id='seed-weather-demo')",Timestamp.from(now),Timestamp.from(now));
        for(int n=1;n<=7;n++)jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_id,source_mode,route_version_id,owner_org_id,district_id,start_at,end_at,created_at,updated_at,version) SELECT ?,?,?,source_id,'mock',route_version_id,owner_org_id,district_id,?,?,created_at,updated_at,0 FROM flight_plan WHERE plan_id='seed-stage3-plan-legal'",
            prefix+"-"+n,"TEST-"+n,n<=5?"PENDING":"EXECUTING",Timestamp.from(now.plusSeconds(n<=5?3600:-600)),Timestamp.from(now.plusSeconds(7200)));
        var seed=new LocalFlightPlanEnrichmentSeeder(jdbc,clock,runs,risks,weather,prefix);
        seed.run(null);
        var details=new com.uav.lowaltitude.integration.mock.LocalFlightPlanDetailsSeeder(jdbc,clock,prefix);
        details.run(null);
        assertThat(jdbc.queryForObject("SELECT pilot_name FROM flight_plan WHERE plan_id=?",String.class,prefix+"-5")).isEqualTo("演示飞手5（模拟）");
        assertThat(jdbc.queryForObject("SELECT uav_sn FROM flight_plan WHERE plan_id=?",String.class,prefix+"-6")).isEqualTo(prefix+"-6");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flight_risk WHERE plan_id LIKE ?",Integer.class,prefix+"-%")).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weather_risk_fact w JOIN flight_risk r ON r.risk_id=w.risk_id WHERE r.plan_id LIKE ?",Integer.class,prefix+"-%")).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE track_id=?",Integer.class,prefix+"-6-tr")).isEqualTo(21);
        assertThat(jdbc.queryForObject("SELECT uav_sn FROM flight_plan WHERE plan_id=?",String.class,prefix+"-7")).startsWith("DEMO-UAV-");
        String session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        var filtered=json.readTree(mvc.perform(get("/api/v1/risks").param("plan_id",prefix+"-1")
            .param("risk_types","WEATHER,SPACE_OBJECT").header("Authorization","Bearer "+session))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("risk_type").asText()).isEqualTo("WEATHER");
        mvc.perform(get("/api/v1/flight-plans/"+prefix+"-1/verifications/device-check").header("Authorization","Bearer "+session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/flight-plans/"+prefix+"-1/verifications/automatic").header("Authorization","Bearer "+session)
            .header("Idempotency-Key","preflight-must-not-notify").contentType(MediaType.APPLICATION_JSON).content("{\"expected_revision\":0}"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flight_plan_verification WHERE plan_id=?",Integer.class,prefix+"-1")).isZero();
        var trajectory=json.readTree(mvc.perform(get("/api/v1/flight-plans/"+prefix+"-6/trajectory").header("Authorization","Bearer "+session))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(trajectory.path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(trajectory.path("points").size()).isEqualTo(21);
        assertThat(trajectory.path("points").findValuesAsText("corridor_relation")).containsOnly("WITHIN");
        String before=jdbc.queryForObject("SELECT string_agg(CAST(r AS text),'|' ORDER BY risk_id) FROM flight_risk r WHERE plan_id LIKE ?",String.class,prefix+"-%");
        String oldPlans=jdbc.queryForObject("SELECT string_agg(CAST(p AS text),'|' ORDER BY plan_id) FROM flight_plan p",String.class);
        var preserve=new org.springframework.boot.DefaultApplicationArguments("--preserve-existing-flight-demos");
        new com.uav.lowaltitude.integration.mock.LocalStage3PlanningSeeder(jdbc,clock).run(preserve);
        new com.uav.lowaltitude.integration.mock.LocalPendingPlanDemoSeeder(jdbc,clock,risks,null,null).run(preserve);
        new com.uav.lowaltitude.integration.mock.LocalFlightPathDemoSeeder(jdbc,clock,runs).run(preserve);
        assertThat(jdbc.queryForObject("SELECT string_agg(CAST(p AS text),'|' ORDER BY plan_id) FROM flight_plan p",String.class)).isEqualTo(oldPlans);
        seed.run(null);
        details.run(null);
        assertThat(jdbc.queryForObject("SELECT string_agg(CAST(p AS text),'|' ORDER BY plan_id) FROM flight_plan p",String.class)).isEqualTo(oldPlans);
        assertThat(jdbc.queryForObject("SELECT string_agg(CAST(r AS text),'|' ORDER BY risk_id) FROM flight_risk r WHERE plan_id LIKE ?",String.class,prefix+"-%")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE track_id=?",Integer.class,prefix+"-6-tr")).isEqualTo(21);
    }
}
