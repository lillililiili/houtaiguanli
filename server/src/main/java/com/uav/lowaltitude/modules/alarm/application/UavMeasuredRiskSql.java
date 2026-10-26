package com.uav.lowaltitude.modules.alarm.application;

import java.sql.Timestamp;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** One measured handoff predicate for lists and action guards. Prediction expiry is not its clock. */
public final class UavMeasuredRiskSql {
    private UavMeasuredRiskSql() { }
    public static final String TOKEN = "__CURRENT_MEASURED_RISK__";

    public static Long freshnessMillis(JdbcTemplate jdbc) {
        var values=jdbc.queryForList("SELECT p.value_text FROM rule_param p JOIN rule_set s ON s.active_version_id=p.rule_set_version_id WHERE p.rule_code='C03' AND p.param_key='fresh_seconds'",String.class);
        if(values.size()!=1)return null;
        try{long seconds=Long.parseLong(values.get(0).trim());return seconds>0?Math.multiplyExact(seconds,1000L):null;}
        catch(IllegalArgumentException|ArithmeticException invalid){return null;}
    }
    public static boolean realtimeSimulation(com.uav.lowaltitude.platform.config.SimulationPolicy policy,org.springframework.core.env.Environment environment){
        return policy.allowed()&&environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local & !prod & !production"))
            &&environment.getProperty("app.flight-device-check.simulator-device-bridge-enabled",Boolean.class,false);
    }
    public static void addTimes(Map<String,Object> parameters,JdbcTemplate jdbc,long now,boolean qa,boolean realtimeSimulation) {
        Long window=freshnessMillis(jdbc);
        parameters.put("measured_configured",window!=null);
        parameters.put("measured_since",new Timestamp(window==null?now:now-window));
        parameters.put("measured_now",new Timestamp(now));
        parameters.put("measured_qa_allowed",qa);
        parameters.put("measured_simulation_allowed",realtimeSimulation);
    }
    public static boolean postgis(JdbcTemplate jdbc) {
        try(var connection=jdbc.getDataSource().getConnection()){return connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres");}
        catch(java.sql.SQLException unavailable){throw new IllegalStateException("Cannot identify spatial database",unavailable);}
    }
    public static String expand(String sql,boolean postgis){return sql.replace(TOKEN,postgis?POSTGIS_PREDICATE:RELATIONAL_PREDICATE);}

    private static String predicate(boolean postgis) {
        return "EXISTS(SELECT 1 FROM target mt JOIN target_latest_state ms ON ms.target_id=mt.target_id"
            +" JOIN prediction_actual_entry_link mx ON mx.target_id=mt.target_id AND mx.airspace_id=c.airspace_id"
            +" JOIN uav_risk_case_member mm ON mm.risk_case_id=c.risk_case_id AND mm.event_id=mx.event_id AND mm.role='OBSERVED'"
            +" JOIN uav_event me ON me.event_id=mx.event_id JOIN alarm ma ON ma.alarm_id=me.alarm_id"
            +" JOIN rule_evaluation mr ON mr.evaluation_id=mx.evaluation_id AND mr.alarm_id=ma.alarm_id"
            +(postgis?SPATIAL_JOINS:"")
            +" WHERE c.target_id=mt.target_id AND mt.owner_org_id=c.owner_org_id AND mt.district_id=c.district_id AND mt.source_mode=c.source_mode"
            +" AND mt.object_type_code='UAV' AND NOT EXISTS(SELECT 1 FROM target_current_alias alias WHERE alias.historical_target_id=mt.target_id)"
            +" AND (c.source_mode='live' OR(c.source_mode='replay' AND :measured_simulation_allowed=TRUE))"
            +" AND ma.target_id=mt.target_id AND ma.owner_org_id=c.owner_org_id AND ma.district_id=c.district_id AND ma.source_mode=c.source_mode"
            +" AND me.owner_org_id=c.owner_org_id AND me.district_id=c.district_id"
            +" AND mr.target_id=mt.target_id AND mr.owner_org_id=c.owner_org_id AND mr.district_id=c.district_id AND mr.source_mode=c.source_mode"
            +" AND mr.mode='ACTIVE' AND mr.legal_status='ILLEGAL' AND (mr.freshness_code='FRESH' OR (mr.freshness_code='REPLAY' AND c.source_mode='replay'))"
            +" AND mr.decision_assurance_code='SUFFICIENT' AND NULLIF(mr.decision_algorithm_version,'') IS NOT NULL"
            +" AND :measured_configured=TRUE AND ms.location IS NOT NULL AND ms.observed_at BETWEEN :measured_since AND :measured_now"
            +" AND ms.received_at BETWEEN ms.observed_at AND :measured_now"
            +" AND mr.observed_at BETWEEN :measured_since AND ms.observed_at AND mr.evaluated_at BETWEEN :measured_since AND :measured_now"
            +" AND mx.observed_at=CAST(EXTRACT(EPOCH FROM mr.observed_at)*1000 AS BIGINT)"
            +" AND NOT EXISTS(SELECT 1 FROM rule_evaluation newer WHERE newer.target_id=mt.target_id AND newer.owner_org_id=c.owner_org_id"
            +" AND newer.district_id=c.district_id AND newer.source_mode=c.source_mode AND newer.mode='ACTIVE'"
            +" AND (newer.evaluated_at>mr.evaluated_at OR(newer.evaluated_at=mr.evaluated_at AND newer.evaluation_id>mr.evaluation_id)))"
            +(postgis?SPATIAL_CURRENT:" AND mr.observed_at=ms.observed_at")+")";
    }
    private static final String SPATIAL_JOINS=" JOIN target_degradation mg ON mg.target_id=mt.target_id AND mg.determined=TRUE"
        +" LEFT JOIN target_track_status mts ON mts.target_id=mt.target_id"
        +" LEFT JOIN target_attribute_selection msel ON msel.target_id=mt.target_id"
        +" JOIN airspace mar ON mar.airspace_id=mx.airspace_id"
        +" JOIN airspace_version mav ON mav.airspace_version_id=mx.airspace_version_id AND mav.airspace_id=mar.airspace_id";
    private static final String SPATIAL_CURRENT=" AND (mar.deleted_at IS NULL OR :measured_now<mar.deleted_at)"
        +" AND COALESCE(mts.status,'') NOT IN ('MERGE','SPLIT','TERMINATED','SHORT_LOST')"
        +" AND "+com.uav.lowaltitude.modules.fusion.infrastructure.TargetRecognitionSql.type("mt","msel")+"='UAV'"
        +" AND jsonb_typeof(ms.unknown_fields::jsonb)='array' AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(ms.unknown_fields::jsonb) uf WHERE uf->>'field' IN ('location','observed_at'))"
        +" AND (mar.source_mode='live' OR (c.source_mode IN ('mock','replay') AND mar.source_mode IN ('mock','replay') AND mar.owner_org_id=c.owner_org_id AND mar.district_id=c.district_id))"
        +" AND mav.kind_code='PROHIBITED' AND mav.valid_from<=mr.observed_at AND (mav.valid_to IS NULL OR :measured_now<mav.valid_to)"
        +" AND NOT EXISTS(SELECT 1 FROM airspace_version other WHERE other.airspace_id=mar.airspace_id AND other.airspace_version_id<>mav.airspace_version_id"
        +" AND other.valid_from<=:measured_now AND (other.valid_to IS NULL OR :measured_now<other.valid_to))"
        +" AND ST_IsValid(mav.boundary) AND NOT ST_IsEmpty(mav.boundary) AND ST_Contains(mav.boundary,ms.location)"
        +" AND CASE mav.altitude_datum WHEN 'AMSL' THEN ms.altitude_amsl_m WHEN 'AGL' THEN ms.height_agl_m END>mav.min_altitude_m"
        +" AND CASE mav.altitude_datum WHEN 'AMSL' THEN ms.altitude_amsl_m WHEN 'AGL' THEN ms.height_agl_m END<mav.max_altitude_m"
        +" AND jsonb_typeof(mr.unknown_reasons::jsonb)='array' AND (mr.unknown_reasons::jsonb-'PILOT_POSITION_UNAVAILABLE')='[]'::jsonb"
        +" AND EXISTS(SELECT 1 FROM track mtr JOIN track_point mp ON mp.track_id=mtr.track_id"
        +" JOIN source_observation mo ON mo.observation_id=mp.observation_id AND mo.source_id=mp.position_source_id"
        +" JOIN integration_source src ON src.source_id=mo.source_id AND src.enabled=TRUE AND src.source_mode=c.source_mode"
        +" JOIN device md ON md.device_id=mo.device_id AND md.source_id=mo.source_id AND md.enabled=TRUE"
        +" JOIN inbox_message mi ON mi.inbox_id=mo.inbox_id AND mi.source_id=mo.source_id"
        +" WHERE mtr.target_id=mt.target_id AND mtr.layer='FUSED' AND mtr.ended_at IS NULL AND mp.observed_at=ms.observed_at AND mp.received_at=ms.received_at"
        +" AND NOT EXISTS(SELECT 1 FROM track_point newer_point WHERE newer_point.track_id=mp.track_id AND newer_point.point_seq>mp.point_seq)"
        +" AND mp.point_kind='MEAS' AND mp.position_accuracy_m>0 AND ST_Equals(mp.location,ms.location)"
        +" AND mp.altitude_amsl_m IS NOT DISTINCT FROM ms.altitude_amsl_m AND mp.height_agl_m IS NOT DISTINCT FROM ms.height_agl_m"
        +" AND mo.owner_org_id=c.owner_org_id AND mo.district_id=c.district_id AND mo.source_mode=c.source_mode"
        +" AND (:measured_qa_allowed=TRUE OR NOT(EXISTS(SELECT 1 FROM ops_integration_source simsrc WHERE simsrc.source_code=src.source_code AND simsrc.simulated=TRUE)"
        +" OR EXISTS(SELECT 1 FROM mqtt_device_binding smb JOIN ops_integration_source simsrc ON simsrc.source_id=smb.ops_source_id WHERE smb.source_id=src.source_id AND simsrc.simulated=TRUE)"
        +" OR EXISTS(SELECT 1 FROM eo_device_binding seb JOIN ops_integration_source simsrc ON simsrc.source_id=seb.ops_source_id WHERE seb.source_id=src.source_id AND simsrc.simulated=TRUE)))"
        +" AND md.owner_org_id=c.owner_org_id AND md.district_id=c.district_id AND md.source_mode=c.source_mode"
        +" AND mo.observed_at BETWEEN :measured_since AND mp.observed_at AND mo.received_at BETWEEN mo.observed_at AND mp.received_at"
        +" AND jsonb_typeof(mo.quality::jsonb)='object' AND (mo.position_accuracy_m>0 OR mo.quality::jsonb->>'accuracy_defaulted'='true')"
        +" AND COALESCE(mo.quality::jsonb->>'time_untrusted','false')='false' AND COALESCE(mo.quality::jsonb->>'identity_conflict','false')='false'"
        +" AND COALESCE(mo.quality::jsonb->>'class_association_ambiguous','false')='false' AND NOT jsonb_exists(mo.quality::jsonb,'eo_fusion_exclusion')"
        +" AND EXISTS(SELECT 1 FROM target_source_link ml WHERE ml.target_id=mt.target_id AND ml.source_id=mo.source_id AND ml.device_id=mo.device_id"
        +" AND ml.source_session_key=mo.source_session_key AND ml.external_target_id=mo.external_target_id)"
        +" AND ST_Distance(ST_Boundary(mav.boundary)::geography,ms.location::geography)>mp.position_accuracy_m"
        +" AND (c.source_mode='live' OR EXISTS(SELECT 1 FROM simulator_observation_source ss WHERE ss.source_id=mo.source_id AND ss.device_id=mo.device_id"
        +" AND ss.owner_org_id=c.owner_org_id AND ss.district_id=c.district_id AND src.protocol_code='SIM_NORMALIZED' AND mi.source=CONCAT('sim-normalized:',src.source_id))"
        +" OR EXISTS(SELECT 1 FROM mqtt_device_binding mb JOIN mqtt_broker broker ON broker.broker_id=mb.broker_id"
        +" JOIN ops_device od ON od.device_id=mb.ops_device_id JOIN ops_integration_source os ON os.source_id=mb.ops_source_id"
        +" JOIN device_business_scope bs ON bs.ops_device_id=od.device_id WHERE mb.source_id=mo.source_id AND mb.device_id=mo.device_id"
        +" AND mb.source_mode='replay' AND mi.source=CONCAT('lingyun:',mb.device_type_abbr,':',mb.device_id) AND src.protocol_code='LINGYUN_MQTT_V8_6'"
        +" AND broker.source_mode='replay' AND broker.enabled=TRUE AND broker.owner_org_id=c.owner_org_id AND broker.district_id=c.district_id"
        +" AND od.enabled=TRUE AND od.deleted_at IS NULL AND od.simulated=TRUE AND os.enabled=TRUE AND os.simulated=TRUE"
        +" AND bs.owner_org_id=c.owner_org_id AND bs.district_id=c.district_id)"
        +" OR EXISTS(SELECT 1 FROM eo_device_binding eb JOIN mqtt_broker broker ON broker.broker_id=eb.broker_id"
        +" JOIN eo_edge edge ON edge.edge_id=eb.edge_id JOIN ops_device od ON od.device_id=eb.ops_device_id"
        +" JOIN ops_integration_source os ON os.source_id=eb.ops_source_id JOIN device_business_scope bs ON bs.ops_device_id=od.device_id"
        +" WHERE eb.source_id=mo.source_id AND eb.device_id=mo.device_id AND eb.source_mode='replay'"
        +" AND src.protocol_code='EO_EDGE_MQTT_20250826' AND mi.source=CONCAT('eo-edge:',eb.edge_id)"
        +" AND broker.source_mode='replay' AND broker.enabled=TRUE AND edge.owner_org_id=c.owner_org_id AND edge.district_id=c.district_id"
        +" AND od.enabled=TRUE AND od.deleted_at IS NULL AND od.simulated=TRUE AND os.enabled=TRUE AND os.simulated=TRUE"
        +" AND bs.owner_org_id=c.owner_org_id AND bs.district_id=c.district_id)))";
    private static final String POSTGIS_PREDICATE=predicate(true);
    private static final String RELATIONAL_PREDICATE=predicate(false);
}
