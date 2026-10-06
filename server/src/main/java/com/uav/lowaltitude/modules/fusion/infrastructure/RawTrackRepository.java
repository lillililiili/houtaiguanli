package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * 一帧里同一来源同一会话的全部 link，一次取回（ZT-06）：逐条观测各查一次是每帧 O(观测数) 次往返，
     * 50 个目标的一帧光这一项就是上百次。键是 external_target_id。
     */
    public Map<String, LinkRow> findLinks(String sourceId, String sessionKey, Collection<String> externalTargetIds) {
        Map<String, LinkRow> out = new LinkedHashMap<>();
        if (externalTargetIds == null || externalTargetIds.isEmpty()) return out;
        Map<String, Object> p = Map.of("source", sourceId, "session", sessionKey, "externals", List.copyOf(new java.util.LinkedHashSet<>(externalTargetIds)));
        for (LinkRow row : jdbc.query("SELECT link_id,target_id,source_id,device_id,source_session_key,external_target_id FROM target_source_link"
                + " WHERE source_id=:source AND source_session_key=:session AND external_target_id IN (:externals)", p, RawTrackRepository::link)) {
            out.put(row.externalTargetId(), row);
        }
        return out;
    }

    /**
     * 多条 link 各自"当前目标名下最新的未结束 RAW 轨迹"，一次取回；与 {@link #findOpenRawTrack} 同一口径（按 started_at、track_id 倒序取第一条）。
     * 键是 link_id。
     */
    public Map<String, TrackRow> findOpenRawTracks(Collection<LinkRow> links) {
        Map<String, TrackRow> out = new LinkedHashMap<>();
        if (links == null || links.isEmpty()) return out;
        Map<String, String> targetByLink = new HashMap<>();
        for (LinkRow link : links) targetByLink.put(link.linkId(), link.targetId());
        List<TrackRow> rows = jdbc.query("SELECT track_id,target_id,link_id,external_track_id,started_at,CAST(filter_state AS VARCHAR) AS filter_state_text FROM track"
                + " WHERE link_id IN (:links) AND layer='RAW' AND ended_at IS NULL ORDER BY link_id, started_at DESC, track_id DESC",
                Map.of("links", List.copyOf(targetByLink.keySet())), RawTrackRepository::track);
        for (TrackRow row : rows) {
            if (out.containsKey(row.linkId()) || !row.targetId().equals(targetByLink.get(row.linkId()))) continue;
            out.put(row.linkId(), row);
        }
        return out;
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

    /** 一帧内多条 RAW 轨迹的滤波状态快照，一次批量写（ZT-06）。 */
    public void updateFilterStates(Map<String, String> stateJsonByTrack) {
        if (stateJsonByTrack == null || stateJsonByTrack.isEmpty()) return;
        List<Map<String, Object>> batch = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : stateJsonByTrack.entrySet()) batch.add(Map.of("id", entry.getKey(), "state", entry.getValue()));
        jdbc.batchUpdate("UPDATE track SET filter_state=CAST(:state AS JSON) WHERE track_id=:id", batchOf(batch));
    }

    public void endTrack(String trackId, Instant endedAt) {
        jdbc.update("UPDATE track SET ended_at=:ended WHERE track_id=:id AND ended_at IS NULL", Map.of("id", trackId, "ended", Timestamp.from(endedAt)));
    }

    public void endOpenRawTracks(String targetId, Instant endedAt) {
        jdbc.update("UPDATE track SET ended_at=:ended WHERE target_id=:target AND layer='RAW' AND ended_at IS NULL", Map.of("target", targetId, "ended", Timestamp.from(endedAt)));
    }

    private static final String INSERT_POINT = "INSERT INTO track_point (point_id,track_id,inbox_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at,"
            + "point_kind,observation_id,position_accuracy_m) VALUES (:id,:track,:inbox,:seq,:observed,:received,CAST(:location AS GEOMETRY),:amsl,:agl,:created,"
            + ":kind,:observation,:accuracy)";

    public void insertPoint(String pointId, String trackId, String inboxId, long pointSeq, Instant observedAt, Instant receivedAt, double longitude, double latitude,
            Double altitudeAmslM, Double heightAglM, String observationId, double accuracyM, String kind) {
        jdbc.update(INSERT_POINT, pointParams(pointId, trackId, inboxId, pointSeq, observedAt, receivedAt, longitude, latitude, altitudeAmslM, heightAglM,
                observationId, accuracyM, kind));
    }

    private static Map<String, Object> pointParams(String pointId, String trackId, String inboxId, long pointSeq, Instant observedAt, Instant receivedAt,
            double longitude, double latitude, Double altitudeAmslM, Double heightAglM, String observationId, double accuracyM, String kind) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", pointId); p.put("track", trackId); p.put("inbox", inboxId); p.put("seq", pointSeq); p.put("observed", Timestamp.from(observedAt));
        p.put("received", Timestamp.from(receivedAt)); p.put("location", ObservationRepository.ewkt(longitude, latitude)); p.put("amsl", altitudeAmslM); p.put("agl", heightAglM);
        p.put("observation", observationId); p.put("accuracy", accuracyM); p.put("kind", kind); p.put("created", Timestamp.from(receivedAt));
        return p;
    }

    /** 一条待写的 RAW 轨迹点（字段与 {@link #insertPoint} 一一对应）。 */
    public record PointInsert(String pointId, String trackId, String inboxId, long pointSeq, Instant observedAt, Instant receivedAt, double longitude,
            double latitude, Double altitudeAmslM, Double heightAglM, String observationId, double accuracyM, String kind) { }

    /** 一帧的 RAW 轨迹点批量写入（ZT-06）：调用方保证所属 track 已先插入。 */
    public void insertPoints(List<PointInsert> points) {
        if (points == null || points.isEmpty()) return;
        List<Map<String, Object>> batch = new java.util.ArrayList<>();
        for (PointInsert point : points) batch.add(pointParams(point.pointId(), point.trackId(), point.inboxId(), point.pointSeq(), point.observedAt(),
                point.receivedAt(), point.longitude(), point.latitude(), point.altitudeAmslM(), point.heightAglM(), point.observationId(), point.accuracyM(), point.kind()));
        jdbc.batchUpdate(INSERT_POINT, batchOf(batch));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object>[] batchOf(List<Map<String, Object>> rows) {
        return rows.toArray(new Map[0]);
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
