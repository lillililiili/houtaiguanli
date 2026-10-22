package com.uav.lowaltitude.modules.prediction.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * Links an independently established measured violation to a prediction episode. Neither prediction
 * snapshots nor current mutable airspace geometry can create or substitute the violation evidence.
 */
@Service
public class PredictionActualEntryBridge {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AppClock clock;
    private com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository inputs;
    private PredictionConfiguration configuration;
    @org.springframework.beans.factory.annotation.Autowired
    public void currentInputs(com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository inputs,
            PredictionConfiguration configuration) {
        this.inputs=inputs;this.configuration=configuration;
    }

    public PredictionActualEntryBridge(JdbcTemplate jdbc,ObjectMapper json,AppClock clock) {
        this.jdbc=jdbc;this.json=json;this.clock=clock;
    }

    /** Acquire these before the existing target/group/event locks in alarm merge paths. */
    public void lockTargetEpisodes(String targetId) {
        if(targetId!=null)jdbc.queryForList("""
                SELECT episode_id FROM prediction_episode
                WHERE target_id=? AND active_key IS NOT NULL ORDER BY episode_id FOR UPDATE
                """,targetId);
    }

    @Transactional
    public void linkEvaluation(String evaluationId,String alarmId) {
        if(evaluationId==null||alarmId==null)return;
        var rows=jdbc.query("""
                SELECT r.target_id,r.owner_org_id,r.district_id,r.source_mode,r.observed_at,r.freshness_code,
                       r.hit_details,r.input_snapshot,e.event_id
                FROM rule_evaluation r JOIN alarm a ON a.alarm_id=?
                JOIN uav_event e ON e.alarm_id=a.alarm_id
                WHERE r.evaluation_id=? AND (r.alarm_id IS NULL OR r.alarm_id=a.alarm_id)
                  AND r.mode='ACTIVE' AND r.legal_status='ILLEGAL' AND r.freshness_code IN ('FRESH','REPLAY')
                  AND a.target_id=r.target_id AND a.owner_org_id=r.owner_org_id
                  AND a.district_id=r.district_id AND a.source_mode=r.source_mode
                  AND NOT EXISTS(SELECT 1 FROM prediction_event_link l
                                 WHERE l.event_id=e.event_id AND l.role='PREDICTED')
                """,(r,n)->new Evaluation(r.getString("target_id"),r.getString("owner_org_id"),
                    r.getString("district_id"),r.getString("source_mode"),
                    r.getTimestamp("observed_at")==null?null:r.getTimestamp("observed_at").getTime(),
                    r.getString("hit_details"),r.getString("input_snapshot"),r.getString("event_id"),
                    r.getString("freshness_code")),alarmId,evaluationId);
        if(rows.isEmpty())return;
        Evaluation evaluation=rows.get(0);
        long now=clock.nowMillis();
        boolean realtimeReplay="REPLAY".equals(evaluation.freshness())&&currentReplay(evaluation,now);
        if(!"FRESH".equals(evaluation.freshness())&&!realtimeReplay)return;
        List<Entry> entries=entries(evaluation.hits(),evaluation.snapshot(),evaluation.observedAt(),realtimeReplay);
        if(entries.isEmpty())return;
        lockTargetEpisodes(evaluation.targetId());
        for(Entry entry:entries) {
            // Both merge and backfill invoke the bridge in the same transaction; retain one fact.
            if(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM prediction_actual_entry_link
                    WHERE event_id=? AND evaluation_id=? AND airspace_id=?
                    """,Long.class,evaluation.eventId(),evaluationId,entry.airspaceId())==0)
                jdbc.update("""
                    INSERT INTO prediction_actual_entry_link
                    (event_id,target_id,airspace_id,observed_at,evaluation_id,airspace_version_id)
                    VALUES (?,?,?,?,?,?)
                    """,evaluation.eventId(),evaluation.targetId(),entry.airspaceId(),evaluation.observedAt(),
                    evaluationId,entry.versionId());
            // Historical/recomputed violations remain evidence but never attach to a newer cycle.
            long freshness=configuration==null
                    ? com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Config.defaults().freshnessMillis()
                    : configuration.parameters().forecast().freshnessMillis();
            if(evaluation.observedAt()>now||evaluation.observedAt()<now-freshness)continue;
            var episodes=jdbc.queryForList("""
                    SELECT episode_id FROM prediction_episode
                    WHERE target_id=? AND airspace_id=? AND source_mode=? AND owner_org_id=? AND district_id=?
                      AND active_key IS NOT NULL AND started_at<=?
                      AND status IN ('ACTIVE','UNKNOWN','ENTERED')
                    ORDER BY episode_id
                    """,String.class,evaluation.targetId(),entry.airspaceId(),evaluation.sourceMode(),
                    evaluation.orgId(),evaluation.districtId(),evaluation.observedAt());
            for(String episode:episodes)
                if(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE event_id=? AND episode_id=?",
                        Long.class,evaluation.eventId(),episode)==0)
                    jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES (?,?,'OBSERVED',?)",
                            evaluation.eventId(),episode,now);
        }
    }

    record Entry(String airspaceId,String versionId) { }
    private record Evaluation(String targetId,String orgId,String districtId,String sourceMode,Long observedAt,
                              String hits,String snapshot,String eventId,String freshness) { }

    List<Entry> entries(String hitsJson,String snapshotJson,Long observedAt) {
        return entries(hitsJson,snapshotJson,observedAt,false);
    }
    private List<Entry> entries(String hitsJson,String snapshotJson,Long observedAt,boolean realtimeReplay) {
        if(observedAt==null)return List.of();
        JsonNode snapshot=read(snapshotJson),hits=read(hitsJson),state=snapshot.path("state");
        String freshness=snapshot.path("freshness").asText();
        if(!("FRESH".equals(freshness)||realtimeReplay&&"REPLAY".equals(freshness))||!state.path("observed_at").isIntegralNumber()
                ||state.path("observed_at").longValue()!=observedAt
                ||!state.path("longitude").isNumber()||!state.path("latitude").isNumber()||!hits.isArray())
            return List.of();
        List<Entry> found=new ArrayList<>();
        for(JsonNode hit:hits) {
            if(!"C02-1".equals(field(hit,"rule_code","ruleCode"))
                    ||!"FAIL".equals(field(hit,"result_code","resultCode"))
                    ||!"INSIDE_RESTRICTED_AIRSPACE".equals(field(hit,"reason_code","reasonCode")))continue;
            JsonNode facts=hit.path("facts");
            String airspace=facts.path("airspace_id").asText(""),version=facts.path("airspace_version_id").asText(""),
                    kind=facts.path("kind_code").asText("");
            if(airspace.isBlank()||version.isBlank()||!"PROHIBITED".equals(kind)||!"COVERS".equals(facts.path("relation").asText()))continue;
            boolean frozen=false;
            for(JsonNode relation:snapshot.path("airspace_hits"))
                if(airspace.equals(relation.path("airspace_id").asText())
                        &&version.equals(relation.path("airspace_version_id").asText())
                        &&kind.equals(relation.path("kind_code").asText())
                        &&"COVERS".equals(relation.path("relation").asText())
                        &&relation.path("unknown_reason").asText("").isBlank())frozen=true;
            Entry entry=new Entry(airspace,version);
            if(frozen&&!found.contains(entry))found.add(entry);
        }
        return List.copyOf(found);
    }
    private boolean currentReplay(Evaluation evaluation,long now) {
        if(inputs==null||configuration==null||!"replay".equals(evaluation.sourceMode())||evaluation.observedAt()==null)return false;
        var input=inputs.load(evaluation.targetId(),now,configuration.parameters().forecast());
        if(input==null||input.target()==null||input.invalidReason()!=null)return false;
        var target=input.target();
        if(!target.simulated()||!evaluation.targetId().equals(target.id())||!evaluation.orgId().equals(target.orgId())
                ||!evaluation.districtId().equals(target.districtId())||!"replay".equals(target.sourceMode()))return false;
        var latest=input.points().stream().max(java.util.Comparator.comparingLong(
                com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Measurement::observedAt)).orElse(null);
        return latest!=null&&latest.observedAt()==evaluation.observedAt()&&latest.observedAt()<=now
                &&now-latest.observedAt()<=configuration.parameters().forecast().freshnessMillis();
    }
    private static String field(JsonNode node,String snake,String camel) {
        return node.has(snake)?node.path(snake).asText():node.path(camel).asText();
    }
    private JsonNode read(String value) {
        try {
            JsonNode node=json.readTree(Objects.toString(value,"null"));
            return node!=null&&node.isTextual()?json.readTree(node.textValue()):node==null?json.nullNode():node;
        } catch(JsonProcessingException invalid){return json.nullNode();}
    }
}
