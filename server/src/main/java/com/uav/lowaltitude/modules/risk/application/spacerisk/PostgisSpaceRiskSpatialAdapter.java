package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;

/**
 * 唯一的 PostGIS 依赖：米制距离一律走 geography（ST_Distance 的平面度数结果没有业务含义）。
 * 走廊半宽 = corridor_width_m / 2，与阶段 3/7 同一口径。
 * 非 PostgreSQL 后端（H2）时 {@link #available()} 返回 false，评估记 UNAVAILABLE——
 * 让 H2 假装会算空间关系比拿不到结果更危险：它会产出看似正常、实则编造的风险。
 */
@Component
public class PostgisSpaceRiskSpatialAdapter implements SpaceRiskSpatialPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public PostgisSpaceRiskSpatialAdapter(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
    }

    @Override public boolean available() { return postgis; }

    @Override
    public List<SpaceObservation> observations(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes) {
        if (!postgis) return List.of();
        Map<String, Object> p = window(windowFrom, windowTo, planWindowPadMinutes);
        // 目标经 target_current_alias 解析到存活目标：被并的历史目标不再单独产生风险。
        // 计划与航线用 LEFT JOIN：没有活动计划的异物目标也必须出现在结果里——决策 9-5 要求它们计入
        // targets_seen 但不生成风险。用 INNER JOIN 会让这类目标在查询层就消失，"无计划只计数"变成永不可达的死代码。
        // planId 为 null 时决策表直接返回不生成；距离为 null 时走廊关系为 UNKNOWN，同样不生成。
        return jdbc.query("""
                SELECT DISTINCT survivor.target_id, survivor.target_no, sub.subtype_code, p.plan_id, p.route_version_id,
                       ST_Distance(rv.centerline::geography, ls.location::geography) AS distance_m,
                       rv.corridor_width_m / 2 AS half_width_m,
                       COALESCE(ls.height_agl_m, ls.altitude_amsl_m) AS altitude_m,
                       CASE WHEN ls.height_agl_m IS NOT NULL THEN 'AGL'
                            WHEN ls.altitude_amsl_m IS NOT NULL THEN 'AMSL' END AS altitude_datum,
                       rv.altitude_datum AS route_altitude_datum,
                       ST_X(ls.location) AS longitude, ST_Y(ls.location) AS latitude,
                       survivor.owner_org_id, survivor.district_id, ls.observed_at
                FROM target t
                LEFT JOIN target_current_alias alias ON alias.historical_target_id = t.target_id
                JOIN target survivor ON survivor.target_id = COALESCE(alias.current_target_id, t.target_id)
                JOIN space_object_subtype sub
                  ON sub.enabled = TRUE
                 AND CAST(sub.aliases AS TEXT) LIKE CONCAT('%"', COALESCE(NULLIF(survivor.subtype,''),survivor.object_type_code), '"%')
                JOIN target_latest_state ls ON ls.target_id = survivor.target_id
                LEFT JOIN flight_plan p
                  ON p.owner_org_id = survivor.owner_org_id AND p.district_id = survivor.district_id
                 AND p.start_at <= :window_to_padded AND p.end_at >= :window_from_padded
                LEFT JOIN route_version rv ON rv.route_version_id = p.route_version_id AND rv.centerline IS NOT NULL
                WHERE ls.observed_at >= :window_from AND ls.observed_at < :window_to
                  AND ls.location IS NOT NULL
                  AND survivor.owner_org_id IS NOT NULL AND survivor.district_id IS NOT NULL
                ORDER BY survivor.target_id, distance_m ASC
                """, p, (rs, i) -> new SpaceObservation(rs.getString("target_id"), rs.getString("target_no"), rs.getString("subtype_code"),
                rs.getString("plan_id"), rs.getString("route_version_id"), rs.getBigDecimal("distance_m"), rs.getBigDecimal("half_width_m"),
                rs.getBigDecimal("altitude_m"), rs.getString("altitude_datum"), rs.getString("route_altitude_datum"),
                null, C04DecisionTable.Trend.UNKNOWN.name(), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"), SpaceRiskRepository.time(rs, "observed_at")));
    }

    @Override
    public List<AirportProximity> airportProximity(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes) {
        if (!postgis) return List.of();
        Map<String, Object> p = window(windowFrom, windowTo, planWindowPadMinutes);
        return jdbc.query("""
                SELECT survivor.target_id, sub.subtype_code, a.airport_id, a.name AS airport_name,
                       p.plan_id, p.route_version_id,
                       MIN(ST_Distance(pr.centerline::geography, ls.location::geography)) AS distance_procedure_m,
                       MIN(ST_Distance(pt.location::geography, ls.location::geography)) AS distance_protected_m,
                       COALESCE(ls.height_agl_m, ls.altitude_amsl_m) AS altitude_m,
                       CASE WHEN ls.height_agl_m IS NOT NULL THEN 'AGL'
                            WHEN ls.altitude_amsl_m IS NOT NULL THEN 'AMSL' END AS altitude_datum,
                       ls.observed_at
                FROM target t
                LEFT JOIN target_current_alias alias ON alias.historical_target_id = t.target_id
                JOIN target survivor ON survivor.target_id = COALESCE(alias.current_target_id, t.target_id)
                JOIN space_object_subtype sub
                  ON sub.enabled = TRUE
                 AND CAST(sub.aliases AS TEXT) LIKE CONCAT('%"', COALESCE(NULLIF(survivor.subtype,''),survivor.object_type_code), '"%')
                JOIN target_latest_state ls ON ls.target_id = survivor.target_id
                JOIN airport a ON a.enabled = TRUE
                 AND a.owner_org_id = survivor.owner_org_id AND a.district_id = survivor.district_id
                LEFT JOIN airport_procedure_route pr ON pr.airport_id = a.airport_id
                LEFT JOIN airport_protected_target pt ON pt.airport_id = a.airport_id
                LEFT JOIN flight_plan p
                  ON p.owner_org_id = survivor.owner_org_id AND p.district_id = survivor.district_id
                 AND p.start_at <= :window_to_padded AND p.end_at >= :window_from_padded
                WHERE ls.observed_at >= :window_from AND ls.observed_at < :window_to AND ls.location IS NOT NULL
                GROUP BY survivor.target_id, sub.subtype_code, a.airport_id, a.name, p.plan_id, p.route_version_id,
                         ls.height_agl_m, ls.altitude_amsl_m, ls.observed_at
                ORDER BY survivor.target_id, a.airport_id
                """, p, (rs, i) -> new AirportProximity(rs.getString("target_id"), rs.getString("subtype_code"), rs.getString("airport_id"),
                rs.getString("airport_name"), rs.getString("plan_id"), rs.getString("route_version_id"),
                rs.getBigDecimal("distance_procedure_m"), rs.getBigDecimal("distance_protected_m"), rs.getBigDecimal("altitude_m"),
                rs.getString("altitude_datum"), null, SpaceRiskRepository.time(rs, "observed_at")));
    }

    private static Map<String, Object> window(OffsetDateTime from, OffsetDateTime to, int padMinutes) {
        Map<String, Object> p = new HashMap<>();
        p.put("window_from", from);
        p.put("window_to", to);
        // 计划窗口前后放宽：起飞前与落地后的短时间内异物同样威胁该次飞行活动。
        p.put("window_from_padded", from.minusMinutes(padMinutes));
        p.put("window_to_padded", to.plusMinutes(padMinutes));
        return p;
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }
}
