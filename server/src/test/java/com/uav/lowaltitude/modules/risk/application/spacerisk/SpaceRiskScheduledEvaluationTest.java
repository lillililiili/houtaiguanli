package com.uav.lowaltitude.modules.risk.application.spacerisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.SpaceObservation;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.AirportProximity;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.RunRow;

/**
 * BUG-17：鸟群停在执行中计划的航线上，每一次（每一批）都要出风险。
 * H2 没有 PostGIS，空间事实由替身端口给出；入库、去重、保存点与运行记录走真实的服务、仓储和事务。
 * 不用测试级事务：评估本身是 REQUIRES_NEW，夹具必须先提交它才看得到。
 */
@SpringBootTest(properties = "app.rule-engine.allow-demo-active=true")
@ActiveProfiles("test")
class SpaceRiskScheduledEvaluationTest {
    @Autowired SpaceRiskEvaluationService evaluation;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SpaceRiskSpatialPort spatial;

    private OffsetDateTime now;
    private String suffix, org, district, plan, routeVersion;

    @BeforeEach
    void fixture() {
        now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        when(spatial.available()).thenReturn(true);
        jdbc.update("update rule_set set active_version_id='space-risk-demo-v1' where rule_set_code='SPACE-RISK-DEMO'");
        org = "c04-org-" + suffix;
        district = "c04-district-" + suffix;
        directory(org, district);
        plan = plan("main", org);
        routeVersion = routeVersionOf(plan);
    }

    @Test
    void aFlockThatStaysOnTheRouteKeepsOneRiskAndEveryNewAppearanceGetsItsOwn() {
        String flock = target("first");
        refreshed(observation(flock, plan));
        RunRow first = scheduled(0);
        assertThat(first.status()).as(first.message()).isEqualTo("SUCCESS");
        assertThat(first.risksCreated()).isEqualTo(1);

        // 下一轮窗口回叠、鸟群仍在：归到同一条未解除的风险，不再每分钟多造一条。
        RunRow second = scheduled(1);
        assertThat(second.risksCreated()).isZero();
        assertThat(second.risksDeduplicated()).isEqualTo(1);
        // 手动按观测窗口评估同一鸟群，也归到那一条。
        when(spatial.observations(any(), any(), anyInt(), anyInt())).thenReturn(List.of(observation(flock, plan)));
        RunRow manual = evaluation.evaluate("C04", now.minusMinutes(5), now.plusMinutes(5), "MANUAL", null);
        assertThat(manual.risksCreated()).isZero();
        assertThat(risks(flock)).hasSize(1);

        // 下一批：终止后重新出现的鸟群是新目标，必须有自己的风险。
        String nextBatch = target("second");
        refreshed(observation(nextBatch, plan));
        assertThat(scheduled(2).risksCreated()).isEqualTo(1);
        assertThat(risks(nextBatch)).hasSize(1);

        // 第一群离开（已保存解除依据）后又飞回航线：这是新的一次，再出一条风险。
        clear(risks(flock).get(0), flock);
        refreshed(observation(flock, plan));
        assertThat(scheduled(3).risksCreated()).isEqualTo(1);
        assertThat(risks(flock)).hasSize(2);
    }

