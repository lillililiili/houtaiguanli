package com.uav.lowaltitude.modules.airspace.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** GeoJSON 导入批次与要素项：暂存、按范围读取、确认/放弃的条件更新、确认后回填建出的空域版本。 */
@Repository
public class AirspaceImportRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AirspaceImportRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public record BatchRow(String batchId, String status, int featureCount, int acceptedCount, String note, String ownerOrgId,
            String districtId, String createdBy, Instant createdAt, String decidedBy, Instant decidedAt, long version) { }
    public record ItemRow(String itemId, String batchId, int seq, String name, String airspaceNo, String kindCode, String boundaryGeoJson,
            BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum, Instant validFrom, Instant validTo,
            String issuesJson, boolean accepted, String targetAirspaceId, String resultVersionId) { }

    public void insertBatch(String batchId, int featureCount, int acceptedCount, String note, String ownerOrgId, String districtId,
            String createdBy, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", batchId); p.put("features", featureCount); p.put("accepted", acceptedCount); p.put("note", note);
        p.put("org", ownerOrgId); p.put("district", districtId); p.put("by", createdBy); p.put("at", Timestamp.from(at));
        jdbc.update("INSERT INTO airspace_import_batch (batch_id,status,feature_count,accepted_count,note,owner_org_id,district_id,created_by,created_at,version)"
                + " VALUES (:id,'STAGED',:features,:accepted,:note,:org,:district,:by,:at,0)", p);
    }

    public void insertItem(ItemRow row, String boundaryEwkt, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.itemId()); p.put("batch", row.batchId()); p.put("seq", row.seq()); p.put("name", row.name());
        p.put("no", row.airspaceNo()); p.put("kind", row.kindCode()); p.put("geojson", row.boundaryGeoJson()); p.put("boundary", boundaryEwkt);
        p.put("min", row.minAltitudeM()); p.put("max", row.maxAltitudeM()); p.put("datum", row.altitudeDatum());
        p.put("from", row.validFrom() == null ? null : Timestamp.from(row.validFrom()));
        p.put("to", row.validTo() == null ? null : Timestamp.from(row.validTo()));
        p.put("issues", row.issuesJson()); p.put("accepted", row.accepted()); p.put("at", Timestamp.from(at));
        jdbc.update("INSERT INTO airspace_import_item (item_id,batch_id,seq,name,airspace_no,kind_code,boundary_geojson,boundary,"
                + "min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,issues,accepted,created_at)"
                + " VALUES (:id,:batch,:seq,:name,:no,:kind,CAST(:geojson AS JSON),CAST(:boundary AS GEOMETRY),:min,:max,:datum,:from,:to,"
                + "CAST(:issues AS JSON),:accepted,:at)", p);
    }

    /** 锁批次头行并带范围谓词；确认与放弃都必须先锁，避免两个操作者同时决定同一批次。 */
    public BatchRow lockBatch(String batchId, String scopeUserId) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", batchId);
        StringBuilder sql = new StringBuilder(select() + " WHERE b.batch_id=:id" + scope(scopeUserId, p) + " FOR UPDATE");
        List<BatchRow> rows = jdbc.query(sql.toString(), p, AirspaceImportRepository::batch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public BatchRow findBatch(String batchId, String scopeUserId) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", batchId);
        List<BatchRow> rows = jdbc.query(select() + " WHERE b.batch_id=:id" + scope(scopeUserId, p), p, AirspaceImportRepository::batch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ItemRow> items(String batchId) {
        return jdbc.query("SELECT item_id,batch_id,seq,name,airspace_no,kind_code,CAST(boundary_geojson AS VARCHAR) AS geojson_text,"
                + "min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,CAST(issues AS VARCHAR) AS issues_text,accepted,"
                + "target_airspace_id,result_airspace_version_id FROM airspace_import_item WHERE batch_id=:id ORDER BY seq ASC",
                Map.of("id", batchId), AirspaceImportRepository::item);
    }

    /** 决定（确认/放弃）：条件更新带 expected_version 与 STAGED 状态，重复决定只会影响 0 行。 */
    public int decide(String batchId, long expectedVersion, String status, String decidedBy, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", batchId); p.put("expected", expectedVersion); p.put("status", status); p.put("by", decidedBy); p.put("at", Timestamp.from(at));
        return jdbc.update("UPDATE airspace_import_batch SET status=:status, decided_by=:by, decided_at=:at, version=version+1"
                + " WHERE batch_id=:id AND version=:expected AND status='STAGED'", p);
    }

    public void linkItemResult(String itemId, String airspaceId, String airspaceVersionId) {
        jdbc.update("UPDATE airspace_import_item SET target_airspace_id=:airspace, result_airspace_version_id=:version WHERE item_id=:id",
                Map.of("id", itemId, "airspace", airspaceId, "version", airspaceVersionId));
    }

    public String boundaryEwkt(String itemId, boolean postgis) {
        String geometry = postgis ? "ST_AsEWKT(boundary)" : "CAST(boundary AS VARCHAR)";
        List<String> rows = jdbc.queryForList("SELECT " + geometry + " AS boundary_text FROM airspace_import_item WHERE item_id=:id",
                Map.of("id", itemId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String scope(String scopeUserId, Map<String, Object> p) {
        if (scopeUserId == null) return "";
        p.put("scope_user", scopeUserId);
        return " AND EXISTS (SELECT 1 FROM app_user_data_scope s JOIN app_org o ON o.org_id=s.org_id AND o.enabled=TRUE"
                + " JOIN app_district d ON d.district_id=s.district_id AND d.enabled=TRUE"
                + " WHERE s.user_id=:scope_user AND s.org_id=b.owner_org_id AND s.district_id=b.district_id)";
    }

    private static String select() {
        return "SELECT b.batch_id,b.status,b.feature_count,b.accepted_count,b.note,b.owner_org_id,b.district_id,b.created_by,b.created_at,"
                + "b.decided_by,b.decided_at,b.version FROM airspace_import_batch b";
    }

    private static BatchRow batch(ResultSet rs, int i) throws SQLException {
        return new BatchRow(rs.getString("batch_id"), rs.getString("status"), rs.getInt("feature_count"), rs.getInt("accepted_count"),
                rs.getString("note"), rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("created_by"),
                instant(rs, "created_at"), rs.getString("decided_by"), instant(rs, "decided_at"), rs.getLong("version"));
    }

    private static ItemRow item(ResultSet rs, int i) throws SQLException {
        return new ItemRow(rs.getString("item_id"), rs.getString("batch_id"), rs.getInt("seq"), rs.getString("name"), rs.getString("airspace_no"),
                rs.getString("kind_code"), rs.getString("geojson_text"), rs.getBigDecimal("min_altitude_m"), rs.getBigDecimal("max_altitude_m"),
                rs.getString("altitude_datum"), instant(rs, "valid_from"), instant(rs, "valid_to"), rs.getString("issues_text"),
                rs.getBoolean("accepted"), rs.getString("target_airspace_id"), rs.getString("result_airspace_version_id"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
