package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段 7 演示"体量"夹具（决策 15-46）：只在 local（不含 test）且 app.dev-seed.enabled=true 时存在。
 * 目的：让飞行计划页的"本航线风险"和合法性研判页有足够多的记录可看。
 * 内容：十个额外回放场景的目标/状态/轨迹/计划（复用阶段七的机构、区域、来源与空域），以及挂在阶段七计划上的十条飞行风险事实。
 * 研判由 {@link RuleDemoVolumeReplayRunner} 产生。与契约十场景分开：不改 SCENARIOS，不影响回放回归与 test profile 的计数断言。
 * 只补缺行，不 UPDATE 已存在的记录。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(66)
@DependsOn("localStage7RuleEngineSeeder")
public class LocalStage7DemoVolumeSeeder implements ApplicationRunner {
    private static final String ORG = LocalStage7RuleEngineSeeder.ORG, DISTRICT = LocalStage7RuleEngineSeeder.DISTRICT;
    private static final String SOURCE_ID = LocalStage7RuleEngineSeeder.SOURCE_ID;
    private static final Instant T0 = LocalStage7RuleEngineSeeder.T0;
    /** 夜航组的评估时刻：T0 + 11h = 2026-09-05 21:00 Asia/Shanghai，落在 C02-5 的 [20, 6) 夜航窗内。 */
    public static final Instant T_NIGHT = T0.plusSeconds(11 * 3_600);

    /** 白天组（as_of = T0）：三条合法、一条偏航、一条超计划高度、一条穿越禁飞区、两条无计划。 */
    public static final List<String> DAY_SCENARIOS = List.of("vol-legal-1", "vol-legal-2", "vol-legal-3", "vol-deviation-1", "vol-plan-altitude-1",
            "vol-prohibited-1", "vol-no-plan-1", "vol-no-plan-2");
    /** 夜航组（as_of = T_NIGHT）：两条在计划窗内的夜间飞行。 */
    public static final List<String> NIGHT_SCENARIOS = List.of("vol-night-1", "vol-night-2");
    /** 只为状态覆盖而造的计划（不回放、无目标）：vol-status-executing / completed / cancelled / today。 */

    private final JdbcTemplate jdbc;

