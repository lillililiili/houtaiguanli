package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.platform.time.AppClock;

/** 仅 local/test 且显式开关开启的确定性 Stage3 演示夹具；production 永不注册。 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
// 种子按阶段顺序执行：后阶段样例引用前阶段的计划/风险，靠明确 @Order 而不是 Bean 名称顺序。
@Order(35)
public class LocalStage3PlanningSeeder implements ApplicationRunner {
    private static final String SOURCE_ID = "seed-stage3-source";
    private final JdbcTemplate jdbc;
    private final AppClock clock;
    public LocalStage3PlanningSeeder(JdbcTemplate jdbc, AppClock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Override @Transactional
    public void run(ApplicationArguments arguments) {
        // 演示记录的时间统一从 AppClock 取得，避免数据库 current_timestamp 让证据、计划时段和审计基准漂移。
        Instant at = clock.now(), start = at.plusSeconds(300), end = start.plusSeconds(3600);
        org("seed-stage3-org", "SEED-STAGE3", "飞行计划演示机构", at);
        district("seed-stage3-district", "SEED-STAGE3", "飞行计划演示区域", at);
        org("seed-stage3-other-org", "SEED-STAGE3-OTHER", "飞行计划跨范围机构", at);
        district("seed-stage3-other-district", "SEED-STAGE3-OTHER", "飞行计划跨范围区域", at);
        source(at);
        jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,source_snapshot,created_at) select 'seed-stage3-rule','LOCAL-DEMO-V1',1,'ACTIVE',?,'mock','{}',? where not exists(select 1 from rule_version where rule_version_id='seed-stage3-rule')", ts(at), ts(at));
        // LEGAL 路线刻意远离禁止多边形；不能让相同空间事实仅靠手写结论区分合法/非法。
        plan("legal", "LEGAL", "seed-stage3-org", "seed-stage3-district", start, end, "SRID=4326;LINESTRING (118.02 37.02,118.03 37.03)", 10, 100, "AMSL", "[{\"rule_code\":\"C01\",\"result_code\":\"PASS\"},{\"rule_code\":\"C02\",\"result_code\":\"PASS\"}]", "[]", at);
        plan("illegal", "ILLEGAL", "seed-stage3-org", "seed-stage3-district", start, end, "SRID=4326;LINESTRING (118 37,118.01 37)", 10, 100, "AMSL", "[{\"rule_code\":\"C01\",\"result_code\":\"FAIL\",\"reason_code\":\"PROHIBITED_AIRSPACE_OVERLAP\"}]", "[]", at);
        // 版本表要求中心线非空；此样例仅缺可信高度基准，读取端必须保留不可判定而非默认 AGL 或推导合法。
        plan("undetermined", "UNDETERMINED", "seed-stage3-org", "seed-stage3-district", start, end, "SRID=4326;LINESTRING (118.02 37.02,118.03 37.03)", null, null, null, "[{\"rule_code\":\"C01\",\"result_code\":\"UNDETERMINED\",\"reason_code\":\"ALTITUDE_DATUM_OR_RANGE_UNKNOWN\"}]", "[\"ALTITUDE_DATUM_OR_RANGE_UNKNOWN\"]", at);
        plan("cross-scope", "LEGAL", "seed-stage3-other-org", "seed-stage3-other-district", start, end, "SRID=4326;LINESTRING (119 38,119.01 38.01)", 10, 100, "AMSL", "[{\"rule_code\":\"C01\",\"result_code\":\"PASS\"}]", "[]", at);
        airspace(at, start, end);
    }

    private void plan(String suffix, String conclusion, String owner, String area, Instant start, Instant end, String line, Integer min, Integer max, String datum, String checks, String unknown, Instant at) {
        String route = "seed-stage3-route-" + suffix, rv = "seed-stage3-rv-" + suffix, plan = "seed-stage3-plan-" + suffix, result = "seed-stage3-assessment-" + suffix;
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) select ?,?,?,true,?,'mock',?,?,?,?,0 where not exists(select 1 from route where route_id=?)", route, routeNo(suffix), routeName(suffix), SOURCE_ID, owner, area, ts(at), ts(at), route);
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) select ?,?,1,CAST(? AS GEOMETRY),?,?,?,?,?,? where not exists(select 1 from route_version where route_version_id=?)", rv, route, line, 100, min, max, datum, ts(start), ts(at), rv);
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_id,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) select ?,?,'PENDING',?,'mock',?,?,?,?,?,?,?,0 where not exists(select 1 from flight_plan where plan_id=?)", plan, planNo(suffix), SOURCE_ID, ts(start), ts(end), rv, owner, area, ts(at), ts(at), plan);
        jdbc.update("update route set route_no=?,name=? where route_id=? and (route_no<>? or name<>?)", routeNo(suffix), routeName(suffix), route, routeNo(suffix), routeName(suffix));
        jdbc.update("update flight_plan set plan_no=? where plan_id=? and plan_no<>?", planNo(suffix), plan, planNo(suffix));
        // 显式 JSON 转换同时兼容 H2 与 PostgreSQL JSONB，避免 local 真库启动时把 VARCHAR 当 JSON 写入失败。
        jdbc.update("insert into assessment_result (assessment_id,plan_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,evidence_references,source_mode,source_snapshot,created_at) select ?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON), 'mock',CAST('{}' AS JSON),? where not exists(select 1 from assessment_result where assessment_id=?)", result, plan, rv, "seed-stage3-rule", ts(at), conclusion, checks, unknown, "[]", ts(at), result);
    }

    private void airspace(Instant at, Instant start, Instant end) {
        jdbc.update("update airspace set airspace_no='空域-001' where airspace_id='seed-stage3-airspace-prohibited' and airspace_no<>'空域-001'");
        jdbc.update("insert into airspace (airspace_id,airspace_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) select 'seed-stage3-airspace-prohibited','空域-001','禁止演示空域',?,'mock','seed-stage3-org','seed-stage3-district',?,?,0 where not exists(select 1 from airspace where airspace_id='seed-stage3-airspace-prohibited')", SOURCE_ID, ts(at), ts(at));
        // 禁止空域确实穿过 illegal 固定航线，前端仅读已保存研判与服务端冲突事实。
        jdbc.update("insert into airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,created_at) select 'seed-stage3-av-prohibited','seed-stage3-airspace-prohibited',1,'PROHIBITED',CAST(? AS GEOMETRY),10,100,'AMSL',?,?,? where not exists(select 1 from airspace_version where airspace_version_id='seed-stage3-av-prohibited')", "SRID=4326;MULTIPOLYGON(((118.004 36.999,118.006 36.999,118.006 37.001,118.004 37.001,118.004 36.999)))", ts(start), ts(end), ts(at));
    }

    /* 演示业务编号：计划 JH、航线 HX；页面展示编号与名称，不再展示内部 ID。 */
    private static int seq(String suffix) { return switch (suffix) { case "legal" -> 1; case "illegal" -> 2; case "undetermined" -> 3; default -> 4; }; }
    private static String planNo(String suffix) { return String.format("计划-0905-%03d", seq(suffix)); }
    private static String routeNo(String suffix) { return String.format("航线-%03d", seq(suffix)); }
    private static String routeName(String suffix) { return switch (suffix) { case "legal" -> "演示航线（合法样例）"; case "illegal" -> "演示航线（穿越禁飞区）";
            case "undetermined" -> "演示航线（高度基准缺失）"; default -> "演示航线（跨范围样例）"; }; }

    private void source(Instant at) {
        // 所有阶段三夹具显式引用同一固定来源，既验证外键顺序，也让 source_code 筛选可复核。
        jdbc.update("insert into integration_source (source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,created_at,updated_at,version) select ?,'STAGE3-PLANNING-MOCK','飞行计划模拟源','PLANNING_MOCK','1.0',true,'mock',?,?,0 where not exists(select 1 from integration_source where source_id=?)", SOURCE_ID, ts(at), ts(at), SOURCE_ID);
        jdbc.update("update integration_source set name='飞行计划模拟源' where source_id=? and name<>'飞行计划模拟源'", SOURCE_ID);
    }

    private void org(String id, String code, String name, Instant at) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists(select 1 from app_org where org_id=?)", id, code, name, at.toEpochMilli(), at.toEpochMilli(), id);
        jdbc.update("update app_org set name=? where org_id=? and name<>?", name, id, name);
    }
    private void district(String id, String code, String name, Instant at) {
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) select ?,?,?,true,?,?,0 where not exists(select 1 from app_district where district_id=?)", id, code, name, at.toEpochMilli(), at.toEpochMilli(), id);
        jdbc.update("update app_district set name=? where district_id=? and name<>?", name, id, name);
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
