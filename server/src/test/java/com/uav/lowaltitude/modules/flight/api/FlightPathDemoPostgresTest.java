package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.integration.mock.LocalFlightPathDemoSeeder;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;
import com.uav.lowaltitude.platform.time.AppClock;

/** 真实PostGIS与完整读取链验证移动采样、偏离及缺口；沿用强制隔离库配置。 */
@EnabledIfEnvironmentVariable(named="FLIGHT_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/stage_flight_verify_[a-z0-9_]+")
class FlightPathDemoPostgresTest extends FlightVerificationPostgresTest {
    @Autowired AppClock clock;
    @Autowired RuleRunService runs;

    @Test void movingPointsProduceGreenRedAndBrokenSegmentsThroughActualApi() throws Exception {
        new LocalFlightPathDemoSeeder(jdbc,clock,runs).run(null);
        String prefix=LocalFlightPathDemoSeeder.prefix(clock.now());
        JsonNode matched=read(prefix+"-3","trajectory");
        assertThat(matched.path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(matched.path("points").size()).isEqualTo(21);
        java.util.Set<Double> longitudes=new java.util.HashSet<>();
        for(JsonNode point:matched.path("points")){
            longitudes.add(point.path("longitude").asDouble());
            assertThat(point.path("corridor_relation").asText()).isEqualTo("WITHIN");
        }
        assertThat(longitudes).hasSize(21);
        assertThat(read(prefix+"-3","actuals").path("match").path("plan_match_code").asText()).isEqualTo("FULL");
        JsonNode deviated=read(prefix+"-2","trajectory");
        assertThat(deviated.path("points").findValuesAsText("corridor_relation")).contains("WITHIN","OUTSIDE");
        JsonNode actuals=read(prefix+"-2","actuals");
        assertThat(actuals.path("match").path("hit_details_c01").get(0).path("facts").path("dimensions").path("corridor").asText()).isEqualTo("MISMATCH");
        JsonNode gap=read(prefix+"-4","trajectory");
        assertThat(gap.path("points").size()).isEqualTo(16);
        assertThat(gap.path("points").get(8).path("point_seq").asInt()).isEqualTo(13);
        assertThat(gap.path("points").get(8).path("break_before").asBoolean()).isTrue();
    }

    @Test void repeatedSeedingKeepsObservationHistoryAndFiveDedicatedPendingPlans() {
        var seeder=new LocalFlightPathDemoSeeder(jdbc,clock,runs);seeder.run(null);
        String prefix=LocalFlightPathDemoSeeder.prefix(clock.now());
        long points=jdbc.queryForObject("SELECT count(*) FROM track_point WHERE point_id LIKE ?",Long.class,prefix+"-%");
        long evaluations=jdbc.queryForObject("SELECT count(*) FROM rule_evaluation WHERE plan_id LIKE ?",Long.class,prefix+"-%");
        seeder.run(null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM track_point WHERE point_id LIKE ?",Long.class,prefix+"-%")).isEqualTo(points);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_evaluation WHERE plan_id LIKE ?",Long.class,prefix+"-%")).isEqualTo(evaluations);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flight_plan WHERE plan_id LIKE 'seed-pending-demo-%' AND status_code='PENDING'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status_code FROM flight_plan WHERE plan_id='seed-stage3-plan-pending'",String.class)).isEqualTo("CANCELLED");
    }
    @Test void stationaryOldExampleDoesNotInventMovingTrajectory() throws Exception {
        jdbc.update("UPDATE flight_plan SET status_code='COMPLETED' WHERE plan_id='seed-stage7-plan-legal'");
        JsonNode trajectory=read("seed-stage7-plan-legal","trajectory");
        // 旧固定样例可能尚无ACTIVE研判；让已有引擎按该样例观测时刻执行，不修改其轨迹。
        if(!"AVAILABLE".equals(trajectory.path("availability").asText())) {
            var asOf=com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder.T0.atOffset(java.time.ZoneOffset.UTC);
            var run=runs.start("LEGALITY-DEMO",com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode.ACTIVE,"REPLAY","stationary-flight-test",null,asOf);
            runs.runBatch(run,java.util.List.of(new com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject(com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind.TARGET,"seed-stage7-target-legal",null,null,null)),asOf);
            trajectory=read("seed-stage7-plan-legal","trajectory");
        }
        assertThat(trajectory.path("note").asText()).contains("同一位置");
        assertThat(trajectory.path("points").size()).isEqualTo(5);
    }
    private JsonNode read(String plan,String endpoint) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/flight-plans/"+plan+"/"+endpoint).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
