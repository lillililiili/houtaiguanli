package com.uav.lowaltitude.modules.prediction.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.prediction.domain.*;
import com.uav.lowaltitude.modules.prediction.domain.PredictionStability.*;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository.Inputs;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository.Target;
import com.uav.lowaltitude.platform.number.BusinessNumberService;
import com.uav.lowaltitude.platform.config.RealtimeSchedulingConfig;
import com.uav.lowaltitude.platform.time.AppClock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable per-target outbox consumer. Its row lock also serializes retries and concurrent workers. */
@Service
public class PredictionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PredictionWorker.class);
    public static final String ORIGIN = "PREDICTED_AIRSPACE_ENTRY";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PredictionJobQueue jobs;
    private final PredictionInputRepository inputs;
    private final PredictionConfiguration config;
    private final PredictionResponseService response;
    private final AlarmMergeRepository alarms;
    private final UavEventRepository events;
    private final BusinessNumberService numbers;
    private final AppClock clock;
    private final TransactionTemplate tx;
    private final PredictionEngine engine = new PredictionEngine();
    private final PredictionSpatialEvaluator spatial = new PredictionSpatialEvaluator();
    private final PredictionStability stability = new PredictionStability();

    public PredictionWorker(JdbcTemplate jdbc, ObjectMapper json, PredictionJobQueue jobs,
            PredictionInputRepository inputs, PredictionConfiguration config, PredictionResponseService response,
            AlarmMergeRepository alarms, UavEventRepository events, BusinessNumberService numbers,
            AppClock clock, PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.json=json; this.jobs=jobs; this.inputs=inputs; this.config=config; this.response=response;
        this.alarms=alarms; this.events=events; this.numbers=numbers; this.clock=clock; this.tx=new TransactionTemplate(transactions);
    }

    @Scheduled(scheduler=RealtimeSchedulingConfig.PREDICTION_SCHEDULER,
            fixedDelayString="${app.prediction.poll-ms:1000}", initialDelayString="${app.prediction.initial-delay-ms:5000}")
    public void poll() {
        if (!jobs.enabled()) return;
        long now=clock.nowMillis();
        // Once registered, polling also evaluates expiry and future airspace changes without a fresh message.
        for (String target : jdbc.queryForList("SELECT target_id FROM prediction_job WHERE next_attempt_at<=? ORDER BY next_attempt_at,target_id FETCH FIRST 100 ROWS ONLY", String.class, now)) {
            try { tx.executeWithoutResult(status -> process(target, clock.nowMillis())); }
            catch (RuntimeException failure) {
                LOG.warn("K03 target {} processing failed; durable retry retained: {}",target,failure.getClass().getSimpleName());
                jdbc.update("UPDATE prediction_job SET attempts=attempts+1,next_attempt_at=?,last_error=? WHERE target_id=?",
                        now+1000,"PROCESSING_FAILED:"+failure.getClass().getSimpleName(),target);
                // A failed forecast cannot leave previous authorization usable until the next successful computation.
                try { tx.executeWithoutResult(status -> invalidate(target,now,"PREDICTION_PROCESSING_FAILED")); }
                catch (RuntimeException stopFailure) { LOG.error("K03 safety invalidation failed for {}; retry retained",target,stopFailure); }
            }
        }
    }

    public void process(String targetId,long now) {
        if (jdbc.queryForList("SELECT target_id FROM prediction_job WHERE target_id=? AND next_attempt_at<=? FOR UPDATE SKIP LOCKED",String.class,targetId,now).isEmpty()) return;
        Inputs input=inputs.load(targetId,now,config.parameters().forecast());
        if (input==null || input.target()==null) {
            invalidate(targetId,now,"TARGET_OR_SCOPE_UNAVAILABLE");
            finish(targetId,now);
            return;
        }
        Target target=input.target();
        jdbc.update("INSERT INTO prediction_parameter_version(version_id,parameters_json,created_at) VALUES (?,CAST(? AS JSON),?) ON CONFLICT(version_id) DO NOTHING",
                config.version(),config.serialized(),now);
        PredictionEngine.Result result=input.invalidReason()==null
                ? engine.forecast(input.points(),now,config.parameters().forecast())
                : PredictionEngine.Result.unknown(input.invalidReason());
        var latest=input.points().stream().max(Comparator.comparingLong(PredictionEngine.Measurement::observedAt)).orElse(null);
        if(!result.reliable()) {
            var last=jdbc.queryForList("SELECT s.status,s.reason,s.anchor_point_id,s.rule_version FROM prediction_current c JOIN prediction_snapshot s ON s.prediction_id=c.prediction_id WHERE c.target_id=?",targetId);
            // A direction/fit failure does not erase independently verified, still-fresh actual entry.
            // Repeated unknown forecasts may be coalesced only when no current measured entry needs renewal.
            if(input.actuallyInside().isEmpty() && !last.isEmpty() && "UNKNOWN".equals(last.get(0).get("status")) && result.reason().equals(last.get(0).get("reason"))
                    && config.version().equals(last.get(0).get("rule_version")) && Objects.equals(latest==null?null:latest.pointId(),last.get(0).get("anchor_point_id"))) {
                invalidate(targetId,now,result.reason());
                jdbc.update("UPDATE prediction_job SET processed_at=?,next_attempt_at=?,attempts=0,last_error=NULL WHERE target_id=?",now,now+60_000,targetId);
                return;
            }
        }
        String prediction=UUID.randomUUID().toString();
        long validUntil=result.reliable() ? result.forecast().lastMeasuredAt()+config.parameters().forecast().freshnessMillis() : now;
        Map<String,Object> payload=payload(target,prediction,result,latest,now,validUntil);
        if(!result.reliable()) {
            payload.put("evidence_status","LAST_KNOWN");
            var basis=jdbc.queryForList("SELECT prediction_id,generated_at,based_on_observed_at FROM prediction_snapshot"
                    +" WHERE target_id=? AND owner_org_id=? AND district_id=? AND source_mode=? AND status='READY'"
                    +" ORDER BY generated_at DESC,prediction_id DESC FETCH FIRST 1 ROW ONLY",
                    target.id(),target.orgId(),target.districtId(),target.sourceMode());
            if(!basis.isEmpty()) {
                var last=basis.get(0);
                payload.put("last_evidence_prediction_id",last.get("prediction_id"));
                payload.put("last_evidence_generated_at",last.get("generated_at"));
                payload.put("last_evidence_observed_at",last.get("based_on_observed_at"));
                if(latest==null) payload.put("based_on_observed_at",last.get("based_on_observed_at"));
            }
        }
        payload.put("evidence_notes",input.evidenceNotes());
        var spatialResults=result.reliable()?spatial.evaluate(result.forecast(),input.airspaces()):List.<PredictionSpatialEvaluator.Assessment>of();
        // Display and lifecycle use the same forecast and airspace versions in this transaction.
        // Reuse that calculation; never retain spatial decisions across observations or versions.
        Map<String,PredictionSpatialEvaluator.Assessment> spatialByRegion=new HashMap<>();
        spatialResults.forEach(assessment->spatialByRegion.put(assessment.airspaceId(),assessment));
        payload.put("proximity",spatialResults.stream().filter(a->a.decision()!=Decision.CLEAR).map(a->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("airspace_id",a.airspaceId());item.put("airspace_name",input.airspaceNames().getOrDefault(a.airspaceId(),a.airspaceId()));
            item.put("state",a.decision()==Decision.ENTER?"CONFIRMING":"POSSIBLE");
            item.put("reason_codes",a.reasons());item.put("airspace_version_ids",a.versionIds());
            return item;
        }).toList());
        jdbc.update("""
                INSERT INTO prediction_snapshot(prediction_id,target_id,anchor_point_id,generated_at,based_on_observed_at,valid_until,
                status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,source_mode,simulated)
                VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),?,?,?,?)
                """,prediction,target.id(),latest==null?null:latest.pointId(),now,latest==null?null:latest.observedAt(),validUntil,
                result.reliable()?"READY":"UNKNOWN",result.reason(),PredictionEngine.ALGORITHM_VERSION,config.version(),
                write(Map.of("points",input.points(),"continuity_key",input.continuityKey(),"notes",input.evidenceNotes(),"airspace_versions",input.airspaces())),
                config.serialized(),write(payload),target.orgId(),target.districtId(),target.sourceMode(),target.simulated());
        jdbc.update("INSERT INTO prediction_current(target_id,prediction_id) VALUES (?,?) ON CONFLICT(target_id) DO UPDATE SET prediction_id=EXCLUDED.prediction_id",target.id(),prediction);

        Map<String,Map<String,Object>> open=new LinkedHashMap<>();
        for(var row:jdbc.queryForList("SELECT * FROM prediction_episode WHERE target_id=? AND active_key IS NOT NULL ORDER BY episode_id FOR UPDATE",target.id())) {
            if(!target.orgId().equals(row.get("owner_org_id")) || !target.districtId().equals(row.get("district_id"))
                    || !target.sourceMode().equals(row.get("source_mode"))) {
                long changed=((Number)row.get("version")).longValue()+1;
                jdbc.update("UPDATE prediction_episode SET status='UNKNOWN',version=?,valid_until=?,current_evidence_valid=FALSE,entered_evidence_valid=FALSE,active_key=NULL,closed_at=?,reason='TARGET_DOMAIN_CHANGED' WHERE episode_id=?",
                        changed,now,now,row.get("episode_id"));
                jdbc.update("INSERT INTO prediction_episode_history(history_id,episode_id,prediction_id,version,state,reason,created_at) VALUES (?,?,?,?,'UNKNOWN','TARGET_DOMAIN_CHANGED',?)",
                        UUID.randomUUID().toString(),row.get("episode_id"),row.get("latest_prediction_id"),changed,now);
                pause((String)row.get("episode_id"),"TARGET_DOMAIN_CHANGED",changed);
            } else open.put((String)row.get("airspace_id"),row);
        }
        Set<String> regions=new LinkedHashSet<>(open.keySet());
        input.airspaces().forEach(v->regions.add(v.airspaceId()));
        for(String region:regions) {
            var versions=input.airspaces().stream().filter(v->region.equals(v.airspaceId())).toList();
            var assessment=result.reliable()?spatialByRegion.computeIfAbsent(region,
                    missing->spatial.evaluateForAirspace(missing,result.forecast(),versions))
                    : new PredictionSpatialEvaluator.Assessment(region,Decision.UNKNOWN,null,List.of(),List.of(result.reason()));
            Map<String,Object> previous=open.get(region);
            // A clear region without an ongoing candidate has no lifecycle to maintain.
            if(previous==null && assessment.decision()!=Decision.ENTER) continue;
            if(previous==null && input.actuallyInside().contains(region)) continue;
            advance(target,input,prediction,latest,result,assessment,previous,versions,now,validUntil);
        }
        finish(targetId,now);
    }

    private void advance(Target target,Inputs input,String prediction,PredictionEngine.Measurement latest,
            PredictionEngine.Result result,PredictionSpatialEvaluator.Assessment assessment,Map<String,Object> previous,
            List<PredictionSpatialEvaluator.AirspaceVersion> versions,long now,long validUntil) {
        String region=assessment.airspaceId(),id=previous==null?UUID.randomUUID().toString():(String)previous.get("episode_id");
        State prior=previous==null?State.initial():read(Objects.toString(previous.get("stability_json")),State.class);
        String continuity=input.continuityKey()+"|"+config.version()+"|"+write(versions);
        Decision decision=assessment.decision();
        boolean candidateEntered=previous!=null && previous.get("first_prediction_id")==null && input.actuallyInside().contains(region);
        if(candidateEntered) decision=Decision.UNKNOWN;
        if(decision==Decision.CLEAR && !input.actuallyOutside().contains(region)) decision=Decision.UNKNOWN;
        Transition transition=latest==null?new Transition(stability.unavailable(prior),false,false,false)
                : stability.advance(prior,decision,latest.pointId(),latest.observedAt(),continuity,config.parameters().stability(),
                        input.points().stream().map(PredictionEngine.Measurement::observedAt).toList());
        State state=transition.state();
        if(!result.reliable()) state=stability.unavailable(state);
        boolean entered=previous!=null && previous.get("first_prediction_id")!=null
                && ("ENTERED".equals(previous.get("status")) || input.actuallyInside().contains(region));
        boolean established=state.active();
        boolean endCycle=transition.cleared() || candidateEntered || !prior.active() && state.status()==Status.CLEARED;
        boolean clear=endCycle && !entered;
        if(entered && latest!=null && input.actuallyInside().contains(region))
            validUntil=latest.observedAt()+config.parameters().forecast().freshnessMillis();
        boolean valid=result.reliable() && decision==Decision.ENTER && state.streakDecision()==Decision.ENTER
                && state.streakCount()>=config.parameters().stability().enterCount()
                && state.streakStartedAt()!=null && latest.observedAt()-state.streakStartedAt()>=config.parameters().stability().enterSpanMillis();
        String status=entered?"ENTERED":clear?"CLEARED":!established?"CANDIDATE":valid?"ACTIVE":"UNKNOWN";
        String reason=candidateEntered?"ACTUAL_ENTRY_BEFORE_WARNING":entered?"ACTUAL_ENTRY_OBSERVED":clear?"NEW_MEASUREMENTS_CONFIRM_AVOIDANCE":valid?"STABLE_ENTRY_PREDICTION":
                decision==Decision.CLEAR?"AVOIDANCE_AWAITING_CONFIRMATION":decision==Decision.ENTER?"ENTRY_AWAITING_CONFIRMATION":String.join(",",assessment.reasons());
        if(reason.isBlank()) reason="STABILITY_PENDING";
        long version=previous==null?0:((Number)previous.get("version")).longValue()+1;
        Map<String,Object> warning=new LinkedHashMap<>();
        Map<String,Object> oldWarning=previous==null?Map.of():read(Objects.toString(previous.get("warning_json")),Map.class);
        warning.put("episode_id",id); warning.put("state",publicStatus(status)); warning.put("airspace_id",region);
        warning.put("airspace_name",input.airspaceNames().getOrDefault(region,Objects.toString(oldWarning.get("airspace_name"),region)));
        boolean historicalVersions=assessment.versionIds().isEmpty();
        warning.put("airspace_version_ids",historicalVersions?oldWarning.getOrDefault("airspace_version_ids",List.of()):assessment.versionIds());
        warning.put("airspace_version_id",historicalVersions?oldWarning.get("airspace_version_id"):assessment.versionIds().get(0));
        warning.put("evidence_status",valid?"CURRENT":"LAST_KNOWN");
        warning.put("airspace_basis",historicalVersions?"LAST_KNOWN":"CURRENT_ASSESSMENT");
        warning.put("last_evidence_observed_at",latest==null?(previous==null?null:previous.get("last_observed_at")):latest.observedAt());
        warning.put("reason",reason); warning.put("version",version);
        if(assessment.entrySeconds()!=null && result.reliable()) {
            double second=assessment.entrySeconds();
            warning.put("entry_at",now+Math.round(second*1000));
            var points=result.forecast().points();
            int index=Math.min(29,(int)Math.floor(second)); double ratio=Math.max(0,Math.min(1,second-index));
            var a=points.get(index); var b=points.get(index+1);
            warning.put("entry_location",Map.of("longitude",a.longitude()+(b.longitude()-a.longitude())*ratio,"latitude",a.latitude()+(b.latitude()-a.latitude())*ratio));
        }
        if(previous==null) {
            jdbc.update("""
                    INSERT INTO prediction_episode(episode_id,target_id,airspace_id,active_key,source_mode,owner_org_id,district_id,status,
                    version,latest_prediction_id,first_prediction_id,last_observed_at,valid_until,current_evidence_valid,entered_evidence_valid,
                    reason,started_at,manual_counter_only,stability_json,warning_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE,CAST(? AS JSON),CAST(? AS JSON))
                    """,id,target.id(),region,target.id()+"|"+region,target.sourceMode(),target.orgId(),target.districtId(),status,version,
                    prediction,null,latest==null?null:latest.observedAt(),validUntil,valid,false,reason,now,write(state),write(warning));
        } else jdbc.update("""
                UPDATE prediction_episode SET status=?,version=?,latest_prediction_id=?,last_observed_at=?,valid_until=?,
                current_evidence_valid=?,entered_evidence_valid=?,reason=?,closed_at=?,active_key=?,stability_json=CAST(? AS JSON),warning_json=CAST(? AS JSON)
                WHERE episode_id=?
                """,status,version,prediction,latest==null?previous.get("last_observed_at"):latest.observedAt(),validUntil,valid,
                entered && input.actuallyInside().contains(region),reason,endCycle?now:null,endCycle?null:target.id()+"|"+region,write(state),write(warning),id);
        jdbc.update("INSERT INTO prediction_episode_snapshot(episode_id,prediction_id,created_at) VALUES (?,?,?)",id,prediction,now);
        if(transition.activated() && !entered) {
            jdbc.update("UPDATE prediction_episode SET first_prediction_id=? WHERE episode_id=? AND first_prediction_id IS NULL",prediction,id);
            createAlarm(target,id,prediction,warning,now);
        }
        if(previous==null || !status.equals(previous.get("status")) || !reason.equals(previous.get("reason"))) {
            jdbc.update("INSERT INTO prediction_episode_history(history_id,episode_id,prediction_id,version,state,reason,created_at) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),id,prediction,version,status,reason,now);
        }
        if(entered) linkObserved(target,id,region,now);
        boolean evidence=entered ? input.actuallyInside().contains(region) : valid;
        if((prior.active() || previous!=null && previous.get("first_prediction_id")!=null) && (!evidence || endCycle || validUntil<=now))
            pause(id,reason,version);
    }

    private void createAlarm(Target target,String episode,String prediction,Map<String,Object> warning,long now) {
        String source="k03-prediction-"+target.sourceMode();
        if(!alarms.lockSource(source,target.sourceMode()) || alarms.targetScope(target.id(),target.sourceMode())==null)
            throw new IllegalStateException("Prediction source/scope unavailable");
        if(alarms.findAlarmBySource(source,episode)!=null) return;
        String alarm=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
        var at=Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC);
        alarms.insertAlarm(new AlarmMergeRepository.AlarmInsert(alarm,target.id(),source,episode,ORIGIN,"HIGH",at,at,
                write(Map.of("origin_kind",ORIGIN,"prediction_id",prediction,"episode_id",episode,"prediction",warning)),
                target.sourceMode(),target.orgId(),target.districtId(),numbers.next("ALARM",at.toInstant())));
        if(!events.createForAlarm(event,alarm,"PENDING_VERIFICATION",target.orgId(),target.districtId(),at))
            throw new IllegalStateException("Prediction event creation conflict");
        jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES (?,?,'PREDICTED',?)",event,episode,now);
    }

    /** Normal measured-rule ingestion remains responsible for actual alarms; linkage never creates a violation. */
    private void linkObserved(Target target,String episode,String airspace,long now) {
        // Filled by the actual evaluation bridge, after inspecting that evaluation's frozen spatial facts.
        for(String event:jdbc.queryForList("""
                SELECT DISTINCT e.event_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id
                JOIN prediction_actual_entry_link x ON x.event_id=e.event_id
                WHERE x.target_id=? AND x.airspace_id=? AND x.observed_at>=? AND a.owner_org_id=? AND a.district_id=? AND a.source_mode=?
                AND NOT EXISTS(SELECT 1 FROM prediction_event_link l WHERE l.event_id=e.event_id AND l.episode_id=?)
                """,String.class,target.id(),airspace,now-config.parameters().forecast().freshnessMillis(),target.orgId(),target.districtId(),target.sourceMode(),episode))
            jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES (?,?,'OBSERVED',?) ON CONFLICT DO NOTHING",event,episode,now);
    }
    private void invalidate(String target,long now,String reason) {
        for(var row:jdbc.queryForList("SELECT * FROM prediction_episode WHERE target_id=? AND active_key IS NOT NULL ORDER BY episode_id FOR UPDATE",target)) {
            String id=(String)row.get("episode_id");
            long version=((Number)row.get("version")).longValue()+1;
            String state="ENTERED".equals(row.get("status"))?"ENTERED":"UNKNOWN";
            jdbc.update("UPDATE prediction_episode SET status=?,version=?,valid_until=?,current_evidence_valid=FALSE,entered_evidence_valid=FALSE,reason=?,stability_json=CAST(? AS JSON) WHERE episode_id=?",
                    state,version,now,reason,write(stability.unavailable(read(Objects.toString(row.get("stability_json")),State.class))),id);
            if(!state.equals(row.get("status")) || !reason.equals(row.get("reason")))
                jdbc.update("INSERT INTO prediction_episode_history(history_id,episode_id,prediction_id,version,state,reason,created_at) VALUES (?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(),id,row.get("latest_prediction_id"),version,state,reason,now);
            pause(id,reason,version);
        }
    }
    private void pause(String episode,String reason,long version) {
        for(String event:jdbc.queryForList("SELECT event_id FROM prediction_event_link WHERE episode_id=? AND role='PREDICTED' ORDER BY event_id",String.class,episode))
            response.pause(event,reason,version);
    }
    private void finish(String target,long now) {
        jdbc.update("UPDATE prediction_job SET processed_at=?,next_attempt_at=?,attempts=0,last_error=NULL WHERE target_id=?",now,now+1000,target);
    }
    private Map<String,Object> payload(Target t,String id,PredictionEngine.Result r,PredictionEngine.Measurement latest,long now,long until) {
        Map<String,Object> map=new LinkedHashMap<>();
        map.put("prediction_id",id); map.put("target_id",t.id()); map.put("target_no",t.no()); map.put("version",now);
        map.put("status",r.reliable()?"READY":"UNKNOWN"); map.put("reason_codes",List.of(r.reason()));
        map.put("based_on_observed_at",latest==null?null:latest.observedAt()); map.put("generated_at",now); map.put("valid_until",until);
        map.put("source_mode",t.sourceMode()); map.put("simulated",t.simulated());
        map.put("model_version",PredictionEngine.ALGORITHM_VERSION); map.put("rule_version",config.version());
        if(r.reliable()) {
            var f=r.forecast();
            map.put("anchor",Map.of("location",Map.of("longitude",f.originLongitude(),"latitude",f.originLatitude())));
            map.put("points",f.milestones().stream().map(p->point(p,now)).toList());
            map.put("trajectory",f.points().stream().map(p->point(p,now)).toList());
            map.put("heading_deg",f.quality().headingDeg()); map.put("speed_mps",f.quality().speedMps());
            map.put("quality",f.quality()); map.put("uncertainty_note","估计不确定范围，不代表准确率或已校准置信概率");
        } else map.put("points",List.of());
        return map;
    }
    private Map<String,Object> point(PredictionEngine.Point point,long now) {
        Map<String,Object> map=new LinkedHashMap<>();
        map.put("horizon_seconds",point.seconds()); map.put("predicted_at",now+point.seconds()*1000L);
        map.put("location",Map.of("longitude",point.longitude(),"latitude",point.latitude()));
        map.put("uncertainty_m",point.uncertaintyM());
        if(point.amsl()!=null) { map.put("altitude_m",point.amsl().valueM()); map.put("altitude_datum","AMSL"); }
        else if(point.agl()!=null) { map.put("altitude_m",point.agl().valueM()); map.put("altitude_datum","AGL"); }
        return map;
    }
    public static String publicStatus(String status) { return "CLEARED".equals(status)?"WITHDRAWN":status; }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException(e); } }
}
