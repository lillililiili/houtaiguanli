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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.SpaceObservation;
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
        when(spatial.observations(any(), any(), anyInt())).thenReturn(List.of(observation(flock, plan)));
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

    /** 第 n 轮定时评估：窗口按处理时间推进、与上一轮回叠 30 秒，观测下限为 30 分钟前。 */
    private RunRow scheduled(int tick) {
        OffsetDateTime to = now.plusMinutes(tick);
        OffsetDateTime from = tick == 0 ? to.minusMinutes(30) : to.minusSeconds(90);
        return evaluation.evaluateScheduled(from, to, to.minusMinutes(30));
    }

    private void refreshed(SpaceObservation... observations) {
        when(spatial.refreshedObservations(any(), any(), any())).thenReturn(List.of(observations));
    }

    /** 走廊内（10 m ≤ 半宽 50 m）、同基准 AGL 60 m 的鸟群：HIGH，必须生成。 */
    private SpaceObservation observation(String targetId, String planId) {
        return new SpaceObservation(targetId, "TGT-" + targetId, "BIRD_FLOCK", planId, routeVersionOf(planId),
                new BigDecimal("10.00"), new BigDecimal("50.00"), new BigDecimal("60.00"), "AGL", "AGL", null, "UNKNOWN",
                org, district, new BigDecimal("118.025"), new BigDecimal("37.025"), now.minusSeconds(20));
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
