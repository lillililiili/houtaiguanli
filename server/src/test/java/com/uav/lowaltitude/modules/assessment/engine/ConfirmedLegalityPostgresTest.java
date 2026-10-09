package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.assessment.engine.checks.PlanMatchCheck;
import com.uav.lowaltitude.modules.assessment.engine.checks.RouteDeviationCheck;

@SpringBootTest
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Transactional
class ConfirmedLegalityPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @Autowired JdbcTemplate jdbc;
    @Autowired PostgisSpatialFactAdapter spatial;
    @Autowired RuleEngineRepository repository;
    @Autowired DataSource dataSource;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @ParameterizedTest @CsvSource({"105.2,22.5,30,100,FULL", "123.1,41.2,150,500,NONE"})
    void actualGeographyDistanceUsesConfirmedCenterlineAndKeepsTimeBoundaryCandidate(double lon, double lat, int offset, int width, PlanMatchCode expected) {
        String route=id(), version=id(), plan=id(), sn="PG-"+id();
        OffsetDateTime now=OffsetDateTime.now().withNano(0);
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at)"
                + " VALUES (?,?,?,TRUE,'seed-stage3-source','mock','seed-stage3-org','seed-stage3-district',?,?)", route, route, "确认规则空间夹具", now, now);
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " VALUES (?,?,1,ST_SetSRID(ST_MakeLine(ST_MakePoint(?,?),ST_MakePoint(?,?)),4326),?,0,120,'AMSL',?,?)",
                version, route, lon, lat-0.02, lon, lat+0.02, width, now.minusHours(1), now);
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at)"
                + " VALUES (?,?,'COMPLETED','seed-stage3-source','mock',?,?,?,?, 'seed-stage3-org','seed-stage3-district',?,?)",
                plan, plan, sn, now.minusHours(1), now.minusMinutes(10), version, now, now);
        var xy=jdbc.queryForMap("SELECT ST_X(p) AS lon,ST_Y(p) AS lat FROM (SELECT ST_Project(ST_SetSRID(ST_MakePoint(?,?),4326)::geography,?,pi()/2)::geometry AS p) q",lon,lat,offset);
        var state=new TargetState(id(),id(),sn,new BigDecimal(xy.get("lon").toString()),new BigDecimal(xy.get("lat").toString()),BigDecimal.valueOf(80),null,null,null,new BigDecimal("0.9"),now,now);
        var actual=spatial.distanceToRoute(state,version);
        assertThat(actual.distanceM().doubleValue()).isCloseTo(offset,org.assertj.core.data.Offset.offset(0.05));
        var candidates=repository.candidatePlans("seed-stage3-org","seed-stage3-district",null,now,10,"mock");
        var own=candidates.stream().filter(value -> value.planId().equals(plan)).toList();
        assertThat(own).hasSize(1);
        assertThat(repository.candidatePlans("seed-stage3-org","seed-stage3-district",null,now.plusSeconds(1),10,"mock"))
                .noneMatch(value -> value.planId().equals(plan));
        var params=ConfirmedLegalityRulesTest.confirmed();
        var match=new PlanMatchCheck().match(state,own,rv -> spatial.distanceToRoute(state,rv),now,params);
        assertThat(match.code()).isEqualTo(expected);
        var context=new EvaluationContext(new Subject(SubjectKind.TARGET,state.targetId(),"seed-stage3-org","seed-stage3-district","mock"),state,new TrackQuality(5,5L,false),match,List.of(),now,Freshness.FRESH,RunMode.ACTIVE,"mock");
        assertThat(new RouteDeviationCheck(spatial).evaluate(context,params).resultCode())
                .isEqualTo(expected == PlanMatchCode.NONE ? ResultCode.NOT_APPLICABLE : ResultCode.FAIL);
    }

    @Test void qualityQueryBoundsObservedTimeBeforeOrderingAndLimit() {
        OffsetDateTime now=OffsetDateTime.now().withNano(0);
        String target=id(),link=id(),track=id();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at)"
                + " VALUES (?,?,'UAV','mock','seed-stage3-org','seed-stage3-district',?,?)",target,target,now,now);
        jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,source_session_key,external_target_id,created_at) VALUES (?,?,'seed-stage3-source',?,?,?)",link,target,id(),target,now);
        jdbc.update("INSERT INTO track(track_id,target_id,link_id,external_track_id,started_at,created_at) VALUES (?,?,?,?,?,?)",track,target,link,track,now.minusMinutes(5),now);
        int sequence=0;
        for(int seconds : List.of(-120,-119,-1,0,-121,1)) {
            OffsetDateTime point=now.plusSeconds(seconds);
            jdbc.update("INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at) VALUES (?,?,?,?,?,ST_SetSRID(ST_MakePoint(116,35),4326),?)",id(),track,sequence++,point,now,now);
        }
        var bounded=repository.recentPointTimes(track,10,now.minusSeconds(120),now);
        assertThat(bounded.stream().map(OffsetDateTime::toInstant)).containsExactly(now.toInstant(),now.minusSeconds(1).toInstant(),now.minusSeconds(119).toInstant(),now.minusSeconds(120).toInstant());
        assertThat(repository.recentPointTimes(track,3,now.minusSeconds(120),now)).hasSize(3);
    }

    @ParameterizedTest @CsvSource({"local,true,false", "local,false,false", "test,true,false", "local,true,true"})
    void planAndTargetSourceModesStayIsolatedExceptTheExplicitLocalBridge(String profile, boolean bridgeEnabled, boolean production) {
        var env=new MockEnvironment().withProperty("app.flight-device-check.simulator-device-bridge-enabled",Boolean.toString(bridgeEnabled));
        env.setActiveProfiles(production ? new String[]{"test",profile,"production"} : new String[]{"test",profile});
        var rules=new RuleEngineRepository(jdbc,dataSource,new SimulationPolicy(env),env);
        String sn="ISOLATED-"+id();
        OffsetDateTime now=OffsetDateTime.now().withNano(0);
        Map<String,String> planIds=new LinkedHashMap<>(),targetIds=new LinkedHashMap<>(),sourceIds=new LinkedHashMap<>();
        int order=0;
        for(String mode:List.of("live","mock","replay")) {
            String source=id();
            jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES (?,?,?,TRUE,?,?,?,0)",source,source,"来源隔离夹具",mode,now,now);
            sourceIds.put(mode,source);
            planIds.put(mode,sourcePlan(sn,source,mode,now));
            targetIds.put(mode,sourceTarget(sn,mode,now.plusSeconds(order++)));
        }
        String simulator="local-flight-plan-simulator";
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " SELECT ?,?,'本地模拟计划夹具',TRUE,'mock',?,?,0 WHERE NOT EXISTS(SELECT 1 FROM integration_source WHERE source_id=?)",simulator,"LOCAL_ISOLATION_"+id(),now,now,simulator);
        String simulatorPlan=sourcePlan(sn,simulator,"mock",now);
        boolean bridge="local".equals(profile)&&bridgeEnabled&&!production;
        for(String mode:List.of("live","mock","replay")) {
            var plans=rules.candidatePlans("seed-stage3-org","seed-stage3-district",sn,now,10,mode).stream()
                    .filter(plan -> sn.equals(plan.uavSn())).map(PlanFact::planId).toList();
            if("mock".equals(mode)||("replay".equals(mode)&&bridge)) assertThat(plans).containsExactlyInAnyOrder(planIds.get(mode),simulatorPlan);
            else assertThat(plans).containsExactly(planIds.get(mode));
            var target=rules.latestTargetBySn(sn,"seed-stage3-org","seed-stage3-district",rules.planSource(planIds.get(mode)));
            assertThat(target.targetId()).isEqualTo(targetIds.get(mode));
        }
        var simulatorTarget=rules.latestTargetBySn(sn,"seed-stage3-org","seed-stage3-district",rules.planSource(simulatorPlan));
        assertThat(simulatorTarget.targetId()).isEqualTo(targetIds.get(bridge?"replay":"mock"));
        // Remove the real authorization: neither ordinary simulation nor the privileged bridge can replace it.
        jdbc.update("UPDATE flight_plan SET status_code='CANCELLED' WHERE plan_id=?",planIds.get("live"));
        assertThat(rules.candidatePlans("seed-stage3-org","seed-stage3-district",sn,now,10,"live")).noneMatch(plan -> sn.equals(plan.uavSn()));
        // With the revised no-plan policy, missing registration remains visible but cannot generate
        // NO_AUTHORIZATION. A permitted bridge still supplies its real plan, rather than a fake PASS.
        for(String mode:List.of("mock","replay")) jdbc.update("UPDATE flight_plan SET status_code='CANCELLED' WHERE plan_id=?",planIds.get(mode));
        var params=ConfirmedLegalityRulesTest.confirmed().put("C03","no_plan_status","LEGAL");
        for(String mode:List.of("live","replay")) {
            var available=rules.candidatePlans("seed-stage3-org","seed-stage3-district",sn,now,10,mode);
            var state=new TargetState(targetIds.get(mode),id(),sn,new BigDecimal("116.01"),new BigDecimal("35"),BigDecimal.valueOf(80),null,null,null,new BigDecimal("0.9"),now,now);
            var match=new PlanMatchCheck().match(state,available,rv -> spatial.distanceToRoute(state,rv),now,params);
            assertThat(match.code()).isEqualTo("replay".equals(mode)&&bridge?PlanMatchCode.FULL:PlanMatchCode.NONE);
            var context=new EvaluationContext(new Subject(SubjectKind.TARGET,state.targetId(),"seed-stage3-org","seed-stage3-district",mode),state,new TrackQuality(5,5L,false),match,List.of(),now,Freshness.FRESH,RunMode.ACTIVE,mode);
            var hits=ConfirmedLegalityRulesTest.checks(context,params,spatial);
            var verdict=new C03Decision().decide(context,hits,params);
            assertThat(verdict.violationReasons()).doesNotContain("NO_AUTHORIZATION");
            assertThat(new DecisionAssuranceAlgorithm().assess(context,hits,verdict,params).status()).isEqualTo("SUFFICIENT");
            if(match.code()==PlanMatchCode.FULL) assertThat(verdict.status()).isEqualTo(LegalStatus.LEGAL);
            else assertThat(verdict.violationReasons()).allMatch(reason -> "NIGHT_FLIGHT".equals(reason));
        }
    }

    private String sourcePlan(String sn,String source,String mode,OffsetDateTime now) {
        String route=id(),version=id(),plan=id();
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,TRUE,?,?,'seed-stage3-org','seed-stage3-district',?,?)",route,route,"来源隔离航线",source,mode,now,now);
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) VALUES (?,?,1,ST_GeomFromText('LINESTRING(116 35,116.02 35)',4326),100,0,120,'AMSL',?,?)",version,route,now.minusHours(1),now);
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,'APPROVED',?,?,?,?,?,?,'seed-stage3-org','seed-stage3-district',?,?)",plan,plan,source,mode,sn,now.minusMinutes(10),now.plusMinutes(10),version,now,now);
        return plan;
    }

    private String sourceTarget(String sn,String mode,OffsetDateTime now) {
        String target=id();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,uav_sn,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,'UAV',?,?,'seed-stage3-org','seed-stage3-district',?,?)",target,target,sn,mode,now,now);
        jdbc.update("INSERT INTO target_latest_state(target_id,location,observed_at,received_at,created_at,updated_at) VALUES (?,ST_GeomFromText('POINT(116.01 35)',4326),?,?,?,?)",target,now,now,now,now);
        return target;
    }
    private static String id() { return UUID.randomUUID().toString(); }
}
