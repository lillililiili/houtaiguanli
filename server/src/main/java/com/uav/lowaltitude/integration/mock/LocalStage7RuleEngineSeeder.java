package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.airspace.domain.AirspaceKind;

/**
 * 阶段 7 规则引擎演示夹具（双门禁：!production 且 local/test，且 app.dev-seed.enabled=true）。
 * 内容：规则集 LEGALITY-DEMO v1（PUBLISHED，契约 DEMO 参数目录全量，首次种入时置为 ACTIVE）与 v2（PUBLISHED 未激活，仅 C02-3.tolerance_m=50）、
 * 规则定义 C01/C02-1…C02-8/C03/C06、演示机构/区域、空域 P1/H1/T1，以及契约十个回放场景的目标/状态/轨迹/计划事实。
 * 只写事实不写研判：研判、复核与告警由 RuleReplayRunner/引擎产生；重启只补缺行，不重置已激活版本、复核或告警。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(65)
public class LocalStage7RuleEngineSeeder implements ApplicationRunner {
    public static final String RULE_SET_CODE = "LEGALITY-DEMO";
    public static final String RULE_SET_ID = "seed-stage7-ruleset";
    public static final String VERSION_1 = "seed-stage7-rsv-1", VERSION_2 = "seed-stage7-rsv-2";
    public static final String ORG = "seed-stage7-org", DISTRICT = "seed-stage7-district";
    public static final String OTHER_ORG = "seed-stage7-other-org", OTHER_DISTRICT = "seed-stage7-other-district";
    public static final String SOURCE_ID = "seed-stage7-source";
    /** 场景基准时刻：2026-09-05 10:00 Asia/Shanghai（非夜航窗口）。回放以 observed_at 为 as_of，结论可重现。 */
    public static final Instant T0 = Instant.parse("2026-09-05T02:00:00Z");
    public static final List<String> SCENARIOS = List.of("legal", "deviation", "airspace-limit", "plan-altitude", "boundary", "no-plan", "degraded",
            "datum-mismatch", "merge", "cross-scope");
    private static final String[] RULE_CODES = {RuleCodes.C01, RuleCodes.C02_1, RuleCodes.C02_2, RuleCodes.C02_3, RuleCodes.C02_4, RuleCodes.C02_5,
            RuleCodes.C02_6, RuleCodes.C02_7, RuleCodes.C02_8, RuleCodes.C03, RuleCodes.C06};
    /** C06 在决策之后执行，优先级排在 C03 之后（RuleCodes 未定义，这里只表示顺序，不是阈值）。 */
    private static final int PRIORITY_C06 = RuleCodes.PRIORITY_C03 + 100;
    private static final int[] PRIORITIES = {RuleCodes.PRIORITY_C01, RuleCodes.PRIORITY_C02_1, RuleCodes.PRIORITY_C02_2, RuleCodes.PRIORITY_C02_3,
            RuleCodes.PRIORITY_C02_4, RuleCodes.PRIORITY_C02_5, RuleCodes.PRIORITY_C02_6, RuleCodes.PRIORITY_C02_7, RuleCodes.PRIORITY_C02_8,
            RuleCodes.PRIORITY_C03, PRIORITY_C06};
    /**
     * 契约 DEMO 参数目录（LEGALITY-DEMO v1）；v2 只改 C02-3.tolerance_m。
     * 阶段 10（决策 10-2）：C02-2.kinds 只写规范值 ALTITUDE_LIMIT、C02-8.kinds 只写 TEMPORARY_CONTROL——迁移 074 起 airspace_version.kind_code 的 CHECK 只留
     * AirspaceKind 的五值，HEIGHT_LIMIT / TEMPORARY 已不可能出现在库里，参数里再列它们只是让读者以为它们仍是合法种类。
     * 注意 param() 只补缺行：已发布版本的参数在 PostgreSQL 上不可改，已有库里的 C02-2.kinds / C02-8.kinds 仍是旧串（含规范值，判定不变）。
     */
    private static final String[][] DEMO_PARAMS = {
            {"C01", "time_window_min", "10", "INTEGER", "min"}, {"C01", "corridor_tolerance_m", "100", "NUMBER", "m"},
            {"C02-1", "kinds", "PROHIBITED,RESTRICTED", "LIST", null}, {"C02-2", "kinds", "ALTITUDE_LIMIT", "LIST", null},
            {"C02-3", "tolerance_m", "20", "NUMBER", "m"}, {"C02-4", "grace_min", "10", "INTEGER", "min"},
            {"C02-5", "timezone", "Asia/Shanghai", "STRING", null}, {"C02-5", "night_from", "20", "INTEGER", "h"}, {"C02-5", "night_to", "6", "INTEGER", "h"},
            {"C02-6", "vlos_m", "500", "NUMBER", "m"}, {"C02-8", "kinds", "TEMPORARY_CONTROL", "LIST", null},
            {"C03", "fresh_seconds", "120", "INTEGER", "s"}, {"C03", "track_points", "10", "INTEGER", null}, {"C03", "conf_min", "0.75", "NUMBER", null},
            {"C03", "min_points", "3", "INTEGER", null}, {"C03", "gap_seconds", "30", "INTEGER", "s"}, {"C03", "no_plan_status", "ILLEGAL", "STRING", null},
            {"C03", "ignore_undetermined_rules", "C02-6", "LIST", null},
            {"C03", "w.violation", "0.40", "NUMBER", null}, {"C03", "w.plan_match", "0.25", "NUMBER", null}, {"C03", "w.airspace", "0.15", "NUMBER", null},
            {"C03", "w.track", "0.10", "NUMBER", null}, {"C03", "w.confidence", "0.10", "NUMBER", null},
            {"C03", "severity.INSIDE_RESTRICTED_AIRSPACE", "1.0", "NUMBER", null}, {"C03", "severity.AIRSPACE_ALTITUDE_EXCEEDED", "0.9", "NUMBER", null},
            {"C03", "severity.TEMPORARY_RESTRICTION_ACTIVE", "0.9", "NUMBER", null}, {"C03", "severity.NO_AUTHORIZATION", "0.8", "NUMBER", null},
            {"C03", "severity.ROUTE_DEVIATION", "0.6", "NUMBER", null}, {"C03", "severity.PLAN_ALTITUDE_EXCEEDED", "0.5", "NUMBER", null},
            {"C03", "severity.TIME_WINDOW_OVERRUN", "0.4", "NUMBER", null}, {"C03", "severity.NIGHT_FLIGHT", "0.3", "NUMBER", null},
            {"C03", "grade.high", "67", "NUMBER", null}, {"C03", "grade.medium", "34", "NUMBER", null},
            {"C06", "dedup_window_min", "5", "INTEGER", "min"}, {"C06", "upgrade_window_min", "10", "INTEGER", "min"}, {"C06", "auto_close_min", "15", "INTEGER", "min"},
            {"C06", "severity_by_grade", "HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW", "LIST", null}};
    private static final String V2_C02_3_TOLERANCE = "50";

    private final JdbcTemplate jdbc;

    public LocalStage7RuleEngineSeeder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String targetId(String scenario) { return "seed-stage7-target-" + scenario; }
    public static String planId(String scenario) { return "seed-stage7-plan-" + scenario; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant created = T0.minusSeconds(86_400);
        directories(created);
        ruleSet(created);
        airspaces(created);
        scenarios(created);
    }

    private void directories(Instant at) {
        org(ORG, "SEED-STAGE7", "合法性演示机构", at);
        district(DISTRICT, "SEED-STAGE7", "合法性演示区域", at);
        org(OTHER_ORG, "SEED-STAGE7-OTHER", "跨范围演示机构", at);
        district(OTHER_DISTRICT, "SEED-STAGE7-OTHER", "跨范围演示区域", at);
        jdbc.update("insert into integration_source (source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,created_at,updated_at,version)"
                + " select ?,'STAGE7-RULE-MOCK','合法性研判模拟源','RULE_MOCK','1.0',true,'mock',?,?,0 where not exists (select 1 from integration_source where source_id=?)",
                SOURCE_ID, ts(at), ts(at), SOURCE_ID);
        jdbc.update("update integration_source set name='合法性研判模拟源' where source_id=? and name<>'合法性研判模拟源'", SOURCE_ID);
    }

    private void ruleSet(Instant at) {
        for (int i = 0; i < RULE_CODES.length; i++) {
            String id = "seed-stage7-rule-" + RULE_CODES[i];
            jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,source_snapshot,created_at)"
                    + " select ?,?,1,'ACTIVE',?,'mock',CAST('{}' AS JSON),? where not exists (select 1 from rule_version where rule_version_id=?)",
                    id, RULE_CODES[i], ts(at), ts(at), id);
        }
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,active_version_id,shadow_version_id,previous_active_version_id,version,created_at,updated_at)"
                + " select ?,?,'合法性研判演示规则集',null,null,null,0,?,? where not exists (select 1 from rule_set where rule_set_id=?)",
                RULE_SET_ID, RULE_SET_CODE, ts(at), ts(at), RULE_SET_ID);
        version(VERSION_1, 1, "演示参数，尚未经业务方确认", at);
        version(VERSION_2, 2, "v2：C02-3 航线偏离容差放宽到 50 m（未激活，供影子/激活演示）", at);
        for (int i = 0; i < RULE_CODES.length; i++) {
            member(VERSION_1, "seed-stage7-rule-" + RULE_CODES[i], PRIORITIES[i]);
            member(VERSION_2, "seed-stage7-rule-" + RULE_CODES[i], PRIORITIES[i]);
        }
        for (String[] p : DEMO_PARAMS) {
            param(VERSION_1, p[0], p[1], p[2], p[3], p[4]);
            boolean tolerance = RuleCodes.C02_3.equals(p[0]) && "tolerance_m".equals(p[1]);
            param(VERSION_2, p[0], p[1], tolerance ? V2_C02_3_TOLERANCE : p[2], p[3], p[4]);
        }
        // 只在首次种入时激活 v1：运维通过接口激活/回滚后的选择在重启后必须保留。
        jdbc.update("update rule_set set active_version_id=?,updated_at=? where rule_set_id=? and active_version_id is null and previous_active_version_id is null",
                VERSION_1, ts(at), RULE_SET_ID);
    }

    private void version(String id, int no, String description, Instant at) {
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,valid_to,description,source_mode,created_at,published_at)"
                + " select ?,?,?,'PUBLISHED','DEMO',?,null,?,'mock',?,? where not exists (select 1 from rule_set_version where rule_set_version_id=?)",
                id, RULE_SET_ID, no, ts(at), description, ts(at), ts(at), id);
    }

    private void member(String versionId, String ruleVersionId, int priority) {
        jdbc.update("insert into rule_set_member (rule_set_version_id,rule_version_id,priority,enabled) select ?,?,?,true"
                + " where not exists (select 1 from rule_set_member where rule_set_version_id=? and rule_version_id=?)", versionId, ruleVersionId, priority, versionId, ruleVersionId);
    }

    private void param(String versionId, String ruleCode, String key, String value, String type, String unit) {
        // 已发布版本的参数在 PostgreSQL 上不可改：这里只补缺行，绝不 UPDATE 已存在的参数。
        jdbc.update("insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)"
                + " select ?,?,?,?,?,?,?,'DEMO','演示参数，尚未经业务方确认' where not exists (select 1 from rule_param where rule_set_version_id=? and rule_code=? and param_key=?)",
                stableId(versionId + ":" + ruleCode + ":" + key), versionId, ruleCode, key, value, type, unit, versionId, ruleCode, key);
    }

    /**
     * P1 禁止（无高度带）、H1 限高（AMSL 0–60）、T1 临时管制（T0±1h）；各自一个约 900 m 见方的边界。
     * kind_code 一律用 AirspaceKind 的规范值（决策 10-2）：种子是迁移 074 之后唯一还可能写出 HEIGHT_LIMIT/TEMPORARY 的地方，
     * 继续写旧值会在全新库启动时直接撞上 ck_stage9_airspace_kind_code。
     */
    private void airspaces(Instant at) {
        airspace("p1", "KY-S7-P1", "演示禁飞空域 P1", AirspaceKind.PROHIBITED, square(118.300, 37.300), null, null, null, T0.minusSeconds(86_400), null, at);
        airspace("h1", "KY-S7-H1", "演示限高空域 H1", AirspaceKind.ALTITUDE_LIMIT, square(118.400, 37.400), 0, 60, "AMSL", T0.minusSeconds(86_400), null, at);
        airspace("t1", "KY-S7-T1", "演示临时管制 T1", AirspaceKind.TEMPORARY_CONTROL, square(118.500, 37.500), null, null, null, T0.minusSeconds(3_600), T0.plusSeconds(3_600), at);
    }

    private void airspace(String suffix, String no, String name, String kind, String boundary, Integer min, Integer max, String datum, Instant from, Instant to, Instant at) {
        String id = "seed-stage7-airspace-" + suffix, version = "seed-stage7-av-" + suffix;
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,?,?,'mock',?,?,?,?,0 where not exists (select 1 from airspace where airspace_id=?)", id, no, name, SOURCE_ID, ORG, DISTRICT, ts(at), ts(at), id);
        jdbc.update("update airspace set name=? where airspace_id=? and name<>?", name, id, name);
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,created_at)"
                + " select ?,?,1,?,CAST(? AS GEOMETRY),?,?,?,?,?,? where not exists (select 1 from airspace_version where airspace_version_id=?)",
                version, id, kind, boundary, min, max, datum, ts(from), to == null ? null : ts(to), ts(at), version);
    }

    /**
     * 十个场景。走廊宽 100 m（半宽 50）+ C01/C02-3 容差 20 m；航线为经过目标的东西向短线，偏航场景把目标北移约 133 m。
     * H2 只保存几何，空间事实由测试桩按目标 ID 给出；PostGIS 上由几何本身推出同样的事实。
     */
    private void scenarios(Instant at) {
        Instant start = T0.minusSeconds(1_800), end = T0.plusSeconds(1_800);
        // legal：在走廊内、时间窗内、sn 一致、远离空域。
        target("legal", 1, ORG, DISTRICT, "S7-SN-LEGAL", 118.200, 37.200, 50d, 40d, 0.90, T0, 5, 5);
        plan("legal", 1, ORG, DISTRICT, "S7-SN-LEGAL", 118.200, 37.200, 10, 100, "AMSL", start, end, at);
        // deviation：同一计划，目标偏离中心线约 133 m（> 半宽 50 + 容差 20）。
        target("deviation", 2, ORG, DISTRICT, "S7-SN-DEV", 118.210, 37.2112, 50d, 40d, 0.90, T0, 5, 5);
        plan("deviation", 2, ORG, DISTRICT, "S7-SN-DEV", 118.210, 37.210, 10, 100, "AMSL", start, end, at);
        // airspace-limit：位于 H1（AMSL 0–60）内，高度 AMSL 120；计划高度带 10–150 不越界。
        target("airspace-limit", 3, ORG, DISTRICT, "S7-SN-AIR", 118.400, 37.400, 120d, 110d, 0.90, T0, 5, 5);
        plan("airspace-limit", 3, ORG, DISTRICT, "S7-SN-AIR", 118.400, 37.400, 10, 150, "AMSL", start, end, at);
        // plan-altitude：远离空域，高度 AMSL 130 超过计划上限 100。
        target("plan-altitude", 4, ORG, DISTRICT, "S7-SN-ALT", 118.220, 37.220, 130d, 120d, 0.90, T0, 5, 5);
        plan("plan-altitude", 4, ORG, DISTRICT, "S7-SN-ALT", 118.220, 37.220, 10, 100, "AMSL", start, end, at);
        // boundary：恰在 P1 的西南顶点上（ST_Touches），航线经过该点。
        target("boundary", 5, ORG, DISTRICT, "S7-SN-BND", 118.296, 37.296, 50d, 40d, 0.90, T0, 5, 5);
        plan("boundary", 5, ORG, DISTRICT, "S7-SN-BND", 118.296, 37.296, 10, 100, "AMSL", start, end, at);
        // no-plan：观测时刻在所有计划时间窗之外且 sn 无计划 → 无候选。
        target("no-plan", 6, ORG, DISTRICT, "S7-SN-NOPLAN", 118.230, 37.230, 50d, 40d, 0.90, T0.plusSeconds(7_200), 5, 5);
        // degraded：置信度 0.5 < 0.75，三点间隔 60 s > 30 s。
        target("degraded", 7, ORG, DISTRICT, "S7-SN-DEG", 118.240, 37.240, 50d, 40d, 0.50, T0, 3, 60);
        plan("degraded", 7, ORG, DISTRICT, "S7-SN-DEG", 118.240, 37.240, 10, 100, "AMSL", start, end, at);
        // datum-mismatch：位于 H1（AMSL 带）内但只有 AGL 高度；计划高度带 AMSL。
        target("datum-mismatch", 8, ORG, DISTRICT, "S7-SN-DATUM", 118.401, 37.401, null, 40d, 0.90, T0, 5, 5);
        plan("datum-mismatch", 8, ORG, DISTRICT, "S7-SN-DATUM", 118.401, 37.401, 10, 100, "AMSL", start, end, at);
        // merge：位于 T1（临时管制，生效窗内）→ ILLEGAL；回放对同一目标评估两次，验证只建一条告警。
        target("merge", 9, ORG, DISTRICT, "S7-SN-MERGE", 118.500, 37.500, 50d, 40d, 0.90, T0, 5, 5);
        plan("merge", 9, ORG, DISTRICT, "S7-SN-MERGE", 118.500, 37.500, 10, 100, "AMSL", start, end, at);
        // cross-scope：另一元组、无计划 → ILLEGAL/NO_AUTHORIZATION，默认范围读不到。
        target("cross-scope", 10, OTHER_ORG, OTHER_DISTRICT, "S7-SN-CROSS", 118.250, 37.250, 50d, 40d, 0.90, T0, 5, 5);
    }

    private void target(String scenario, int seq, String org, String district, String sn, double lon, double lat, Double amsl, Double agl, double confidence,
            Instant observed, int points, int gapSeconds) {
        String id = targetId(scenario), link = "seed-stage7-link-" + scenario, track = "seed-stage7-track-" + scenario;
        String no = String.format("MB-S7-%03d", seq);
        Instant first = observed.minusSeconds((long) (points - 1) * gapSeconds);
        jdbc.update("insert into target (target_id,target_no,object_type_code,subtype,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,'UAV','QUADCOPTER',?,?,?,'mock',?,?,?,?,0 where not exists (select 1 from target where target_id=?)",
                id, no, sn, ts(first), ts(observed), org, district, ts(first), ts(observed), id);
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,protocol_version,created_at)"
                + " select ?,?,?,?,?,'1.0',? where not exists (select 1 from target_source_link where link_id=?)",
                link, id, SOURCE_ID, "s7-" + scenario, "ext-s7-" + scenario, ts(first), link);
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,"
                + "observed_at,received_at,unknown_fields,created_at,updated_at,version) select ?,CAST(? AS GEOMETRY),?,?,8.0,90.0,?,?,?,?,'[]',?,?,0"
                + " where not exists (select 1 from target_latest_state where target_id=?)",
                id, point(lon, lat), amsl, agl, confidence, confidence, ts(observed), ts(observed.plusSeconds(2)), ts(first), ts(observed), id);
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) select ?,?,?,?,?,? where not exists (select 1 from track where track_id=?)",
                track, id, link, "tr-s7-" + scenario, ts(first), ts(first), track);
        for (int i = 0; i < points; i++) {
            Instant seen = first.plusSeconds((long) i * gapSeconds);
            String pointId = "seed-stage7-pt-" + scenario + "-" + i;
            jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at)"
                    + " select ?,?,?,?,?,CAST(? AS GEOMETRY),?,?,? where not exists (select 1 from track_point where point_id=?)",
                    pointId, track, i, ts(seen), ts(seen.plusSeconds(2)), point(lon, lat), amsl, agl, ts(seen), pointId);
        }
    }

    private void plan(String scenario, int seq, String org, String district, String sn, double lon, double lat, Integer min, Integer max, String datum,
            Instant start, Instant end, Instant at) {
        String route = "seed-stage7-route-" + scenario, rv = "seed-stage7-rv-" + scenario, plan = planId(scenario);
        String line = String.format(Locale.ROOT, "SRID=4326;LINESTRING (%.4f %.4f,%.4f %.4f)", lon - 0.01, lat, lon + 0.01, lat);
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,?,true,?,'mock',?,?,?,?,0 where not exists (select 1 from route where route_id=?)",
                route, String.format("HX-S7-%03d", seq), "演示航线（" + scenario + "）", SOURCE_ID, org, district, ts(at), ts(at), route);
        jdbc.update("update route set name=? where route_id=? and name<>?", "演示航线（" + scenario + "）", route, "演示航线（" + scenario + "）");
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " select ?,?,1,CAST(? AS GEOMETRY),100,?,?,?,?,? where not exists (select 1 from route_version where route_version_id=?)",
                rv, route, line, min, max, datum, ts(start), ts(at), rv);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,uav_sn,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " select ?,?,'PENDING',?,'mock',?,?,?,?,?,?,?,?,0 where not exists (select 1 from flight_plan where plan_id=?)",
                plan, String.format("JH-S7-%03d", seq), SOURCE_ID, sn, ts(start), ts(end), rv, org, district, ts(at), ts(at), plan);
    }

    private static String square(double lon, double lat) {
        double d = 0.004;
        return String.format(Locale.ROOT, "SRID=4326;MULTIPOLYGON(((%.3f %.3f,%.3f %.3f,%.3f %.3f,%.3f %.3f,%.3f %.3f)))",
                lon - d, lat - d, lon + d, lat - d, lon + d, lat + d, lon - d, lat + d, lon - d, lat - d);
    }
    private static String point(double lon, double lat) { return String.format(Locale.ROOT, "SRID=4326;POINT(%.4f %.4f)", lon, lat); }
    /** 参数行主键限 36 字符：用稳定的名称 UUID 保证幂等，而不是拼接超长字符串。 */
    private static String stableId(String seed) { return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString(); }

    private void org(String id, String code, String name, Instant at) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists (select 1 from app_org where org_id=?)",
                id, code, name, at.toEpochMilli(), at.toEpochMilli(), id);
        jdbc.update("update app_org set name=? where org_id=? and name<>?", name, id, name);
    }
    private void district(String id, String code, String name, Instant at) {
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists (select 1 from app_district where district_id=?)",
                id, code, name, at.toEpochMilli(), at.toEpochMilli(), id);
        jdbc.update("update app_district set name=? where district_id=? and name<>?", name, id, name);
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
