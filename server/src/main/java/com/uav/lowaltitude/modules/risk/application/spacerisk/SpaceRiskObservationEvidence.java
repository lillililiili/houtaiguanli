package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.Trend;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.SpaceObservation;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;

/** 只从同一实测融合点引用的来源观测取数量；轨迹趋势来自当前计划航线的米制距离变化。 */
final class SpaceRiskObservationEvidence {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean localSimulatorPlanBridge;

    SpaceRiskObservationEvidence(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, false);
    }

    SpaceRiskObservationEvidence(NamedParameterJdbcTemplate jdbc, ObjectMapper json, boolean localSimulatorPlanBridge) {
        this.jdbc = jdbc;
        this.json = json;
        this.localSimulatorPlanBridge = localSimulatorPlanBridge;
    }

    List<SpaceObservation> enrich(List<SpaceObservation> rows, int trendMinutes) {
        if (rows.isEmpty()) return rows;
        Map<Anchor, Set<String>> references = references(rows);
        Set<String> ids = new LinkedHashSet<>();
        references.values().forEach(ids::addAll);
        Map<Anchor, Integer> counts = counts(references, ids);
        List<SpaceObservation> result = new ArrayList<>();
        for (SpaceObservation row : rows) {
            String trend = trendMinutes <= 0 || row.routeVersionId() == null ? Trend.UNKNOWN.name() : trend(row, trendMinutes).name();
            result.add(new SpaceObservation(row.targetId(), row.targetNo(), row.subtypeCode(), row.planId(), row.routeVersionId(),
                    row.distanceToRouteM(), row.corridorHalfWidthM(), row.altitudeM(), row.altitudeDatum(), row.routeAltitudeDatum(),
                    counts.get(Anchor.of(row)), trend, row.ownerOrgId(), row.districtId(), row.longitude(), row.latitude(), row.observedAt(), row.receivedAt()));
        }
        return result;
    }

    private Map<Anchor, Set<String>> references(List<SpaceObservation> rows) {
        Map<Anchor, Set<String>> result = new LinkedHashMap<>();
        for (SpaceObservation row : rows) {
            if (row.observedAt() != null && row.receivedAt() != null && row.longitude() != null && row.latitude() != null)
                result.putIfAbsent(Anchor.of(row), new LinkedHashSet<>());
        }
        if (result.isEmpty()) return result;
        List<Anchor> anchors = List.copyOf(result.keySet());
        List<String> values = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        for (int i = 0; i < anchors.size(); i++) {
            Anchor anchor = anchors.get(i);
            values.add("(:index" + i + ",:target" + i + ",CAST(:observed" + i + " AS TIMESTAMP WITH TIME ZONE),CAST(:received" + i
                    + " AS TIMESTAMP WITH TIME ZONE),CAST(:longitude" + i + " AS DOUBLE PRECISION),CAST(:latitude" + i + " AS DOUBLE PRECISION))");
            parameters.put("index" + i, i); parameters.put("target" + i, anchor.target());
            parameters.put("observed" + i, anchor.observed()); parameters.put("received" + i, anchor.received());
            parameters.put("longitude" + i, anchor.longitude()); parameters.put("latitude" + i, anchor.latitude());
        }
        // 锚点来自第一次空间查询，后续融合刷新不能把新一帧数量拼到上一帧的距离/时间上。
        jdbc.query("WITH requested(anchor_id,target_id,observed_at,received_at,longitude,latitude) AS (VALUES " + String.join(",", values) + ")" + """
                SELECT requested.anchor_id,p.observation_id,CAST(p.contributing AS VARCHAR) AS contributing
                FROM requested JOIN track tr ON tr.target_id=requested.target_id
                JOIN track_point p ON p.track_id=tr.track_id
                WHERE p.point_kind='MEAS' AND p.observed_at=requested.observed_at AND p.received_at=requested.received_at
                  AND ST_Equals(p.location,ST_SetSRID(ST_MakePoint(requested.longitude,requested.latitude),4326))
                  AND (tr.layer='FUSED' OR NOT EXISTS(SELECT 1 FROM track f WHERE f.target_id=requested.target_id AND f.layer='FUSED'))
                """, parameters, rs -> {
            Set<String> ids = result.get(anchors.get(rs.getInt("anchor_id")));
            String primary = rs.getString("observation_id");
            JsonNode contributors = read(rs.getString("contributing"));
            if (contributors == null || (contributors.isArray() && contributors.isEmpty())) {
                if (primary != null) ids.add(primary);
            } else if (contributors.isArray()) for (JsonNode contributor : contributors) {
                JsonNode weight = contributor.get("weight");
                String id = contributor.path("observation_id").asText("");
                if (!id.isBlank() && weight != null && weight.isNumber() && weight.decimalValue().signum() > 0) ids.add(id);
            }
        });
        return result;
    }

    private Map<Anchor, Integer> counts(Map<Anchor, Set<String>> references, Set<String> ids) {
        Map<Anchor, Integer> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        Set<String> targets = new LinkedHashSet<>();
        references.keySet().forEach(anchor -> targets.add(anchor.target()));
        Map<String, Map<String, Integer>> byTarget = new HashMap<>();
        // 双重校验目标与原始来源的模式/单位/区域；JSON 由 Java 读取，不把任意 raw payload 当作已校验事实。
        jdbc.query("""
                SELECT t.target_id,o.observation_id,CAST(o.quality AS VARCHAR) AS quality
                FROM source_observation o JOIN target t ON t.source_mode=o.source_mode
                  AND t.owner_org_id=o.owner_org_id AND t.district_id=o.district_id
                WHERE o.observation_id IN (:ids) AND t.target_id IN (:targets)
                """, Map.of("ids", ids, "targets", targets), rs -> {
            String target = rs.getString("target_id"), id = rs.getString("observation_id");
            JsonNode quality = read(rs.getString("quality"));
            JsonNode count = quality == null ? null : quality.get("object_count");
            if (count != null && count.isIntegralNumber() && count.canConvertToInt() && count.intValue() > 0)
                byTarget.computeIfAbsent(target, ignored -> new HashMap<>()).put(id, count.intValue());
        });
        references.forEach((anchor, observations) -> {
            Map<String, Integer> targetCounts = byTarget.getOrDefault(anchor.target(), Map.of());
            Set<Integer> distinct = new LinkedHashSet<>();
            observations.forEach(id -> { if (targetCounts.containsKey(id)) distinct.add(targetCounts.get(id)); });
            // 不把多来源对同一群鸟的数量相加；来源矛盾时保留未知。
            if (distinct.size() == 1) result.put(anchor, distinct.iterator().next());
        });
        return result;
    }

    private Trend trend(SpaceObservation row, int minutes) {
        if (row.receivedAt() == null || row.longitude() == null || row.latitude() == null) return Trend.UNKNOWN;
        List<Distance> distances = jdbc.query("""
                SELECT tr.track_id,p.observed_at,p.position_accuracy_m,
                       ST_Distance(rv.centerline::geography,p.location::geography) AS distance_m
                FROM track tr JOIN target t ON t.target_id=tr.target_id
                JOIN flight_plan plan ON plan.plan_id=:plan
                  AND (plan.source_mode=t.source_mode OR (:local_simulator_bridge
                    AND t.source_mode='replay' AND plan.source_mode='mock' AND plan.source_id='local-flight-plan-simulator'))
                  AND plan.owner_org_id=t.owner_org_id AND plan.district_id=t.district_id
                JOIN route_version rv ON rv.route_version_id=plan.route_version_id
                JOIN track_point p ON p.track_id=tr.track_id
                WHERE t.target_id=:target AND p.point_kind='MEAS' AND p.location IS NOT NULL
                  AND p.observed_at>=:from AND p.observed_at<=:to
                  AND p.received_at<=:received
                  AND EXISTS (SELECT 1 FROM track_point current_point
                    WHERE current_point.track_id=tr.track_id AND current_point.point_kind='MEAS'
                      AND current_point.observed_at=:to AND current_point.received_at=:received
                      AND ST_Equals(current_point.location,ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)))
                  AND (tr.layer='FUSED' OR NOT EXISTS(SELECT 1 FROM track f WHERE f.target_id=t.target_id AND f.layer='FUSED'))
                ORDER BY p.observed_at,p.point_seq
                """, Map.of("plan", row.planId(), "target", row.targetId(), "from", row.observedAt().minusMinutes(minutes), "to", row.observedAt(),
                        "received", row.receivedAt(), "longitude", row.longitude(), "latitude", row.latitude(), "local_simulator_bridge", localSimulatorPlanBridge),
                (rs, ignored) -> new Distance(rs.getString("track_id"), SpaceRiskRepository.time(rs, "observed_at"), rs.getBigDecimal("distance_m"), rs.getBigDecimal("position_accuracy_m")));
        if (distances.size() < 2) return Trend.UNKNOWN;
        if (distances.stream().map(Distance::track).distinct().count() != 1) return Trend.UNKNOWN;
        Map<OffsetDateTime, BigDecimal> sameTime = new HashMap<>();
        for (Distance distance : distances) {
            BigDecimal previous = sameTime.putIfAbsent(distance.at(), distance.distance());
            if (previous != null && previous.compareTo(distance.distance()) != 0) return Trend.UNKNOWN;
            if (distance.accuracy() != null && distance.accuracy().signum() < 0) return Trend.UNKNOWN;
        }
        Distance first = distances.get(0), last = distances.get(distances.size() - 1);
        if (!first.at().isBefore(last.at()) || !last.at().isEqual(row.observedAt())) return Trend.UNKNOWN;
        if (first.distance().compareTo(last.distance()) == 0) return Trend.FLAT;
        if (first.accuracy() == null || last.accuracy() == null) return Trend.UNKNOWN;
        if (first.distance().subtract(first.accuracy()).compareTo(last.distance().add(last.accuracy())) > 0) return Trend.RISING;
        if (first.distance().add(first.accuracy()).compareTo(last.distance().subtract(last.accuracy())) < 0) return Trend.FALLING;
        return Trend.UNKNOWN;
    }

    private JsonNode read(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (java.io.IOException ex) { return null; }
    }

    private record Distance(String track, OffsetDateTime at, BigDecimal distance, BigDecimal accuracy) { }
    private record Anchor(String target, OffsetDateTime observed, OffsetDateTime received, BigDecimal longitude, BigDecimal latitude) {
        static Anchor of(SpaceObservation row) { return new Anchor(row.targetId(), row.observedAt(), row.receivedAt(), row.longitude(), row.latitude()); }
    }
}