    public LocalStage7DemoVolumeSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String targetId(String scenario) { return "seed-vol-target-" + scenario; }
    public static String planId(String scenario) { return "seed-vol-plan-" + scenario; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant created = T0.minusSeconds(86_400);
        Instant dayStart = T0.minusSeconds(1_800), dayEnd = T0.plusSeconds(1_800);
        Instant nightStart = T_NIGHT.minusSeconds(1_800), nightEnd = T_NIGHT.plusSeconds(1_800);
        // 位置都放在阶段七十场景（118.20–118.25 / 37.20–37.25）与三块空域之外，走廊互不相交。
        target("vol-legal-1", 11, "S7-SN-VOL-L1", 118.260, 37.260, 50d, 40d, 0.90, T0);
        plan("vol-legal-1", 11, "S7-SN-VOL-L1", 118.260, 37.260, 10, 100, dayStart, dayEnd, created);
        target("vol-legal-2", 12, "S7-SN-VOL-L2", 118.270, 37.270, 60d, 50d, 0.92, T0);
        plan("vol-legal-2", 12, "S7-SN-VOL-L2", 118.270, 37.270, 10, 100, dayStart, dayEnd, created);
        target("vol-legal-3", 13, "S7-SN-VOL-L3", 118.280, 37.280, 45d, 35d, 0.88, T0);
        plan("vol-legal-3", 13, "S7-SN-VOL-L3", 118.280, 37.280, 10, 100, dayStart, dayEnd, created);
        // 偏航：目标北移约 133 m（> 半宽 50 + 容差 20）。
        target("vol-deviation-1", 14, "S7-SN-VOL-DEV", 118.260, 37.2912, 50d, 40d, 0.90, T0);
        plan("vol-deviation-1", 14, "S7-SN-VOL-DEV", 118.260, 37.290, 10, 100, dayStart, dayEnd, created);
        // 超计划高度：AMSL 140 > 计划上限 100。
        target("vol-plan-altitude-1", 15, "S7-SN-VOL-ALT", 118.270, 37.290, 140d, 130d, 0.90, T0);
        plan("vol-plan-altitude-1", 15, "S7-SN-VOL-ALT", 118.270, 37.290, 10, 100, dayStart, dayEnd, created);
        // 穿越禁飞区：位于阶段七 P1（118.296–118.304 / 37.296–37.304）内，计划航线也经过。
        target("vol-prohibited-1", 16, "S7-SN-VOL-P1", 118.300, 37.3005, 50d, 40d, 0.90, T0);
        plan("vol-prohibited-1", 16, "S7-SN-VOL-P1", 118.300, 37.3005, 10, 100, dayStart, dayEnd, created);
        // 无计划：sn 无任何计划、位置远离所有走廊。
        target("vol-no-plan-1", 17, "S7-SN-VOL-NP1", 118.280, 37.260, 50d, 40d, 0.90, T0);
        target("vol-no-plan-2", 18, "S7-SN-VOL-NP2", 118.290, 37.270, 70d, 60d, 0.85, T0);
        // 夜航：计划窗覆盖 21:00，其余条件合法。
        target("vol-night-1", 19, "S7-SN-VOL-N1", 118.265, 37.245, 50d, 40d, 0.90, T_NIGHT);
        plan("vol-night-1", 19, "S7-SN-VOL-N1", 118.265, 37.245, 10, 100, nightStart, nightEnd, created);
        target("vol-night-2", 20, "S7-SN-VOL-N2", 118.275, 37.255, 55d, 45d, 0.90, T_NIGHT);
        plan("vol-night-2", 20, "S7-SN-VOL-N2", 118.275, 37.255, 10, 100, nightStart, nightEnd, created);
        risks(dayStart, nightStart);
        planStatuses(created);
    }

    /** 计划状态覆盖（决策 15-56）：执行中 / 已完成 / 已取消 各一条，且"今日"有计划可数；只补缺行，日期以首次种入为准。 */
    private void planStatuses(Instant created) {
        Instant dayStart = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        plan("vol-status-executing", 21, "S7-SN-VOL-EXEC", 118.285, 37.285, 10, 120, Instant.now().minusSeconds(1_800), Instant.now().plusSeconds(1_800), created);
        plan("vol-status-completed", 22, "S7-SN-VOL-DONE", 118.295, 37.275, 10, 100, dayStart.minusSeconds(86_400 - 3_600), dayStart.minusSeconds(86_400 - 7_200), created);
        plan("vol-status-cancelled", 23, "S7-SN-VOL-CANC", 118.255, 37.295, 10, 100, dayStart.plusSeconds(86_400 + 3_600), dayStart.plusSeconds(86_400 + 7_200), created);
        plan("vol-status-today", 24, "S7-SN-VOL-TODAY", 118.245, 37.285, 10, 100, dayStart.plusSeconds(14 * 3_600), dayStart.plusSeconds(15 * 3_600), created);
        jdbc.update("update flight_plan set status_code='EXECUTING' where plan_id=? and status_code='PENDING' and version=0", planId("vol-status-executing"));
        jdbc.update("update flight_plan set status_code='COMPLETED' where plan_id=? and status_code='PENDING' and version=0", planId("vol-status-completed"));
        jdbc.update("update flight_plan set status_code='CANCELLED' where plan_id=? and status_code='PENDING' and version=0", planId("vol-status-cancelled"));
    }

