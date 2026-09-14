package com.uav.lowaltitude.modules.fusion.infrastructure;

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

/**
 * 原始层：target_source_link / track(layer='RAW') / track_point。一条 link 同一时刻只有一条未结束的 RAW 轨迹，
 * α-β 滤波状态与该源最近一次观测的属性快照一起放在 track.filter_state（JSON）。
 */
@Repository
public class RawTrackRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public RawTrackRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public record LinkRow(String linkId, String targetId, String sourceId, String deviceId, String sourceSessionKey, String externalTargetId) { }
    public record TrackRow(String trackId, String targetId, String linkId, String externalTrackId, Instant startedAt, String filterStateJson) { }
    /** 目标某条 link 的当前状态（用于候选预测与 SourceEstimate 组装）。 */
    public record LinkState(String targetId, String linkId, String sourceId, String sourceCode, String sourceType, String schemaStatus,
            String trackId, String filterStateJson) { }

    public LinkRow findLink(String sourceId, String sessionKey, String externalTargetId) {
        Map<String, Object> p = Map.of("source", sourceId, "session", sessionKey, "external", externalTargetId);
        List<LinkRow> rows = jdbc.query("SELECT link_id,target_id,source_id,device_id,source_session_key,external_target_id FROM target_source_link"
                + " WHERE source_id=:source AND source_session_key=:session AND external_target_id=:external", p, RawTrackRepository::link);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertLink(String linkId, String targetId, String sourceId, String deviceId, String sessionKey, String externalTargetId, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", linkId); p.put("target", targetId); p.put("source", sourceId); p.put("device", deviceId); p.put("session", sessionKey);
        p.put("external", externalTargetId); p.put("created", Timestamp.from(at));
        jdbc.update("INSERT INTO target_source_link (link_id,target_id,source_id,device_id,source_session_key,external_target_id,protocol_version,created_at)"
                + " VALUES (:id,:target,:source,:device,:session,:external,NULL,:created)", p);
    }

    /** 分裂时把继续上报的回波挂到新目标；旧轨迹留在原目标名下作为历史。 */
    public void relink(String linkId, String newTargetId) {
        jdbc.update("UPDATE target_source_link SET target_id=:target WHERE link_id=:id", Map.of("id", linkId, "target", newTargetId));
    }

    public TrackRow findOpenRawTrack(String linkId, String targetId) {
        List<TrackRow> rows = jdbc.query("SELECT track_id,target_id,link_id,external_track_id,started_at,CAST(filter_state AS VARCHAR) AS filter_state_text FROM track"
                + " WHERE link_id=:link AND target_id=:target AND layer='RAW' AND ended_at IS NULL ORDER BY started_at DESC, track_id DESC FETCH FIRST 1 ROWS ONLY",
                Map.of("link", linkId, "target", targetId), RawTrackRepository::track);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertRawTrack(String trackId, String targetId, String linkId, String externalTrackId, Instant startedAt, String configVersion, String filterStateJson) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", trackId); p.put("target", targetId); p.put("link", linkId); p.put("external", externalTrackId); p.put("started", Timestamp.from(startedAt));
        p.put("config", configVersion); p.put("state", filterStateJson); p.put("created", Timestamp.from(startedAt));
        jdbc.update("INSERT INTO track (track_id,target_id,link_id,external_track_id,started_at,created_at,layer,filter_state,config_version)"
                + " VALUES (:id,:target,:link,:external,:started,:created,'RAW',CAST(:state AS JSON),:config)", p);
    }

    /** 非 INSERT 的 JSON 表达式：SET filter_state=CAST(? AS JSON)（PostgreSQL 上 json→jsonb 为赋值转换；已列入 PG 专项先验清单）。 */
    public void updateFilterState(String trackId, String filterStateJson) {
        jdbc.update("UPDATE track SET filter_state=CAST(:state AS JSON) WHERE track_id=:id", Map.of("id", trackId, "state", filterStateJson));
    }

    public void endTrack(String trackId, Instant endedAt) {
        jdbc.update("UPDATE track SET ended_at=:ended WHERE track_id=:id AND ended_at IS NULL", Map.of("id", trackId, "ended", Timestamp.from(endedAt)));
    }

    public void endOpenRawTracks(String targetId, Instant endedAt) {
        jdbc.update("UPDATE track SET ended_at=:ended WHERE target_id=:target AND layer='RAW' AND ended_at IS NULL", Map.of("target", targetId, "ended", Timestamp.from(endedAt)));
    }

    public void insertPoint(String pointId, String trackId, String inboxId, long pointSeq, Instant observedAt, Instant receivedAt, double longitude, double latitude,
            Double altitudeAmslM, Double heightAglM, String observationId, double accuracyM, String kind) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", pointId); p.put("track", trackId); p.put("inbox", inboxId); p.put("seq", pointSeq); p.put("observed", Timestamp.from(observedAt));
        p.put("received", Timestamp.from(receivedAt)); p.put("location", ObservationRepository.ewkt(longitude, latitude)); p.put("amsl", altitudeAmslM); p.put("agl", heightAglM);
        p.put("observation", observationId); p.put("accuracy", accuracyM); p.put("kind", kind); p.put("created", Timestamp.from(receivedAt));
        jdbc.update("INSERT INTO track_point (point_id,track_id,inbox_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at,"
                + "point_kind,observation_id,position_accuracy_m) VALUES (:id,:track,:inbox,:seq,:observed,:received,CAST(:location AS GEOMETRY),:amsl,:agl,:created,"
                + ":kind,:observation,:accuracy)", p);
    }

    /** 目标（含别名成员）名下所有 link 及其未结束 RAW 轨迹的状态。 */
    public List<LinkState> linkStates(List<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        return jdbc.query("SELECT l.target_id,l.link_id,l.source_id,s.source_code,s.source_type,c.schema_status,tr.track_id,CAST(tr.filter_state AS VARCHAR) AS filter_state_text"
                + " FROM target_source_link l JOIN integration_source s ON s.source_id=l.source_id LEFT JOIN source_type_catalog c ON c.source_type=s.source_type"
                + " LEFT JOIN track tr ON tr.link_id=l.link_id AND tr.target_id=l.target_id AND tr.layer='RAW' AND tr.ended_at IS NULL"
                + " WHERE l.target_id IN (:targets) ORDER BY l.target_id, l.link_id", Map.of("targets", targetIds),
                (rs, i) -> new LinkState(rs.getString("target_id"), rs.getString("link_id"), rs.getString("source_id"), rs.getString("source_code"), rs.getString("source_type"),
                        rs.getString("schema_status"), rs.getString("track_id"), rs.getString("filter_state_text")));
    }

    public long countLinks(String targetId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM target_source_link WHERE target_id=:id", Map.of("id", targetId), Long.class);
        return count == null ? 0 : count;
    }

    private static LinkRow link(ResultSet rs, int i) throws SQLException {
        return new LinkRow(rs.getString("link_id"), rs.getString("target_id"), rs.getString("source_id"), rs.getString("device_id"), rs.getString("source_session_key"), rs.getString("external_target_id"));
    }

    private static TrackRow track(ResultSet rs, int i) throws SQLException {
        Timestamp started = rs.getTimestamp("started_at");
        return new TrackRow(rs.getString("track_id"), rs.getString("target_id"), rs.getString("link_id"), rs.getString("external_track_id"),
                started == null ? null : started.toInstant(), rs.getString("filter_state_text"));
    }
}
