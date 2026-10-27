package com.uav.lowaltitude.modules.handoff.application;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;
import com.uav.lowaltitude.modules.alarm.application.UavMeasuredRiskSql;
import com.uav.lowaltitude.platform.time.AppClock;

/** Independent measured facts for punishment; a risk verification is never a legal verdict. */
@Service
public class MeasuredPunishmentBasis {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final UavRiskCaseIdentity cases;
    private final AppClock clock;
    private final boolean postgis;
    private boolean simulationAllowed,qaAllowed;

    public MeasuredPunishmentBasis(JdbcTemplate jdbc,UavRiskCaseIdentity cases,AppClock clock) {
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.cases=cases;this.clock=clock;
        this.postgis=UavMeasuredRiskSql.postgis(jdbc);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public void simulation(com.uav.lowaltitude.platform.config.SimulationPolicy policy,org.springframework.core.env.Environment env) {
        simulationAllowed=UavMeasuredRiskSql.realtimeSimulation(policy,env);qaAllowed=policy.allowed();
    }
    public record Basis(String riskCaseId,String eventId,String authorizationId,String evaluationId,
                        String airspaceId,String airspaceVersionId,String observationId,String pointId,
                        Long observedAt,Long completedAt,Map<String,Object> facts) { }

    public boolean derived(String eventId) {
        String selected=cases.selectedCase();
        if(eventId!=null&&selected!=null){
            var scoped=jdbc.queryForList("SELECT c.episode_id,a.alarm_type FROM uav_risk_case c"
                    +" JOIN uav_risk_case_member m ON m.risk_case_id=c.risk_case_id AND m.event_id=?"
                    +" JOIN uav_event e ON e.event_id=m.event_id JOIN alarm a ON a.alarm_id=e.alarm_id"
                    +" WHERE c.risk_case_id=? AND c.superseded_by IS NULL AND a.target_id IS NOT DISTINCT FROM c.target_id"
                    +" AND a.owner_org_id=c.owner_org_id AND a.district_id=c.district_id AND a.source_mode=c.source_mode"
                    +" AND e.owner_org_id=c.owner_org_id AND e.district_id=c.district_id"
                    +" AND (c.episode_id IS NOT NULL OR (m.role='OBSERVED' AND ((c.airspace_id IS NULL AND c.root_event_id=e.event_id)"
                    +" OR EXISTS(SELECT 1 FROM prediction_actual_entry_link x"
                    +" WHERE x.event_id=e.event_id AND x.target_id=c.target_id AND x.airspace_id=c.airspace_id))))",eventId,selected);
            // A selected region must actually belong to this event before it can narrow origin.
            // Invalid/foreign selections remain guarded; pure prediction is never a legal fact.
            return scoped.size()!=1||scoped.get(0).get("episode_id")!=null
                    ||"PREDICTED_AIRSPACE_ENTRY".equals(scoped.get(0).get("alarm_type"));
        }
        return eventId!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?"
                +" AND (a.alarm_type='PREDICTED_AIRSPACE_ENTRY' OR EXISTS(SELECT 1 FROM prediction_event_link p WHERE p.event_id=e.event_id)"
                +" OR EXISTS(SELECT 1 FROM uav_risk_case_member m JOIN uav_risk_case c USING(risk_case_id) WHERE m.event_id=e.event_id AND c.episode_id IS NOT NULL))",Long.class,eventId)>0;
    }
    public String scope(String eventId) {
        String selected=cases.selectedCase();
        var ids=jdbc.queryForList("SELECT c.risk_case_id FROM uav_risk_case c JOIN uav_risk_case_member m USING(risk_case_id)"
                +" WHERE m.event_id=? AND c.superseded_by IS NULL ORDER BY c.risk_case_id",String.class,eventId);
        return selected==null?(ids.size()==1?ids.get(0):null):(ids.contains(selected)?selected:null);
    }
    public boolean independentMeasuredEvent(String eventId) {return measured(eventId,scope(eventId),clock.nowMillis(),null,null,null,null)!=null;}

