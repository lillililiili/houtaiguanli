package com.uav.lowaltitude.modules.airport.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;

/**
 * 机场基础数据只增：这里没有 UPDATE / DELETE 路径。
 * 几何统一以 EWKT 文本经 CAST(? AS GEOMETRY) 写入（H2 与 PostgreSQL 同一写法），
 * 读出时 PostGIS 用 ST_X/ST_Y，H2 回退解析 EWKT 文本。
 */
@Repository
public class AirportRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public AirportRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
    }

    public long countAirports(AccessDecision access) {
        Where where = scope(access);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM airport a" + where.sql, where.params, Long.class);
        return count == null ? 0 : count;
    }

    public List<AirportRow> listAirports(AccessDecision access, int offset, int size) {
        Where where = scope(access);
        where.params.put("offset", offset);
        where.params.put("size", size);
        return jdbc.query(select() + where.sql + " ORDER BY a.icao_code ASC, a.airport_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.params, this::airport);
    }

    public AirportRow findAirport(String airportId, AccessDecision access) {
        Where where = scope(access);
        where.sql.append(" AND a.airport_id=:id");
        where.params.put("id", airportId);
        List<AirportRow> rows = jdbc.query(select() + where.sql, where.params, this::airport);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean icaoExists(String icaoCode) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM airport WHERE icao_code=:code", Map.of("code", icaoCode), Long.class);
        return count != null && count > 0;
    }

    public void insertAirport(String airportId, String icaoCode, String name, double longitude, double latitude,
            BigDecimal elevation, String ownerOrgId, String districtId, String note, String createdBy, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", airportId); p.put("code", icaoCode); p.put("name", name); p.put("geom", point(longitude, latitude));
        p.put("elevation", elevation); p.put("org", ownerOrgId); p.put("district", districtId); p.put("note", note);
        p.put("by", createdBy); p.put("at", at);
        jdbc.update("INSERT INTO airport (airport_id,icao_code,name,reference_point,elevation_amsl_m,owner_org_id,district_id,enabled,note,created_by,created_at,version)"
                + " VALUES (:id,:code,:name,CAST(:geom AS GEOMETRY),:elevation,:org,:district,TRUE,:note,:by,:at,0)", p);
    }

    public void insertRunway(String runwayId, String airportId, String designator, BigDecimal heading, BigDecimal length, String centerline, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", runwayId); p.put("airport", airportId); p.put("designator", designator); p.put("heading", heading);
        p.put("length", length); p.put("geom", centerline); p.put("at", at);
        jdbc.update("INSERT INTO airport_runway (runway_id,airport_id,designator,heading_deg,length_m,centerline,created_at)"
                + " VALUES (:id,:airport,:designator,:heading,:length,CAST(:geom AS GEOMETRY),:at)", p);
    }

    public void insertProcedureRoute(String routeId, String airportId, String kind, String name, String centerline,
            BigDecimal protectWidth, BigDecimal minAltitude, BigDecimal maxAltitude, String datum, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", routeId); p.put("airport", airportId); p.put("kind", kind); p.put("name", name); p.put("geom", centerline);
        p.put("width", protectWidth); p.put("min", minAltitude); p.put("max", maxAltitude); p.put("datum", datum); p.put("at", at);
        jdbc.update("INSERT INTO airport_procedure_route (route_id,airport_id,kind,name,centerline,protect_width_m,min_altitude_m,max_altitude_m,altitude_datum,created_at)"
                + " VALUES (:id,:airport,:kind,:name,CAST(:geom AS GEOMETRY),:width,:min,:max,:datum,:at)", p);
    }

    public void insertProtectedTarget(String id, String airportId, String name, String kind, double longitude, double latitude, BigDecimal radius, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("airport", airportId); p.put("name", name); p.put("kind", kind);
        p.put("geom", point(longitude, latitude)); p.put("radius", radius); p.put("at", at);
        jdbc.update("INSERT INTO airport_protected_target (protected_target_id,airport_id,name,kind,location,radius_m,created_at)"
                + " VALUES (:id,:airport,:name,:kind,CAST(:geom AS GEOMETRY),:radius,:at)", p);
    }

    public void insertNotificationTarget(String id, String airportId, String name, String role, String channelKind, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("airport", airportId); p.put("name", name); p.put("role", role); p.put("channel", channelKind); p.put("at", at);
        jdbc.update("INSERT INTO airport_notification_target (notification_target_id,airport_id,name,role,channel_kind,enabled,created_at)"
                + " VALUES (:id,:airport,:name,:role,:channel,TRUE,:at)", p);
    }

    public List<RunwayRow> runways(String airportId) {
        return jdbc.query("SELECT runway_id,airport_id,designator,heading_deg,length_m,created_at FROM airport_runway WHERE airport_id=:id ORDER BY designator ASC",
                Map.of("id", airportId), (rs, i) -> new RunwayRow(rs.getString("runway_id"), rs.getString("airport_id"), rs.getString("designator"),
                        rs.getBigDecimal("heading_deg"), rs.getBigDecimal("length_m"), SpaceRiskRepository.time(rs, "created_at")));
    }

    public List<ProcedureRow> procedureRoutes(String airportId) {
        return jdbc.query("SELECT route_id,airport_id,kind,name,protect_width_m,min_altitude_m,max_altitude_m,altitude_datum,created_at"
                + " FROM airport_procedure_route WHERE airport_id=:id ORDER BY kind ASC, name ASC", Map.of("id", airportId),
                (rs, i) -> new ProcedureRow(rs.getString("route_id"), rs.getString("airport_id"), rs.getString("kind"), rs.getString("name"),
                        rs.getBigDecimal("protect_width_m"), rs.getBigDecimal("min_altitude_m"), rs.getBigDecimal("max_altitude_m"),
                        rs.getString("altitude_datum"), SpaceRiskRepository.time(rs, "created_at")));
    }

    public List<ProtectedRow> protectedTargets(String airportId) {
        return jdbc.query(protectedSelect() + " WHERE p.airport_id=:id ORDER BY p.name ASC", Map.of("id", airportId), this::protectedTarget);
    }

    public List<NotificationRow> notificationTargets(String airportId) {
        return jdbc.query("SELECT notification_target_id,airport_id,name,role,channel_kind,enabled,created_at"
                + " FROM airport_notification_target WHERE airport_id=:id ORDER BY name ASC", Map.of("id", airportId),
                (rs, i) -> new NotificationRow(rs.getString("notification_target_id"), rs.getString("airport_id"), rs.getString("name"),
                        rs.getString("role"), rs.getString("channel_kind"), rs.getBoolean("enabled"), SpaceRiskRepository.time(rs, "created_at")));
    }

    public boolean childExists(String table, String column, String airportId, String value) {
        String sql = switch (table) {
            case "airport_runway" -> "SELECT COUNT(*) FROM airport_runway WHERE airport_id=:id AND designator=:value";
            case "airport_procedure_route" -> "SELECT COUNT(*) FROM airport_procedure_route WHERE airport_id=:id AND name=:value";
            case "airport_protected_target" -> "SELECT COUNT(*) FROM airport_protected_target WHERE airport_id=:id AND name=:value";
            case "airport_notification_target" -> "SELECT COUNT(*) FROM airport_notification_target WHERE airport_id=:id AND name=:value";
            default -> throw new IllegalArgumentException("unsupported airport child table: " + table);
        };
        Long count = jdbc.queryForObject(sql, Map.of("id", airportId, "value", value), Long.class);
        return count != null && count > 0;
    }

    private String select() {
        return "SELECT a.airport_id,a.icao_code,a.name,a.elevation_amsl_m,a.owner_org_id,a.district_id,a.enabled,a.note,a.created_at,a.version,"
                + "org_ref.name AS owner_org_name,dist_ref.name AS district_name," + coordinates("a.reference_point")
                + " FROM airport a LEFT JOIN app_org org_ref ON org_ref.org_id=a.owner_org_id"
                + " LEFT JOIN app_district dist_ref ON dist_ref.district_id=a.district_id";
    }

    private String protectedSelect() {
        return "SELECT p.protected_target_id,p.airport_id,p.name,p.kind,p.radius_m,p.created_at," + coordinates("p.location")
                + " FROM airport_protected_target p";
    }

    private String coordinates(String column) {
        if (postgis) {
            return "ST_X(" + column + ") AS longitude, ST_Y(" + column + ") AS latitude, CAST(NULL AS VARCHAR) AS location_text";
        }
        return "CAST(NULL AS NUMERIC) AS longitude, CAST(NULL AS NUMERIC) AS latitude, CAST(" + column + " AS VARCHAR) AS location_text";
    }

    private Where scope(AccessDecision access) {
        Where where = new Where();
        where.sql.append(" WHERE a.owner_org_id IS NOT NULL AND a.district_id IS NOT NULL")
                .append(" AND EXISTS (SELECT 1 FROM app_org o JOIN app_district d ON d.district_id=a.district_id")
                .append(" WHERE o.org_id=a.owner_org_id AND o.enabled=TRUE AND d.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            where.sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs JOIN app_org so ON so.org_id=gs.org_id AND so.enabled=TRUE")
                    .append(" JOIN app_district sd ON sd.district_id=gs.district_id AND sd.enabled=TRUE WHERE gs.user_id=:scope_user")
                    .append(" AND gs.org_id=a.owner_org_id AND gs.district_id=a.district_id)");
            where.params.put("scope_user", access.userId());
        } else if (access.scopeMode() != ScopeMode.ALL) {
            where.sql.append(" AND 1=0");
        }
        return where;
    }

    private AirportRow airport(ResultSet rs, int ignored) throws SQLException {
        double[] lonLat = coordinates(rs);
        return new AirportRow(rs.getString("airport_id"), rs.getString("icao_code"), rs.getString("name"),
                lonLat == null ? null : BigDecimal.valueOf(lonLat[0]), lonLat == null ? null : BigDecimal.valueOf(lonLat[1]),
                rs.getBigDecimal("elevation_amsl_m"), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getString("owner_org_name"), rs.getString("district_name"), rs.getBoolean("enabled"), rs.getString("note"),
                SpaceRiskRepository.time(rs, "created_at"), rs.getLong("version"));
    }

    private ProtectedRow protectedTarget(ResultSet rs, int ignored) throws SQLException {
        double[] lonLat = coordinates(rs);
        return new ProtectedRow(rs.getString("protected_target_id"), rs.getString("airport_id"), rs.getString("name"), rs.getString("kind"),
                lonLat == null ? null : BigDecimal.valueOf(lonLat[0]), lonLat == null ? null : BigDecimal.valueOf(lonLat[1]),
                rs.getBigDecimal("radius_m"), SpaceRiskRepository.time(rs, "created_at"));
    }

    /** 非 4326 的几何一律当作不可信坐标丢弃，不猜坐标系。 */
    private static double[] coordinates(ResultSet rs) throws SQLException {
        BigDecimal longitude = rs.getBigDecimal("longitude"), latitude = rs.getBigDecimal("latitude");
        if (longitude != null && latitude != null) return new double[] { longitude.doubleValue(), latitude.doubleValue() };
        String text = rs.getString("location_text");
        if (text == null) return null;
        int point = text.toUpperCase(java.util.Locale.ROOT).indexOf("POINT");
        int open = text.indexOf('(', point), close = text.indexOf(')', open);
        if (point < 0 || open < 0 || close < 0 || !text.substring(0, point).contains("4326")) return null;
        String[] parts = text.substring(open + 1, close).trim().split("\\s+");
        if (parts.length != 2) return null;
        try { return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) }; }
        catch (NumberFormatException ex) { return null; }
    }

    public static String point(double longitude, double latitude) { return "SRID=4326;POINT (" + longitude + " " + latitude + ")"; }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }

    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final Map<String, Object> params = new HashMap<>();
    }

    public record AirportRow(String airportId, String icaoCode, String name, BigDecimal longitude, BigDecimal latitude,
            BigDecimal elevationAmslM, String ownerOrgId, String districtId, String ownerOrgName, String districtName,
            boolean enabled, String note, OffsetDateTime createdAt, long version) { }
    public record RunwayRow(String runwayId, String airportId, String designator, BigDecimal headingDeg, BigDecimal lengthM, OffsetDateTime createdAt) { }
    public record ProcedureRow(String routeId, String airportId, String kind, String name, BigDecimal protectWidthM,
            BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum, OffsetDateTime createdAt) { }
    public record ProtectedRow(String protectedTargetId, String airportId, String name, String kind, BigDecimal longitude,
            BigDecimal latitude, BigDecimal radiusM, OffsetDateTime createdAt) { }
    public record NotificationRow(String notificationTargetId, String airportId, String name, String role, String channelKind,
            boolean enabled, OffsetDateTime createdAt) { }
}
