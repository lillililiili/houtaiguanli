package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.domain.NoCounterRules;
import com.uav.lowaltitude.modules.alarm.domain.NoCounterRules.Basis;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Internal scoped-event reads. Mutations require the caller's event lock and write transaction. */
@Repository
public class NoCounterRepository {
    public static final String ACTIVE_REASON = "已人工确认当前无风险并决定不反制，本次处置已结束，继续监测";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DisposalRepository disposal;
    private final EmergencyStopRepository stops;
    private final AppClock clock;
    public NoCounterRepository(JdbcTemplate jdbc, ObjectMapper json, DisposalRepository disposal, EmergencyStopRepository stops, AppClock clock) {
        this.jdbc=jdbc; this.json=json; this.disposal=disposal; this.stops=stops; this.clock=clock;
    }
    public record Evaluation(Basis basis, boolean reliable, String alarmId) { }
    public record FrozenBasis(Basis basis, long reviewFrom, List<String> knownEvaluationIds) { }
    public record Decision(String id, long at, String actorName, String reason, FrozenBasis frozen,
                           String targetId, String orgId, String districtId, String sourceMode) { }
    public record Snapshot(Basis basis, String blockReason, Decision decision, boolean active, boolean reviewRequired, FrozenBasis frozen) { }
    private static final String EVALUATIONS = "SELECT r.* FROM rule_evaluation r JOIN uav_event e ON e.event_id=? JOIN alarm a ON a.alarm_id=e.alarm_id "
            + "WHERE r.target_id=a.target_id AND r.owner_org_id=e.owner_org_id AND r.district_id=e.district_id AND r.source_mode=a.source_mode AND r.mode='ACTIVE' AND r.subject_kind='TARGET' ";
    public Evaluation latest(String eventId) {
        var rows=jdbc.query(EVALUATIONS+"ORDER BY r.evaluated_at DESC,r.evaluation_id DESC FETCH FIRST 1 ROWS ONLY",this::evaluation,eventId);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Evaluation evaluation(ResultSet r, int ignored) throws SQLException {
        List<String> violations=strings(r.getString("violation_reasons"));
        List<String> unknown=strings(r.getString("unknown_reasons"));
        var basis=new Basis(r.getString("evaluation_id"),time(r,"observed_at"),time(r,"evaluated_at"),r.getString("legal_status"),r.getString("grade"),violations==null?List.of():violations);
        return new Evaluation(basis, "FRESH".equals(r.getString("freshness_code")) && "SUFFICIENT".equals(r.getString("decision_assurance_code"))
                && r.getString("decision_algorithm_version")!=null && !r.getString("decision_algorithm_version").isBlank()
                && unknown!=null && unknown.isEmpty() && violations!=null && NoCounterRules.explicit(basis.legalStatus()),r.getString("alarm_id"));
    }
    public Snapshot snapshot(EventRow event) {
        Integer seconds=disposal.freshSeconds();
        long reviewFrom=clock.nowMillis()-(seconds==null||seconds<=0?0:seconds*1000L);
        // One statement freezes both the displayed current basis and every already-seen evaluation ID.
        // Concurrent calculations may have earlier timestamps but commit later; those IDs remain unseen.
        var observed=jdbc.query(EVALUATIONS+"AND r.evaluated_at>=? ORDER BY r.evaluated_at DESC,r.evaluation_id DESC",this::evaluation,event.eventId(),new Timestamp(reviewFrom));
        Evaluation current=observed.isEmpty()?null:observed.get(0);
        Decision decision=decision(event.eventId());
        boolean review=decision!=null && reviewRequired(event,decision);
        boolean active=decision!=null&&!review;
        String reason=active?ACTIVE_REASON:evidenceBlock(event,current);
        if(reason.isEmpty())reason=operationBlock(event.eventId());
        Basis basis=current==null?null:current.basis();
        return new Snapshot(basis,reason,decision,active,review,new FrozenBasis(basis,reviewFrom,observed.stream().map(e->e.basis().evaluationId()).toList()));
    }
    public boolean active(String eventId) {
        Decision decision=decision(eventId);
        if(decision==null)return false;
        var rows=jdbc.query("SELECT e.*,a.target_id,a.source_mode FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?",
                (r,n)->new EventRow(eventId,r.getString("alarm_id"),r.getString("target_id"),r.getString("state_code"),r.getString("owner_org_id"),r.getString("district_id"),null,null,r.getLong("version"),r.getString("source_mode")),eventId);
        return !rows.isEmpty()&&!reviewRequired(rows.get(0),decision);
    }
    public String evidenceBlock(EventRow event, Evaluation current) {
        if(!"CONFIRMED".equals(event.state()))return "请先核实事件属实，再确认本次是否需要反制";
        Integer seconds=disposal.freshSeconds(); long now=clock.nowMillis();
        var observed=jdbc.query("SELECT s.observed_at FROM target t JOIN target_latest_state s ON s.target_id=t.target_id JOIN app_org o ON o.org_id=t.owner_org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=t.district_id AND d.enabled=TRUE WHERE t.target_id=? AND t.owner_org_id=? AND t.district_id=? AND t.source_mode=? AND t.object_type_code='UAV'",(r,n)->time(r,"observed_at"),event.targetId(),event.ownerOrgId(),event.districtId(),event.sourceMode());
        if(observed.isEmpty()||!NoCounterRules.fresh(observed.get(0),now,seconds))return "缺少当前有效无人机观测，不能确认当前无风险";
        if(current==null || !current.reliable() || !NoCounterRules.fresh(current.basis().evaluatedAt(),now,seconds)
                || !NoCounterRules.fresh(current.basis().observedAt(),now,seconds)
                || (current.alarmId()!=null&&!event.alarmId().equals(current.alarmId())))return "缺少同一目标、范围与来源的当前可靠明确研判，不能确认当前无风险";
        return "";
    }
    public String operationBlock(String eventId) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE subject_kind='UAV_EVENT' AND subject_id=? AND action_type IN ('COUNTERMEASURE','JAMMING') AND status IN ('REQUESTED','APPROVED','EXECUTING')",Integer.class,eventId)>0)
            return "存在申请中、已授权或执行中的反制，请先完成或撤销在途处置";
        if(stops.unresolved(eventId))return "设备急停后的实际停机尚未核查，不能结束本次处置";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM device_command c JOIN disposal_authorization a ON a.authorization_id=c.authorization_id WHERE a.subject_kind='UAV_EVENT' AND a.subject_id=? AND a.action_type IN ('COUNTERMEASURE','JAMMING') AND c.status IN ('QUEUED','SENT','ACCEPTED','TIMED_OUT') AND NOT EXISTS (SELECT 1 FROM disposal_emergency_stop_device sd WHERE sd.authorization_id=a.authorization_id AND sd.confirmed_at IS NOT NULL)",Integer.class,eventId)>0)
            return "设备指令仍在途或结果不明确，请先核查实际设备状态";
        return "";
    }
    private boolean reviewRequired(EventRow event, Decision decision) {
        if(!Objects.equals(event.targetId(),decision.targetId())||!Objects.equals(event.ownerOrgId(),decision.orgId())||!Objects.equals(event.districtId(),decision.districtId())||!Objects.equals(event.sourceMode(),decision.sourceMode()))return true;
        // Look at all subsequent reliable observations: a later downgrade cannot erase an already new risk.
        var later=jdbc.query(EVALUATIONS+"AND r.evaluated_at>=? ORDER BY r.evaluated_at,r.evaluation_id",this::evaluation,event.eventId(),new Timestamp(decision.frozen().reviewFrom()));
        Basis baseline=decision.frozen().basis();
        for(Evaluation e:later) {
            Basis b=e.basis();
            // Historical reliability is the evaluation's persisted FRESH/assurance judgment, not today's changed freshness configuration.
            if(decision.frozen().knownEvaluationIds().contains(b.evaluationId())||!e.reliable()||b.observedAt()==null||b.observedAt()>b.evaluatedAt()||b.evaluatedAt()>clock.nowMillis())continue;
            if(NoCounterRules.newRisk(baseline.legalStatus(),baseline.grade(),baseline.violationReasons(),b.legalStatus(),b.grade(),b.violationReasons()))return true;
            // A reliable LEGAL interval followed by recurrence is a new episode, even at the original severity.
            if("LEGAL".equals(b.legalStatus()))baseline=b;
        }
        return false;
    }
    public Decision decision(String eventId) {
        var rows=jdbc.query("SELECT * FROM uav_no_counter_decision WHERE event_id=? ORDER BY event_version DESC FETCH FIRST 1 ROWS ONLY",
                (r,n)->new Decision(r.getString("decision_id"),r.getLong("decided_at"),r.getString("actor_name"),r.getString("reason"),read(r.getString("basis_text"),FrozenBasis.class),r.getString("target_id"),r.getString("owner_org_id"),r.getString("district_id"),r.getString("source_mode")),eventId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void append(String id, EventRow event, String actorId, String actorName, long at, FrozenBasis frozen) {
        jdbc.update("INSERT INTO uav_no_counter_decision(decision_id,event_id,event_version,actor_id,actor_name,decided_at,reason,evaluation_id,target_id,owner_org_id,district_id,source_mode,basis_text) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,event.eventId(),event.version()+1,actorId,actorName,at,"人工确认当前无风险，决定不反制",frozen.basis().evaluationId(),event.targetId(),event.ownerOrgId(),event.districtId(),event.sourceMode(),write(frozen));
    }
    public UavAdvisoryRepository.Replay replay(String actor,String key) {
        var rows=jdbc.query("SELECT request_hash,event_id,response_text FROM uav_no_counter_request WHERE actor_id=? AND request_key=?",(r,n)->new UavAdvisoryRepository.Replay(r.getString(1),r.getString(2),r.getString(3)),actor,key);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void saveReplay(String actor,String key,String hash,String event,String response) { jdbc.update("INSERT INTO uav_no_counter_request(actor_id,request_key,request_hash,event_id,response_text) VALUES(?,?,?,?,?)",actor,key,hash,event,response); }
    public String actorName(String id) { return jdbc.queryForObject("SELECT name FROM app_user WHERE user_id=?",String.class,id); }
    private List<String> strings(String raw) {
        try {var node=json.readTree(raw);if(node.isTextual())node=json.readTree(node.textValue());if(!node.isArray())return null;var values=new ArrayList<String>();for(var item:node){if(!item.isTextual())return null;values.add(item.textValue());}return List.copyOf(values);}catch(Exception invalid){return null;}
    }
    private <T>T read(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception invalid){throw new IllegalStateException("Invalid no-counter audit",invalid);}}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException(invalid);}}
    private static Long time(ResultSet row,String name)throws SQLException {var value=row.getTimestamp(name);return value==null?null:value.getTime();}
}
