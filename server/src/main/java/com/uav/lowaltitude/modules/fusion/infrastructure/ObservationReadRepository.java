package com.uav.lowaltitude.modules.fusion.infrastructure;

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

import com.uav.lowaltitude.modules.fusion.domain.QualityFacts;

/**
 * 原始观测层读取与融合来源状态。观测按目标的 link（source_id + external_target_id + session）归属，
 * 与阶段 2 一样只在同一 source_mode 内匹配：跨模式的同名外部 ID 不是同一个目标。
 */
@Repository
public class ObservationReadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public ObservationReadRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
    }

    public long countObservations(String targetId, ObservationQuery query) {
        Params p = params(targetId, query);
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + FROM + p.sql, p.values, Long.class);
        return count == null ? 0 : count;
    }

    public List<ObservationRow> listObservations(String targetId, ObservationQuery query, int offset, int size) {
        Params p = params(targetId, query);
        p.values.put("offset", offset);
        p.values.put("size", size);
        return jdbc.query(select() + FROM + p.sql + " ORDER BY o.observed_at DESC, o.observation_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", p.values, ObservationReadRepository::row);
    }

    /**
     * 可见来源与最近观测时刻：只统计当前用户可见目标所属模式下、已登记 source_type 的来源。
     * online 与 data_interrupted 由应用层按 short_lost_after_ms 判断，这里只给事实。
     */
    public List<SourceStatusRow> sourceStatuses() {
        return jdbc.query("""
                SELECT s.source_code, s.source_type, c.schema_status, MAX(o.observed_at) AS last_observed_at
                FROM integration_source s
                JOIN source_type_catalog c ON c.source_type = s.source_type
                LEFT JOIN source_observation o ON o.source_id = s.source_id
                WHERE s.source_type IS NOT NULL AND s.enabled = TRUE
                GROUP BY s.source_code, s.source_type, c.schema_status
                ORDER BY s.source_code ASC
                """, Map.of(), (rs, i) -> new SourceStatusRow(rs.getString("source_code"), rs.getString("source_type"),
                rs.getString("schema_status"), FusionConfigRepository.time(rs, "last_observed_at")));
    }

    private static final String FROM = """
             FROM source_observation o
             JOIN integration_source s ON s.source_id = o.source_id
             JOIN target_source_link l ON l.source_id = o.source_id
              AND l.source_session_key = o.source_session_key
              AND l.external_target_id = o.external_target_id
             JOIN target t ON t.target_id = l.target_id AND t.source_mode = o.source_mode
            """;

    private Params params(String targetId, ObservationQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE l.target_id=:target_id");
        Map<String, Object> values = new HashMap<>();
        values.put("target_id", targetId);
        if (query.sourceCode() != null) { sql.append(" AND s.source_code=:source_code"); values.put("source_code", query.sourceCode()); }
        if (query.timeFrom() != null) {
            sql.append(" AND o.observed_at>=:time_from AND o.observed_at<=:time_to");
            values.put("time_from", query.timeFrom()); values.put("time_to", query.timeTo());
        }
        return new Params(sql.toString(), values);
    }

    private String select() {
        return "SELECT o.observation_id,s.source_code,o.source_type,o.external_target_id,o.observed_at,o.received_at,"
                + "o.position_accuracy_m,o.altitude_amsl_m,o.height_agl_m,o.speed_mps,o.heading_deg,o.class_code,"
                + "o.class_confidence,o.identity_clue,o.source_mode,o.class_source,"
                // 阶段 15（决策 15-5）：AOA 只报方位不报位置，页面据此画方位线；identity_confidence 与
                // class_confidence 分开给，来源面板要能分别说清"像不像这类"和"是不是这一架"。
                + "o.identity_confidence,o.device_id,o.quality,"
                + locationColumns("o.location", "") + "," + locationColumns("o.pilot_location", "pilot_") + " ";
    }

    /** alias 前缀区分同一行里的多个几何列（观测位置与飞手位置）。 */
    private String locationColumns(String column, String alias) {
        if (postgis) {
            return "CASE WHEN " + column + " IS NOT NULL AND ST_SRID(" + column + ")=4326 THEN ST_X(" + column + ") END AS " + alias + "longitude,"
                    + "CASE WHEN " + column + " IS NOT NULL AND ST_SRID(" + column + ")=4326 THEN ST_Y(" + column + ") END AS " + alias + "latitude,"
                    + "CAST(NULL AS VARCHAR) AS " + alias + "location_text";
        }
        return "CAST(NULL AS NUMERIC) AS " + alias + "longitude,CAST(NULL AS NUMERIC) AS " + alias + "latitude,"
                + "CAST(" + column + " AS VARCHAR) AS " + alias + "location_text";
    }

    private static ObservationRow row(ResultSet rs, int ignored) throws SQLException {
        BigDecimal[] location = coordinate(rs, ""), pilot = coordinate(rs, "pilot_");
        return new ObservationRow(rs.getString("observation_id"), rs.getString("source_code"), rs.getString("source_type"), rs.getString("external_target_id"),
                FusionConfigRepository.time(rs, "observed_at"), FusionConfigRepository.time(rs, "received_at"),
                location == null ? null : location[0], location == null ? null : location[1], rs.getBigDecimal("position_accuracy_m"),
                rs.getBigDecimal("altitude_amsl_m"), rs.getBigDecimal("height_agl_m"), rs.getBigDecimal("speed_mps"), rs.getBigDecimal("heading_deg"),
                rs.getString("class_code"), rs.getBigDecimal("class_confidence"), rs.getString("identity_clue"), rs.getString("source_mode"),
                pilot == null ? null : pilot[0], pilot == null ? null : pilot[1], rs.getString("class_source"),
                QualityFacts.bearingDeg(FusionConfigRepository.jsonText(rs.getObject("quality"))),
                rs.getBigDecimal("identity_confidence"), rs.getString("device_id"));
    }

    /** PG 分支直接给数值，H2 分支回读 EWKT 文本再解析；两端都不接受非 4326 的坐标。 */
    private static BigDecimal[] coordinate(ResultSet rs, String alias) throws SQLException {
        BigDecimal longitude = rs.getBigDecimal(alias + "longitude"), latitude = rs.getBigDecimal(alias + "latitude");
        if (longitude == null || latitude == null) {
            double[] parsed = parse(rs.getString(alias + "location_text"));
            if (parsed == null) return null;
            longitude = BigDecimal.valueOf(parsed[0]); latitude = BigDecimal.valueOf(parsed[1]);
        }
        return new BigDecimal[] { longitude, latitude };
    }

    /** H2 上几何列回读为 EWKT 文本；非 4326 一律当作不可信坐标丢弃，不猜测坐标系。 */
    static double[] parse(String text) {
        if (text == null) return null;
        int point = text.toUpperCase().indexOf("POINT");
        int open = text.indexOf('(', point), close = text.indexOf(')', open);
        if (point < 0 || open < 0 || close < 0 || !text.substring(0, point).contains("4326")) return null;
        String[] parts = text.substring(open + 1, close).trim().split("\\s+");
        if (parts.length != 2) return null;
        try { return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) }; }
        catch (NumberFormatException ex) { return null; }
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }

    private record Params(String sql, Map<String, Object> values) { }

    public record ObservationQuery(String sourceCode, OffsetDateTime timeFrom, OffsetDateTime timeTo) { }
    public record ObservationRow(String observationId, String sourceCode, String sourceType, String externalTargetId, OffsetDateTime observedAt, OffsetDateTime receivedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal positionAccuracyM, BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps, BigDecimal headingDeg,
            String classCode, BigDecimal classConfidence, String identityClue, String sourceMode,
            /* 阶段 8.5：飞手位置与类别来源，可空。 */
            BigDecimal pilotLongitude, BigDecimal pilotLatitude, String classSource,
            /* 阶段 15：方位角（来自 quality.bearing_deg）、身份置信度、出这条观测的设备，均可空。 */
            BigDecimal bearingDeg, BigDecimal identityConfidence, String deviceId) { }
    public record SourceStatusRow(String sourceCode, String sourceType, String schemaStatus, OffsetDateTime lastObservedAt) { }
}
