package com.uav.lowaltitude.modules.flight.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

@Repository
public class FlightReadRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public FlightReadRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
    }

    public long countPlans(PlanQuery query, AccessDecision access) {
        Where where = planWhere(query, access);
        return count("SELECT COUNT(*) " + planFrom() + where.sql, where.parameters);
    }

    public List<PlanRow> listPlans(PlanQuery query, AccessDecision access, int offset, int size) {
        Where where = planWhere(query, access);
        where.parameters.put("offset", offset);
        where.parameters.put("size", size);
        return jdbc.query(planSelect() + planFrom() + where.sql
                + " ORDER BY p.start_at DESC NULLS LAST,p.plan_id ASC"
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.parameters, this::planRow);
    }

    public PlanRow findPlan(String planId, AccessDecision access) {
        Where where = planWhere(PlanQuery.empty(), access);
        where.sql.append(" AND p.plan_id=:plan_id");
        where.parameters.put("plan_id", planId);
        List<PlanRow> rows = jdbc.query(planSelect() + planFrom() + where.sql, where.parameters, this::planRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countRoutes(RouteQuery query, AccessDecision access) {
        Where where = routeWhere(query, access, "r");
        return count("SELECT COUNT(*)" + routeFrom() + where.sql, where.parameters);
    }

    public List<RouteRow> listRoutes(RouteQuery query, AccessDecision access, int offset, int size) {
        Where where = routeWhere(query, access, "r");
        where.parameters.put("offset", offset);
        where.parameters.put("size", size);
        return jdbc.query(routeSelect() + routeFrom() + where.sql
                + " ORDER BY r.updated_at DESC,r.route_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters, this::routeRow);
    }

    public RouteRow findRoute(String routeId, AccessDecision access) {
        Where where = routeWhere(RouteQuery.empty(), access, "r");
        where.sql.append(" AND r.route_id=:route_id");
        where.parameters.put("route_id", routeId);
        List<RouteRow> rows = jdbc.query(routeSelect() + routeFrom() + where.sql,
                where.parameters, this::routeRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countRouteVersions(String routeId, AccessDecision access) {
        Where where = routeVersionWhere(routeId, access, null);
        return count("SELECT COUNT(*) FROM route_version rv JOIN route r ON r.route_id=rv.route_id" + where.sql,
                where.parameters);
    }

    public List<RouteVersionRow> listRouteVersions(String routeId, AccessDecision access, int offset, int size) {
        Where where = routeVersionWhere(routeId, access, null);
        where.parameters.put("offset", offset);
        where.parameters.put("size", size);
        return jdbc.query(routeVersionSelect() + " FROM route_version rv JOIN route r ON r.route_id=rv.route_id"
                + where.sql + " ORDER BY rv.version_no DESC,rv.route_version_id ASC"
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.parameters, this::routeVersionRow);
    }

    public RouteVersionRow findRouteVersion(String routeVersionId, AccessDecision access) {
        Where where = routeVersionWhere(null, access, routeVersionId);
        List<RouteVersionRow> rows = jdbc.query(routeVersionSelect()
                + " FROM route_version rv JOIN route r ON r.route_id=rv.route_id" + where.sql,
                where.parameters, this::routeVersionRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Where planWhere(PlanQuery query, AccessDecision access) {
        Where where = new Where();
        appendScope(where, access, "p");
        // 计划详情会带出精确航线版本，因此关联航线也必须拥有同一完整范围元组，避免侧信道泄露。
        where.sql.append(" AND r.owner_org_id=p.owner_org_id AND r.district_id=p.district_id");
        if (query.statusCode != null) add(where, "p.status_code", "status_code", query.statusCode);
        if (query.sourceCode != null) add(where, "s.source_code", "source_code", query.sourceCode);
        if (query.routeId != null) add(where, "r.route_id", "route_id", query.routeId);
        if (query.uavSn != null) add(where, "p.uav_sn", "uav_sn", query.uavSn);
        if (query.ownerOrgId != null) add(where, "p.owner_org_id", "owner_org_id", query.ownerOrgId);
        if (query.districtId != null) add(where, "p.district_id", "district_id", query.districtId);
        if (query.windowFrom != null) {
            where.sql.append(" AND p.start_at IS NOT NULL AND p.end_at IS NOT NULL"
                    + " AND p.start_at<=:window_to AND p.end_at>=:window_from");
            where.parameters.put("window_from", query.windowFrom);
            where.parameters.put("window_to", query.windowTo);
        }
        if (query.keyword != null) {
            where.sql.append(" AND (LOWER(p.plan_no) LIKE :keyword ESCAPE '\\' OR LOWER(COALESCE(p.uav_sn,'')) LIKE :keyword ESCAPE '\\'")
                    .append(" OR LOWER(r.route_no) LIKE :keyword ESCAPE '\\' OR LOWER(r.name) LIKE :keyword ESCAPE '\\')");
            where.parameters.put("keyword", like(query.keyword));
        }
        return where;
    }

    private Where routeWhere(RouteQuery query, AccessDecision access, String alias) {
        Where where = new Where();
        appendScope(where, access, alias);
        if (query.enabled != null) add(where, alias + ".enabled", "enabled", query.enabled);
        if (query.sourceMode != null) add(where, alias + ".source_mode", "source_mode", query.sourceMode);
        if (query.ownerOrgId != null) add(where, alias + ".owner_org_id", "owner_org_id", query.ownerOrgId);
        if (query.districtId != null) add(where, alias + ".district_id", "district_id", query.districtId);
        if (query.keyword != null) {
            where.sql.append(" AND (LOWER(").append(alias).append(".route_no) LIKE :keyword ESCAPE '\\' OR LOWER(")
                    .append(alias).append(".name) LIKE :keyword ESCAPE '\\')");
            where.parameters.put("keyword", like(query.keyword));
        }
        return where;
    }

    private Where routeVersionWhere(String routeId, AccessDecision access, String routeVersionId) {
        Where where = new Where();
        appendScope(where, access, "r");
        if (routeId != null) add(where, "rv.route_id", "route_id", routeId);
        if (routeVersionId != null) add(where, "rv.route_version_id", "route_version_id", routeVersionId);
        return where;
    }

    private static void appendScope(Where where, AccessDecision access, String alias) {
        where.sql.append(" WHERE ").append(alias).append(".owner_org_id IS NOT NULL AND ")
                .append(alias).append(".district_id IS NOT NULL ");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 必须在同一授权记录内匹配组织和行政区，不能把两个范围元组拼成越权访问。
            where.sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM app_user_data_scope granted_scope
                         JOIN app_org scope_org ON scope_org.org_id=granted_scope.org_id AND scope_org.enabled=TRUE
                         JOIN app_district scope_district ON scope_district.district_id=granted_scope.district_id
                             AND scope_district.enabled=TRUE
                         WHERE granted_scope.user_id=:scope_user_id
                           AND granted_scope.org_id=""").append(alias).append(".owner_org_id")
                    .append(" AND granted_scope.district_id=").append(alias).append(".district_id) ");
            where.parameters.put("scope_user_id", access.userId());
        }
    }

    private static String planFrom() {
        return """
                 FROM flight_plan p
                 JOIN route_version rv ON rv.route_version_id=p.route_version_id
                 JOIN route r ON r.route_id=rv.route_id
                 LEFT JOIN integration_source s ON s.source_id=p.source_id AND s.source_mode=p.source_mode
                 LEFT JOIN app_org org_ref ON org_ref.org_id=p.owner_org_id
                 LEFT JOIN app_district dist_ref ON dist_ref.district_id=p.district_id
                """;
    }

    private String planSelect() {
        return """
                SELECT p.plan_id,p.plan_no,p.status_code,p.source_id,p.source_mode,p.uav_sn,p.start_at,p.end_at,
                       p.owner_org_id,p.district_id,p.created_at,p.updated_at,p.version,s.source_code,
                       rv.route_version_id,rv.version_no,rv.max_altitude_m,r.route_id,r.route_no,r.name,
                       s.name AS source_name,org_ref.name AS owner_org_name,dist_ref.name AS district_name
                """;
    }

    private static String routeFrom() {
        return " FROM route r LEFT JOIN integration_source s ON s.source_id=r.source_id AND s.source_mode=r.source_mode"
                + " LEFT JOIN app_org org_ref ON org_ref.org_id=r.owner_org_id LEFT JOIN app_district dist_ref ON dist_ref.district_id=r.district_id";
    }

    private static String routeSelect() {
        return """
                SELECT r.route_id,r.route_no,r.name,r.enabled,r.source_id,r.source_mode,r.owner_org_id,r.district_id,
                       r.created_at,r.updated_at,r.version,s.source_code,
                       s.name AS source_name,org_ref.name AS owner_org_name,dist_ref.name AS district_name
                """;
    }

    private String routeVersionSelect() {
        return """
                SELECT rv.route_version_id,rv.route_id,rv.version_no,rv.corridor_width_m,rv.min_altitude_m,
                       rv.max_altitude_m,rv.altitude_datum,rv.valid_from,rv.valid_to,rv.change_reason,rv.created_at,
                """ + geometryText("rv.centerline") + " AS centerline_text";
    }

    private String geometryText(String column) {
        return postgis ? "ST_AsEWKT(" + column + ")" : "CAST(" + column + " AS VARCHAR)";
    }

    private PlanRow planRow(ResultSet rs, int rowNum) throws SQLException {
        return new PlanRow(rs.getString("plan_id"), rs.getString("plan_no"), rs.getString("status_code"),
                rs.getString("source_id"), rs.getString("source_code"), rs.getString("source_mode"), rs.getString("uav_sn"),
                time(rs, "start_at"), time(rs, "end_at"), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getString("route_version_id"), rs.getString("route_id"), rs.getString("route_no"), rs.getString("name"),
                rs.getInt("version_no"), rs.getBigDecimal("max_altitude_m"), time(rs, "created_at"), time(rs, "updated_at"), rs.getLong("version"),
                rs.getString("source_name"), rs.getString("owner_org_name"), rs.getString("district_name"));
    }

    private RouteRow routeRow(ResultSet rs, int rowNum) throws SQLException {
        return new RouteRow(rs.getString("route_id"), rs.getString("route_no"), rs.getString("name"), rs.getBoolean("enabled"),
                rs.getString("source_id"), rs.getString("source_code"), rs.getString("source_mode"), rs.getString("owner_org_id"),
                rs.getString("district_id"), time(rs, "created_at"), time(rs, "updated_at"), rs.getLong("version"),
                rs.getString("source_name"), rs.getString("owner_org_name"), rs.getString("district_name"));
    }

    private RouteVersionRow routeVersionRow(ResultSet rs, int rowNum) throws SQLException {
        return new RouteVersionRow(rs.getString("route_version_id"), rs.getString("route_id"), rs.getInt("version_no"),
                rs.getString("centerline_text"), rs.getBigDecimal("corridor_width_m"), rs.getBigDecimal("min_altitude_m"),
                rs.getBigDecimal("max_altitude_m"), rs.getString("altitude_datum"), time(rs, "valid_from"), time(rs, "valid_to"),
                rs.getString("change_reason"), time(rs, "created_at"));
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0L : value;
    }

    private static String like(String value) {
        return "%" + value.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time;
        if (value instanceof ZonedDateTime time) return time.toOffsetDateTime();
        if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC);
        if (value instanceof Number time) return Instant.ofEpochMilli(time.longValue()).atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }

    private static void add(Where where, String column, String name, Object value) {
        where.sql.append(" AND ").append(column).append("=:").append(name);
        where.parameters.put(name, value);
    }

    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final Map<String, Object> parameters = new HashMap<>();
    }

    public record PlanQuery(String statusCode, String sourceCode, String routeId, String uavSn, String ownerOrgId,
            String districtId, OffsetDateTime windowFrom, OffsetDateTime windowTo, String keyword) {
        public static PlanQuery empty() { return new PlanQuery(null, null, null, null, null, null, null, null, null); }
    }

    public record RouteQuery(Boolean enabled, String sourceMode, String ownerOrgId, String districtId, String keyword) {
        public static RouteQuery empty() { return new RouteQuery(null, null, null, null, null); }
    }

    public record PlanRow(String planId, String planNo, String statusCode, String sourceId, String sourceCode,
            String sourceMode, String uavSn, OffsetDateTime startAt, OffsetDateTime endAt, String ownerOrgId,
            String districtId, String routeVersionId, String routeId, String routeNo, String routeName, int versionNo,
            BigDecimal maxAltitudeM, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version,
            String sourceName, String ownerOrgName, String districtName) {
    }

    public record RouteRow(String routeId, String routeNo, String name, boolean enabled, String sourceId,
            String sourceCode, String sourceMode, String ownerOrgId, String districtId, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, long version, String sourceName, String ownerOrgName, String districtName) {
    }

    public record RouteVersionRow(String routeVersionId, String routeId, int versionNo, String centerlineText,
            BigDecimal corridorWidthM, BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum,
            OffsetDateTime validFrom, OffsetDateTime validTo, String changeReason, OffsetDateTime createdAt) {
    }
}
