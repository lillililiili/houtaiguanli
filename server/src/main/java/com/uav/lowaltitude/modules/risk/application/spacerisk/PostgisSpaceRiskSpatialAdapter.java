package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/**
 * 唯一的 PostGIS 依赖：米制距离一律走 geography（ST_Distance 的平面度数结果没有业务含义）。
 * 走廊半宽 = corridor_width_m / 2，与阶段 3/7 同一口径。
 * 非 PostgreSQL 后端（H2）时 {@link #available()} 返回 false，评估记 UNAVAILABLE——
 * 让 H2 假装会算空间关系比拿不到结果更危险：它会产出看似正常、实则编造的风险。
 */
@Component
public class PostgisSpaceRiskSpatialAdapter implements SpaceRiskSpatialPort {
    // A retained/predicted fused coordinate may have a fresh frame timestamp after source loss.
    // Once a target has a fused layer, only its matching MEAS point can supply a new assessment.
    // Targets ingested before fusion retain the existing normalized latest-state input contract.
    private static final String MEASURED_FUSION_POSITION = """
                  AND (NOT EXISTS (SELECT 1 FROM track ft WHERE ft.target_id=survivor.target_id AND ft.layer='FUSED')
                    OR EXISTS (SELECT 1 FROM track ft JOIN track_point fp ON fp.track_id=ft.track_id
                      WHERE ft.target_id=survivor.target_id AND ft.layer='FUSED' AND fp.point_kind='MEAS'
                        AND fp.observed_at=ls.observed_at AND fp.received_at=ls.received_at
                        AND ST_Equals(fp.location,ls.location)))
                """;
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;
    private final SpaceRiskObservationEvidence evidence;
    private final boolean localSimulatorPlanBridge;

