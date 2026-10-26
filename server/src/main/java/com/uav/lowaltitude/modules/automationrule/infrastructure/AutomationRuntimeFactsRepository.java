package com.uav.lowaltitude.modules.automationrule.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AutomaticCounterNotificationGate;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts.Fact;
import com.uav.lowaltitude.modules.device.infrastructure.ObservationDeviceIdentitySql;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionConfigRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** No writes, no authorization and no device dispatch. Facts use an independent consistent read snapshot. */
@Repository
public class AutomationRuntimeFactsRepository {
    private com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity cases;
    @org.springframework.beans.factory.annotation.Autowired
    public void cases(com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity cases){this.cases=cases;}
    private final NamedParameterJdbcTemplate jdbc;
    private final RuleEngineRepository states;
    private final SpatialFactPort spatial;
    private final ObjectMapper json;
    private final AutomaticCounterNotificationGate notifications;
    private final long maxAgeMs;

    public AutomationRuntimeFactsRepository(JdbcTemplate jdbc, RuleEngineRepository states, SpatialFactPort spatial,
            ObjectMapper json, AutomaticCounterNotificationGate notifications,
            @Value("${app.automation-rules.fact-max-age-ms:30000}") long maxAgeMs) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.states = states;
        this.spatial = spatial;
        this.json = json;
        this.notifications = notifications;
        if (maxAgeMs <= 0 || maxAgeMs > 300_000) throw new IllegalArgumentException("Invalid automation fact age");
        this.maxAgeMs = maxAgeMs;
    }

    /** Revisit existing events while their latest facts remain recent; not only newly observed events. */
    public List<String> candidates(long now, int limit) {
        return candidates(now, limit, "");
    }

    public List<String> candidates(long now, int limit, String afterEventId) {
        return jdbc.queryForList("""
                SELECT candidate.event_id FROM (
                SELECT e.event_id FROM uav_event e
                JOIN alarm a ON a.alarm_id=e.alarm_id AND a.owner_org_id=e.owner_org_id AND a.district_id=e.district_id
                JOIN target t ON t.target_id=a.target_id AND t.owner_org_id=e.owner_org_id
                  AND t.district_id=e.district_id AND t.source_mode=a.source_mode
                JOIN app_org o ON o.org_id=e.owner_org_id AND o.enabled=TRUE
                JOIN app_district d ON d.district_id=e.district_id AND d.enabled=TRUE
                JOIN target_latest_state s ON s.target_id=t.target_id
                WHERE e.state_code<>'FALSE_POSITIVE' AND t.object_type_code='UAV'
                  AND s.observed_at>=:since AND s.observed_at<=:now
                UNION
                SELECT event_id FROM automation_runtime_state WHERE status IN ('PASS','WAITING')
                ) candidate WHERE candidate.event_id>:after
                ORDER BY candidate.event_id FETCH FIRST :limit ROWS ONLY
                """, Map.of("since", at(now - maxAgeMs), "now", at(now), "after", afterEventId == null ? "" : afterEventId,
                        "limit", Math.max(1, Math.min(1000, limit))), String.class);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public AutomationRuntimeFacts read(String eventId, long now) {
        List<Base> bases = jdbc.query("""
                SELECT e.event_id,e.alarm_id,e.owner_org_id,e.district_id,e.state_code,t.target_id,
                  t.source_mode,t.object_type_code,t.uav_sn
                FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id
                  AND a.owner_org_id=e.owner_org_id AND a.district_id=e.district_id
                JOIN target t ON t.target_id=a.target_id AND t.owner_org_id=e.owner_org_id
                  AND t.district_id=e.district_id AND t.source_mode=a.source_mode
                JOIN app_org org ON org.org_id=e.owner_org_id AND org.enabled=TRUE
                JOIN app_district district ON district.district_id=e.district_id AND district.enabled=TRUE
                WHERE e.event_id=:event
                """, Map.of("event", eventId), (r, n) -> new Base(r.getString("event_id"), r.getString("alarm_id"),
                r.getString("owner_org_id"), r.getString("district_id"), r.getString("state_code"),
                r.getString("target_id"), r.getString("source_mode"), r.getString("object_type_code"), r.getString("uav_sn")));
        if (bases.isEmpty()) return null;
        Base b = bases.get(0);
        Map<String, Fact> facts = new LinkedHashMap<>();
        for (String code : List.of("confidence", "freshness", "consistency", "sourceCount", "accuracy", "identity",
                "trackDuration", "counterFreshness", "counterVoiceWait", "device", "receipt", "conflictTask", "riskLevel", "position",
                "riskActive", "disposeFreshness", "eventLink", "sourceKnown")) facts.put(code, unknown("FACT_NOT_AVAILABLE"));
        if (!"UAV".equals(b.objectType)) {
            facts.replaceAll((k, v) -> unknown("TARGET_NOT_EXPLICIT_UAV"));
            return result(b, null, Set.of(), false, facts);
        }
        var s = states.latestState(b.target);
        Long observed = s == null ? null : epoch(s.observedAt());
        String stateEvidence = "target_latest_state:" + b.target + ":" + observed;
        if (observed != null && observed <= now) {
            Fact age = value(BigDecimal.valueOf(now - observed, 3).toPlainString(), observed, stateEvidence);
            facts.put("freshness", age);
            facts.put("counterFreshness", age);
            facts.put("eventLink", value("true", observed, "uav_event:" + b.event + "/alarm:" + b.alarm));
            if (s.classificationConfidence() != null) facts.put("confidence", value(
                    s.classificationConfidence().multiply(BigDecimal.valueOf(100)).toPlainString(), observed, stateEvidence));
        }
        for (String key : List.of("device", "receipt", "conflictTask")) {
            facts.put(key, unknown("REQUIRES_AUTHORIZED_EXECUTION_DEVICE_AND_CURRENT_DISPATCH_CHECK"));
        }
        if (!fresh(observed, now)) {
            for (String key : List.of("sourceCount", "consistency", "identity", "accuracy", "position", "riskActive", "riskLevel",
                    "disposeFreshness", "sourceKnown", "trackDuration")) facts.put(key, unknown("LATEST_OBSERVATION_MISSING_STALE_OR_FUTURE"));
            return result(b, observed, Set.of(), false, facts);
        }
        List<Observation> observations = observations(b, now);
        if (!observations.isEmpty()) {
            Long earliest = observations.stream().map(Observation::observed).min(Long::compareTo).orElseThrow();
            String evidence = observations.stream().map(o -> "source_observation:" + o.id).reduce((a, c) -> a + "," + c).orElseThrow();
            facts.put("sourceKnown", value("true", earliest, evidence));
            boolean allIdentity = observations.stream().allMatch(o -> present(o.identity));
            if (observations.size() >= 2 && allIdentity) {
                boolean consistent = observations.stream().map(Observation::identity).distinct().count() == 1;
                facts.put("consistency", value(Boolean.toString(consistent), earliest, evidence));
            } else facts.put("consistency", unknown("TWO_INDEPENDENT_IDENTITY_SOURCES_REQUIRED"));
            if (present(b.sn) && allIdentity) facts.put("identity", value(Boolean.toString(
                    observations.stream().allMatch(o -> b.sn.equals(o.identity))), earliest, evidence));
            accuracy(b, observations, observed, facts);
        }
        facts.put("sourceCount", independentDeviceCount(b, now));
        facts.put("counterVoiceWait", phoneWait(b, now));
        trackDuration(b, observed, now, facts);
        risk(b, observed, now, facts);
        executionFacts(b, now, facts);
        boolean position = s.longitude() != null && s.latitude() != null
                && s.longitude().abs().compareTo(BigDecimal.valueOf(180)) <= 0
                && s.latitude().abs().compareTo(BigDecimal.valueOf(90)) <= 0;
        Set<String> airspaces = new LinkedHashSet<>();
        boolean known = false;
        if (position) {
            TargetState targetState = new TargetState(b.target, null, b.sn, s.longitude(), s.latitude(), s.altitudeAmslM(),
                    s.heightAglM(), s.speedMps(), s.headingDeg(), s.classificationConfidence(), s.observedAt(), s.receivedAt());
            try {
                known = !spatial.ambiguousEffectiveAirspaceVersion(at(now), b.mode);
                Set<String> scope = new LinkedHashSet<>(jdbc.queryForList(
                        "SELECT airspace_id FROM airspace WHERE owner_org_id=:org AND district_id=:district",
                        params(b), String.class));
                for (var hit : spatial.airspaceHits(targetState, at(now), b.mode)) {
                    if (!scope.contains(hit.airspaceId())) continue;
                    if (hit.validFrom() == null || hit.validFrom().isAfter(at(now))
                            || (hit.validTo() != null && !at(now).isBefore(hit.validTo()))) { known = false; continue; }
                    if (!"COVERS".equals(hit.relation()) && !"DISJOINT".equals(hit.relation())) { known = false; continue; }
                    if (!"COVERS".equals(hit.relation())) continue;
                    BigDecimal altitude = "AGL".equals(hit.altitudeDatum()) ? s.heightAglM()
                            : "AMSL".equals(hit.altitudeDatum()) ? s.altitudeAmslM() : null;
                    if (altitude == null || hit.minAltitudeM() == null || hit.maxAltitudeM() == null) { known = false; continue; }
                    if (altitude.compareTo(hit.minAltitudeM()) >= 0 && altitude.compareTo(hit.maxAltitudeM()) <= 0) airspaces.add(hit.airspaceId());
                }
            } catch (ApiException unavailable) {
                if (!"SPATIAL_BACKEND_UNAVAILABLE".equals(unavailable.getCode())) throw unavailable;
                known = false;
            }
            if (known) facts.put("position", value("true", observed, stateEvidence));
            else facts.put("position", unknown("CURRENT_AIRSPACE_RELATION_UNKNOWN"));
        }
        if(cases!=null&&cases.selectedCase()!=null){
            var selected=cases.cases(b.event);
            if(selected.size()!=1)known=false;
            else if(selected.get(0).airspaceId()!=null)airspaces.retainAll(Set.of(selected.get(0).airspaceId()));
        }
        return result(b, observed, airspaces, known, facts);
    }

    /** Elapsed time is derived from the persisted completion receipt, never from polling or page entry. */
    private Fact phoneWait(Base b, long now) {
        if (notifications.phoneWaitNotRequired(b.event)) return value("NOT_REQUIRED",now,"response_order_or_notification_configuration:"+b.event);
        var p = params(b); p.put("event", b.event); p.put("now", now);
        if(cases!=null){p.put("event",cases.notificationTaskOwner(b.event,"VOICE"));p.put("risk_scope",cases.taskScope(b.event,"VOICE"));}else p.put("risk_scope","");
        var rows = jdbc.query("""
                SELECT r.record_id,r.playback_completed_at FROM uav_auto_voice_task t
                JOIN uav_event_voice_advisory r ON r.record_id=t.delivery_record_id AND r.event_id=t.event_id
                WHERE t.event_id=:event AND t.risk_scope=:risk_scope AND t.status='SIMULATED_PLAYED' AND r.delivery_status='SIMULATED_PLAYED'
                  AND t.answered_at=r.answered_at AND t.playback_completed_at=r.playback_completed_at
                  AND r.answered_at>=0 AND r.playback_completed_at>=r.answered_at
                  AND r.playback_completed_at<=:now
                """, p, (r,n) -> {
            long completed = r.getLong("playback_completed_at");
            // The receipt remains historical evidence; this fact is the duration calculated as of now.
            return value(BigDecimal.valueOf(now-completed, 3).toPlainString(), now,
                    "uav_event_voice_advisory:"+r.getString("record_id")+":"+completed);
        });
        if (rows.size()==1) return rows.get(0);
        // An incomplete, mismatched or orphan receipt cannot become a configuration exception.
        if (rows.isEmpty() && jdbc.queryForObject(
                "SELECT COUNT(*) FROM uav_event_voice_advisory WHERE event_id=:event", p, Integer.class)==0
                && notifications.phoneWaitNotRequired(b.event)) {
            return value("NOT_REQUIRED", now, "notification_configuration:"+b.event);
        }
        return unknown("PHONE_PLAYBACK_COMPLETION_UNAVAILABLE");
    }

    private List<Observation> observations(Base b, long now) {
        Map<String, Object> p = params(b);
        p.put("since", at(now - maxAgeMs)); p.put("now", at(now));
        List<Observation> rows = jdbc.query("""
                SELECT o.observation_id,o.source_id,o.observed_at,o.identity_clue,o.position_accuracy_m
                FROM target_source_link l JOIN source_observation o ON o.source_id=l.source_id
                  AND o.source_session_key=l.source_session_key AND o.external_target_id=l.external_target_id
                JOIN integration_source s ON s.source_id=o.source_id AND s.enabled=TRUE AND s.source_mode=o.source_mode
                WHERE l.target_id=:target AND o.owner_org_id=:org AND o.district_id=:district
                  AND o.source_mode=:mode AND o.observed_at>=:since AND o.observed_at<=:now
                ORDER BY o.observed_at DESC,o.observation_id DESC
                """, p, (r, n) -> new Observation(r.getString("observation_id"), r.getString("source_id"),
                time(r, "observed_at"), r.getString("identity_clue"), r.getBigDecimal("position_accuracy_m")));
        Map<String, Observation> independent = new LinkedHashMap<>();
        rows.forEach(o -> independent.putIfAbsent(o.source, o));
        return new ArrayList<>(independent.values());
    }

    private Fact independentDeviceCount(Base b, long now) {
        Map<String, Object> p = params(b);
        p.put("since", at(now - 60_000)); p.put("now", at(now));
        var rows = jdbc.query("WITH physical_identity AS (" + ObservationDeviceIdentitySql.PHYSICAL_IDENTITIES + """
                ), observation_identity AS (
                  SELECT device_id,source_id,ops_device_id,owner_org_id,district_id,source_mode
                  FROM physical_identity
                  UNION
                  SELECT r.device_id,r.source_id,p.ops_device_id,r.owner_org_id,r.district_id,'replay'
                  FROM simulator_observation_source r
                  JOIN physical_identity p ON p.ops_device_id=r.origin_ops_device_id AND p.simulated=TRUE
                    AND p.owner_org_id=r.owner_org_id AND p.district_id=r.district_id
                  JOIN device f ON f.device_id=r.device_id AND f.source_id=r.source_id
                    AND f.source_mode='replay' AND f.enabled=TRUE
                    AND f.owner_org_id=r.owner_org_id AND f.district_id=r.district_id
                  JOIN integration_source s ON s.source_id=r.source_id AND s.source_mode='replay'
                    AND s.enabled=TRUE AND s.protocol_code='SIM_NORMALIZED'
                ), unambiguous_identity AS (
                  SELECT device_id,source_id,owner_org_id,district_id,source_mode,MIN(ops_device_id) AS ops_device_id
                  FROM observation_identity GROUP BY device_id,source_id,owner_org_id,district_id,source_mode
                  HAVING COUNT(DISTINCT ops_device_id)=1
                )
                SELECT o.observation_id,o.observed_at,i.ops_device_id
                FROM target_source_link l JOIN source_observation o ON o.source_id=l.source_id
                  AND o.device_id=l.device_id AND o.source_session_key=l.source_session_key
                  AND o.external_target_id=l.external_target_id
                JOIN unambiguous_identity i ON i.device_id=o.device_id AND i.source_id=o.source_id
                  AND i.owner_org_id=o.owner_org_id AND i.district_id=o.district_id AND i.source_mode=o.source_mode
                WHERE l.target_id=:target AND o.owner_org_id=:org AND o.district_id=:district
                  AND o.source_mode=:mode AND o.observed_at>=:since AND o.observed_at<=:now
                ORDER BY o.observed_at DESC,o.observation_id DESC
                """, p, (r, n) -> new DeviceObservation(r.getString("observation_id"), r.getString("ops_device_id"), time(r,"observed_at")));
        Map<String, DeviceObservation> devices = new LinkedHashMap<>();
        rows.forEach(o -> devices.putIfAbsent(o.device, o));
        if (devices.isEmpty()) return unknown("NO_CURRENT_VERIFIABLE_DEVICE_OBSERVATION_WITHIN_60_SECONDS");
        long earliest = devices.values().stream().mapToLong(DeviceObservation::observed).min().orElseThrow();
        String evidence = devices.values().stream().map(o -> "ops_device:" + o.device + "/source_observation:" + o.id)
                .reduce((a, c) -> a + "," + c).orElseThrow();
        return value(Integer.toString(devices.size()), earliest, evidence);
    }

    private record DeviceObservation(String id, String device, long observed) { }

    private void accuracy(Base b, List<Observation> observations, Long observed, Map<String, Fact> facts) {
        List<String> sources = jdbc.queryForList("SELECT position_source_id FROM target_attribute_selection WHERE target_id=:target",
                params(b), String.class);
        if (sources.isEmpty() || sources.get(0) == null) return;
        observations.stream().filter(o -> Objects.equals(o.source, sources.get(0)) && Objects.equals(o.observed, observed)
                && o.accuracy != null && o.accuracy.signum() > 0).findFirst().ifPresent(o ->
                facts.put("accuracy", value(o.accuracy.toPlainString(), o.observed, "source_observation:" + o.id)));
    }

    private void risk(Base b, Long observed, long now, Map<String, Fact> facts) {
        List<Risk> rows = jdbc.query("""
                SELECT evaluation_id,observed_at,evaluated_at,freshness_code,grade,legal_status,alarm_id FROM rule_evaluation
                WHERE target_id=:target AND owner_org_id=:org AND district_id=:district AND source_mode=:mode
                  AND mode='ACTIVE' AND subject_kind='TARGET'
                ORDER BY evaluated_at DESC,evaluation_id DESC FETCH FIRST 1 ROWS ONLY
                """, params(b), (r, n) -> new Risk(r.getString("evaluation_id"), time(r,"observed_at"), time(r,"evaluated_at"),
                r.getString("freshness_code"), r.getString("grade"), r.getString("legal_status"), r.getString("alarm_id")));
        if (rows.isEmpty()) return;
        Risk r = rows.get(0);
        // 取最近一次研判，不要求它研判的正是最新那一帧：规则引擎对老目标每 reevaluate-millis（5 秒）才重评一次，
        // 观测却每秒都来，要求逐帧对上时风险等级大半时间读不到，反制规则在通过和等待之间来回跳，
        // 下发前的复查就把已发起的自动反制取消了（2026-10-08 第二批复验）。研判的那一帧仍须在有效期内、不晚于最新观测。
        if (!Objects.equals(r.alarm, b.alarm) || !fresh(r.observed, now) || r.evaluated == null || r.evaluated > now
                || observed == null || r.observed > observed || !"FRESH".equals(r.freshness)) return;
        String evidence = "rule_evaluation:" + r.id;
        facts.put("disposeFreshness", value(BigDecimal.valueOf(now - r.observed, 3).toPlainString(), r.observed, evidence));
        if ("FALSE_POSITIVE".equals(b.state) || "LEGAL".equals(r.legal)) {
            facts.put("riskActive", value("false", r.observed, evidence)); return;
        }
        if (("ILLEGAL".equals(r.legal) || "ABNORMAL".equals(r.legal)) && Set.of("HIGH", "MEDIUM", "LOW").contains(r.grade == null ? "" : r.grade)) {
            facts.put("riskActive", value("true", r.observed, evidence));
            facts.put("riskLevel", value(r.grade, r.observed, evidence));
        }
    }

    private void trackDuration(Base b, Long observed, long now, Map<String, Fact> facts) {
        // RAW observations preserve their source provenance; predicted/bridged points cannot extend continuity.
        List<Track> tracks = jdbc.query("""
                SELECT tr.track_id,c.params FROM track tr JOIN target_source_link l ON l.link_id=tr.link_id AND l.target_id=tr.target_id
                JOIN integration_source src ON src.source_id=l.source_id AND src.enabled=TRUE AND src.source_mode=:mode
                LEFT JOIN fusion_config c ON c.config_version=tr.config_version
                WHERE tr.target_id=:target AND tr.layer='RAW' AND tr.ended_at IS NULL
                ORDER BY tr.started_at DESC,tr.track_id
                """, params(b), (r,n) -> new Track(r.getString("track_id"), FusionConfigRepository.jsonText(r.getObject("params"))));
        Fact longest = null;
        for (Track tr : tracks) {
            Long maxGap = continuityGap(tr.params);
            if (maxGap == null) continue;
            Map<String,Object> p = params(b); p.put("track",tr.id);
            List<Point> points = jdbc.query("""
                    SELECT p.point_id,p.point_seq,p.point_kind,p.observed_at,o.observation_id,o.source_mode,o.owner_org_id,o.district_id
                    FROM track_point p JOIN track tr ON tr.track_id=p.track_id
                    JOIN target_source_link l ON l.link_id=tr.link_id AND l.target_id=tr.target_id
                    LEFT JOIN source_observation o ON o.observation_id=p.observation_id AND o.observed_at=p.observed_at
                      AND o.source_id=l.source_id AND o.source_session_key=l.source_session_key AND o.external_target_id=l.external_target_id
                    WHERE p.track_id=:track ORDER BY p.point_seq DESC FETCH FIRST 10000 ROWS ONLY
                    """, p, (r,n) -> new Point(r.getString("point_id"), r.getLong("point_seq"),r.getString("point_kind"),time(r,"observed_at"),
                    r.getString("observation_id"),r.getString("source_mode"),r.getString("owner_org_id"),r.getString("district_id")));
            if (points.isEmpty() || !Objects.equals(points.get(0).observed, observed)) continue;
            Point newest = points.get(0), prior = null, oldest = null;
            for (Point point : points) {
                if (!"MEAS".equals(point.kind) || point.observation == null || point.observed == null || point.observed > now
                        || !b.mode.equals(point.mode) || !b.org.equals(point.org) || !b.district.equals(point.district)) break;
                if (prior != null && (prior.seq - point.seq != 1 || prior.observed <= point.observed || prior.observed - point.observed > maxGap)) break;
                oldest = point; prior = point;
            }
            if (oldest == null) continue;
            Fact duration = value(BigDecimal.valueOf(newest.observed - oldest.observed,3).toPlainString(), newest.observed,
                    "track:" + tr.id + "/track_point:" + oldest.id + "," + newest.id);
            if (longest == null || new BigDecimal(duration.value()).compareTo(new BigDecimal(longest.value())) > 0) longest = duration;
        }
        if (longest != null) facts.put("trackDuration",longest);
        else facts.put("trackDuration",unknown("NO_CURRENT_CONTIGUOUS_RAW_OBSERVATIONS_WITH_TRACK_CONFIG"));
    }

    private void executionFacts(Base b, long now, Map<String, Fact> facts) {
        Map<String,Object> p = params(b); p.put("event",b.event); p.put("now",at(now));
        String riskCase=cases==null?null:cases.caseId(b.event);p.put("risk_case",riskCase);
        List<String[]> authorizations = jdbc.query("""
                SELECT authorization_id,device_id,channel FROM disposal_authorization
                WHERE subject_kind='UAV_EVENT' AND subject_id=:event AND target_id=:target
                  AND owner_org_id=:org AND district_id=:district AND source_mode=:mode
                  AND action_type='COUNTERMEASURE' AND status IN ('APPROVED','EXECUTING')
                  AND valid_from<=:now AND :now<valid_until
                  AND (CAST(:risk_case AS VARCHAR) IS NULL OR risk_case_id=:risk_case OR (risk_case_id IS NULL
                    AND (SELECT COUNT(*) FROM uav_risk_case_member m JOIN uav_risk_case c ON c.risk_case_id=m.risk_case_id WHERE m.event_id=:event AND c.superseded_by IS NULL)=1))
                """, p, (r,n) -> new String[]{r.getString(1),r.getString(2),r.getString(3)});
        if (authorizations.size() != 1) {
            for (String key : List.of("device","receipt","conflictTask")) facts.put(key,unknown("UNIQUE_CURRENT_COUNTER_AUTHORIZATION_REQUIRED"));
            return;
        }
        String[] a = authorizations.get(0);
        if (a[1] == null || !"COUNTERMEASURE_4CH".equals(a[2])) return;
        p.put("device",a[1]); p.put("authorization",a[0]);
        List<Fact> health = jdbc.query("""
                SELECT s.observed_at,s.connectivity,s.health_code,s.has_alarm,d.enabled,
                  src.enabled AS source_enabled,d.source_mode,src.protocol_code
                FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id
                JOIN device_business_scope bs ON bs.ops_device_id=d.device_id AND bs.owner_org_id=:org AND bs.district_id=:district
                LEFT JOIN ops_integration_source src ON src.source_id=d.source_id AND src.source_mode=d.source_mode
                WHERE d.device_id=:device AND d.source_mode=:mode
                """, p, (r,n) -> {
                    Long observed = r.getObject("observed_at",Long.class);
                    Boolean alarm = r.getObject("has_alarm",Boolean.class);
                    if (!fresh(observed,now) || alarm == null || r.getString("health_code") == null
                            || "UNKNOWN".equals(r.getString("health_code")) || "UNKNOWN".equals(r.getString("connectivity")))
                        return unknown("AUTHORIZED_DEVICE_CURRENT_HEALTH_UNKNOWN");
                    boolean good = r.getBoolean("enabled") && r.getBoolean("source_enabled")
                            && "ONLINE".equals(r.getString("connectivity")) && "GOOD".equals(r.getString("health_code")) && !alarm;
                    return value(Boolean.toString(good),observed,"disposal_authorization:" + a[0] + "/ops_device_state:" + a[1]);
                });
        if (!health.isEmpty()) facts.put("device",health.get(0));
        Long conflicts = jdbc.queryForObject("""
                SELECT COUNT(*) FROM disposal_authorization
                WHERE authorization_id<>:authorization AND status IN ('APPROVED','EXECUTING')
                  AND (target_id=:target OR (device_id=:device AND (subject_kind<>'UAV_EVENT' OR subject_id<>:event)))
                """,p,Long.class);
        facts.put("conflictTask",value(Boolean.toString(conflicts != null && conflicts == 0),now,
                "disposal_authorization:" + a[0] + "/current-conflict-query"));
        facts.put("receipt",unknown("NO_PERSISTED_CURRENT_RECEIPT_CHANNEL_READINESS_EVIDENCE"));
    }

    private Long continuityGap(String config) {
        if (config == null) return null;
        try {
            var gap = json.readTree(config).path("filter").path("max_dt_ms");
            return gap.isIntegralNumber() && gap.longValue() > 0 ? gap.longValue() : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return null; }
    }
    private boolean fresh(Long time, long now) { return time != null && time <= now && now - time <= maxAgeMs; }
    private static boolean present(String text) { return text != null && !text.isBlank(); }
    private static Fact unknown(String reason) { return new Fact(null,null,null,reason); }
    private static Fact value(String value, Long observed, String evidence) { return new Fact(value,observed,evidence,null); }
    private static OffsetDateTime at(long epoch) { return Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.UTC); }
    private static Long epoch(OffsetDateTime at) { return at == null ? null : at.toInstant().toEpochMilli(); }
    private static Long time(ResultSet r, String column) throws SQLException { return epoch(r.getObject(column,OffsetDateTime.class)); }
    private static Map<String,Object> params(Base b) {
        Map<String,Object> p = new LinkedHashMap<>();
        p.put("target",b.target); p.put("org",b.org); p.put("district",b.district); p.put("mode",b.mode); return p;
    }
    private static AutomationRuntimeFacts result(Base b, Long observed, Set<String> airspaces, boolean known, Map<String,Fact> facts) {
        return new AutomationRuntimeFacts(b.event,b.target,b.alarm,b.org,b.district,b.mode,b.state,b.objectType,observed,airspaces,known,facts);
    }
    private record Base(String event,String alarm,String org,String district,String state,String target,String mode,String objectType,String sn) { }
    private record Observation(String id,String source,Long observed,String identity,BigDecimal accuracy) { }
    private record Risk(String id,Long observed,Long evaluated,String freshness,String grade,String legal,String alarm) { }
    private record Track(String id,String params) { }
    private record Point(String id,long seq,String kind,Long observed,String observation,String mode,String org,String district) { }
}
