package com.uav.lowaltitude.modules.prediction.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.TargetRecognitionSql;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Config;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Measurement;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.AirspaceVersion;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.GeoPoint;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.Polygon;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/** Trusted current MEAS inputs. No forecast, interpolated point or historical replay can renew evidence. */
@Repository
public class PredictionInputRepository {
    private static final int MAX_WINDOW_POINTS = 2_000;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SimulationPolicy simulation;
    private final boolean postgis, realtimeSimulation;

    public PredictionInputRepository(JdbcTemplate jdbc, DataSource source, ObjectMapper json, SimulationPolicy simulation, Environment environment) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
        this.simulation = simulation;
        try (var connection = source.getConnection()) {
            this.postgis = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        } catch (SQLException ex) { throw new IllegalStateException("Cannot inspect prediction spatial backend", ex); }
        this.realtimeSimulation = simulation.allowed() && environment.acceptsProfiles(Profiles.of("local & !prod & !production"))
                && environment.getProperty("app.flight-device-check.simulator-device-bridge-enabled", Boolean.class, false);
    }

    public record Target(String id, String no, String orgId, String districtId, String sourceMode, boolean simulated) { }
    public record Inputs(Target target, List<Measurement> points, String continuityKey, String invalidReason,
            List<AirspaceVersion> airspaces, Map<String, String> airspaceNames, Set<String> actuallyInside,
            Set<String> actuallyOutside, List<String> evidenceNotes) {
        public Inputs {
            points = List.copyOf(points); airspaces = List.copyOf(airspaces); airspaceNames = Map.copyOf(airspaceNames);
            actuallyInside = Set.copyOf(actuallyInside); actuallyOutside = Set.copyOf(actuallyOutside); evidenceNotes = List.copyOf(evidenceNotes);
        }
    }
    private record Header(Target target, String status, String type, String unknown, boolean determined, boolean aliased,
            String selectionConfig, Long observedAt, Long receivedAt) { }
    private record Sample(Measurement measurement, String trackId, String configVersion, String observationId,
            String positionSourceId, String contributing, boolean latestMatches, boolean conflictingPeer) { }
    private record SourceEvidence(String id, String sourceId, long observedAt, long receivedAt, boolean scopeValid,
            boolean simulated, boolean registeredRealtime, String quality, Double accuracyM, String protocol) { }
    private record AirspaceData(List<AirspaceVersion> versions, Map<String, String> names, Set<String> inside, Set<String> outside) { }

    public Inputs load(String targetId, long now, Config config) {
        if (!postgis) return unavailable(null, "POSTGIS_REQUIRED", List.of(), "");
        Header header = header(targetId);
        if (header == null) return unavailable(null, "TARGET_UNAVAILABLE", List.of(), "");
        Target target = header.target();
        String continuity = String.join("|", target.id(), String.valueOf(target.orgId()), String.valueOf(target.districtId()), target.sourceMode());
        if (target.orgId() == null || target.districtId() == null) return unavailable(target, "TARGET_SCOPE_UNKNOWN", List.of(), continuity);
        if (header.aliased() || Set.of("MERGE", "SPLIT", "TERMINATED").contains(header.status() == null ? "" : header.status()))
            return unavailable(target, "TARGET_IDENTITY_NOT_CURRENT", List.of(), continuity);
        if (!"UAV".equals(header.type())) return unavailable(target, "CURRENT_UAV_CLASS_REQUIRED", List.of(), continuity);
        if (!"live".equals(target.sourceMode()) && !"replay".equals(target.sourceMode()))
            return unavailable(target, "CURRENT_SOURCE_MODE_UNSUPPORTED", List.of(), continuity);
        if ("replay".equals(target.sourceMode()) && !realtimeSimulation)
            return unavailable(target, "REALTIME_SIMULATION_DISABLED", List.of(), continuity);
        if (!header.determined() || positionUnknown(header.unknown()))
            return unavailable(target, "CURRENT_POSITION_UNRELIABLE", List.of(), continuity);
        if (header.observedAt() == null || header.receivedAt() == null || header.observedAt() > now || header.receivedAt() > now
                || header.receivedAt() < header.observedAt() || now - header.observedAt() > config.freshnessMillis())
            return unavailable(target, "STALE_OR_UNTRUSTED_MEASUREMENT", List.of(), continuity);
        List<Sample> samples = samples(targetId, now, config);
        if (samples.isEmpty()) return unavailable(target, "NO_CURRENT_MEASUREMENT", List.of(), continuity);
        if (samples.size() > MAX_WINDOW_POINTS) return unavailable(target, "MEASUREMENT_WINDOW_LIMIT_EXCEEDED", List.of(), continuity);
        if (samples.stream().map(Sample::trackId).distinct().count() != 1)
            return unavailable(target, "MULTIPLE_ACTIVE_TRACKS", List.of(), continuity);
        Sample anchor = samples.get(samples.size() - 1);
        if (!anchor.latestMatches() || samples.stream().anyMatch(Sample::conflictingPeer))
            return unavailable(target, "CURRENT_MEASUREMENT_CONFLICT", List.of(), continuity);
        if (anchor.configVersion() == null || header.selectionConfig() == null)
            return unavailable(target, "FUSION_CONFIG_UNKNOWN", List.of(), continuity);
        List<Measurement> points = samples.stream().map(Sample::measurement).toList();
        Set<String> notes = new LinkedHashSet<>();
        notes.add("FUSION_TRACK_CONFIG:" + anchor.configVersion());
        notes.add("FUSION_CURRENT_CONFIG:" + header.selectionConfig());
        notes.add("ALTITUDE_ERROR_IS_MODEL_ASSUMPTION");
        Map<String, Map<String, String>> references = new LinkedHashMap<>();
        Set<String> observationIds = new LinkedHashSet<>();
        try {
            for (Sample sample : samples) {
                Map<String, String> ids = references(sample);
                if (ids.isEmpty() || sample.observationId() == null || sample.positionSourceId() == null)
                    return unavailable(target, "SOURCE_OBSERVATION_MISSING", points, continuity);
                references.put(sample.measurement().pointId(), ids); observationIds.addAll(ids.keySet());
            }
        } catch (IllegalArgumentException ex) { return unavailable(target, "SOURCE_CONTRIBUTION_INVALID", points, continuity); }
        Map<String, SourceEvidence> evidence = evidence(target, observationIds);
        boolean simulated = target.simulated();
        for (Sample sample : samples) {
            Measurement p = sample.measurement();
            if (!Double.isFinite(p.accuracyM()) || p.accuracyM() <= 0 || p.accuracyM() > config.maximumAccuracyM())
                return unavailable(target, "POSITION_ACCURACY_UNSUITABLE", points, continuity);
            for (var reference : references.get(p.pointId()).entrySet()) {
                String id = reference.getKey();
                SourceEvidence source = evidence.get(id);
                if (source == null || !source.scopeValid() || !source.sourceId().equals(reference.getValue())
                        || source.observedAt() == Long.MIN_VALUE || source.receivedAt() == Long.MIN_VALUE
                        || source.observedAt() > p.observedAt() || source.receivedAt() > p.receivedAt()
                        || source.receivedAt() < source.observedAt() || p.observedAt() - source.observedAt() > config.freshnessMillis()
                        || source.receivedAt() - source.observedAt() > config.freshnessMillis()
                        || sample == anchor && now - source.observedAt() > config.freshnessMillis()
                        || id.equals(sample.observationId()) && !source.sourceId().equals(sample.positionSourceId()))
                    return unavailable(target, "SOURCE_SCOPE_OR_TIME_INVALID", points, continuity);
                JsonNode quality = object(source.quality());
                if (quality == null || quality.path("time_untrusted").asBoolean(false)
                        || quality.path("identity_conflict").asBoolean(false) || quality.path("class_association_ambiguous").asBoolean(false)
                        || quality.hasNonNull("eo_fusion_exclusion"))
                    return unavailable(target, "SOURCE_QUALITY_UNRELIABLE", points, continuity);
                if (quality.path("accuracy_defaulted").asBoolean(false)) {
                    notes.add("ACCURACY_DEFAULTED");
                    if ("SIM_NORMALIZED".equals(source.protocol()))
                        notes.add("SIMULATOR_DEFAULT_CONFIGURATION_NOT_HISTORICALLY_FROZEN");
                }
                else if (source.accuracyM() == null || !Double.isFinite(source.accuracyM()) || source.accuracyM() <= 0)
                    return unavailable(target, "SOURCE_ACCURACY_UNKNOWN", points, continuity);
                if ("replay".equals(target.sourceMode()) && !source.registeredRealtime())
                    return unavailable(target, "HISTORICAL_REPLAY_NOT_CURRENT", points, continuity);
                simulated |= source.simulated();
            }
        }
        target = new Target(target.id(), target.no(), target.orgId(), target.districtId(), target.sourceMode(), simulated);
        if (simulated && !simulation.allowed()) return unavailable(target, "SIMULATION_DISABLED", points, continuity);
        // This stable membership snapshot changes when an actual source identity/session is added,
        // not whenever the selected position source alternates during normal multi-source fusion.
        // EO links are left out: an EO tracking session follows this target and gets a new session per task,
        // so counting it would restart stability, drop the warning and stop the very EO task that observes it.
        List<String> identities = jdbc.query("""
                SELECT l.source_id,l.source_session_key,l.external_target_id FROM target_source_link l
                JOIN target original ON original.target_id=l.target_id
                LEFT JOIN target_current_alias a ON a.historical_target_id=l.target_id
                WHERE COALESCE(a.current_target_id,l.target_id)=:target
                  AND original.owner_org_id=:org AND original.district_id=:district AND original.source_mode=:mode
                  AND NOT EXISTS(SELECT 1 FROM integration_source eo WHERE eo.source_id=l.source_id AND eo.source_type='EO')
                ORDER BY l.source_id,l.source_session_key,l.external_target_id
                """, scope(target), (rs, n) -> rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3));
        continuity += "|" + anchor.trackId() + "|" + header.selectionConfig() + "|" + String.join(",", identities);
        AirspaceData areas = airspaces(target, anchor.measurement(), now, config);
        return new Inputs(target, points, continuity, null, areas.versions(), areas.names(), areas.inside(), areas.outside(), List.copyOf(notes));
    }

    private Inputs unavailable(Target target, String reason, List<Measurement> points, String continuity) {
        return new Inputs(target, points, continuity, reason, List.of(), Map.of(), Set.of(), Set.of(), List.of());
    }

    private Header header(String id) {
        return jdbc.query("""
                SELECT t.target_id,t.target_no,t.owner_org_id,t.district_id,t.source_mode,ts.status,
                  """ + TargetRecognitionSql.type("t", "sel") + """
                  AS current_type,CAST(ls.unknown_fields AS VARCHAR) unknown_fields,ls.observed_at,ls.received_at,
                  COALESCE(d.determined,FALSE) determined,sel.config_version,
                  EXISTS(SELECT 1 FROM target_current_alias a WHERE a.historical_target_id=t.target_id AND a.current_target_id<>t.target_id) aliased
                FROM target t JOIN target_latest_state ls ON ls.target_id=t.target_id
                LEFT JOIN target_track_status ts ON ts.target_id=t.target_id
                LEFT JOIN target_attribute_selection sel ON sel.target_id=t.target_id
                LEFT JOIN target_degradation d ON d.target_id=t.target_id
                JOIN app_org org ON org.org_id=t.owner_org_id AND org.enabled=TRUE
                JOIN app_district district ON district.district_id=t.district_id AND district.enabled=TRUE
                WHERE t.target_id=:target
                """, Map.of("target", id), (rs, n) -> new Header(new Target(rs.getString("target_id"), rs.getString("target_no"),
                        rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("source_mode"), !"live".equals(rs.getString("source_mode"))),
                        rs.getString("status"), rs.getString("current_type"), rs.getString("unknown_fields"), rs.getBoolean("determined"),
                        rs.getBoolean("aliased"), rs.getString("config_version"), time(rs, "observed_at"), time(rs, "received_at")))
                .stream().findFirst().orElse(null);
    }

    // A frame observed slightly earlier can arrive after the latest one. The fused writer stores it as a history
    // point with a higher point_seq and keeps latest_state, so only newer-seq points observed no earlier than the
    // anchor make the anchor stale; a late one does not mean latest_state moved elsewhere.
    private List<Sample> samples(String target, long now, Config config) {
        return jdbc.query("""
                SELECT p.point_id,p.track_id,tr.config_version,p.observed_at,p.received_at,ST_X(p.location) lon,ST_Y(p.location) lat,
                       p.position_accuracy_m,p.altitude_amsl_m,p.height_agl_m,p.observation_id,p.position_source_id,
                       CAST(p.contributing AS VARCHAR) contributing,
                       (ls.observed_at=p.observed_at AND ls.received_at=p.received_at AND ST_Equals(ls.location,p.location)
                        AND ls.altitude_amsl_m IS NOT DISTINCT FROM p.altitude_amsl_m
                        AND ls.height_agl_m IS NOT DISTINCT FROM p.height_agl_m
                        AND NOT EXISTS(SELECT 1 FROM track_point newer WHERE newer.track_id=p.track_id
                          AND newer.point_seq>p.point_seq AND newer.observed_at>=p.observed_at)) latest_matches,
                       EXISTS(SELECT 1 FROM track other JOIN track_point peer ON peer.track_id=other.track_id
                         WHERE other.target_id=tr.target_id AND other.layer='FUSED' AND peer.observed_at=p.observed_at
                           AND peer.point_id<>p.point_id
                           AND (peer.location IS NULL OR peer.position_accuracy_m IS NULL OR peer.point_kind<>'MEAS'
                             OR NOT ST_DWithin(peer.location::geography,p.location::geography,
                                    LEAST(peer.position_accuracy_m,p.position_accuracy_m)))) conflicting_peer
                FROM track tr JOIN track_point p ON p.track_id=tr.track_id
                JOIN target_latest_state ls ON ls.target_id=tr.target_id
                WHERE tr.target_id=:target AND tr.layer='FUSED' AND tr.ended_at IS NULL
                  AND p.point_kind='MEAS' AND p.location IS NOT NULL
                  AND p.observed_at>=:from AND p.observed_at<=:now
                ORDER BY p.observed_at,p.received_at,p.point_seq,p.point_id
                FETCH FIRST :limit ROWS ONLY
                """, Map.of("target", target, "from", at(now - config.windowMillis()), "now", at(now), "limit", MAX_WINDOW_POINTS + 1),
                (rs, n) -> new Sample(new Measurement(rs.getString("point_id"), requiredTime(rs, "observed_at"), requiredTime(rs, "received_at"),
                        rs.getDouble("lon"), rs.getDouble("lat"), numberOrNaN(rs, "position_accuracy_m"),
                        number(rs, "altitude_amsl_m"), number(rs, "height_agl_m")), rs.getString("track_id"), rs.getString("config_version"),
                        rs.getString("observation_id"), rs.getString("position_source_id"), rs.getString("contributing"),
                        rs.getBoolean("latest_matches"), rs.getBoolean("conflicting_peer")));
    }

    private Map<String, String> references(Sample sample) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (sample.observationId() != null) ids.put(sample.observationId(), sample.positionSourceId());
        JsonNode contributors = tree(sample.contributing());
        if (contributors == null || !contributors.isArray()) throw new IllegalArgumentException("Missing contribution list");
        for (JsonNode item : contributors) {
            String id = item.path("observation_id").asText("");
            String source = item.path("source_id").asText("");
            if (id.isBlank() || source.isBlank() || !item.path("weight").isNumber()
                    || !Double.isFinite(item.path("weight").asDouble()) || item.path("weight").asDouble() < 0)
                throw new IllegalArgumentException("Invalid position contribution");
            if (item.path("weight").asDouble() == 0) continue;
            String previous = ids.putIfAbsent(id, source);
            if (previous != null && !previous.equals(source)) throw new IllegalArgumentException("Conflicting contribution source");
        }
        return ids;
    }

    private Map<String, SourceEvidence> evidence(Target target, Set<String> ids) {
        Map<String, Object> p = scope(target); p.put("ids", ids);
        Map<String, SourceEvidence> out = new HashMap<>();
        for (SourceEvidence row : jdbc.query("""
                SELECT o.observation_id,o.source_id,o.observed_at,o.received_at,o.position_accuracy_m,CAST(o.quality AS VARCHAR) quality,
                       incoming.source AS inbox_source,s.protocol_code,
                       (s.enabled=TRUE AND s.source_mode=:mode AND o.source_mode=:mode AND o.location IS NOT NULL
                        AND d.enabled=TRUE AND d.source_id=o.source_id AND d.source_mode=:mode
                        AND d.owner_org_id=:org AND d.district_id=:district
                        AND o.owner_org_id=:org AND o.district_id=:district
                        AND incoming.source_id=o.source_id
                        AND EXISTS(SELECT 1 FROM target_source_link l JOIN target original ON original.target_id=l.target_id
                          LEFT JOIN target_current_alias a ON a.historical_target_id=l.target_id
                          WHERE l.source_id=o.source_id AND l.source_session_key=o.source_session_key
                            AND l.external_target_id=o.external_target_id AND l.device_id=o.device_id
                            AND COALESCE(a.current_target_id,l.target_id)=:target
                            AND original.owner_org_id=:org AND original.district_id=:district AND original.source_mode=:mode)) scope_valid,
                       (o.source_mode='replay' OR EXISTS(SELECT 1 FROM ops_integration_source os
                         WHERE os.source_code=s.source_code AND os.simulated=TRUE)
                         OR EXISTS(SELECT 1 FROM mqtt_device_binding m JOIN ops_integration_source os ON os.source_id=m.ops_source_id
                           WHERE m.source_id=s.source_id AND os.simulated=TRUE)
                         OR EXISTS(SELECT 1 FROM eo_device_binding e JOIN ops_integration_source os ON os.source_id=e.ops_source_id
                           WHERE e.source_id=s.source_id AND os.simulated=TRUE)) simulated,
                       ((s.protocol_code='SIM_NORMALIZED' AND incoming.source=CONCAT('sim-normalized:',s.source_id)
                          AND EXISTS(SELECT 1 FROM simulator_observation_source r
                            WHERE r.source_id=s.source_id AND r.device_id=o.device_id AND r.owner_org_id=:org AND r.district_id=:district))
                        OR (s.protocol_code='LINGYUN_MQTT_V8_6' AND incoming.source LIKE 'lingyun:%'
                          AND EXISTS(SELECT 1 FROM mqtt_device_binding m JOIN mqtt_broker b ON b.broker_id=m.broker_id
                            JOIN ops_device od ON od.device_id=m.ops_device_id
                            JOIN ops_integration_source os ON os.source_id=m.ops_source_id
                            JOIN device_business_scope bs ON bs.ops_device_id=od.device_id
                            WHERE m.source_id=s.source_id AND m.device_id=o.device_id AND m.source_mode='replay'
                              AND incoming.source=CONCAT('lingyun:',m.device_type_abbr,':',m.device_id)
                              AND b.source_mode='replay' AND b.enabled=TRUE AND b.owner_org_id=:org AND b.district_id=:district
                              AND od.enabled=TRUE AND od.deleted_at IS NULL AND od.simulated=TRUE
                              AND os.enabled=TRUE AND os.simulated=TRUE AND bs.owner_org_id=:org AND bs.district_id=:district))
                        OR (s.protocol_code='EO_EDGE_MQTT_20250826' AND incoming.source LIKE 'eo-edge:%'
                          AND EXISTS(SELECT 1 FROM eo_device_binding e JOIN mqtt_broker b ON b.broker_id=e.broker_id
                            JOIN eo_edge edge ON edge.edge_id=e.edge_id
                            JOIN ops_device od ON od.device_id=e.ops_device_id
                            JOIN ops_integration_source os ON os.source_id=e.ops_source_id
                            JOIN device_business_scope bs ON bs.ops_device_id=od.device_id
                            WHERE e.source_id=s.source_id AND e.device_id=o.device_id AND e.source_mode='replay'
                              AND incoming.source=CONCAT('eo-edge:',e.edge_id) AND b.source_mode='replay' AND b.enabled=TRUE
                              AND edge.owner_org_id=:org AND edge.district_id=:district
                              AND od.enabled=TRUE AND od.deleted_at IS NULL AND od.simulated=TRUE
                              AND os.enabled=TRUE AND os.simulated=TRUE AND bs.owner_org_id=:org AND bs.district_id=:district))) registered_realtime
                FROM source_observation o JOIN integration_source s ON s.source_id=o.source_id
                LEFT JOIN device d ON d.device_id=o.device_id
                LEFT JOIN inbox_message incoming ON incoming.inbox_id=o.inbox_id
                WHERE o.observation_id IN (:ids)
                """, p, (rs, n) -> new SourceEvidence(rs.getString("observation_id"), rs.getString("source_id"),
                        requiredTime(rs, "observed_at"), requiredTime(rs, "received_at"), rs.getBoolean("scope_valid"),
                        rs.getBoolean("simulated"), rs.getBoolean("registered_realtime"), rs.getString("quality"),
                        number(rs, "position_accuracy_m"), rs.getString("protocol_code")))) out.put(row.id(), row);
        return out;
    }

    private AirspaceData airspaces(Target target, Measurement anchor, long now, Config config) {
        Map<String, Object> p = scope(target);
        p.put("now", at(now)); p.put("observed", at(anchor.observedAt()));
        p.put("lon", anchor.longitude()); p.put("lat", anchor.latitude()); p.put("accuracy", anchor.accuracyM());
        p.put("amsl", anchor.altitudeAmslM()); p.put("agl", anchor.heightAglM()); p.put("height_error", config.altitudeModelErrorM());
        p.put("simulation", target.simulated() && simulation.allowed());
        List<AirspaceVersion> versions = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<String>> relations = new LinkedHashMap<>();
        // Read all versions of each visible root before temporal filtering: old episodes need an
        // explicit NOT_EFFECTIVE result, and future version ambiguity must not be hidden by geometry.
        jdbc.query("""
                SELECT a.airspace_id,a.name,av.airspace_version_id,av.valid_from,av.valid_to,av.kind_code,
                  av.min_altitude_m,av.max_altitude_m,av.altitude_datum,
                  CASE WHEN av.boundary IS NOT NULL AND ST_IsValid(av.boundary) AND NOT ST_IsEmpty(av.boundary)
                         THEN ST_AsGeoJSON(av.boundary) END geometry,
                  CASE WHEN av.valid_from>:now OR (av.valid_to IS NOT NULL AND av.valid_to<=:now)
                         THEN 'NOT_EFFECTIVE'
                       WHEN av.kind_code<>'PROHIBITED' THEN 'OUTSIDE'
                       WHEN av.valid_from>:observed OR (av.valid_to IS NOT NULL AND av.valid_to<=:observed) THEN 'UNKNOWN'
                       WHEN av.boundary IS NULL OR NOT ST_IsValid(av.boundary) OR ST_IsEmpty(av.boundary) THEN 'UNKNOWN'
                       WHEN av.min_altitude_m IS NULL OR av.max_altitude_m IS NULL OR av.altitude_datum IS NULL
                         OR CASE WHEN av.altitude_datum='AGL' THEN CAST(:agl AS NUMERIC) ELSE CAST(:amsl AS NUMERIC) END IS NULL THEN 'UNKNOWN'
                       WHEN NOT ST_DWithin(av.boundary::geography,pt.geom::geography,:accuracy)
                         OR CASE WHEN av.altitude_datum='AGL' THEN CAST(:agl AS NUMERIC) ELSE CAST(:amsl AS NUMERIC) END + :height_error < av.min_altitude_m
                         OR CASE WHEN av.altitude_datum='AGL' THEN CAST(:agl AS NUMERIC) ELSE CAST(:amsl AS NUMERIC) END - :height_error > av.max_altitude_m
                         THEN 'OUTSIDE'
                       WHEN ST_Contains(av.boundary,pt.geom) AND ST_Distance(ST_Boundary(av.boundary)::geography,pt.geom::geography)>:accuracy
                         AND CASE WHEN av.altitude_datum='AGL' THEN CAST(:agl AS NUMERIC) ELSE CAST(:amsl AS NUMERIC) END - :height_error > av.min_altitude_m
                         AND CASE WHEN av.altitude_datum='AGL' THEN CAST(:agl AS NUMERIC) ELSE CAST(:amsl AS NUMERIC) END + :height_error < av.max_altitude_m
                         THEN 'INSIDE'
                       ELSE 'UNKNOWN' END actual_relation
                FROM airspace a JOIN airspace_version av ON av.airspace_id=a.airspace_id
                CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(:lon,:lat),4326) geom) pt
                WHERE a.owner_org_id IS NOT NULL AND a.district_id IS NOT NULL
                  AND (a.source_mode='live' OR (:simulation AND a.source_mode IN ('mock','replay')
                    AND a.owner_org_id=:org AND a.district_id=:district))
                  AND EXISTS(SELECT 1 FROM airspace_version prohibited WHERE prohibited.airspace_id=a.airspace_id AND prohibited.kind_code='PROHIBITED')
                ORDER BY a.airspace_id,av.valid_from,av.airspace_version_id
                """, p, (ResultSet rs) -> {
                    String id = rs.getString("airspace_id");
                    names.put(id, rs.getString("name"));
                    String relation = rs.getString("actual_relation");
                    if (!"NOT_EFFECTIVE".equals(relation)) relations.computeIfAbsent(id, key -> new ArrayList<>()).add(relation);
                    else relations.computeIfAbsent(id, key -> new ArrayList<>());
                    versions.add(new AirspaceVersion(id, rs.getString("airspace_version_id"), requiredTime(rs, "valid_from"),
                            time(rs, "valid_to"), rs.getString("kind_code"), number(rs, "min_altitude_m"), number(rs, "max_altitude_m"),
                            rs.getString("altitude_datum"), polygons(rs.getString("geometry"))));
                });
        Set<String> inside = new LinkedHashSet<>(), outside = new LinkedHashSet<>();
        for (var entry : relations.entrySet()) {
            List<String> current = entry.getValue();
            if (current.isEmpty() || current.size() == 1 && "OUTSIDE".equals(current.get(0))) outside.add(entry.getKey());
            if (current.size() == 1 && "INSIDE".equals(current.get(0))) inside.add(entry.getKey());
        }
        return new AirspaceData(List.copyOf(versions), Map.copyOf(names), Set.copyOf(inside), Set.copyOf(outside));
    }

    private List<Polygon> polygons(String geometry) {
        JsonNode root = tree(geometry);
        if (root == null || !root.isObject()) return List.of();
        JsonNode coordinates = root.path("coordinates");
        List<Polygon> polygons = new ArrayList<>();
        try {
            if ("Polygon".equals(root.path("type").asText())) polygons.add(polygon(coordinates));
            else if ("MultiPolygon".equals(root.path("type").asText())) for (JsonNode p : coordinates) polygons.add(polygon(p));
        } catch (IllegalArgumentException ex) { return List.of(); }
        return List.copyOf(polygons);
    }
    private static Polygon polygon(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.isEmpty()) throw new IllegalArgumentException("Polygon rings missing");
        List<List<GeoPoint>> rings = new ArrayList<>();
        for (JsonNode ring : coordinates) {
            List<GeoPoint> points = new ArrayList<>();
            for (JsonNode xy : ring) {
                if (!xy.isArray() || xy.size() < 2 || !xy.get(0).isNumber() || !xy.get(1).isNumber()) throw new IllegalArgumentException("Invalid coordinate");
                points.add(new GeoPoint(xy.get(0).doubleValue(), xy.get(1).doubleValue()));
            }
            rings.add(List.copyOf(points));
        }
        return new Polygon(rings.get(0), rings.subList(1, rings.size()));
    }
    private boolean positionUnknown(String value) {
        JsonNode unknowns = tree(value);
        if (unknowns == null || !unknowns.isArray()) return true;
        for (JsonNode unknown : unknowns) {
            String field = unknown.path("field").asText("");
            if ("location".equals(field) || "observed_at".equals(field)) return true;
        }
        return false;
    }
    private JsonNode object(String value) { JsonNode node = tree(value); return node != null && node.isObject() ? node : null; }
    private JsonNode tree(String value) {
        if (value == null) return null;
        try { JsonNode node = json.readTree(value); return node != null && node.isTextual() ? json.readTree(node.textValue()) : node; }
        catch (java.io.IOException ex) { return null; }
    }
    private static Map<String, Object> scope(Target target) {
        Map<String, Object> out = new HashMap<>();
        out.put("target", target.id()); out.put("org", target.orgId()); out.put("district", target.districtId()); out.put("mode", target.sourceMode());
        return out;
    }
    private static OffsetDateTime at(long value) { return Instant.ofEpochMilli(value).atOffset(ZoneOffset.UTC); }
    private static Long time(ResultSet rs, String field) throws SQLException {
        var value = rs.getTimestamp(field); return value == null ? null : value.toInstant().toEpochMilli();
    }
    private static long requiredTime(ResultSet rs, String field) throws SQLException {
        Long value = time(rs, field); return value == null ? Long.MIN_VALUE : value;
    }
    private static Double number(ResultSet rs, String field) throws SQLException {
        Number value = (Number) rs.getObject(field); return value == null ? null : value.doubleValue();
    }
    private static double numberOrNaN(ResultSet rs, String field) throws SQLException {
        Double value = number(rs, field); return value == null ? Double.NaN : value;
    }
}
