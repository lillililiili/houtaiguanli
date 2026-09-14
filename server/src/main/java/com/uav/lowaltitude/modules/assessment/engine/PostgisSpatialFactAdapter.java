package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 唯一的 PostGIS 依赖：目标点与生效空域版本的拓扑关系、到航线中心线的米制距离、版本歧义。
 * 复用阶段 3 的谓词：走廊半径 = corridor_width_m/2（宽度是全宽）、geography 计算米制、ST_Touches 先于 ST_Covers。
 * 为什么 ST_Touches 是"未知"：点恰好落在边界上时，"算进入还是算在外"没有既定业务政策；PostGIS 的拓扑定义不能替监管方决定。
 * 非 PostgreSQL 后端（H2）调用即抛 SPATIAL_BACKEND_UNAVAILABLE，测试必须用桩替换而不是让 H2 假装会算空间关系。
 */
@Component
public class PostgisSpatialFactAdapter implements SpatialFactPort {
    static final String ERROR_CODE = "SPATIAL_BACKEND_UNAVAILABLE";
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public PostgisSpatialFactAdapter(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = RuleEngineRepository.databaseIsPostgres(dataSource);
    }

    @Override
    public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) {
        requirePostgis();
        if (state == null || state.longitude() == null || state.latitude() == null) return List.of();
        Map<String, Object> p = point(state);
        p.put("as_of", asOf);
        // 只返回 COVERS/TOUCHES/几何缺失的版本；DISJOINT 不是事实缺失，不必逐条返回。空域必须有完整归属元组（阶段 3 同一约束）。
        return jdbc.query("""
                SELECT a.airspace_id,av.airspace_version_id,av.kind_code,av.min_altitude_m,av.max_altitude_m,av.altitude_datum,av.valid_from,av.valid_to,
                  CASE WHEN av.boundary IS NULL THEN 'UNKNOWN'
                       WHEN ST_Touches(av.boundary,pt.geom) THEN 'TOUCHES'
                       WHEN ST_Covers(av.boundary,pt.geom) THEN 'COVERS'
                       ELSE 'DISJOINT' END AS relation,
                  CASE WHEN av.boundary IS NULL THEN 'AIRSPACE_BOUNDARY_UNKNOWN'
                       WHEN ST_Touches(av.boundary,pt.geom) THEN 'BOUNDARY_POLICY_UNKNOWN' ELSE NULL END AS unknown_reason
                FROM airspace_version av JOIN airspace a ON a.airspace_id=av.airspace_id
                CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(:lon,:lat),4326) AS geom) pt
                WHERE a.owner_org_id IS NOT NULL AND a.district_id IS NOT NULL
                  AND av.valid_from<=:as_of AND (av.valid_to IS NULL OR :as_of<av.valid_to)
                  AND (av.boundary IS NULL OR av.boundary && pt.geom)
                  AND (av.boundary IS NULL OR ST_Intersects(av.boundary,pt.geom))
                ORDER BY a.airspace_id,av.version_no
                """, p, (rs, i) -> new AirspaceHit(rs.getString("airspace_id"), rs.getString("airspace_version_id"), rs.getString("kind_code"),
                rs.getString("relation"), rs.getBigDecimal("min_altitude_m"), rs.getBigDecimal("max_altitude_m"), rs.getString("altitude_datum"),
                RuleEngineRepository.time(rs, "valid_from"), RuleEngineRepository.time(rs, "valid_to"), rs.getString("unknown_reason")));
    }

    @Override
    public RouteDistance distanceToRoute(TargetState state, String routeVersionId) {
        requirePostgis();
        if (routeVersionId == null) return new RouteDistance(null, null, null, RuleCodes.ROUTE_GEOMETRY_UNKNOWN);
        if (state == null || state.longitude() == null || state.latitude() == null) return new RouteDistance(routeVersionId, null, null, RuleCodes.POSITION_UNKNOWN);
        Map<String, Object> p = point(state);
        p.put("route", routeVersionId);
        List<RouteDistance> rows = jdbc.query("""
                SELECT rv.corridor_width_m,
                  CASE WHEN rv.centerline IS NULL THEN NULL
                       ELSE ST_Distance(rv.centerline::geography,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography) END AS distance_m
                FROM route_version rv WHERE rv.route_version_id=:route
                """, p, (rs, i) -> {
                    BigDecimal width = rs.getBigDecimal("corridor_width_m");
                    BigDecimal distance = rs.getBigDecimal("distance_m");
                    if (distance == null) return new RouteDistance(routeVersionId, null, null, RuleCodes.ROUTE_GEOMETRY_UNKNOWN);
                    if (width == null || width.signum() <= 0) return new RouteDistance(routeVersionId, null, null, RuleCodes.CORRIDOR_WIDTH_UNKNOWN);
                    return new RouteDistance(routeVersionId, distance, width.divide(BigDecimal.valueOf(2)), null);
                });
        return rows.isEmpty() ? new RouteDistance(routeVersionId, null, null, RuleCodes.ROUTE_GEOMETRY_UNKNOWN) : rows.get(0);
    }

    /** 同一空域两个版本在 as_of 同时生效即歧义（阶段 3 同判定，用时点代替计划窗）。 */
    @Override
    public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf) {
        requirePostgis();
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM airspace_version first_version JOIN airspace_version second_version
                  ON second_version.airspace_id=first_version.airspace_id AND first_version.airspace_version_id<second_version.airspace_version_id
                WHERE first_version.valid_from<=:as_of AND (first_version.valid_to IS NULL OR :as_of<first_version.valid_to)
                  AND second_version.valid_from<=:as_of AND (second_version.valid_to IS NULL OR :as_of<second_version.valid_to)
                """, Map.of("as_of", asOf), Long.class);
        return count != null && count > 0;
    }

    private static Map<String, Object> point(TargetState state) {
        Map<String, Object> p = new HashMap<>();
        p.put("lon", state.longitude().doubleValue());
        p.put("lat", state.latitude().doubleValue());
        return p;
    }

    private void requirePostgis() {
        if (!postgis) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_CODE, "空间事实需要 PostGIS，当前数据库不支持");
    }
}
