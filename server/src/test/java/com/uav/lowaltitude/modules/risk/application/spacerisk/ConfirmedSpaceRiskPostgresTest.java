package com.uav.lowaltitude.modules.risk.application.spacerisk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.SpaceObservation;

/** 客户确认第四部分：实库验证时间/空间/来源边界、规范观测数量、趋势与重复评估。 */
@SpringBootTest(properties = "app.rule-engine.allow-demo-active=true")
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class ConfirmedSpaceRiskPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) { DATABASE.springProperties(properties); }
    @AfterAll static void close() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired SpaceRiskSpatialPort spatial;
    @Autowired SpaceRiskEvaluationService evaluation;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    String org, district, plan, route, target, track, point, source;
    OffsetDateTime now;

    @BeforeEach void fixture() {
        now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        org = id(); district = id(); plan = id(); route = id(); target = id(); track = id(); point = id(); source = source("mock");
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", org, org, "空间规则测试单位");
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)", district, district, "空间规则测试区域");
        String routeRoot = id();
        jdbc.update("insert into route(route_id,route_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'测试航线','mock',?,?,?,?,0)",
                routeRoot, routeRoot, org, district, ts(now), ts(now));
        jdbc.update("insert into route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " values(?,?,1,ST_GeomFromText('LINESTRING(117.9 37,118.1 37)',4326),100,0,300,'AMSL',?,?)", route, routeRoot, ts(now.minusHours(1)), ts(now));
        jdbc.update("insert into flight_plan(plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " values(?,?,'PENDING','mock',?,?,?,?,?,?,?,0)", plan, plan, ts(now.minusMinutes(30)), ts(now.plusMinutes(30)), route, org, district, ts(now), ts(now));
        jdbc.update("insert into target(target_id,target_no,object_type_code,subtype,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,created_at,updated_at,version)"
                + " values(?,?,'BIRD','BIRD_FLOCK','mock',?,?,?,?,?,?,0)", target, target, org, district, ts(now), ts(now), ts(now), ts(now));
        jdbc.update("insert into track(track_id,target_id,external_track_id,layer,started_at,created_at) values(?,?,?,'FUSED',?,?)", track, target, track, ts(now.minusHours(1)), ts(now));
        sample(now, 37.001, 25);
        jdbc.update("update rule_set set active_version_id='space-risk-demo-v1' where rule_set_code='SPACE-RISK-DEMO'");
    }

    @ParameterizedTest
    @CsvSource({"-901,false", "-900,true", "0,true", "3600,true", "4500,true", "4501,false"})
    void c04UsesTheObservationTimeAndExactlyFifteenMinutesPadding(int observationOffset, boolean matches) {
        jdbc.update("update flight_plan set start_at=?,end_at=? where plan_id=?", ts(now.minusSeconds(observationOffset)), ts(now.minusSeconds(observationOffset).plusHours(1)), plan);
        assertThat(observations().stream().anyMatch(row -> plan.equals(row.planId()))).isEqualTo(matches);
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,true", "3599,true", "3600,false", "3900,false"})
    void c05OnlyUsesTheActualPlanWindowWithoutC04Padding(int observationOffset, boolean matches) {
        airport();
        jdbc.update("update flight_plan set start_at=?,end_at=? where plan_id=?", ts(now.minusSeconds(observationOffset)), ts(now.minusSeconds(observationOffset).plusHours(1)), plan);
        var results = spatial.airportProximity(now.minusSeconds(1), now.plusSeconds(1), 15);
        assertThat(results.stream().anyMatch(row -> target.equals(row.targetId()) && plan.equals(row.planId()))).isEqualTo(matches);
    }

    @ParameterizedTest
    @CsvSource({"mock,mock,PENDING,true", "mock,mock,COMPLETED,true", "mock,mock,CANCELLED,false", "mock,live,PENDING,false", "replay,mock,PENDING,false", "live,live,EXECUTING,true"})
    void cancelledAndCrossModePlansCannotReceiveSpaceRisks(String targetMode, String planMode, String status, boolean matches) {
        airport();
        jdbc.update("update target set source_mode=? where target_id=?", targetMode, target);
        jdbc.update("update flight_plan set source_mode=?,status_code=? where plan_id=?", planMode, status, plan);
        assertThat(observations().stream().anyMatch(row -> plan.equals(row.planId()))).isEqualTo(matches);
        assertThat(spatial.airportProximity(now.minusSeconds(1), now.plusSeconds(1), 15).stream()
                .anyMatch(row -> target.equals(row.targetId()) && plan.equals(row.planId()))).isEqualTo(matches);
    }

    @ParameterizedTest
    @CsvSource({
            "local|qa,true,replay,special,true",
            "local|qa,false,replay,special,false",
            "test,true,replay,special,false",
            "local,true,replay,special,false",
            "local|qa|production,true,replay,special,false",
            "local|qa|prod,true,replay,special,false",
            "local|qa,true,replay,ordinary,false",
            "local|qa,true,live,special,false",
            "local|qa,true,mock,special,true"
    })
    void onlyExplicitLocalSimulatorPlansMayBridgeReplayTargetsForC04C05AndTrend(String profiles, boolean enabled,
            String targetMode, String planSourceKind, boolean matches) {
        String simulator = "local-flight-plan-simulator";
        jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select ?,?,'本地模拟计划测试',true,'mock',?,?,0 where not exists(select 1 from integration_source where source_id=?)",
                simulator, id(), ts(now), ts(now), simulator);
        jdbc.update("update flight_plan set source_id=? where plan_id=?", "special".equals(planSourceKind) ? simulator : source, plan);
        jdbc.update("update target set source_mode=? where target_id=?", targetMode, target);
        String matchingSource = "mock".equals(targetMode) ? source : source(targetMode);
        jdbc.update("update track_point set observation_id=? where point_id=?", observation(matchingSource, now, 25), point);
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,position_accuracy_m,point_kind,created_at)"
                + " values(?,?,0,?,?,ST_SetSRID(ST_MakePoint(118,37.01),4326),1,'MEAS',?)", id(), track, ts(now.minusMinutes(5)), ts(now.minusMinutes(5)), ts(now));
        airport();
        MockEnvironment environment = new MockEnvironment().withProperty("app.flight-device-check.simulator-device-bridge-enabled", Boolean.toString(enabled));
        environment.setActiveProfiles(profiles.split("\\|"));
        var adapter = new PostgisSpaceRiskSpatialAdapter(jdbc, dataSource, json, new SimulationPolicy(environment), environment);
        OffsetDateTime from = now.minusSeconds(1), to = now.plusSeconds(1);
        var manual = adapter.observations(from, to, 15, 30).stream().filter(row -> target.equals(row.targetId()) && plan.equals(row.planId())).toList();
        assertThat(manual).hasSize(matches ? 1 : 0);
        if (matches) {
            assertThat(manual.get(0).trend()).isEqualTo("RISING");
            assertThat(manual.get(0).objectCount()).isEqualTo(25);
        }
        assertThat(adapter.refreshedObservations(from, to, now.minusMinutes(30), 15, 30).stream()
                .anyMatch(row -> target.equals(row.targetId()) && plan.equals(row.planId()))).isEqualTo(matches);
        assertThat(adapter.airportProximity(from, to, 15).stream()
                .anyMatch(row -> target.equals(row.targetId()) && plan.equals(row.planId()))).isEqualTo(matches);
        assertThat(adapter.refreshedAirportProximity(from, to, now.minusMinutes(30), 0).stream()
                .anyMatch(row -> target.equals(row.targetId()) && plan.equals(row.planId()))).isEqualTo(matches);
    }

    @Test void duplicatePlanCannotReceiveRiskEvenWhenItsTimesAndRouteMatch() {
        String duplicate = id();
        jdbc.update("insert into flight_plan(plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,'PENDING',source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,0 from flight_plan where plan_id=?", duplicate, duplicate, plan);
        jdbc.update("insert into flight_plan_duplicate(duplicate_plan_id,canonical_plan_id,reason) values(?,?,'同源重复计划')", duplicate, plan);
        assertThat(observations().stream().filter(row -> target.equals(row.targetId())).map(SpaceObservation::planId)).contains(plan).doesNotContain(duplicate);
    }

    @Test void countsComeFromReferencedSourcesAndConflictingValuesRemainUnknown() {
        assertThat(observation().objectCount()).isEqualTo(25);
        String same = observation(source, now, 25), conflict = observation(source, now, 10);
        String primary = jdbc.queryForObject("select observation_id from track_point where point_id=?", String.class, point);
        jdbc.update("update track_point set contributing=CAST(? AS JSON) where point_id=?", contributors(primary, 1, same, 1), point);
        assertThat(observation().objectCount()).isEqualTo(25);
        jdbc.update("update track_point set contributing=CAST(? AS JSON) where point_id=?", contributors(primary, 1, conflict, 1), point);
        assertThat(observation().objectCount()).isNull();
        String otherMode = observation(source("replay"), now, 80);
        jdbc.update("update track_point set contributing=CAST(? AS JSON) where point_id=?", contributors(primary, 1, otherMode, 1), point);
        assertThat(observation().objectCount()).isEqualTo(25);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "-1,0", "0,1"})
    void zeroOrNegativeWeightCannotSupplyTheOnlyCount(int primaryWeight, int otherWeight) {
        String primary = jdbc.queryForObject("select observation_id from track_point where point_id=?", String.class, point);
        String other = observation(source, now, 10);
        jdbc.update("update track_point set contributing=CAST(? AS JSON) where point_id=?", contributors(primary, primaryWeight, other, otherWeight), point);
        assertThat(observation().objectCount()).isEqualTo(otherWeight > 0 ? 10 : null);
    }

    @ParameterizedTest
    @CsvSource({"0", "5", "37"})
    void aNewFusionFrameCannotProvideCountForAnEarlierSpatialSnapshot(int observedDeltaSeconds) {
        String oldPoint = point;
        String tenBirds = observation(source, now, 10);
        jdbc.update("update track_point set observation_id=? where point_id=?", tenBirds, point);
        SpaceObservation earlier = observation();
        assertThat(earlier.objectCount()).isEqualTo(10);
        assertThat(earlier.distanceToRouteM()).isLessThan(new BigDecimal("300"));
        OffsetDateTime nextObserved = now.plusSeconds(observedDeltaSeconds), nextReceived = nextObserved.plusSeconds(1);
        String twentyFiveBirds = observation(source, nextObserved, 25);
        point = id();
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,position_accuracy_m,height_agl_m,observation_id,point_kind,created_at)"
                + " values(?,?,2,?,?,ST_SetSRID(ST_MakePoint(118,37.004),4326),1,100,?,'MEAS',?)", point, track, ts(nextObserved), ts(nextReceived), twentyFiveBirds, ts(nextReceived));
        jdbc.update("update target_latest_state set location=ST_SetSRID(ST_MakePoint(118,37.004),4326),observed_at=?,received_at=?,updated_at=? where target_id=?",
                ts(nextObserved), ts(nextReceived), ts(nextReceived), target);
        SpaceRiskObservationEvidence reader = new SpaceRiskObservationEvidence(new NamedParameterJdbcTemplate(jdbc), json);
        SpaceObservation preserved = reader.enrich(List.of(earlier), 30).get(0);
        assertThat(preserved.objectCount()).isEqualTo(10);
        assertThat(preserved.distanceToRouteM()).isEqualByComparingTo(earlier.distanceToRouteM());
        assertThat(new C04DecisionTable().decide(new C04DecisionTable.Observation(C04DecisionTable.CorridorRelation.NEAR,
                C04DecisionTable.AltitudeBand.CLIMB, true, preserved.objectCount(), C04DecisionTable.Trend.UNKNOWN, "BIRD_FLOCK"), C04DecisionTableTest.params()).generate()).isFalse();
        // 若原锚点已不再是可信实测点，返回未知；不得改用最新的25只补齐。
        jdbc.update("update track_point set point_kind='PRED' where point_id=?", oldPoint);
        assertThat(reader.enrich(List.of(earlier), 30).get(0).objectCount()).isNull();
    }

    private static String contributors(String first, int firstWeight, String second, int secondWeight) {
        return "[{\"observation_id\":\"" + first + "\",\"weight\":" + firstWeight + "},{\"observation_id\":\"" + second + "\",\"weight\":" + secondWeight + "}]";
    }

    @ParameterizedTest
    @CsvSource({"25,true", "20,true", "19,false", "10,false"})
    void countGateAndConfirmedRuleMembershipReachTheStoredRisk(int count, boolean expectedRisk) {
        String counted = observation(source, now, count);
        jdbc.update("update track_point set observation_id=? where point_id=?", counted, point);
        // 使用实际发布成员关系，不能从规则集版本号拼接 rule_version_id。
        String formal = jdbc.queryForObject("select rule_set_version_id from rule_set_version where rule_set_version_id='space-risk-confirmed-20261008'", String.class);
        jdbc.update("update rule_set set active_version_id=? where rule_set_code='SPACE-RISK-DEMO'", formal);
        var run = evaluation.evaluate("C04", now.minusSeconds(1), now.plusSeconds(1), "MANUAL", null);
        assertThat(run.status()).as(run.message()).isEqualTo("SUCCESS");
        assertThat(riskCount()).isEqualTo(expectedRisk ? 1 : 0);
        if (expectedRisk) {
            assertThat(jdbc.queryForObject("select state_code from flight_risk where target_id=?", String.class, target)).isEqualTo("PENDING_VERIFICATION");
            assertThat(jdbc.queryForObject("select f.rule_version_id from space_risk_fact f join flight_risk r on r.risk_id=f.risk_id where r.target_id=?", String.class, target))
                    .isEqualTo("confirmed-20261008-C04");
        }
    }

    @ParameterizedTest
    @CsvSource({"29,37.01,RISING", "31,37.01,UNKNOWN", "29,37.001,FLAT", "29,37.0001,FALLING"})
    void trendUsesOnlyRecentMeasuredDistances(int minutesAgo, double priorLatitude, String expected) {
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,position_accuracy_m,point_kind,created_at)"
                + " values(?,?,0,?,?,ST_SetSRID(ST_MakePoint(118,?),4326),1,'MEAS',?)", id(), track, ts(now.minusMinutes(minutesAgo)), ts(now.minusMinutes(minutesAgo)), priorLatitude, ts(now));
        assertThat(observation().trend()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"1,3000", "7,5000"})
    void laterReceivedAccuracyCorrectionCannotChangeAnEarlierFrameTrend(int receiveDelaySeconds, int correctedAccuracy) {
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,position_accuracy_m,point_kind,created_at)"
                + " values(?,?,0,?,?,ST_SetSRID(ST_MakePoint(118,37.01),4326),1,'MEAS',?)", id(), track, ts(now.minusMinutes(5)), ts(now.minusMinutes(5)), ts(now));
        SpaceObservation earlier = observation();
        assertThat(earlier.trend()).isEqualTo("RISING");
        // 观测时刻和坐标完全相同，只是后来收到精度修正；不能改变原帧当时已知的趋势证据。
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,position_accuracy_m,point_kind,created_at)"
                + " values(?,?,2,?,?,ST_SetSRID(ST_MakePoint(118,37.001),4326),?,'MEAS',?)", id(), track, ts(now), ts(now.plusSeconds(receiveDelaySeconds)), correctedAccuracy, ts(now.plusSeconds(receiveDelaySeconds)));
        SpaceRiskObservationEvidence reader = new SpaceRiskObservationEvidence(new NamedParameterJdbcTemplate(jdbc), json);
        assertThat(reader.enrich(List.of(earlier), 30).get(0).trend()).isEqualTo("RISING");
    }

    @Test void twoConcurrentWindowsCannotDuplicateAnUnresolvedRisk() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var runs = executor.invokeAll(List.of(
                    () -> evaluation.evaluate("C04", now.minusSeconds(1), now.plusSeconds(1), "MANUAL", null),
                    () -> evaluation.evaluate("C04", now.minusSeconds(2), now.plusSeconds(2), "MANUAL", null)));
            for (var run : runs) assertThat(run.get()).isNotNull();
            assertThat(riskCount()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    private List<SpaceObservation> observations() {
        return spatial.observations(now.minusSeconds(1), now.plusSeconds(1), 15, 30).stream().filter(row -> target.equals(row.targetId())).toList();
    }
    private SpaceObservation observation() { return observations().stream().filter(row -> plan.equals(row.planId())).findFirst().orElseThrow(); }
    private long riskCount() { return jdbc.queryForObject("select count(*) from flight_risk where target_id=?", Long.class, target); }

    private String source(String mode) {
        String id = id();
        jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values(?,?,?,true,?,?,?,0)",
                id, id, "数量测试来源", mode, ts(now), ts(now));
        return id;
    }
    private String observation(String sourceId, OffsetDateTime at, int count) {
        String id = id();
        jdbc.update("insert into source_observation(observation_id,source_id,source_session_key,external_target_id,observed_at,received_at,location,position_accuracy_m,height_agl_m,quality,source_mode,owner_org_id,district_id,created_at)"
                + " select ?,source_id,?,?,?, ?,ST_SetSRID(ST_MakePoint(118,37.001),4326),1,100,CAST(? AS JSON),source_mode,?,?,? from integration_source where source_id=?",
                id, id(), target, ts(at), ts(at), "{\"object_count\":" + count + "}", org, district, ts(at), sourceId);
        return id;
    }
    private void sample(OffsetDateTime at, double latitude, int count) {
        String observation = observation(source, at, count);
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,position_accuracy_m,height_agl_m,observation_id,point_kind,created_at)"
                + " values(?,?,1,?,?,ST_SetSRID(ST_MakePoint(118,?),4326),1,100,?,'MEAS',?)", point, track, ts(at), ts(at), latitude, observation, ts(at));
        jdbc.update("insert into target_latest_state(target_id,location,height_agl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version)"
                + " select ?,location,height_agl_m,observed_at,received_at,CAST('[]' AS JSON),created_at,created_at,0 from track_point where point_id=?", target, point);
    }
    private void airport() {
        String airport = id();
        jdbc.update("insert into airport(airport_id,icao_code,name,reference_point,owner_org_id,district_id,created_at) values(?,?,?,ST_SetSRID(ST_MakePoint(118,37),4326),?,?,?)",
                airport, airport.substring(0, 8), "规则测试机场", org, district, ts(now));
        jdbc.update("insert into airport_procedure_route(route_id,airport_id,kind,name,centerline,protect_width_m,created_at) values(?,?,'APPROACH','进近航线',ST_GeomFromText('LINESTRING(117.9 37,118.1 37)',4326),100,?)", id(), airport, ts(now));
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static Timestamp ts(OffsetDateTime time) { return Timestamp.from(time.toInstant()); }
}