    /** 挂在阶段七/体量计划上的风险事实，原因码只用前端字典已收录的值。 */
    private void risks(Instant day, Instant night) {
        risk("seed-vol-risk-01", "风险-0907-201", "seed-stage7-plan-legal", "seed-stage7-rv-legal", "FLIGHT_OPERATION", "LOW", "PENDING_VERIFICATION",
                "ROUTE_DEVIATION", "邻近走廊 80 m 处发现未报备目标，待核验", day.plusSeconds(600), null, null);
        risk("seed-vol-risk-02", "风险-0907-202", "seed-stage7-plan-deviation", "seed-stage7-rv-deviation", "FLIGHT_OPERATION", "HIGH", "PENDING_NOTIFICATION",
                "ROUTE_DEVIATION", "目标偏离中心线 133 m，超出走廊半宽与容差", day.plusSeconds(1_200), 50d, "AMSL");
        risk("seed-vol-risk-03", "风险-0907-203", "seed-stage7-plan-airspace-limit", "seed-stage7-rv-airspace-limit", "AIRSPACE", "CRITICAL", "NOTIFIED",
                "AIRSPACE_CONFLICT", "航线穿越限高空域 H1，实测 120 m 超过限高 60 m", day.plusSeconds(900), 120d, "AMSL");
        risk("seed-vol-risk-04", "风险-0907-204", "seed-stage7-plan-boundary", "seed-stage7-rv-boundary", "AIRSPACE", "MEDIUM", "EXCLUDED",
                "PROHIBITED_AIRSPACE_OVERLAP", "航线端点触及禁飞空域 P1 边界，核验后排除", day.plusSeconds(300), null, null);
        risk("seed-vol-risk-05", "风险-0907-205", "seed-stage7-plan-datum-mismatch", "seed-stage7-rv-datum-mismatch", "FLIGHT_OPERATION", "MEDIUM", "PENDING_VERIFICATION",
                "ALTITUDE_DATUM_OR_RANGE_UNKNOWN", "目标只有相对高度，无法与计划的海拔高度带比较", day.plusSeconds(1_500), null, null);
        risk("seed-vol-risk-06", "风险-0907-206", "seed-stage7-plan-degraded", "seed-stage7-rv-degraded", "FLIGHT_OPERATION", "LOW", "PENDING_VERIFICATION",
                "SOURCE_MISMATCH", "来源置信度 0.5，轨迹断续，身份待确认", day.plusSeconds(1_800), null, null);
        risk("seed-vol-risk-07", "风险-0907-207", "seed-stage7-plan-merge", "seed-stage7-rv-merge", "AIRSPACE", "HIGH", "PENDING_NOTIFICATION",
                "AIRSPACE_CONFLICT", "临时管制 T1 生效期间在管制区内飞行", day.plusSeconds(2_100), 50d, "AMSL");
        risk("seed-vol-risk-08", "风险-0907-208", planId("vol-legal-1"), "seed-vol-rv-vol-legal-1", "FLIGHT_OPERATION", "LOW", "EXCLUDED",
                "ROUTE_DEVIATION", "走廊边缘短暂偏出 20 m，核验后排除", day.plusSeconds(2_400), null, null);
        risk("seed-vol-risk-09", "风险-0907-209", planId("vol-prohibited-1"), "seed-vol-rv-vol-prohibited-1", "AIRSPACE", "CRITICAL", "PENDING_NOTIFICATION",
                "PROHIBITED_AIRSPACE_OVERLAP", "航线穿越禁飞空域 P1", day.plusSeconds(1_800), 50d, "AMSL");
        risk("seed-vol-risk-10", "风险-0907-210", planId("vol-night-1"), "seed-vol-rv-vol-night-1", "FLIGHT_OPERATION", "MEDIUM", "PENDING_VERIFICATION",
                "ROUTE_DEVIATION", "夜航时段偏离走廊 60 m", night.plusSeconds(1_200), null, null);
    }

    private void risk(String id, String sourceRisk, String plan, String route, String type, String severity, String state,
            String reason, String text, Instant at, Double altitude, String datum) {
        jdbc.update("INSERT INTO flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,"
                + "reason_code,reason_text,occurred_at,received_at,observed_altitude_m,observed_altitude_datum,height_relation,source_mode,"
                + "owner_org_id,district_id,created_at,updated_at,version) SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                + "'UNKNOWN','mock',?,?,?,?,0 WHERE NOT EXISTS (SELECT 1 FROM flight_risk WHERE risk_id=?)",
                id, SOURCE_ID, sourceRisk, plan, route, type, severity, state, reason, text, ts(at), ts(at.plusSeconds(5)), altitude, datum,
                ORG, DISTRICT, ts(at), ts(at), id);
    }

