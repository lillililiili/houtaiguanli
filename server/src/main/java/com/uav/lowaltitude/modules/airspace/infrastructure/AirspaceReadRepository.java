package com.uav.lowaltitude.modules.airspace.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;

@Repository
public class AirspaceReadRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public AirspaceReadRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = databaseIsPostgres(dataSource);
    }

    public long countAirspaces(AirspaceQuery query, AccessDecision access) {
        Where where = where(query, access);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM airspace a" + where.sql(), where.parameters(), Long.class);
        return count == null ? 0 : count;
    }

    public List<AirspaceRow> listAirspaces(AirspaceQuery query, AccessDecision access, int offset, int size) {
        Where where = where(query, access);
        where.parameters().put("offset", offset);
        where.parameters().put("size", size);
        return jdbc.query("""
                SELECT a.airspace_id,a.airspace_no,a.name,a.source_mode,a.owner_org_id,a.district_id,
                       a.created_at,a.updated_at,a.version,org_ref.name AS owner_org_name,dist_ref.name AS district_name
                FROM airspace a
                LEFT JOIN app_org org_ref ON org_ref.org_id=a.owner_org_id
                LEFT JOIN app_district dist_ref ON dist_ref.district_id=a.district_id
                """ + where.sql()
                + " ORDER BY a.updated_at DESC,a.airspace_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters(), (rs, rowNumber) -> row(rs, rowNumber));
    }

    public AirspaceRow findAirspace(String airspaceId, AccessDecision access) {
        Where where = where(new AirspaceQuery(null, null, null, null, null, null), access);
        where.sql().append(" AND a.airspace_id=:airspace_id");
        where.parameters().put("airspace_id", airspaceId);
        List<AirspaceRow> rows = jdbc.query("""
                SELECT a.airspace_id,a.airspace_no,a.name,a.source_mode,a.owner_org_id,a.district_id,
                       a.created_at,a.updated_at,a.version,org_ref.name AS owner_org_name,dist_ref.name AS district_name
                FROM airspace a
                LEFT JOIN app_org org_ref ON org_ref.org_id=a.owner_org_id
                LEFT JOIN app_district dist_ref ON dist_ref.district_id=a.district_id
                """ + where.sql(), where.parameters(), (rs, rowNumber) -> row(rs, rowNumber));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countVersions(String airspaceId, AccessDecision access) {
        Where where = versionWhere(airspaceId, access);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM airspace_version av JOIN airspace a ON a.airspace_id=av.airspace_id"
                + where.sql(), where.parameters(), Long.class);
        return count == null ? 0 : count;
    }

    public List<AirspaceVersionRow> listVersions(String airspaceId, AccessDecision access, int offset, int size) {
        Where where = versionWhere(airspaceId, access);
        where.parameters().put("offset", offset);
        where.parameters().put("size", size);
        return jdbc.query(versionSelect() + where.sql()
                + " ORDER BY av.version_no DESC,av.airspace_version_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters(), (rs, rowNumber) -> versionRow(rs));
    }

    public AirspaceVersionRow findVersion(String airspaceVersionId, AccessDecision access) {
        Where where = versionWhere(null, access);
        where.sql().append(" AND av.airspace_version_id=:airspace_version_id");
        where.parameters().put("airspace_version_id", airspaceVersionId);
        List<AirspaceVersionRow> rows = jdbc.query(versionSelect() + where.sql(), where.parameters(),
                (rs, rowNumber) -> versionRow(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<AirspaceVersionRow> effectiveVersions(String airspaceId, OffsetDateTime at, AccessDecision access) {
        Where where = versionWhere(airspaceId, access);
        where.sql().append(" AND av.valid_from<=:at AND (av.valid_to IS NULL OR :at<av.valid_to)");
        where.parameters().put("at", at);
        return jdbc.query(versionSelect() + where.sql(), where.parameters(), (rs, number) -> versionRow(rs));
    }

    /**
     * PostGIS 先以 4326 包络框削减候选，再以 geography 缓冲复核米制走廊。
     * 走廊宽度的契约是全宽，故缓冲半径严格除以二；边界触碰没有既定业务政策，保留未知事实。
     */
    public List<ConflictRow> conflicts(PlanRow plan, AccessDecision access) {
        if (!postgis) throw new IllegalStateException("PostGIS is required for spatial conflict facts");
        Where where = where(new AirspaceQuery(null, null, null, null, null, null), access);
        where.parameters().put("route_version_id", plan.routeVersionId());
        where.parameters().put("plan_start", plan.startAt());
        where.parameters().put("plan_end", plan.endAt());
        return jdbc.query("""
                SELECT a.airspace_id,av.airspace_version_id,
                  CASE WHEN :plan_start IS NULL OR :plan_end IS NULL THEN 'UNDETERMINED'
                       -- 两个有效期均为左闭右开；计划终点等于空域起点不是时间重叠。
                       WHEN av.valid_from<:plan_end AND (av.valid_to IS NULL OR :plan_start<av.valid_to) THEN 'OVERLAPS'
                       ELSE 'DISJOINT' END AS time_relation,
                  CASE WHEN rv.min_altitude_m IS NULL OR rv.max_altitude_m IS NULL OR rv.altitude_datum IS NULL
                              OR av.min_altitude_m IS NULL OR av.max_altitude_m IS NULL OR av.altitude_datum IS NULL THEN 'UNDETERMINED'
                       WHEN rv.altitude_datum<>av.altitude_datum THEN 'UNDETERMINED'
                       WHEN rv.min_altitude_m<=av.max_altitude_m AND av.min_altitude_m<=rv.max_altitude_m THEN 'OVERLAPS'
                       ELSE 'DISJOINT' END AS height_relation,
                  CASE WHEN rv.centerline IS NULL OR rv.corridor_width_m IS NULL OR rv.corridor_width_m<=0 OR av.boundary IS NULL THEN 'UNDETERMINED'
                       WHEN ST_Touches(ST_Buffer(rv.centerline::geography,rv.corridor_width_m/2.0)::geometry,av.boundary) THEN 'UNDETERMINED'
                       WHEN ST_Intersects(ST_Buffer(rv.centerline::geography,rv.corridor_width_m/2.0)::geometry,av.boundary) THEN 'OVERLAPS'
                       ELSE 'DISJOINT' END AS horizontal_relation,
                  -- 不同缺失与边界策略不能混为一类：调用方必须知道何种证据不能支撑水平关系。
                  CASE WHEN rv.centerline IS NULL THEN 'ROUTE_GEOMETRY_UNKNOWN'
                       WHEN rv.corridor_width_m IS NULL OR rv.corridor_width_m<=0 THEN 'CORRIDOR_WIDTH_UNKNOWN'
                       WHEN av.boundary IS NULL THEN 'AIRSPACE_BOUNDARY_UNKNOWN'
                       WHEN ST_Touches(ST_Buffer(rv.centerline::geography,rv.corridor_width_m/2.0)::geometry,av.boundary) THEN 'BOUNDARY_POLICY_UNKNOWN'
                       ELSE NULL END AS horizontal_reason
                FROM route_version rv JOIN airspace_version av
                  -- 先用实际 geography 半径形成保守 WGS-84 包络；固定度数换算会在高纬度漏掉东西向真冲突。
                  ON rv.centerline IS NULL OR rv.corridor_width_m IS NULL OR rv.corridor_width_m<=0 OR av.boundary IS NULL
                     OR rv.centerline && ST_Envelope(ST_Buffer(av.boundary::geography,rv.corridor_width_m/2.0)::geometry)
                JOIN airspace a ON a.airspace_id=av.airspace_id
                """ + where.sql() + " AND rv.route_version_id=:route_version_id",
                where.parameters(), (rs, number) -> new ConflictRow(aOrNull(rs, "airspace_id"), aOrNull(rs, "airspace_version_id"),
                        aOrNull(rs, "horizontal_relation"), aOrNull(rs, "horizontal_reason"), aOrNull(rs, "height_relation"), aOrNull(rs, "time_relation")));
    }

    /** 空域版本歧义是时间版本事实，必须在空间候选过滤之前拒绝，不能由不相交几何掩盖。 */
    public boolean hasAmbiguousEffectiveVersion(PlanRow plan, AccessDecision access) {
        if (plan.startAt() == null || plan.endAt() == null) return false;
        Where where = where(new AirspaceQuery(null, null, null, null, null, null), access);
        where.parameters().put("plan_start", plan.startAt());
        where.parameters().put("plan_end", plan.endAt());
        // 仅计划跨过相邻版本不构成歧义：必须两版本彼此半开相交，且该交集也落在计划窗内。
        List<String> roots = jdbc.query("SELECT DISTINCT first_version.airspace_id FROM airspace_version first_version "
                + "JOIN airspace_version second_version ON second_version.airspace_id=first_version.airspace_id "
                + "AND first_version.airspace_version_id<second_version.airspace_version_id "
                + "JOIN airspace a ON a.airspace_id=first_version.airspace_id" + where.sql()
                + " AND first_version.valid_from<COALESCE(second_version.valid_to, :plan_end)"
                + " AND second_version.valid_from<COALESCE(first_version.valid_to, :plan_end)"
                + " AND first_version.valid_from<:plan_end AND second_version.valid_from<:plan_end"
                + " AND (first_version.valid_to IS NULL OR :plan_start<first_version.valid_to)"
                + " AND (second_version.valid_to IS NULL OR :plan_start<second_version.valid_to)", where.parameters(), (rs, number) -> rs.getString(1));
        return !roots.isEmpty();
    }


    private static Where where(AirspaceQuery query, AccessDecision access) {
        StringBuilder sql = new StringBuilder(" WHERE a.owner_org_id IS NOT NULL AND a.district_id IS NOT NULL");
        Map<String, Object> parameters = new HashMap<>();
        // 只允许完整组织/区域元组；ALL 也不能看到未归属空域，避免无范围记录绕过隔离。
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM app_user_data_scope granted_scope
                         JOIN app_org scope_org ON scope_org.org_id=granted_scope.org_id AND scope_org.enabled=TRUE
                         JOIN app_district scope_district
                           ON scope_district.district_id=granted_scope.district_id AND scope_district.enabled=TRUE
                         WHERE granted_scope.user_id=:scope_user_id
                           AND granted_scope.org_id=a.owner_org_id
                           AND granted_scope.district_id=a.district_id
                     )
                    """);
            parameters.put("scope_user_id", access.userId());
        }
        add(sql, parameters, "a.owner_org_id", "owner_org_id", query.ownerOrgId());
        add(sql, parameters, "a.district_id", "district_id", query.districtId());
        add(sql, parameters, "a.source_mode", "source_mode", query.sourceMode());
        if (query.keyword() != null) {
            sql.append(" AND (LOWER(a.airspace_no) LIKE :keyword OR LOWER(a.name) LIKE :keyword)");
            parameters.put("keyword", "%" + query.keyword().toLowerCase() + "%");
        }
        if (query.kindCode() != null || query.validAt() != null) {
            // 类型与有效时点必须由同一个版本同时满足，不能把旧版本类型和当前版本时段交叉拼成虚假命中。
            sql.append(" AND EXISTS (SELECT 1 FROM airspace_version av WHERE av.airspace_id=a.airspace_id");
            if (query.kindCode() != null) {
                sql.append(" AND av.kind_code=:kind_code");
                parameters.put("kind_code", query.kindCode());
            }
            if (query.validAt() != null) {
                // 有效期左闭右开，版本切换瞬间不会把新旧空域同时当作有效事实。
                sql.append(" AND av.valid_from<=:valid_at AND (av.valid_to IS NULL OR :valid_at<av.valid_to)");
                parameters.put("valid_at", query.validAt());
            }
            sql.append(")");
        }
        return new Where(sql, parameters);
    }

    private static Where versionWhere(String airspaceId, AccessDecision access) {
        Where where = where(new AirspaceQuery(null, null, null, null, null, null), access);
        if (airspaceId != null) {
            where.sql().append(" AND av.airspace_id=:airspace_id");
            where.parameters().put("airspace_id", airspaceId);
        }
        return new Where(new StringBuilder(where.sql().toString().replace(" WHERE a.", " WHERE a.")), where.parameters());
    }

    private String versionSelect() {
        String geometry = postgis ? "ST_AsGeoJSON(av.boundary) AS boundary_geojson" : "CAST(av.boundary AS VARCHAR) AS boundary_geojson";
        return "SELECT av.airspace_version_id,av.airspace_id,av.version_no,av.kind_code," + geometry + ",av.min_altitude_m,av.max_altitude_m,"
                + "av.altitude_datum,av.valid_from,av.valid_to,av.change_reason,av.created_at "
                + "FROM airspace_version av JOIN airspace a ON a.airspace_id=av.airspace_id";
    }

    private static void add(StringBuilder sql, Map<String, Object> parameters, String column, String name, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append("=:").append(name);
        parameters.put(name, value);
    }

    private static AirspaceRow row(ResultSet rs, int rowNumber) throws SQLException {
        return new AirspaceRow(rs.getString("airspace_id"), rs.getString("airspace_no"), rs.getString("name"),
                rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"),
                time(rs, "created_at"), time(rs, "updated_at"), rs.getLong("version"),
                rs.getString("owner_org_name"), rs.getString("district_name"));
    }

    private static String aOrNull(ResultSet rs, String name) throws SQLException { return rs.getString(name); }

    private AirspaceVersionRow versionRow(ResultSet rs) throws SQLException {
        return new AirspaceVersionRow(rs.getString("airspace_version_id"), rs.getString("airspace_id"),
                rs.getInt("version_no"), rs.getString("kind_code"), rs.getBigDecimal("min_altitude_m"),
                rs.getBigDecimal("max_altitude_m"), rs.getString("altitude_datum"), time(rs, "valid_from"),
                time(rs, "valid_to"), rs.getString("change_reason"), time(rs, "created_at"),
                postgis, rs.getString("boundary_geojson"));
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime time) return time;
        if (value instanceof ZonedDateTime time) return time.toOffsetDateTime();
        if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC);
        return value == null ? null : OffsetDateTime.parse(value.toString());
    }

    private static boolean databaseIsPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }

    private record Where(StringBuilder sql, Map<String, Object> parameters) {
    }

    public record AirspaceQuery(String kindCode, String sourceMode, String ownerOrgId,
            String districtId, OffsetDateTime validAt, String keyword) {
    }

    public record AirspaceRow(String airspaceId, String airspaceNo, String name, String sourceMode,
            String ownerOrgId, String districtId, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version,
            String ownerOrgName, String districtName) {
    }

    public record AirspaceVersionRow(String airspaceVersionId, String airspaceId, int versionNo, String kindCode,
            java.math.BigDecimal minAltitudeM, java.math.BigDecimal maxAltitudeM, String altitudeDatum,
            OffsetDateTime validFrom, OffsetDateTime validTo, String changeReason, OffsetDateTime createdAt,
            boolean postgis, String boundaryGeoJson) {
    }

    public record ConflictRow(String airspaceId, String airspaceVersionId, String horizontalRelation,
            String horizontalReason, String heightRelation, String timeRelation) { }
}
