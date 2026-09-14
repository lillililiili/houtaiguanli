package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.domain.SourceObservation;

/** source_observation 写入与来源/设备元数据查询。几何用 EWKT 经 cast(? as geometry) 写入（H2 与 PostGIS 通用，同迁移 0006 种子写法）。 */
@Repository
public class ObservationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public ObservationRepository(JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.json = json;
    }

    public record SourceMeta(String sourceId, String sourceCode, String sourceType, String schemaStatus, String sourceMode, boolean enabled) { }
    public record DeviceMeta(String deviceId, String ownerOrgId, String districtId) { }

    public SourceMeta findSource(String sourceId) {
        List<SourceMeta> rows = jdbc.query("SELECT s.source_id,s.source_code,s.source_type,c.schema_status,s.source_mode,s.enabled FROM integration_source s"
                + " LEFT JOIN source_type_catalog c ON c.source_type=s.source_type WHERE s.source_id=:id", Map.of("id", sourceId),
                (rs, i) -> new SourceMeta(rs.getString("source_id"), rs.getString("source_code"), rs.getString("source_type"), rs.getString("schema_status"),
                        rs.getString("source_mode"), rs.getBoolean("enabled")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 来源对应的启用设备（回放来源一来源一设备）；归属元组来自设备，没有设备则元组为空。 */
    public DeviceMeta findDeviceForSource(String sourceId) {
        List<DeviceMeta> rows = jdbc.query("SELECT device_id,owner_org_id,district_id FROM device WHERE source_id=:id AND enabled=TRUE ORDER BY device_no ASC, device_id ASC FETCH FIRST 1 ROWS ONLY",
                Map.of("id", sourceId), (rs, i) -> new DeviceMeta(rs.getString("device_id"), rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(SourceObservation o) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", o.observationId()); p.put("inbox", o.inboxId()); p.put("source", o.sourceId()); p.put("device", o.deviceId()); p.put("type", o.sourceType());
        p.put("session", o.sourceSessionKey()); p.put("external", o.externalTargetId()); p.put("external_track", o.externalTrackId());
        p.put("observed", Timestamp.from(o.observedAt())); p.put("received", Timestamp.from(o.receivedAt()));
        p.put("location", o.hasPosition() ? ewkt(o.longitude(), o.latitude()) : null);
        p.put("accuracy", o.positionAccuracyM()); p.put("amsl", o.altitudeAmslM()); p.put("agl", o.heightAglM()); p.put("speed", o.speedMps()); p.put("heading", o.headingDeg());
        p.put("class_code", o.classCode()); p.put("class_conf", o.classConfidence()); p.put("identity", o.identityClue()); p.put("identity_conf", o.identityConfidence());
        p.put("latency", o.latencyMs()); p.put("quality", write(o.quality())); p.put("mode", o.sourceMode()); p.put("org", o.ownerOrgId()); p.put("district", o.districtId());
        p.put("created", Timestamp.from(Instant.now()));
        // 飞手位置与目标位置分列存放：C02-6 要拿这两个点算大圆距离，合并进 location 就分不开了。
        p.put("pilot", o.hasPilotPosition() ? ewkt(o.pilotLongitude(), o.pilotLatitude()) : null);
        p.put("class_source", o.classSource());
        jdbc.update("INSERT INTO source_observation (observation_id,inbox_id,source_id,device_id,source_type,source_session_key,external_target_id,external_track_id,"
                + "observed_at,received_at,location,position_accuracy_m,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,class_code,class_confidence,identity_clue,"
                + "identity_confidence,latency_ms,quality,source_mode,owner_org_id,district_id,created_at,pilot_location,class_source) VALUES (:id,:inbox,:source,:device,"
                + ":type,:session,:external,:external_track,:observed,:received,CAST(:location AS GEOMETRY),:accuracy,:amsl,:agl,:speed,:heading,:class_code,:class_conf,"
                + ":identity,:identity_conf,:latency,CAST(:quality AS JSON),:mode,:org,:district,:created,CAST(:pilot AS GEOMETRY),:class_source)", p);
    }

    public long countByInbox(String inboxId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM source_observation WHERE inbox_id=:id", Map.of("id", inboxId), Long.class);
        return count == null ? 0 : count;
    }

    static String ewkt(double longitude, double latitude) { return "SRID=4326;POINT(" + longitude + " " + latitude + ")"; }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("观测 quality 无法序列化", ex); }
    }
}
