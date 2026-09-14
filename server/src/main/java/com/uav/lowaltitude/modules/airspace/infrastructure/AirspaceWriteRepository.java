package com.uav.lowaltitude.modules.airspace.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 空域写模型：新建空域、接替式追加版本、版本来源留痕、版本差异。
 * 只读查询仍归 AirspaceReadRepository；这里只放写路径与写路径需要的查询，避免两边的范围谓词互相漂移。
 * 几何一律以 EWKT 经 CAST(? AS GEOMETRY) 写入（与阶段 3/7/8 种子同一写法，H2 与 PostGIS 通用）。
 */
@Repository
public class AirspaceWriteRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public AirspaceWriteRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = databaseIsPostgres(dataSource);
    }

    public boolean postgis() { return postgis; }

    public record AirspaceHead(String airspaceId, String airspaceNo, String name, String ownerOrgId, String districtId, long version) { }
    public record VersionRow(String airspaceVersionId, String airspaceId, int versionNo, String kindCode, BigDecimal minAltitudeM,
            BigDecimal maxAltitudeM, String altitudeDatum, Instant validFrom, Instant validTo, String changeReason, Instant createdAt) { }

    /** 锁空域头行（写路径）；范围谓词与只读侧一致（ASSIGNED 必须命中同一条授权元组，且组织/区域目录启用）。 */
    public AirspaceHead lockAirspace(String airspaceId, String scopeUserId) {
        return findAirspace(airspaceId, scopeUserId, true);
    }

    /**
     * 只判可见性、不加锁（只读路径用）。
     * PostgreSQL 在只读事务里禁止 SELECT ... FOR UPDATE，而 H2 会放行；只读接口必须走这一条，
     * 否则单测全绿、真实库一调就 500。
     */
    public AirspaceHead findAirspace(String airspaceId, String scopeUserId) {
        return findAirspace(airspaceId, scopeUserId, false);
    }

    private AirspaceHead findAirspace(String airspaceId, String scopeUserId, boolean lock) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", airspaceId);
        StringBuilder sql = new StringBuilder("SELECT a.airspace_id,a.airspace_no,a.name,a.owner_org_id,a.district_id,a.version FROM airspace a"
                + " WHERE a.airspace_id=:id AND a.owner_org_id IS NOT NULL AND a.district_id IS NOT NULL"
                + " AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=a.owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=a.district_id AND d.enabled=TRUE)");
        if (scopeUserId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope s JOIN app_org o2 ON o2.org_id=s.org_id AND o2.enabled=TRUE"
                    + " JOIN app_district d2 ON d2.district_id=s.district_id AND d2.enabled=TRUE"
                    + " WHERE s.user_id=:scope_user AND s.org_id=a.owner_org_id AND s.district_id=a.district_id)");
            p.put("scope_user", scopeUserId);
        }
        if (lock) sql.append(" FOR UPDATE");
        List<AirspaceHead> rows = jdbc.query(sql.toString(), p, AirspaceWriteRepository::head);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** ASSIGNED 用户新建空域时，归属元组必须落在他自己的授权行上（且组织/区域目录启用）。 */
    public boolean scopeGranted(String userId, String ownerOrgId, String districtId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user_data_scope s"
                + " JOIN app_org o ON o.org_id=s.org_id AND o.enabled=TRUE"
                + " JOIN app_district d ON d.district_id=s.district_id AND d.enabled=TRUE"
                + " WHERE s.user_id=:user AND s.org_id=:org AND s.district_id=:district",
                Map.of("user", userId, "org", ownerOrgId, "district", districtId), Long.class);
        return count != null && count > 0;
    }

    public boolean airspaceNoExists(String airspaceNo) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM airspace WHERE airspace_no=:no", Map.of("no", airspaceNo), Long.class);
        return count != null && count > 0;
    }

    public String findAirspaceIdByNo(String airspaceNo) {
        List<String> rows = jdbc.queryForList("SELECT airspace_id FROM airspace WHERE airspace_no=:no", Map.of("no", airspaceNo), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 人工与导入的空域没有外部来源（决策 9-2）：source_id 为 NULL，source_mode 记 live。 */
    public void insertAirspace(String airspaceId, String airspaceNo, String name, String ownerOrgId, String districtId, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", airspaceId); p.put("no", airspaceNo); p.put("name", name); p.put("org", ownerOrgId); p.put("district", districtId);
        p.put("at", Timestamp.from(at));
        jdbc.update("INSERT INTO airspace (airspace_id,airspace_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " VALUES (:id,:no,:name,NULL,'live',:org,:district,:at,:at,0)", p);
    }

    public int bumpAirspaceVersion(String airspaceId, long expectedVersion, Instant at) {
        return jdbc.update("UPDATE airspace SET version=version+1, updated_at=:at WHERE airspace_id=:id AND version=:expected",
                Map.of("id", airspaceId, "expected", expectedVersion, "at", Timestamp.from(at)));
    }

    public void insertVersion(VersionRow row, String boundaryEwkt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.airspaceVersionId()); p.put("airspace", row.airspaceId()); p.put("no", row.versionNo()); p.put("kind", row.kindCode());
        p.put("boundary", boundaryEwkt); p.put("min", row.minAltitudeM()); p.put("max", row.maxAltitudeM()); p.put("datum", row.altitudeDatum());
        p.put("from", Timestamp.from(row.validFrom())); p.put("to", row.validTo() == null ? null : Timestamp.from(row.validTo()));
        p.put("reason", row.changeReason()); p.put("at", Timestamp.from(row.createdAt()));
        jdbc.update("INSERT INTO airspace_version (airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,"
                + "altitude_datum,valid_from,valid_to,change_reason,created_at)"
                + " VALUES (:id,:airspace,:no,:kind,CAST(:boundary AS GEOMETRY),:min,:max,:datum,:from,:to,:reason,:at)", p);
    }

    /** 当前仍然开放（或在新生效时刻之后才关闭）的版本；接替式变更要把它关闭到新版本的 valid_from。 */
    public VersionRow findOpenVersion(String airspaceId, Instant newValidFrom) {
        Map<String, Object> p = Map.of("id", airspaceId, "from", Timestamp.from(newValidFrom));
        List<VersionRow> rows = jdbc.query(select() + " WHERE v.airspace_id=:id AND (v.valid_to IS NULL OR v.valid_to > :from)"
                + " ORDER BY v.version_no DESC FETCH FIRST 1 ROWS ONLY", p, AirspaceWriteRepository::version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public VersionRow findLatestVersion(String airspaceId) {
        List<VersionRow> rows = jdbc.query(select() + " WHERE v.airspace_id=:id ORDER BY v.version_no DESC FETCH FIRST 1 ROWS ONLY",
                Map.of("id", airspaceId), AirspaceWriteRepository::version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public VersionRow findVersion(String airspaceVersionId) {
        List<VersionRow> rows = jdbc.query(select() + " WHERE v.airspace_version_id=:id", Map.of("id", airspaceVersionId), AirspaceWriteRepository::version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 唯一允许的版本原位写：把仍然开放的版本关闭到新版本的生效时刻。
     * WHERE 里再判一次 valid_to IS NULL，保证并发下只有一方能关闭它（PostgreSQL 触发器同样只放行 NULL → 非 NULL）。
     */
    public int closeVersion(String airspaceVersionId, Instant validTo) {
        return jdbc.update("UPDATE airspace_version SET valid_to=:to WHERE airspace_version_id=:id AND valid_to IS NULL",
                Map.of("id", airspaceVersionId, "to", Timestamp.from(validTo)));
    }

    public void insertOrigin(String originId, String airspaceVersionId, String originKind, String actorId, String importItemId,
            String supersededVersionId, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", originId); p.put("version", airspaceVersionId); p.put("kind", originKind); p.put("actor", actorId);
        p.put("item", importItemId); p.put("superseded", supersededVersionId); p.put("at", Timestamp.from(at));
        jdbc.update("INSERT INTO airspace_version_origin (origin_id,airspace_version_id,origin_kind,actor_id,import_item_id,superseded_version_id,created_at)"
                + " VALUES (:id,:version,:kind,:actor,:item,:superseded,:at)", p);
    }

    public record OriginRow(String originKind, String actorId, String actorName, String supersededVersionId) { }

    public OriginRow findOrigin(String airspaceVersionId) {
        List<OriginRow> rows = jdbc.query("SELECT o.origin_kind,o.actor_id,u.name AS actor_name,o.superseded_version_id"
                + " FROM airspace_version_origin o LEFT JOIN app_user u ON u.user_id=o.actor_id WHERE o.airspace_version_id=:id",
                Map.of("id", airspaceVersionId),
                (rs, i) -> new OriginRow(rs.getString("origin_kind"), rs.getString("actor_id"), rs.getString("actor_name"), rs.getString("superseded_version_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 两版几何是否不同以及面积差（平方米）。只在 PostGIS 上计算：面积必须在 geography 上算才有米制含义，
     * H2 没有等价能力，读取端据此把几何差标为"暂不可用"，而不是给一个看似精确的错数。
     */
    public GeometryDiff geometryDiff(String versionA, String versionB) {
        if (!postgis) return new GeometryDiff(false, null, false);
        Map<String, Object> p = Map.of("a", versionA, "b", versionB);
        List<GeometryDiff> rows = jdbc.query("SELECT CASE WHEN a.boundary IS NULL OR b.boundary IS NULL THEN NULL"
                + " ELSE ST_Equals(a.boundary,b.boundary) END AS same,"
                + " CASE WHEN a.boundary IS NULL OR b.boundary IS NULL THEN NULL"
                + " ELSE ST_Area(b.boundary::geography) - ST_Area(a.boundary::geography) END AS area_delta"
                + " FROM airspace_version a, airspace_version b WHERE a.airspace_version_id=:a AND b.airspace_version_id=:b", p,
                (rs, i) -> {
                    Object same = rs.getObject("same");
                    BigDecimal delta = rs.getBigDecimal("area_delta");
                    if (same == null) return new GeometryDiff(false, null, false);
                    return new GeometryDiff(!rs.getBoolean("same"), delta, true);
                });
        return rows.isEmpty() ? new GeometryDiff(false, null, false) : rows.get(0);
    }

    public record GeometryDiff(boolean changed, BigDecimal areaDeltaM2, boolean available) { }

    private static String select() {
        return "SELECT v.airspace_version_id,v.airspace_id,v.version_no,v.kind_code,v.min_altitude_m,v.max_altitude_m,v.altitude_datum,"
                + "v.valid_from,v.valid_to,v.change_reason,v.created_at FROM airspace_version v";
    }

    private static AirspaceHead head(ResultSet rs, int i) throws SQLException {
        return new AirspaceHead(rs.getString("airspace_id"), rs.getString("airspace_no"), rs.getString("name"),
                rs.getString("owner_org_id"), rs.getString("district_id"), rs.getLong("version"));
    }

    private static VersionRow version(ResultSet rs, int i) throws SQLException {
        return new VersionRow(rs.getString("airspace_version_id"), rs.getString("airspace_id"), rs.getInt("version_no"), rs.getString("kind_code"),
                rs.getBigDecimal("min_altitude_m"), rs.getBigDecimal("max_altitude_m"), rs.getString("altitude_datum"),
                instant(rs, "valid_from"), instant(rs, "valid_to"), rs.getString("change_reason"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean databaseIsPostgres(DataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        } catch (SQLException ex) {
            return false;
        }
    }
}
