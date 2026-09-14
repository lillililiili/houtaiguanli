package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 融合层落表：track(layer='FUSED', link_id NULL) + track_point 扩展列 + target_latest_state。
 * 几何只用 EWKT 文本经 CAST(? AS GEOMETRY) 写入，H2 与 PostgreSQL 同一写法；域层不依赖 SQL 几何。
 */
@Repository
public class FusedTrackRepository {
    public static final String LAYER_FUSED = "FUSED";
    private final NamedParameterJdbcTemplate jdbc;
    /** PostgreSQL 上 CAST(geometry AS VARCHAR) 得到的是 EWKB 十六进制而不是 'POINT(x y)'，必须走 ST_AsText；H2 的 CAST 直接给 EWKT。 */
    private final boolean postgis;

    public FusedTrackRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            return false;
        }
    }

    /** 当前未结束的 FUSED 层轨迹（每目标至多一条）。 */
    public FusedTrackRow findOpenTrack(String targetId) {
        List<FusedTrackRow> rows = jdbc.query("SELECT track_id,target_id,external_track_id,started_at,ended_at,config_version FROM track"
                + " WHERE target_id=:t AND layer='FUSED' AND ended_at IS NULL ORDER BY started_at DESC, track_id DESC", Map.of("t", targetId), FusedTrackRepository::track);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertTrack(String trackId, String targetId, String externalTrackId, OffsetDateTime startedAt, String configVersion, OffsetDateTime createdAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", trackId); p.put("t", targetId); p.put("ext", externalTrackId); p.put("started", startedAt); p.put("cfg", configVersion); p.put("created", createdAt);
        jdbc.update("INSERT INTO track (track_id,target_id,link_id,external_track_id,started_at,created_at,layer,config_version) VALUES (:id,:t,NULL,:ext,:started,:created,'FUSED',:cfg)", p);
    }

    public void endTrack(String trackId, OffsetDateTime endedAt) {
        jdbc.update("UPDATE track SET ended_at=:at WHERE track_id=:id AND ended_at IS NULL", Map.of("id", trackId, "at", endedAt));
    }

    public long nextPointSeq(String trackId) {
        Long max = jdbc.queryForObject("SELECT MAX(point_seq) FROM track_point WHERE track_id=:id", Map.of("id", trackId), Long.class);
        return max == null ? 0 : max + 1;
    }

    /** 最近一个融合点（用于 PRED：位置保留最后可信点，不外推）。 */
    public LastPoint lastPoint(String trackId) {
        String locationText = postgis ? "ST_AsText(location)" : "CAST(location AS VARCHAR)";
        List<LastPoint> rows = jdbc.query("SELECT point_id,point_seq,observed_at,altitude_amsl_m,height_agl_m,position_accuracy_m,point_kind," + locationText + " AS location_text"
                + " FROM track_point WHERE track_id=:id ORDER BY point_seq DESC FETCH FIRST 1 ROW ONLY", Map.of("id", trackId),
                (rs, i) -> new LastPoint(rs.getString("point_id"), rs.getLong("point_seq"), FusionConfigRepository.time(rs, "observed_at"), rs.getBigDecimal("altitude_amsl_m"),
                        rs.getBigDecimal("height_agl_m"), rs.getBigDecimal("position_accuracy_m"), rs.getString("point_kind"), rs.getString("location_text")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertPoint(FusedPoint point) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", point.pointId()); p.put("track", point.trackId()); p.put("seq", point.pointSeq()); p.put("observed", point.observedAt()); p.put("received", point.receivedAt());
        p.put("geom", ewkt(point.longitude(), point.latitude())); p.put("amsl", point.altitudeAmslM()); p.put("agl", point.heightAglM()); p.put("created", point.createdAt());
        p.put("kind", point.pointKind()); p.put("obs", point.observationId()); p.put("acc", point.positionAccuracyM()); p.put("contrib", point.contributingJson());
        p.put("pos_src", point.positionSourceId()); p.put("switched", point.sourceSwitched()); p.put("level", point.degradationLevel());
        jdbc.update("INSERT INTO track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at,"
                + "point_kind,observation_id,position_accuracy_m,contributing,position_source_id,source_switched,degradation_level)"
                + " VALUES (:id,:track,:seq,:observed,:received,CAST(:geom AS GEOMETRY),:amsl,:agl,:created,:kind,:obs,:acc,CAST(:contrib AS JSON),:pos_src,:switched,:level)", p);
    }

    public OffsetDateTime latestStateObservedAt(String targetId) {
        List<OffsetDateTime> rows = jdbc.query("SELECT observed_at FROM target_latest_state WHERE target_id=:t", Map.of("t", targetId), (rs, i) -> FusionConfigRepository.time(rs, "observed_at"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 更新最新状态但不递增 version（决策 8-6）：version 留给人工写操作做 expected_version 校验。 */
    public void upsertLatestState(LatestState s) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", s.targetId()); p.put("geom", s.longitude() == null ? null : ewkt(s.longitude(), s.latitude())); p.put("amsl", s.altitudeAmslM()); p.put("agl", s.heightAglM());
        p.put("speed", s.speedMps()); p.put("heading", s.headingDeg()); p.put("cconf", s.classificationConfidence()); p.put("fconf", s.fusionConfidence());
        p.put("observed", s.observedAt()); p.put("received", s.receivedAt()); p.put("unknown", s.unknownFieldsJson()); p.put("updated", s.updatedAt());
        // 飞手位置只由"携带身份主源的帧"改写（决策 8.5-27）：没有身份主源的帧对"飞手在哪"不表态，
        // 连同它的观测时刻一起保持原值；SQL 里干脆不出现这两列，而不是写一个看起来像新值的旧值。
        p.put("pilot", s.pilotLongitude() == null || s.pilotLatitude() == null ? null : ewkt(s.pilotLongitude(), s.pilotLatitude()));
        p.put("pilot_at", s.pilotObservedAt());
        String pilotSet = s.pilotDecided() ? " pilot_location=CAST(:pilot AS GEOMETRY), pilot_observed_at=:pilot_at," : "";
        int updated = jdbc.update("UPDATE target_latest_state SET location=CAST(:geom AS GEOMETRY), altitude_amsl_m=:amsl, height_agl_m=:agl, speed_mps=:speed, heading_deg=:heading,"
                + " classification_confidence=:cconf, fusion_confidence=:fconf, observed_at=:observed, received_at=:received, unknown_fields=CAST(:unknown AS JSON),"
                + pilotSet + " updated_at=:updated"
                + " WHERE target_id=:t", p);
        if (updated == 0) {
            // 新行没有"原值"可留，两列照写（未表态时即为 NULL）。
            jdbc.update("INSERT INTO target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,observed_at,received_at,unknown_fields,pilot_location,pilot_observed_at,created_at,updated_at,version)"
                    + " VALUES (:t,CAST(:geom AS GEOMETRY),:amsl,:agl,:speed,:heading,:cconf,:fconf,:observed,:received,CAST(:unknown AS JSON),CAST(:pilot AS GEOMETRY),:pilot_at,:updated,:updated,0)", p);
        }
    }

    /** 人工修订类别后融合层继续写入时保留人工置信度：只更新类别置信度列。 */
    public void updateClassificationConfidence(String targetId, BigDecimal confidence, OffsetDateTime updatedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("c", confidence); p.put("u", updatedAt);
        jdbc.update("UPDATE target_latest_state SET classification_confidence=:c, updated_at=:u WHERE target_id=:t", p);
    }

    /** 来源 ID → 来源编码（contributing 与 attribute_selection 对外只暴露编码）。 */
    public Map<String, String> sourceCodes(Collection<String> sourceIds) {
        Map<String, String> codes = new HashMap<>();
        if (sourceIds == null || sourceIds.isEmpty()) return codes;
        jdbc.query("SELECT source_id,source_code FROM integration_source WHERE source_id IN (:ids)", Map.of("ids", sourceIds), (ResultSet rs) -> { codes.put(rs.getString("source_id"), rs.getString("source_code")); });
        return codes;
    }

    static String ewkt(Double longitude, Double latitude) {
        return "SRID=4326;POINT (" + longitude + " " + latitude + ")";
    }

    private static FusedTrackRow track(ResultSet rs, int ignored) throws SQLException {
        return new FusedTrackRow(rs.getString("track_id"), rs.getString("target_id"), rs.getString("external_track_id"),
                FusionConfigRepository.time(rs, "started_at"), FusionConfigRepository.time(rs, "ended_at"), rs.getString("config_version"));
    }

    public record FusedTrackRow(String trackId, String targetId, String externalTrackId, OffsetDateTime startedAt, OffsetDateTime endedAt, String configVersion) { }
    public record LastPoint(String pointId, long pointSeq, OffsetDateTime observedAt, BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal positionAccuracyM, String pointKind, String locationText) { }
    public record FusedPoint(String pointId, String trackId, long pointSeq, OffsetDateTime observedAt, OffsetDateTime receivedAt, double longitude, double latitude,
            BigDecimal altitudeAmslM, BigDecimal heightAglM, OffsetDateTime createdAt, String pointKind, String observationId, BigDecimal positionAccuracyM,
            String contributingJson, String positionSourceId, boolean sourceSwitched, String degradationLevel) { }
    public record LatestState(String targetId, Double longitude, Double latitude, BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps, BigDecimal headingDeg,
            BigDecimal classificationConfidence, BigDecimal fusionConfidence, OffsetDateTime observedAt, OffsetDateTime receivedAt, String unknownFieldsJson, OffsetDateTime updatedAt,
            /* 阶段 8.5：身份主源给出的飞手位置与它的观测时刻；pilotDecided 为 false 时本帧不表态，两列保持原值。 */
            Double pilotLongitude, Double pilotLatitude, OffsetDateTime pilotObservedAt, boolean pilotDecided) {

        /** 直接指定飞手位置的调用方（测试夹具）：明写就是表态，写入 NULL 即清空；观测时刻留空。 */
        public LatestState(String targetId, Double longitude, Double latitude, BigDecimal altitudeAmslM, BigDecimal heightAglM,
                BigDecimal speedMps, BigDecimal headingDeg, BigDecimal classificationConfidence, BigDecimal fusionConfidence,
                OffsetDateTime observedAt, OffsetDateTime receivedAt, String unknownFieldsJson, OffsetDateTime updatedAt,
                Double pilotLongitude, Double pilotLatitude) {
            this(targetId, longitude, latitude, altitudeAmslM, heightAglM, speedMps, headingDeg, classificationConfidence,
                    fusionConfidence, observedAt, receivedAt, unknownFieldsJson, updatedAt, pilotLongitude, pilotLatitude, null, true);
        }
    }
}
