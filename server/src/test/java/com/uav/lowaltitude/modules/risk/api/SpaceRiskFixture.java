package com.uav.lowaltitude.modules.risk.api;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** 阶段 9 测试夹具：只插自己的数据；所有计数断言都按自身归属过滤（阶段 4/7 种子会污染 test 库）。 */
final class SpaceRiskFixture {
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-05T02:00:00Z");
    static final String RULE_SET_VERSION = "space-risk-demo-v1";
    private final JdbcTemplate jdbc;

    SpaceRiskFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String id() { return UUID.randomUUID().toString(); }

    void org(String id, String code) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", id, code, code);
    }

    void district(String id, String code) {
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", id, code, code);
    }

    void source(String id, String code, String mode) {
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,?,?,?,0)",
                id, code, code, mode, ts(T0), ts(T0));
    }

    String planWithRoute(String org, String district, String suffix) {
        String route = "route-" + suffix, routeVersion = "rv-" + suffix, plan = "plan-" + suffix;
        jdbc.update("insert into route (route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,?,true,'mock',?,?,?,?,0)",
                route, "R-" + suffix, "测试航线", org, district, ts(T0), ts(T0));
        jdbc.update("insert into route_version (route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at)"
                + " values (?,?,1,CAST(? AS GEOMETRY),100,10,300,'AGL',?,?)", routeVersion, route,
                "SRID=4326;LINESTRING (118.02 37.02,118.03 37.03)", ts(T0), ts(T0));
        jdbc.update("insert into flight_plan (plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'APPROVED','mock',?,?,?,?,?,?,?,0)", plan, "P-" + suffix, ts(T0.minusHours(1)), ts(T0.plusHours(2)),
                routeVersion, org, district, ts(T0), ts(T0));
        return plan;
    }

    String routeVersionOf(String plan) {
        return jdbc.queryForObject("select route_version_id from flight_plan where plan_id=?", String.class, plan);
    }

    String target(String org, String district, String subtype, String suffix) {
        String id = "target-" + suffix;
        jdbc.update("insert into target (target_id,target_no,object_type_code,subtype,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,'mock',?,?,?,?,0)", id, "TGT-" + suffix, "BIRD", subtype, ts(T0), ts(T0), org, district, ts(T0), ts(T0));
        return id;
    }

    /** 风险直插仅用于读侧夹具；写路径（种子与评估）一律经 RiskIngestionService。 */
    String risk(String plan, String routeVersion, String sourceId, String targetId, String riskType, String severity,
            String state, String org, String district, String suffix) {
        String id = "risk-" + suffix;
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,"
                + "reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,?,?,?,'SPACE_OBJECT_IN_CORRIDOR','测试风险依据',?,?,'UNKNOWN','mock',?,?,?,?,0)",
                id, sourceId, "SRC-" + suffix, plan, routeVersion, targetId, riskType, severity, state, ts(T0), ts(T0), org, district, ts(T0), ts(T0));
        return id;
    }

    void spaceFact(String riskId, String subtype, String relation, String band, Integer count) {
        spaceFact(riskId, subtype, relation, band, count, "[]", 118.021, 37.021);
    }

    void spaceFact(String riskId, String subtype, String relation, String band, Integer count, String unknownReasons,
            Double longitude, Double latitude) {
        String geometry = longitude == null || latitude == null ? null : "SRID=4326;POINT (" + longitude + " " + latitude + ")";
        jdbc.update("insert into space_risk_fact (risk_id,subtype_code,rule_version_id,rule_set_version_id,distance_to_route_m,corridor_relation,"
                + "altitude_band,altitude_datum,object_count,trend,unknown_reasons,target_location,target_altitude_raw,window_from,window_to,created_at)"
                + " values (?,?,'space-risk-c04-v1',?,?,?,?,?,?,'FLAT',CAST(? AS JSON),CAST(? AS GEOMETRY),?,?,?,?)",
                riskId, subtype, RULE_SET_VERSION, new BigDecimal("42.00"), relation, band, band.equals("UNKNOWN") ? null : "AGL",
                count, unknownReasons, geometry, new BigDecimal("120.00"), ts(T0.minusMinutes(30)), ts(T0), ts(T0));
    }

    String role(String suffix, String... permissions) {
        String role = "ROLE-S9-" + suffix;
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        return role;
    }

    String session(String roleCode, String org, String district, String scope) {
        String user = id(), token = id();
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",
                user, "s9-" + user.substring(0, 8), "阶段九测试", roleCode, scope);
        if ("ASSIGNED".equals(scope)) jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)", user, org, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    static Timestamp ts(OffsetDateTime value) { return Timestamp.from(value.toInstant()); }
}
