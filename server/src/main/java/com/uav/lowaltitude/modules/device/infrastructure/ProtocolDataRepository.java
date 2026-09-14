package com.uav.lowaltitude.modules.device.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.PointBatch;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.Rtk;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackBatch;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackItem;

@Repository
public class ProtocolDataRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;

    public ProtocolDataRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.mapper = mapper;
    }

    public boolean insertInbox(String sourceId, String deviceId, String messageKey, byte[] raw, long now) {
        String source = "live-device:" + deviceId;
        int inserted = jdbc.update("""
                INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at,ops_source_id,
                    protocol_message_key,payload_sha256,payload_bytes,processing_status)
                SELECT ?,?,?,?,?,?,?,?, 'RECEIVED'
                WHERE NOT EXISTS (SELECT 1 FROM inbox_message WHERE source=? AND source_msg_id=?)
                """, UUID.randomUUID().toString(), source, messageKey, now, sourceId,
                messageKey, sha256(raw), raw, source, messageKey);
        return inserted == 1;
    }

    public void inboxProcessed(String deviceId, String messageKey, long now) {
        jdbc.update("""
                UPDATE inbox_message SET processing_status='PROCESSED',ops_processed_at=?,failure_reason=NULL
                WHERE source=? AND source_msg_id=?
                """, now, "live-device:" + deviceId, messageKey);
    }

    public void inboxFailed(String deviceId, String messageKey, String reason, long now) {
        jdbc.update("""
                UPDATE inbox_message SET processing_status='FAILED',ops_processed_at=?,failure_reason=?
                WHERE source=? AND source_msg_id=?
                """, now, truncate(reason), "live-device:" + deviceId, messageKey);
    }

    public void saveTrackBatch(String deviceId, String deviceNo, TrackBatch batch, long now) {
        long observed = batch.uploadedAt() > 0 ? batch.uploadedAt() : now;
        for (TrackItem item : batch.items()) {
            String identity = deviceId + ":" + batch.radarBootMicros() + ":" + item.externalTrackId();
            String targetId = stable("target:" + identity);
            String trackId = stable("track:" + identity);
            insertTargetIfMissing(targetId, deviceId, deviceNo, batch.radarBootMicros(), item, now, now);
            insertLinkIfMissing(stable("link:" + identity), targetId, deviceId, batch.radarBootMicros(), item.externalTrackId(), now);
            insertTrackIfMissing(trackId, targetId, deviceId, batch.radarBootMicros(), item.externalTrackId(), now);
            jdbc.update("""
                    UPDATE sensing_target SET radar_classification=?,category_code=?,active=TRUE,last_seen_at=?,updated_at=?
                    WHERE target_id=?
                    """, item.classification(), item.categoryCode(), now, now, targetId);
            upsertLatest(targetId, batch.payloadFrameId(), item, observed, now);
            insertTrackPoint(trackId, batch.payloadFrameId(), item, observed, now);
            jdbc.update("UPDATE ops_track SET last_point_at=?,active=TRUE WHERE track_id=?", now, trackId);
        }
        upsertRuntime(deviceId, "RADAR_TCP_V3_0_0", "ONLINE", "LOGGED_IN", null, now,
                batch.payloadFrameId(), null, null, batch.items().size(), null, null, null);
        updateDeviceState(deviceId, "ONLINE", now, Map.of(
                "active_track_count", metric("活动航迹数", batch.items().size(), "条", "RADAR_TCP_V3_0_0"),
                "last_frame_id", metric("最近帧 ID", batch.payloadFrameId(), null, "RADAR_TCP_V3_0_0")));
    }

    public void savePointSummary(String deviceId, PointBatch batch, long now) {
        String summary;
        try {
            summary = mapper.writeValueAsString(Map.of("scan_start_deg", batch.scanStartDeg(),
                    "scan_end_deg", batch.scanEndDeg(), "scan_direction", batch.scanDirection(),
                    "point_count", batch.items().size(), "sample", batch.items().stream().limit(20).toList()));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
        int updated = jdbc.update("""
                UPDATE radar_point_summary SET radar_boot_micros=?,frame_id=?,point_count=?,observed_at=?,
                    received_at=?,summary_json=? WHERE device_id=?
                """, batch.radarBootMicros(), batch.payloadFrameId(), batch.items().size(), now, now, summary, deviceId);
        if (updated == 0) jdbc.update("""
                INSERT INTO radar_point_summary (device_id,radar_boot_micros,frame_id,point_count,observed_at,received_at,summary_json)
                VALUES (?,?,?,?,?,?,?)
                """, deviceId, batch.radarBootMicros(), batch.payloadFrameId(), batch.items().size(), now, now, summary);
        upsertRuntime(deviceId, "RADAR_TCP_V3_0_0", "ONLINE", "LOGGED_IN", null, now,
                batch.payloadFrameId(), null, null, null, null, null, null);
        updateDeviceState(deviceId, "ONLINE", now, Map.of(
                "latest_point_count", metric("最近点迹数", batch.items().size(), "点", "RADAR_TCP_V3_0_0"),
                "last_frame_id", metric("最近帧 ID", batch.payloadFrameId(), null, "RADAR_TCP_V3_0_0")));
    }

    public void saveRtk(String deviceId, String frameId, Rtk rtk, long now) {
        try {
            jdbc.update("""
                    INSERT INTO radar_rtk_sample (sample_id,device_id,frame_id,latitude_deg,longitude_deg,
                        heading_deg,satellite_count,received_at) VALUES (?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), deviceId, frameId, rtk.latitudeDeg(), rtk.longitudeDeg(),
                    rtk.headingDeg(), rtk.satelliteCount(), now);
        } catch (DataIntegrityViolationException duplicate) { return; }
        Map<String, Object> aggregate = jdbc.queryForMap("""
                SELECT COUNT(*) AS sample_count,AVG(latitude_deg) AS latitude_deg,
                       AVG(longitude_deg) AS longitude_deg,AVG(heading_deg) AS heading_deg
                FROM (SELECT latitude_deg,longitude_deg,heading_deg FROM radar_rtk_sample
                      WHERE device_id=? ORDER BY received_at DESC OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY) recent
                """, deviceId);
        int count = ((Number) aggregate.get("sample_count")).intValue();
        if (count >= 20) upsertReference(deviceId, aggregate, count, now);
        upsertRuntime(deviceId, "RADAR_TCP_V3_0_0", "ONLINE", "LOGGED_IN", null, now,
                frameId, null, count >= 20 ? "READY_UNVERIFIED" : "COLLECTING", null, null, null, null);
        updateDeviceState(deviceId, "ONLINE", now, Map.of(
                "rtk_satellite_count", metric("RTK 卫星数", rtk.satelliteCount(), "颗", "RADAR_TCP_V3_0_0")));
    }

    public void saveCountermeasureState(String deviceId, String encoding, long rawWord,
                                        Map<String, Boolean> channels, long now) {
        String json;
        try { json = mapper.writeValueAsString(channels); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
        upsertRuntime(deviceId, "COUNTERMEASURE_TCP_4CH_V2_0", "ONLINE", null, encoding, now,
                null, now, null, null, rawWord, json, null);
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        channels.forEach((band, on) -> metrics.put("channel_" + band.replace(".", "_").toLowerCase(),
                metric(band + " 通道", on ? "ON" : "OFF", null, "COUNTERMEASURE_TCP_4CH_V2_0")));
        metrics.put("relay_status_word", metric("继电器状态字", Long.toUnsignedString(rawWord), null,
                "COUNTERMEASURE_TCP_4CH_V2_0"));
        updateDeviceState(deviceId, "ONLINE", now, metrics);
    }

    public void markConnection(String deviceId, String protocol, String state, String loginState,
                               String blockingReason, long now, boolean reconnect) {
        upsertRuntime(deviceId, protocol, state, loginState, null, state.equals("ONLINE") ? now : null,
                null, null, null, null, null, null, blockingReason);
        if (reconnect) jdbc.update("UPDATE protocol_runtime_state SET reconnect_count=reconnect_count+1 WHERE device_id=?", deviceId);
        if ("OFFLINE".equals(state) || "ERROR".equals(state)) updateDeviceState(deviceId, "OFFLINE", now, Map.of());
    }

    public void addCrcErrors(String deviceId, long count, long now) {
        jdbc.update("UPDATE protocol_runtime_state SET crc_error_count=crc_error_count+?,updated_at=? WHERE device_id=?",
                count, now, deviceId);
    }

    public void expireTracks(long before, long now) {
        jdbc.update("UPDATE ops_track SET active=FALSE WHERE active=TRUE AND last_point_at<?", before);
        jdbc.update("""
                UPDATE sensing_target SET active=FALSE,updated_at=? WHERE active=TRUE AND last_seen_at<?
                """, now, before);
        jdbc.update("""
                UPDATE protocol_runtime_state SET active_track_count=(SELECT COUNT(*) FROM sensing_target t
                    WHERE t.primary_device_id=protocol_runtime_state.device_id AND t.active=TRUE),updated_at=?
                WHERE protocol_code='RADAR_TCP_V3_0_0'
                """, now);
    }

    public Map<String, Object> runtime(String deviceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM protocol_runtime_state WHERE device_id=?", deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> siteReference(String deviceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM radar_site_reference WHERE device_id=?", deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, java.math.BigDecimal[]> derivedLonLat(String deviceId, long radarBootMicros) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.external_track_id,s.longitude_deg,s.latitude_deg
                FROM ops_target_source_link l JOIN ops_target_latest_state s ON s.target_id=l.target_id
                WHERE l.device_id=? AND l.radar_boot_micros=? AND s.derived=TRUE
                  AND s.longitude_deg IS NOT NULL AND s.latitude_deg IS NOT NULL
                """, deviceId, radarBootMicros);
        Map<String, java.math.BigDecimal[]> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object lon = row.get("longitude_deg"), lat = row.get("latitude_deg"), id = row.get("external_track_id");
            if (id == null || lon == null || lat == null) continue;
            result.put(String.valueOf(id), new java.math.BigDecimal[] { decimal(lon), decimal(lat) });
        }
        return result;
    }

    public List<Map<String, Object>> targets(String deviceId, Boolean active, Integer classification,
                                             Long updatedAfter, int offset, int size) {
        Map<String, Object> p = new HashMap<>();
        String filters = targetFilters(deviceId, active, classification, updatedAfter, p);
        p.put("offset", offset); p.put("size", size);
        return named.queryForList("""
                SELECT t.*,d.device_no,d.name AS device_name,l.external_track_id,l.radar_boot_micros,
                       s.raw_x_m,s.raw_y_m,s.raw_z_m,s.velocity_x_mps,s.velocity_y_mps,s.velocity_z_mps,
                       s.snr_db,s.rcs_legacy_m2,s.rcs_high_resolution_m2,s.selected,s.longitude_deg,
                       s.latitude_deg,s.derived,s.observed_at,s.received_at,s.frame_id
                FROM sensing_target t JOIN ops_device d ON d.device_id=t.primary_device_id
                JOIN ops_target_source_link l ON l.target_id=t.target_id
                LEFT JOIN ops_target_latest_state s ON s.target_id=t.target_id
                WHERE 1=1
                """ + filters + """
                ORDER BY t.last_seen_at DESC,t.target_id OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                """, p);
    }

    public long countTargets(String deviceId, Boolean active, Integer classification, Long updatedAfter) {
        Map<String, Object> p = new HashMap<>();
        String filters = targetFilters(deviceId, active, classification, updatedAfter, p);
        Long value = named.queryForObject("""
                SELECT COUNT(*) FROM sensing_target t
                WHERE 1=1
                """ + filters, p, Long.class);
        return value == null ? 0 : value;
    }

    public Map<String, Object> findTarget(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sensing_target WHERE target_id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> trackPoints(String targetId, long from, long to, int limit) {
        return jdbc.queryForList("""
                SELECT p.* FROM ops_track_point p JOIN ops_track t ON t.track_id=p.track_id
                WHERE t.target_id=? AND p.received_at>=? AND p.received_at<=?
                ORDER BY p.received_at DESC,p.track_point_id DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """, targetId, from, to, limit);
    }

    private static String targetFilters(String deviceId, Boolean active, Integer classification,
                                        Long updatedAfter, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        if (deviceId != null) {
            sql.append(" AND t.primary_device_id=:device_id\n");
            params.put("device_id", deviceId);
        }
        if (active != null) {
            sql.append(" AND t.active=:active\n");
            params.put("active", active);
        }
        if (classification != null) {
            sql.append(" AND t.radar_classification=:classification\n");
            params.put("classification", classification);
        }
        if (updatedAfter != null) {
            sql.append(" AND t.updated_at>=:updated_after\n");
            params.put("updated_after", updatedAfter);
        }
        return sql.toString();
    }

    private void insertTargetIfMissing(String id, String deviceId, String deviceNo, long boot,
                                       TrackItem item, long observed, long now) {
        try { jdbc.update("""
                INSERT INTO sensing_target (target_id,target_no,primary_device_id,radar_classification,
                    category_code,active,first_seen_at,last_seen_at,created_at,updated_at)
                VALUES (?,?,?,?,?,TRUE,?,?,?,?)
                """, id, "RAD-" + deviceNo + "-" + Long.toUnsignedString(boot) + "-" + item.externalTrackId(),
                deviceId, item.classification(), item.categoryCode(), observed, observed, now, now); }
        catch (DataIntegrityViolationException ignored) { }
    }

    private void insertLinkIfMissing(String id, String targetId, String deviceId, long boot, String externalId, long now) {
        try { jdbc.update("INSERT INTO ops_target_source_link (link_id,target_id,device_id,radar_boot_micros,external_track_id,created_at) VALUES (?,?,?,?,?,?)",
                id, targetId, deviceId, boot, externalId, now); }
        catch (DataIntegrityViolationException ignored) { }
    }

    private void insertTrackIfMissing(String id, String targetId, String deviceId, long boot, String externalId, long observed) {
        try { jdbc.update("INSERT INTO ops_track (track_id,target_id,device_id,radar_boot_micros,external_track_id,started_at,last_point_at,active) VALUES (?,?,?,?,?,?,?,TRUE)",
                id, targetId, deviceId, boot, externalId, observed, observed); }
        catch (DataIntegrityViolationException ignored) { }
    }

    private void upsertLatest(String targetId, String frameId, TrackItem item, long observed, long now) {
        Object[] values = { item.xM(), item.yM(), item.zM(), item.velocityXMps(), item.velocityYMps(), item.velocityZMps(),
                item.snrDb(), item.legacyRcsM2(), item.highResolutionRcsM2(), item.selected(), observed, now, frameId, targetId };
        int updated = jdbc.update("""
                UPDATE ops_target_latest_state SET raw_x_m=?,raw_y_m=?,raw_z_m=?,velocity_x_mps=?,velocity_y_mps=?,
                    velocity_z_mps=?,snr_db=?,rcs_legacy_m2=?,rcs_high_resolution_m2=?,selected=?,
                    observed_at=?,received_at=?,frame_id=? WHERE target_id=?
                """, values);
        if (updated == 0) jdbc.update("""
                INSERT INTO ops_target_latest_state (target_id,raw_x_m,raw_y_m,raw_z_m,velocity_x_mps,velocity_y_mps,
                    velocity_z_mps,snr_db,rcs_legacy_m2,rcs_high_resolution_m2,selected,derived,
                    observed_at,received_at,frame_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,FALSE,?,?,?)
                """, targetId, item.xM(), item.yM(), item.zM(), item.velocityXMps(), item.velocityYMps(),
                item.velocityZMps(), item.snrDb(), item.legacyRcsM2(), item.highResolutionRcsM2(), item.selected(),
                observed, now, frameId);
    }

    private void insertTrackPoint(String trackId, String frameId, TrackItem item, long observed, long now) {
        try { jdbc.update("""
                INSERT INTO ops_track_point (track_point_id,track_id,frame_id,observed_at,received_at,raw_x_m,raw_y_m,
                    raw_z_m,velocity_x_mps,velocity_y_mps,velocity_z_mps,snr_db,rcs_legacy_m2,
                    rcs_high_resolution_m2,derived) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE)
                """, UUID.randomUUID().toString(), trackId, frameId, observed, now, item.xM(), item.yM(), item.zM(),
                item.velocityXMps(), item.velocityYMps(), item.velocityZMps(), item.snrDb(), item.legacyRcsM2(),
                item.highResolutionRcsM2()); }
        catch (DataIntegrityViolationException ignored) { }
    }

    private void upsertReference(String deviceId, Map<String, Object> aggregate, int count, long now) {
        int updated = jdbc.update("""
                UPDATE radar_site_reference SET latitude_deg=?,longitude_deg=?,heading_deg=?,sample_count=?,updated_at=?
                WHERE device_id=?
                """, aggregate.get("latitude_deg"), aggregate.get("longitude_deg"), aggregate.get("heading_deg"), count, now, deviceId);
        if (updated == 0) jdbc.update("""
                INSERT INTO radar_site_reference (device_id,latitude_deg,longitude_deg,heading_deg,sample_count,verified,updated_at)
                VALUES (?,?,?,?,?,FALSE,?)
                """, deviceId, aggregate.get("latitude_deg"), aggregate.get("longitude_deg"), aggregate.get("heading_deg"), count, now);
    }

    private void upsertRuntime(String deviceId, String protocol, String state, String login, String encoding,
                               Long validAt, String frameId, Long queryAt, String coordinateState,
                               Integer activeTracks, Long rawWord, String channels, String blocking) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("""
                UPDATE protocol_runtime_state SET protocol_code=?,connection_state=?,login_state=COALESCE(?,login_state),
                    detected_wire_encoding=COALESCE(?,detected_wire_encoding),last_valid_frame_at=COALESCE(?,last_valid_frame_at),
                    last_frame_id=COALESCE(?,last_frame_id),last_query_at=COALESCE(?,last_query_at),
                    coordinate_reference_state=COALESCE(?,coordinate_reference_state),
                    active_track_count=COALESCE(?,active_track_count),raw_status_word=COALESCE(?,raw_status_word),
                    channel_state_json=COALESCE(?,channel_state_json),blocking_reason=?,updated_at=?,version=version+1
                WHERE device_id=?
                """, protocol, state, login, encoding, validAt, frameId, queryAt, coordinateState, activeTracks,
                rawWord, channels, blocking, now, deviceId);
        if (updated == 0) jdbc.update("""
                INSERT INTO protocol_runtime_state (device_id,protocol_code,connection_state,login_state,
                    detected_wire_encoding,last_valid_frame_at,last_frame_id,last_query_at,
                    coordinate_reference_state,active_track_count,raw_status_word,channel_state_json,
                    blocking_reason,updated_at,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, deviceId, protocol, state, login, encoding, validAt, frameId, queryAt,
                coordinateState == null ? "UNAVAILABLE" : coordinateState, activeTracks == null ? 0 : activeTracks,
                rawWord, channels, blocking, now);
    }

    private void updateDeviceState(String deviceId, String connectivity, long now, Map<String, Object> metrics) {
        String json;
        try { json = metrics.isEmpty() ? null : mapper.writeValueAsString(metrics); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
        int updated = jdbc.update("""
                UPDATE ops_device_state SET connectivity=?,work_state_code=?,health_code=?,observed_at=?,received_at=?,
                    last_heartbeat_at=?,metrics_json=COALESCE(?,metrics_json),unknown_reason=?,simulated=FALSE,
                    version=version+1 WHERE device_id=?
                """, connectivity, "ONLINE".equals(connectivity) ? "REPORTING" : "NO_RESPONSE",
                "ONLINE".equals(connectivity) ? "GOOD" : "UNKNOWN", now, now,
                "ONLINE".equals(connectivity) ? now : null, json,
                "ONLINE".equals(connectivity) ? null : "协议会话未收到有效响应", deviceId);
        if (updated == 0) jdbc.update("""
                INSERT INTO ops_device_state (device_id,connectivity,work_state_code,has_alarm,health_code,
                    observed_at,received_at,last_heartbeat_at,metrics_json,unknown_reason,simulated,version)
                VALUES (?,?,?,FALSE,?,?,?,?,?,?,FALSE,0)
                """, deviceId, connectivity, "ONLINE".equals(connectivity) ? "REPORTING" : "NO_RESPONSE",
                "ONLINE".equals(connectivity) ? "GOOD" : "UNKNOWN", now, now,
                "ONLINE".equals(connectivity) ? now : null, json,
                "ONLINE".equals(connectivity) ? null : "协议会话未收到有效响应");
        if ("ONLINE".equals(connectivity)) metrics.forEach((code, raw) -> {
            if (!(raw instanceof Map<?, ?> metric) || !(metric.get("value") instanceof Number number)) return;
            jdbc.update("""
                    INSERT INTO ops_device_state_history (state_id,device_id,connectivity,observed_at,received_at,
                        metric_code,metric_value,metric_unit,simulated) VALUES (?,?,?,?,?,?,?,?,FALSE)
                    """, UUID.randomUUID().toString(), deviceId, connectivity, now, now, code,
                    number, metric.get("unit"));
        });
    }

    private static Map<String, Object> metric(String label, Object value, String unit, String source) {
        Map<String, Object> metric = new java.util.LinkedHashMap<>();
        metric.put("label", label); metric.put("value", value);
        if (unit != null) metric.put("unit", unit);
        metric.put("source", source);
        return metric;
    }

    private static String stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static java.math.BigDecimal decimal(Object value) {
        return value instanceof java.math.BigDecimal d ? d : new java.math.BigDecimal(String.valueOf(value));
    }
    private static String truncate(String value) { return value == null ? null : value.substring(0, Math.min(1000, value.length())); }
    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