    public PostgisSpaceRiskSpatialAdapter(JdbcTemplate jdbcTemplate, DataSource dataSource, ObjectMapper json,
            SimulationPolicy simulation, Environment environment) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
        this.localSimulatorPlanBridge = simulation.allowed() && environment.acceptsProfiles(Profiles.of("local & !prod & !production"))
                && environment.getProperty("app.flight-device-check.simulator-device-bridge-enabled", Boolean.class, false);
        this.evidence = new SpaceRiskObservationEvidence(this.jdbc, json, localSimulatorPlanBridge);
    }

    @Override public boolean available() { return postgis; }

    @Override
    public List<SpaceObservation> observations(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes) {
        return observations(windowFrom, windowTo, planWindowPadMinutes, 0);
    }

    @Override
    public List<SpaceObservation> observations(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes, int trendWindowMinutes) {
        if (!postgis) return List.of();
        Map<String, Object> p = window(windowFrom, windowTo, planWindowPadMinutes);
        return evidence.enrich(jdbc.query(OBSERVATION_SELECT + " AND ls.observed_at >= :window_from AND ls.observed_at < :window_to" + OBSERVATION_ORDER,
                p, PostgisSpaceRiskSpatialAdapter::observation), trendWindowMinutes);
    }

    @Override
    public List<SpaceObservation> refreshedObservations(OffsetDateTime refreshedFrom, OffsetDateTime refreshedTo, OffsetDateTime observedSince) {
        return refreshedObservations(refreshedFrom, refreshedTo, observedSince, 0, 0);
    }

    @Override
    public List<SpaceObservation> refreshedObservations(OffsetDateTime refreshedFrom, OffsetDateTime refreshedTo, OffsetDateTime observedSince,
            int planWindowPadMinutes, int trendWindowMinutes) {
        if (!postgis) return List.of();
        Map<String, Object> p = new HashMap<>();
        p.put("refreshed_from", refreshedFrom);
        p.put("refreshed_to", refreshedTo);
        p.put("observed_since", observedSince);
        p.put("plan_pad_minutes", planWindowPadMinutes);
        p.put("local_simulator_bridge", localSimulatorPlanBridge);
        // updated_at 是融合写最新状态时的服务器时刻；observed_since 挡住刚灌入、但观测时刻早已过去的历史回放报文。
        return evidence.enrich(jdbc.query(OBSERVATION_SELECT + " AND ls.updated_at >= :refreshed_from AND ls.updated_at < :refreshed_to"
                + " AND ls.observed_at >= :observed_since" + OBSERVATION_ORDER, p, PostgisSpaceRiskSpatialAdapter::observation), trendWindowMinutes);
    }

    // 目标经 target_current_alias 解析到存活目标：被并的历史目标不再单独产生风险。
    // 计划与航线用 LEFT JOIN：没有待执行/执行中计划的异物目标也必须出现在结果里——决策 9-5 要求它们计入
    // targets_seen 但不生成风险。用 INNER JOIN 会让这类目标在查询层就消失，"无计划只计数"变成永不可达的死代码。
    // 按观测发生时刻与该计划的前后放宽窗口匹配；已结束计划在尾部窗口内仍可接受事实，取消计划不可匹配。
    // 同来源模式且同归属元组才匹配，模拟异物不能挂到真实计划。planId 为 null 时决策表直接返回不生成；
    // 距离为 null 时走廊关系为 UNKNOWN，同样不生成。时间条件由调用方追加。
    private static final String OBSERVATION_SELECT = """
            SELECT DISTINCT survivor.target_id, survivor.target_no, sub.subtype_code, p.plan_id, p.route_version_id,
                   ST_Distance(rv.centerline::geography, ls.location::geography) AS distance_m,
                   rv.corridor_width_m / 2 AS half_width_m,
                   COALESCE(ls.height_agl_m, ls.altitude_amsl_m) AS altitude_m,
                   CASE WHEN ls.height_agl_m IS NOT NULL THEN 'AGL'
                        WHEN ls.altitude_amsl_m IS NOT NULL THEN 'AMSL' END AS altitude_datum,
                   rv.altitude_datum AS route_altitude_datum,
                   ST_X(ls.location) AS longitude, ST_Y(ls.location) AS latitude,
                   survivor.owner_org_id, survivor.district_id, ls.observed_at, ls.received_at
            FROM target t
            LEFT JOIN target_current_alias alias ON alias.historical_target_id = t.target_id
            JOIN target survivor ON survivor.target_id = COALESCE(alias.current_target_id, t.target_id)
            JOIN space_object_subtype sub
              ON sub.enabled = TRUE
             AND CAST(sub.aliases AS TEXT) LIKE CONCAT('%"', COALESCE(NULLIF(survivor.subtype,''),survivor.object_type_code), '"%')
            JOIN target_latest_state ls ON ls.target_id = survivor.target_id
            LEFT JOIN flight_plan p
              ON p.owner_org_id = survivor.owner_org_id AND p.district_id = survivor.district_id
             AND (p.source_mode = survivor.source_mode OR (:local_simulator_bridge
               AND survivor.source_mode='replay' AND p.source_mode='mock' AND p.source_id='local-flight-plan-simulator'))
             AND p.status_code IN ('PENDING','EXECUTING','COMPLETED')
             AND ls.observed_at >= p.start_at - :plan_pad_minutes * INTERVAL '1 minute'
             AND ls.observed_at <= p.end_at + :plan_pad_minutes * INTERVAL '1 minute'
             AND NOT EXISTS(SELECT 1 FROM flight_plan_duplicate d WHERE d.duplicate_plan_id=p.plan_id)
            LEFT JOIN route_version rv ON rv.route_version_id = p.route_version_id AND rv.centerline IS NOT NULL
            WHERE ls.location IS NOT NULL
              AND survivor.owner_org_id IS NOT NULL AND survivor.district_id IS NOT NULL
            """ + MEASURED_FUSION_POSITION;
    private static final String OBSERVATION_ORDER = " ORDER BY survivor.target_id, distance_m ASC";

    private static SpaceObservation observation(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new SpaceObservation(rs.getString("target_id"), rs.getString("target_no"), rs.getString("subtype_code"),
                rs.getString("plan_id"), rs.getString("route_version_id"), rs.getBigDecimal("distance_m"), rs.getBigDecimal("half_width_m"),
                rs.getBigDecimal("altitude_m"), rs.getString("altitude_datum"), rs.getString("route_altitude_datum"),
                null, C04DecisionTable.Trend.UNKNOWN.name(), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"), SpaceRiskRepository.time(rs, "observed_at"), SpaceRiskRepository.time(rs, "received_at"));
    }

    @Override
    public List<AirportProximity> airportProximity(OffsetDateTime windowFrom, OffsetDateTime windowTo, int planWindowPadMinutes) {
        if (!postgis) return List.of();
        Map<String, Object> p = window(windowFrom, windowTo, planWindowPadMinutes);
        return airportQuery(p, " AND ls.observed_at >= :window_from AND ls.observed_at < :window_to");
    }

    @Override
    public List<AirportProximity> refreshedAirportProximity(OffsetDateTime from, OffsetDateTime to, OffsetDateTime observedSince, int planWindowPadMinutes) {
        if (!postgis) return List.of();
        return airportQuery(Map.of("refreshed_from", from, "refreshed_to", to, "observed_since", observedSince),
                " AND ls.updated_at >= :refreshed_from AND ls.updated_at < :refreshed_to AND ls.observed_at >= :observed_since");
    }

    private List<AirportProximity> airportQuery(Map<String, Object> p, String windowCondition) {
        p = new HashMap<>(p);
        p.put("local_simulator_bridge", localSimulatorPlanBridge);
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
                 AND (p.source_mode = survivor.source_mode OR (:local_simulator_bridge
                   AND survivor.source_mode='replay' AND p.source_mode='mock' AND p.source_id='local-flight-plan-simulator'))
                 AND p.status_code IN ('PENDING','EXECUTING','COMPLETED')
                 AND p.start_at <= ls.observed_at AND ls.observed_at < p.end_at
                 AND NOT EXISTS(SELECT 1 FROM flight_plan_duplicate d WHERE d.duplicate_plan_id=p.plan_id)
                WHERE ls.location IS NOT NULL
                """ + windowCondition + MEASURED_FUSION_POSITION + """
                GROUP BY survivor.target_id, sub.subtype_code, a.airport_id, a.name, p.plan_id, p.route_version_id,
                         ls.height_agl_m, ls.altitude_amsl_m, ls.observed_at
                ORDER BY survivor.target_id, a.airport_id
                """, p, (rs, i) -> new AirportProximity(rs.getString("target_id"), rs.getString("subtype_code"), rs.getString("airport_id"),
                rs.getString("airport_name"), rs.getString("plan_id"), rs.getString("route_version_id"),
                rs.getBigDecimal("distance_procedure_m"), rs.getBigDecimal("distance_protected_m"), rs.getBigDecimal("altitude_m"),
                rs.getString("altitude_datum"), null, SpaceRiskRepository.time(rs, "observed_at")));
    }

    private Map<String, Object> window(OffsetDateTime from, OffsetDateTime to, int padMinutes) {
        Map<String, Object> p = new HashMap<>();
        p.put("window_from", from);
        p.put("window_to", to);
        p.put("plan_pad_minutes", padMinutes);
        p.put("local_simulator_bridge", localSimulatorPlanBridge);
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