    private void target(String scenario, int seq, String sn, double lon, double lat, Double amsl, Double agl, double confidence, Instant observed) {
        int points = 5, gapSeconds = 5;
        String id = targetId(scenario), link = "seed-vol-link-" + scenario, track = "seed-vol-track-" + scenario;
        String no = String.format("MB-S7-%03d", seq);
        Instant first = observed.minusSeconds((long) (points - 1) * gapSeconds);
        jdbc.update("insert into target (target_id,target_no,object_type_code,subtype,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,'UAV','QUADCOPTER',?,?,?,'mock',?,?,?,?,0 where not exists (select 1 from target where target_id=?)",
                id, no, sn, ts(first), ts(observed), ORG, DISTRICT, ts(first), ts(observed), id);
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,protocol_version,created_at)"
                + " select ?,?,?,?,?,'1.0',? where not exists (select 1 from target_source_link where link_id=?)",
                link, id, SOURCE_ID, "vol-" + scenario, "ext-vol-" + scenario, ts(first), link);
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,"
                + "observed_at,received_at,unknown_fields,created_at,updated_at,version) select ?,CAST(? AS GEOMETRY),?,?,8.0,90.0,?,?,?,?,'[]',?,?,0"
                + " where not exists (select 1 from target_latest_state where target_id=?)",
                id, point(lon, lat), amsl, agl, confidence, confidence, ts(observed), ts(observed.plusSeconds(2)), ts(first), ts(observed), id);
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) select ?,?,?,?,?,? where not exists (select 1 from track where track_id=?)",
                track, id, link, "tr-vol-" + scenario, ts(first), ts(first), track);
        for (int i = 0; i < points; i++) {
            Instant seen = first.plusSeconds((long) i * gapSeconds);
            String pointId = "seed-vol-pt-" + scenario + "-" + i;
            jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at)"
                    + " select ?,?,?,?,?,CAST(? AS GEOMETRY),?,?,? where not exists (select 1 from track_point where point_id=?)",
                    pointId, track, i, ts(seen), ts(seen.plusSeconds(2)), point(lon, lat), amsl, agl, ts(seen), pointId);
        }
    }

    private void plan(String scenario, int seq, String sn, double lon, double lat, Integer min, Integer max, Instant start, Instant end, Instant at) {
        String route = "seed-vol-route-" + scenario, rv = "seed-vol-rv-" + scenario, plan = planId(scenario);
        String line = String.format(Locale.ROOT, "SRID=4326;LINESTRING (%.4f %.4f,%.4f %.4f)", lon - 0.01, lat, lon + 0.01, lat);
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,?,true,?,'mock',?,?,?,?,0 where not exists (select 1 from route where route_id=?)",
                route, String.format("HX-S7-%03d", seq), "演示航线（" + scenario + "）", SOURCE_ID, ORG, DISTRICT, ts(at), ts(at), route);
        jdbc.update("update route set name=? where route_id=? and name<>?", "演示航线（" + scenario + "）", route, "演示航线（" + scenario + "）");
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " select ?,?,1,CAST(? AS GEOMETRY),100,?,?,'AMSL',?,? where not exists (select 1 from route_version where route_version_id=?)",
                rv, route, line, min, max, ts(start), ts(at), rv);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,'PENDING',?,'mock',?,?,?,?,?,?,?,?,0 where not exists (select 1 from flight_plan where plan_id=?)",
                plan, String.format("JH-S7-%03d", seq), SOURCE_ID, sn, ts(start), ts(end), rv, ORG, DISTRICT, ts(at), ts(at), plan);
    }

    private static String point(double lon, double lat) { return String.format(Locale.ROOT, "SRID=4326;POINT(%.4f %.4f)", lon, lat); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