    @Test
    void oneRejectedPairNeitherBlocksTheOthersNorLosesTheRunRecord() {
        // 航线登记在别的单位名下的计划：入库校验拒收（"计划不存在"），以前这会把整轮事务标成只能回滚，
        // 提交时连同其他目标的风险和运行记录一起消失，之后每一轮都卡在它身上。
        String foreignRoutePlan = plan("foreign", "c04-other-org-" + suffix);
        String stuck = target("stuck");
        String flock = target("ok");
        refreshed(observation(stuck, foreignRoutePlan), observation(flock, plan));

        RunRow run = scheduled(0);
        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.risksCreated()).isEqualTo(1);
        assertThat(run.message()).startsWith(SpaceRiskEvaluationService.MESSAGE_PARTIAL_FAILURE + " 1: " + foreignRoutePlan + "/" + stuck);
        assertThat(jdbc.queryForObject("select status from rule_evaluation_run where run_id=?", String.class, run.runId())).isEqualTo("SUCCESS");
        assertThat(risks(flock)).hasSize(1);
        assertThat(risks(stuck)).isEmpty();
    }

    @Test
    void whenEveryPairIsRejectedTheRunIsStillRecordedAsFailed() {
        String foreignRoutePlan = plan("foreign", "c04-other-org-" + suffix);
        String stuck = target("stuck");
        refreshed(observation(stuck, foreignRoutePlan));

        RunRow run = scheduled(0);
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.risksCreated()).isZero();
        assertThat(jdbc.queryForObject("select status from rule_evaluation_run where run_id=?", String.class, run.runId())).isEqualTo("FAILED");
        assertThat(risks(stuck)).isEmpty();
    }

    @Test
    void airportRiskIsMediumPendingVerificationAndDeduplicatesAcrossScheduledAndManualWindows() {
        String balloon = target("airport");
        AirportProximity fact = airport(balloon, plan, "500", "201");
        when(spatial.refreshedAirportProximity(any(), any(), any(), anyInt())).thenReturn(List.of(fact));
        RunRow first = evaluation.evaluateScheduledAirport(now.minusMinutes(1), now, now.minusMinutes(30));
        assertThat(first.status()).as(first.message()).isEqualTo("SUCCESS");
        assertThat(first.risksCreated()).isEqualTo(1);
        String risk = risks(balloon).get(0);
        assertThat(jdbc.queryForObject("select severity from flight_risk where risk_id=?", String.class, risk)).isEqualTo("MEDIUM");
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, risk)).isEqualTo("PENDING_VERIFICATION");
        assertThat(evaluation.evaluateScheduledAirport(now, now.plusMinutes(1), now.minusMinutes(29)).risksDeduplicated()).isEqualTo(1);
        when(spatial.airportProximity(any(), any(), anyInt())).thenReturn(List.of(fact));
        assertThat(evaluation.evaluate("C05", now.minusMinutes(2), now.plusMinutes(2), "MANUAL", null).risksDeduplicated()).isEqualTo(1);
        assertThat(risks(balloon)).hasSize(1);
    }

    @Test
    void airportDistancesHaveInclusiveBoundariesAndNoPlanNeverCreatesRisk() {
        String protectedBoundary = target("protected"), outside = target("outside"), noPlan = target("noplan");
        when(spatial.refreshedAirportProximity(any(), any(), any(), anyInt())).thenReturn(List.of(
                airport(protectedBoundary, plan, "501", "200"), airport(outside, plan, "500.01", "200.01"),
                airport(noPlan, null, "100", "100")));
        RunRow run = evaluation.evaluateScheduledAirport(now.minusMinutes(1), now, now.minusMinutes(30));
        assertThat(run.risksCreated()).as(run.message()).isEqualTo(1);
        assertThat(run.message()).isEqualTo(SpaceRiskEvaluationService.MESSAGE_PLAN_REQUIRED);
        assertThat(risks(protectedBoundary)).hasSize(1);
        assertThat(risks(outside)).isEmpty();
        assertThat(risks(noPlan)).isEmpty();
    }

    private AirportProximity airport(String target, String planId, String procedureDistance, String protectedDistance) {
        return new AirportProximity(target, "BALLOON", "airport-" + suffix, "测试机场", planId,
                planId == null ? null : routeVersionOf(planId), new BigDecimal(procedureDistance), new BigDecimal(protectedDistance),
                new BigDecimal("80"), "AGL", null, now.minusSeconds(20));
    }

    /**
     * P03：鸟群被判的每一次都记进它那条风险的评估历史，按事实分段（离航线按 50 米一档），飞远了“不构成风险”也记；
     * 风险本身的等级不跟着改。设备停报时读到的同一份观测不重复计。
     */
    @Test
    void everyJudgementOfAFlockIsKeptAsSegmentsOfItsRiskWithoutChangingTheRiskLevel() {
        String flock = target("history");
        // 第 0 轮：离中心线 120 米（走廊半宽 50 米外、300 米内），25 只达到数量阈值上调为高风险。
        refreshed(observation(flock, plan, "120.00", 0));
        assertThat(scheduled(0).risksCreated()).isEqualTo(1);
        String risk = risks(flock).get(0);
        // 第 1 轮：130 米，还在 100–150 米这一档 → 并进第一段。
        refreshed(observation(flock, plan, "130.00", 60));
        assertThat(scheduled(1).risksDeduplicated()).isEqualTo(1);
        // 第 2 轮：观测没更新（还是上一份）→ 不算一次新的评估。
        scheduled(2);
        // 第 3 轮：进了走廊（20 米、离地 60 米），数量阈值将当时高风险上调为紧急，另起一段。
        refreshed(observation(flock, plan, "20.00", 120));
        scheduled(3);
        // 第 4 轮：飞到 800 米 → 不构成风险，也记一段，看得出什么时候不再构成风险。
        refreshed(observation(flock, plan, "800.00", 180));
        RunRow away = scheduled(4);
        assertThat(away.message()).isNull();
        assertThat(away.risksCreated()).isZero();

        List<Map<String, Object>> segments = segments(risk);
        assertThat(segments).extracting(row -> row.get("segment_no")).containsExactly(1, 2, 3);
        assertThat(segments.get(0)).containsEntry("evaluation_count", 2).containsEntry("distance_band_m", 100)
                .containsEntry("corridor_relation", "NEAR").containsEntry("altitude_band", "CLIMB")
                .containsEntry("risk_present", true).containsEntry("severity", "HIGH").containsEntry("from_detection", true);
        assertThat(((BigDecimal) segments.get(0).get("min_distance_m"))).isEqualByComparingTo("120");
        assertThat(((BigDecimal) segments.get(0).get("max_distance_m"))).isEqualByComparingTo("130");
        assertThat(segments.get(1)).containsEntry("evaluation_count", 1).containsEntry("distance_band_m", 0)
                .containsEntry("corridor_relation", "INSIDE").containsEntry("risk_present", true).containsEntry("severity", "CRITICAL")
                .containsEntry("from_detection", false);
        assertThat(segments.get(2)).containsEntry("evaluation_count", 1).containsEntry("distance_band_m", 800)
                .containsEntry("corridor_relation", "OUTSIDE").containsEntry("risk_present", false).containsEntry("severity", null);
        assertThat(jdbc.queryForObject("select severity from flight_risk where risk_id=?", String.class, risk))
                .as("评估历史只记事实，不自动改风险等级").isEqualTo("HIGH");

        // 这一段已满 5 分钟：事实没变也另起一段，长时间不变时每 5 分钟至少一条。
        jdbc.update("update space_risk_evaluation_segment set first_evaluated_at=? where risk_id=? and segment_no=3",
                ts(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(6)), risk);
        refreshed(observation(flock, plan, "810.00", 240));
        scheduled(5);
        assertThat(segments(risk)).extracting(row -> row.get("segment_no")).containsExactly(1, 2, 3, 4);

        // 已有解除依据（飞离）的风险不再记：之后同一鸟群再被评估，归不到这条风险上。
        clear(risk, flock);
        refreshed(observation(flock, plan, "820.00", 300));
        scheduled(6);
        assertThat(segments(risk)).hasSize(4);
    }

    @Test
    void aFlockWithoutAnOpenRiskLeavesNoHistory() {
        String far = target("far");
        refreshed(observation(far, plan, "800.00", 0));
        RunRow run = scheduled(0);
        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.risksCreated()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from space_risk_evaluation_segment s join flight_risk r on r.risk_id=s.risk_id"
                + " where r.target_id=?", Long.class, far)).isZero();
    }

    /** 第 n 轮定时评估：窗口按处理时间推进、与上一轮回叠 30 秒，观测下限为 30 分钟前。 */
    private RunRow scheduled(int tick) {
        OffsetDateTime to = now.plusMinutes(tick);
        OffsetDateTime from = tick == 0 ? to.minusMinutes(30) : to.minusSeconds(90);
        return evaluation.evaluateScheduled(from, to, to.minusMinutes(30));
    }

    private void refreshed(SpaceObservation... observations) {
        when(spatial.refreshedObservations(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(observations));
    }

    /** 走廊内（10 m ≤ 半宽 50 m）、同基准 AGL 60 m 的鸟群：HIGH，必须生成。 */
    private SpaceObservation observation(String targetId, String planId) {
        return new SpaceObservation(targetId, "TGT-" + targetId, "BIRD_FLOCK", planId, routeVersionOf(planId),
                new BigDecimal("10.00"), new BigDecimal("50.00"), new BigDecimal("60.00"), "AGL", "AGL", 25, "UNKNOWN",
                org, district, new BigDecimal("118.025"), new BigDecimal("37.025"), now.minusSeconds(20));
    }

    /** 同一航线上离中心线 distance 米、离地 60 米的鸟群，观测时刻比首轮晚 observedAfterSeconds 秒。 */
    private SpaceObservation observation(String targetId, String planId, String distance, int observedAfterSeconds) {
        return new SpaceObservation(targetId, "TGT-" + targetId, "BIRD_FLOCK", planId, routeVersionOf(planId),
                new BigDecimal(distance), new BigDecimal("50.00"), new BigDecimal("60.00"), "AGL", "AGL", 25, "UNKNOWN",
                org, district, new BigDecimal("118.025"), new BigDecimal("37.025"), now.minusSeconds(20).plusSeconds(observedAfterSeconds));
    }

    private List<Map<String, Object>> segments(String riskId) {
        return jdbc.queryForList("select segment_no,evaluation_count,distance_band_m,min_distance_m,max_distance_m,corridor_relation,"
                + "altitude_band,risk_present,severity,from_detection from space_risk_evaluation_segment where risk_id=? order by segment_no", riskId);
    }

    private List<String> risks(String targetId) {
        return jdbc.queryForList("select risk_id from flight_risk where target_id=? and risk_type='SPACE_OBJECT' order by received_at,risk_id",
                String.class, targetId);
    }

    private void directory(String orgId, String districtId) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, orgId, orgId);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                districtId, districtId, districtId);
    }

    /** 执行中计划；routeOwner 与计划单位不同时，航线根记录挂在别的单位下。 */
    private String plan(String name, String routeOwner) {
        String route = "c04-route-" + name + "-" + suffix, version = "c04-rv-" + name + "-" + suffix, id = "c04-plan-" + name + "-" + suffix;
        if (!routeOwner.equals(org)) directory(routeOwner, "c04-other-district-" + suffix);
        String routeDistrict = routeOwner.equals(org) ? district : "c04-other-district-" + suffix;
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,?,?,0)",
                route, "R-" + name + "-" + suffix, "C04 测试航线", routeOwner, routeDistrict, ts(now), ts(now));
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " values (?,?,1,CAST(? AS GEOMETRY),100,10,300,'AGL',?,?)", version, route, "SRID=4326;LINESTRING (118.02 37.02,118.03 37.03)", ts(now), ts(now));
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'EXECUTING','mock',?,?,?,?,?,?,?,0)", id, "P-" + name + "-" + suffix, ts(now.minusHours(1)), ts(now.plusHours(2)),
                version, org, district, ts(now), ts(now));
        return id;
    }

    private String routeVersionOf(String planId) {
        return jdbc.queryForObject("select route_version_id from flight_plan where plan_id=?", String.class, planId);
    }

    private String target(String name) {
        String id = "c04-target-" + name + "-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,subtype,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'BIRD','BIRD_FLOCK',?,?,'mock',?,?,?,?,0)", id, "TGT-" + name + "-" + suffix, ts(now), ts(now), org, district, ts(now), ts(now));
        return id;
    }

    /** 已保存的解除依据：新的有效实测位置在风险边界外（距离 - 精度 > 边界）。 */
    private void clear(String riskId, String targetId) {
        String track = UUID.randomUUID().toString(), point = UUID.randomUUID().toString();
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,layer,started_at,created_at) values (?,?,null,?,'FUSED',?,?)",
                track, targetId, track, ts(now), ts(now));
        jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,position_accuracy_m)"
                + " values (?,?,1,?,?,CAST(? AS GEOMETRY),?,'MEAS',5)", point, track, ts(now), ts(now), "SRID=4326;POINT (118.05 37.01)", ts(now));
        jdbc.update("insert into risk_clearance_evidence (risk_id,target_id,route_version_id,point_id,rule_set_version_id,freshness_rule_set_version_id,"
                + "source_mode,observed_at,received_at,recorded_at,distance_m,accuracy_m,boundary_m,freshness_seconds)"
                + " values (?,?,?,?,'space-risk-demo-v1','space-risk-demo-v1','mock',?,?,?,1000,5,300,30)",
                riskId, targetId, routeVersion, point, ts(now), ts(now), ts(now));
    }

    private static Timestamp ts(OffsetDateTime value) { return Timestamp.from(value.toInstant()); }
}