    /** The completion port has already proved the exact successful device-off receipt. */
    public Basis resolve(String eventId,String authorizationId,Long completedAt) {
        return resolveFrozen(eventId,authorizationId,completedAt,null,null);
    }
    public Basis resolveFrozen(String eventId,String authorizationId,Long completedAt,String evaluationId,String pointId) {
        String scope=scope(eventId);
        if(scope==null||authorizationId==null||completedAt==null||completedAt>clock.nowMillis())return null;
        Long matches=jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization a JOIN uav_risk_case c ON c.risk_case_id=a.risk_case_id"
                +" WHERE a.authorization_id=? AND a.risk_case_id=? AND c.superseded_by IS NULL AND a.subject_kind='UAV_EVENT'"
                +" AND a.owner_org_id=c.owner_org_id AND a.district_id=c.district_id AND a.source_mode=c.source_mode"
                +" AND EXISTS(SELECT 1 FROM uav_risk_case_member m WHERE m.risk_case_id=c.risk_case_id AND m.event_id=a.subject_id)"
                +" AND "+com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.PUNISHMENT_COMPLETION
                +" AND COALESCE((SELECT dc.completed_at FROM device_command dc WHERE dc.command_id=a.execution_command_id AND dc.status='SUCCEEDED'),"
                +" (SELECT CAST(EXTRACT(EPOCH FROM MAX(ae.occurred_at))*1000 AS BIGINT) FROM disposal_authorization_event ae WHERE ae.authorization_id=a.authorization_id AND ae.event_kind='COMPLETE'))=?",
                Long.class,authorizationId,scope,completedAt);
        return matches!=null&&matches==1?measured(eventId,scope,completedAt,authorizationId,completedAt,evaluationId,pointId):null;
    }

    private Basis measured(String eventId,String scope,long before,String authorization,Long completedAt,String evaluationId,String pointId) {
        if(!postgis||scope==null)return null; // Spatial proof must be evaluated by PostGIS, never guessed from an H2 fixture.
        var params=new HashMap<String,Object>();params.put("event",eventId);params.put("case",scope);
        params.put("before",new Timestamp(before));params.put("simulation",simulationAllowed);params.put("qa",qaAllowed);
        params.put("for_completion",completedAt!=null);
        params.put("evaluation",evaluationId);params.put("point",pointId);
        var rows=named.queryForList(SQL,params);
        if(rows.isEmpty())return null;
        var row=rows.get(0);
        var frozen=new LinkedHashMap<String,Object>();
        frozen.put("basis_kind","INDEPENDENT_MEASURED_ENTRY");frozen.put("risk_case_id",scope);
        frozen.put("event_id",eventId);frozen.put("authorization_id",authorization);frozen.put("completed_at",completedAt);
        for(var entry:row.entrySet())frozen.put(entry.getKey(),entry.getValue() instanceof Timestamp value?value.getTime():entry.getValue());
        var verification=jdbc.queryForList("SELECT verification_id,conclusion,basis_kind,trigger_mode,created_at,actor_id,automation_run_id"
                +" FROM uav_risk_case_verification WHERE risk_case_id=? ORDER BY case_version DESC FETCH FIRST 1 ROW ONLY",scope);
        if(!verification.isEmpty())frozen.put("risk_verification",verification.get(0));
        frozen.put("risk_verification_is_legal_verdict",false);
        return new Basis(scope,eventId,authorization,(String)row.get("evaluation_id"),(String)row.get("airspace_id"),
                (String)row.get("airspace_version_id"),(String)row.get("observation_id"),(String)row.get("point_id"),
                ((Timestamp)row.get("observed_at")).getTime(),completedAt,Collections.unmodifiableMap(frozen));
    }

    private static final String SQL="""
        SELECT r.evaluation_id,r.rule_set_version_id,r.decision_algorithm_version,r.observed_at,r.evaluated_at,
          x.airspace_id,x.airspace_version_id,p.point_id,o.observation_id,o.source_id,o.device_id,
          p.received_at,ST_X(p.location) AS longitude,ST_Y(p.location) AS latitude,p.altitude_amsl_m,p.height_agl_m,
          p.position_accuracy_m,c.target_id,c.owner_org_id,c.district_id,c.source_mode,
          c.episode_id AS prediction_context_id,(c.source_mode<>'live'
            OR EXISTS(SELECT 1 FROM ops_integration_source s WHERE s.source_code=src.source_code AND s.simulated=TRUE)
            OR EXISTS(SELECT 1 FROM mqtt_device_binding b JOIN ops_integration_source s ON s.source_id=b.ops_source_id WHERE b.source_id=src.source_id AND s.simulated=TRUE)
            OR EXISTS(SELECT 1 FROM eo_device_binding b JOIN ops_integration_source s ON s.source_id=b.ops_source_id WHERE b.source_id=src.source_id AND s.simulated=TRUE)) AS simulated
        FROM uav_risk_case c JOIN uav_event root_event ON root_event.event_id=c.root_event_id
        LEFT JOIN prediction_episode pe ON pe.episode_id=c.episode_id
        JOIN uav_risk_case_member m ON m.risk_case_id=c.risk_case_id AND m.role='OBSERVED' AND m.event_id=:event
        JOIN uav_event e ON e.event_id=m.event_id JOIN alarm a ON a.alarm_id=e.alarm_id
        JOIN prediction_actual_entry_link x ON x.event_id=e.event_id AND x.target_id=c.target_id AND x.airspace_id=c.airspace_id
        JOIN rule_evaluation r ON r.evaluation_id=x.evaluation_id AND r.alarm_id=a.alarm_id
        JOIN airspace_version av ON av.airspace_version_id=x.airspace_version_id AND av.airspace_id=x.airspace_id
        JOIN airspace ar ON ar.airspace_id=av.airspace_id
        JOIN track t ON t.target_id=c.target_id AND t.layer='FUSED'
        JOIN track_point p ON p.track_id=t.track_id AND p.observed_at=r.observed_at AND p.point_kind='MEAS'
        JOIN source_observation o ON o.observation_id=p.observation_id AND o.source_id=p.position_source_id
        JOIN integration_source src ON src.source_id=o.source_id AND src.source_mode=c.source_mode
        JOIN device d ON d.device_id=o.device_id AND d.source_id=o.source_id AND d.source_mode=c.source_mode
        JOIN inbox_message i ON i.inbox_id=o.inbox_id AND i.source_id=o.source_id
        WHERE c.risk_case_id=:case AND c.superseded_by IS NULL
        AND (CAST(:evaluation AS VARCHAR) IS NULL OR r.evaluation_id=:evaluation)
        AND (CAST(:point AS VARCHAR) IS NULL OR p.point_id=:point)
        AND %s='CONFIRMED' AND e.state_code<>'FALSE_POSITIVE'
        AND a.target_id=c.target_id AND a.owner_org_id=c.owner_org_id AND a.district_id=c.district_id AND a.source_mode=c.source_mode
        AND e.owner_org_id=c.owner_org_id AND e.district_id=c.district_id
        AND r.target_id=c.target_id AND r.owner_org_id=c.owner_org_id AND r.district_id=c.district_id AND r.source_mode=c.source_mode
        AND r.mode='ACTIVE' AND r.legal_status='ILLEGAL' AND r.decision_assurance_code='SUFFICIENT'
        AND NULLIF(r.decision_algorithm_version,'') IS NOT NULL
        AND (r.freshness_code='FRESH' OR (r.freshness_code='REPLAY' AND c.source_mode='replay'))
        AND r.observed_at<=:before AND r.evaluated_at BETWEEN r.observed_at AND :before
        AND (:for_completion=FALSE OR r.evaluated_at<:before)
        AND (c.episode_id IS NULL OR (pe.episode_id IS NOT NULL AND CAST(EXTRACT(EPOCH FROM r.observed_at)*1000 AS BIGINT)>=pe.started_at))
        AND x.observed_at=CAST(EXTRACT(EPOCH FROM r.observed_at)*1000 AS BIGINT)
        AND jsonb_typeof(r.unknown_reasons::jsonb)='array' AND (r.unknown_reasons::jsonb-'PILOT_POSITION_UNAVAILABLE')='[]'::jsonb
        AND av.kind_code='PROHIBITED' AND av.valid_from<=r.observed_at AND (av.valid_to IS NULL OR r.observed_at<av.valid_to)
        AND (ar.deleted_at IS NULL OR r.observed_at<ar.deleted_at)
        AND (ar.source_mode='live' OR (c.source_mode IN ('mock','replay') AND ar.source_mode IN ('mock','replay') AND ar.owner_org_id=c.owner_org_id AND ar.district_id=c.district_id))
        AND ST_IsValid(av.boundary) AND NOT ST_IsEmpty(av.boundary) AND ST_Contains(av.boundary,p.location)
        AND CASE av.altitude_datum WHEN 'AMSL' THEN p.altitude_amsl_m WHEN 'AGL' THEN p.height_agl_m END>av.min_altitude_m
        AND CASE av.altitude_datum WHEN 'AMSL' THEN p.altitude_amsl_m WHEN 'AGL' THEN p.height_agl_m END<av.max_altitude_m
        AND p.position_accuracy_m>0 AND ST_Distance(ST_Boundary(av.boundary)::geography,p.location::geography)>p.position_accuracy_m
        AND p.received_at BETWEEN p.observed_at AND r.evaluated_at
        AND o.owner_org_id=c.owner_org_id AND o.district_id=c.district_id AND o.source_mode=c.source_mode
        AND d.owner_org_id=c.owner_org_id AND d.district_id=c.district_id
        AND o.observed_at<=p.observed_at AND o.received_at BETWEEN o.observed_at AND p.received_at
        AND jsonb_typeof(o.quality::jsonb)='object' AND (o.position_accuracy_m>0 OR o.quality::jsonb->>'accuracy_defaulted'='true')
        AND COALESCE(o.quality::jsonb->>'time_untrusted','false')='false'
        AND COALESCE(o.quality::jsonb->>'identity_conflict','false')='false'
        AND COALESCE(o.quality::jsonb->>'class_association_ambiguous','false')='false'
        AND NOT jsonb_exists(o.quality::jsonb,'eo_fusion_exclusion')
        AND (:qa=TRUE OR NOT(EXISTS(SELECT 1 FROM ops_integration_source s WHERE s.source_code=src.source_code AND s.simulated=TRUE)
          OR EXISTS(SELECT 1 FROM mqtt_device_binding b JOIN ops_integration_source s ON s.source_id=b.ops_source_id WHERE b.source_id=src.source_id AND s.simulated=TRUE)
          OR EXISTS(SELECT 1 FROM eo_device_binding b JOIN ops_integration_source s ON s.source_id=b.ops_source_id WHERE b.source_id=src.source_id AND s.simulated=TRUE)))
        AND EXISTS(SELECT 1 FROM target_source_link l WHERE l.target_id=c.target_id AND l.source_id=o.source_id AND l.device_id=o.device_id
          AND l.source_session_key=o.source_session_key AND l.external_target_id=o.external_target_id)
        AND (c.source_mode='live' OR (c.source_mode='replay' AND :simulation=TRUE AND (
          EXISTS(SELECT 1 FROM simulator_observation_source ss WHERE ss.source_id=o.source_id AND ss.device_id=o.device_id
            AND ss.owner_org_id=c.owner_org_id AND ss.district_id=c.district_id AND src.protocol_code='SIM_NORMALIZED' AND i.source=CONCAT('sim-normalized:',src.source_id))
          OR EXISTS(SELECT 1 FROM mqtt_device_binding b JOIN ops_device od ON od.device_id=b.ops_device_id
            JOIN ops_integration_source os ON os.source_id=b.ops_source_id JOIN device_business_scope bs ON bs.ops_device_id=od.device_id
            WHERE b.source_id=o.source_id AND b.device_id=o.device_id AND b.source_mode='replay' AND od.simulated=TRUE AND os.simulated=TRUE
              AND bs.owner_org_id=c.owner_org_id AND bs.district_id=c.district_id AND src.protocol_code='LINGYUN_MQTT_V8_6'
              AND i.source=CONCAT('lingyun:',b.device_type_abbr,':',b.device_id))
          OR EXISTS(SELECT 1 FROM eo_device_binding b JOIN ops_device od ON od.device_id=b.ops_device_id
            JOIN ops_integration_source os ON os.source_id=b.ops_source_id JOIN device_business_scope bs ON bs.ops_device_id=od.device_id
            WHERE b.source_id=o.source_id AND b.device_id=o.device_id AND b.source_mode='replay' AND od.simulated=TRUE AND os.simulated=TRUE
              AND bs.owner_org_id=c.owner_org_id AND bs.district_id=c.district_id AND src.protocol_code='EO_EDGE_MQTT_20250826'
              AND i.source=CONCAT('eo-edge:',b.edge_id)))))
        ORDER BY r.evaluated_at DESC,r.evaluation_id DESC,p.point_seq DESC FETCH FIRST 1 ROW ONLY
        """.formatted(UavRiskCaseIdentity.VERIFICATION);
}
