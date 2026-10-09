package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionConfigRepository;
import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics;
import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads scoped fused points and their independent measurement lineage, without changing tracking state. */
@Repository
public class ObservationMetricsRepository {
    public static final int MAX_POINTS = 500_000;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FusionConfigRepository configs;
    private final boolean postgres;
    public ObservationMetricsRepository(JdbcTemplate template, ObjectMapper json, FusionConfigRepository configs) {
        this.jdbc = new NamedParameterJdbcTemplate(template); this.json = json; this.configs = configs;
        postgres = Boolean.TRUE.equals(template.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().contains("PostgreSQL")));
    }
    private record Stored(Point point, String org, String district, String contributors) { }
    private record Fact(String id, String source, String mode, String org, String district, String type, long at, Double anomaly) { }

    public Result read(LocalDate from, LocalDate to, long now, ReportingRepository.Scope scope, List<String> modes) {
        Map<String,Policy> policies = new HashMap<>();
        long margin = 0;
        for (var config : configs.listAll()) {
            JsonNode p = tree(config.paramsJson());
            long gap = Math.min(p.path("filter").path("max_dt_ms").asLong(0), p.path("identity").path("short_lost_after_ms").asLong(0));
            double gate = p.path("association").path("gate_sigma").asDouble(0), anomaly = p.path("quality").path("anomaly_zscore").asDouble(0);
            if (gap > 0 && Double.isFinite(gate) && gate > 0 && Double.isFinite(anomaly) && anomaly > 0) {
                policies.put(config.configVersion(), new Policy(config.configVersion(), gap, gate, anomaly, "CONFIRMED".equals(config.schemaStatus())));
                margin = Math.max(margin, gap);
            }
        }
        long start = from.atStartOfDay(ObservationMetrics.ZONE).toInstant().toEpochMilli();
        long end = Math.min(to.plusDays(1).atStartOfDay(ObservationMetrics.ZONE).toInstant().toEpochMilli(), now);
        Map<String,Object> parameters = new HashMap<>();
        parameters.put("start", Instant.ofEpochMilli(start).minusMillis(margin).atOffset(ZoneOffset.UTC));
        parameters.put("end", Instant.ofEpochMilli(end).plusMillis(margin).atOffset(ZoneOffset.UTC));
        parameters.put("modes", modes); parameters.put("user", scope.userId()); parameters.put("org", scope.ownerOrgId());
        String access = " AND t.owner_org_id IS NOT NULL AND t.district_id IS NOT NULL";
        if (scope.ownerOrgId() != null) access += " AND t.owner_org_id=:org";
        if (!scope.allScope()) access += """
             AND EXISTS (SELECT 1 FROM app_user_data_scope ds
               JOIN app_org org ON org.org_id=ds.org_id AND org.enabled=TRUE
               JOIN app_district district ON district.district_id=ds.district_id AND district.enabled=TRUE
               WHERE ds.user_id=:user AND ds.org_id=t.owner_org_id AND ds.district_id=t.district_id)
            """;
        String location = postgres ? "CASE WHEN ST_SRID(p.location)=4326 THEN ST_AsText(p.location) ELSE NULL END" : "CAST(p.location AS VARCHAR)";
        List<Stored> stored = jdbc.query("""
            SELECT t.target_id,t.source_mode,t.owner_org_id,t.district_id,tr.track_id,tr.config_version,
                   p.observed_at,p.point_kind,p.position_accuracy_m,p.observation_id,p.position_source_id,
                   p.source_switched,p.contributing,
            """ + location + " AS position_text FROM target t JOIN track tr ON tr.target_id=t.target_id AND tr.layer='FUSED'"
                + " JOIN track_point p ON p.track_id=tr.track_id WHERE t.object_type_code='UAV' AND t.source_mode IN (:modes)"
                + " AND p.observed_at>=:start AND p.observed_at<=:end"
                + " AND NOT EXISTS (SELECT 1 FROM target_track_status s WHERE s.target_id=t.target_id AND s.status='MERGE')"
                + access + " ORDER BY tr.track_id,p.observed_at,p.point_id FETCH FIRST " + (MAX_POINTS + 1) + " ROWS ONLY", parameters, (r,n) -> {
                    double[] coordinates = coordinates(r.getString("position_text"));
                    Point point = new Point(r.getString("target_id"), r.getString("track_id"), r.getObject("observed_at", OffsetDateTime.class).toInstant().toEpochMilli(),
                            r.getString("point_kind"), coordinates == null ? null : coordinates[0], coordinates == null ? null : coordinates[1],
                            r.getObject("position_accuracy_m") instanceof Number v ? v.doubleValue() : null, r.getString("observation_id"), r.getString("position_source_id"),
                            r.getString("source_mode"), r.getBoolean("source_switched"), policies.get(r.getString("config_version")), null);
                    return new Stored(point, r.getString("owner_org_id"), r.getString("district_id"), FusionConfigRepository.jsonText(r.getObject("contributing")));
                });
        if (stored.size() > MAX_POINTS) return Result.unavailable("轨迹点超过单次统计上限，请缩短统计周期；未返回截断后的合计");
        Set<String> ids = new HashSet<>();
        for (Stored s : stored) {
            if (s.point.observation() != null) ids.add(s.point.observation());
            for (JsonNode c : tree(s.contributors)) if (c.path("weight").asDouble() > 0) ids.add(c.path("observation_id").asText());
        }
        Map<String,Fact> facts = new HashMap<>();
        List<String> all = new ArrayList<>(ids);
        for (int i=0; i<all.size(); i+=500) {
            jdbc.query("SELECT observation_id,source_id,source_mode,owner_org_id,district_id,class_code,observed_at,quality FROM source_observation WHERE observation_id IN (:ids)",
                    Map.of("ids", all.subList(i, Math.min(i+500,all.size()))), r -> {
                        JsonNode q = tree(FusionConfigRepository.jsonText(r.getObject("quality")));
                        Fact f = new Fact(r.getString("observation_id"),r.getString("source_id"),r.getString("source_mode"),r.getString("owner_org_id"),r.getString("district_id"),
                                r.getString("class_code"),r.getObject("observed_at",OffsetDateTime.class).toInstant().toEpochMilli(),
                                q.has("anomaly_z") ? q.path("anomaly_z").asDouble(Double.NaN) : null);
                        facts.put(f.id,f);
                    });
        }
        List<Point> points = new ArrayList<>();
        for (Stored s : stored) {
            Point p = s.point; String reason = null;
            Fact primary = facts.get(p.observation());
            if (!matches(primary,s) || !Objects.equals(primary.source,p.source()) || primary.at != p.at()) reason = "PROVENANCE";
            JsonNode contributions = tree(s.contributors);
            if (!contributions.isArray() || contributions.isEmpty()) reason = "PROVENANCE";
            boolean primaryContributed=false;
            for (JsonNode c : contributions) {
                if (c.path("weight").asDouble() <= 0) continue;
                if (Objects.equals(p.observation(),c.path("observation_id").asText())) primaryContributed=true;
                Fact f = facts.get(c.path("observation_id").asText());
                if (!matches(f,s) || !Objects.equals(f.source,c.path("source_id").asText()) || f.at != p.at()) reason = "PROVENANCE";
                else if (f.anomaly != null && (p.policy() == null || !Double.isFinite(f.anomaly) || f.anomaly > p.policy().anomalyZ())) reason = "ANOMALY";
            }
            if (!primaryContributed) reason="PROVENANCE";
            points.add(new Point(p.target(),p.track(),p.at(),p.kind(),p.lon(),p.lat(),p.accuracy(),p.observation(),p.source(),p.mode(),p.switched(),p.policy(),reason));
        }
        return ObservationMetrics.calculate(points,from,to,now);
    }
    private static boolean matches(Fact f, Stored s) {
        return f != null && Objects.equals(f.mode,s.point.mode()) && Objects.equals(f.org,s.org)
                && Objects.equals(f.district,s.district) && "UAV".equals(f.type);
    }
    private JsonNode tree(String value) {
        try {
            JsonNode parsed=value == null ? json.createObjectNode() : json.readTree(value);
            // H2 CAST(text AS JSON) may store a JSON string; PostgreSQL stores the object/array.
            if (parsed != null && parsed.isTextual()) parsed=json.readTree(parsed.asText());
            return parsed == null ? json.createObjectNode() : parsed;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { return json.createObjectNode(); }
    }
    private static double[] coordinates(String text) {
        if (text == null) return null;
        var match = java.util.regex.Pattern.compile("(?:SRID=4326;)?POINT\\s*\\(\\s*([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s*\\)").matcher(text.trim());
        if (!match.matches()) return null;
        try { return new double[] {Double.parseDouble(match.group(1)),Double.parseDouble(match.group(2))}; }
        catch (NumberFormatException ex) { return null; }
    }
}
