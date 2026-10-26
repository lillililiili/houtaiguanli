package com.uav.lowaltitude.modules.automationrule.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.automationrule.api.ResponseOrderDtos.*;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;

@Repository
public class ResponseOrderRepository {
    private final JdbcTemplate jdbc;
    public ResponseOrderRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Policy policy(boolean lock) {
        if(lock)jdbc.queryForList("SELECT policy_id FROM response_order_policy WHERE policy_id='uav' FOR UPDATE");
        var scope=jdbc.query("SELECT s.airspace_id,a.name FROM response_order_scope s JOIN airspace a ON a.airspace_id=s.airspace_id WHERE s.policy_id='uav' ORDER BY s.ordinal",
                (r,n)->new String[]{r.getString(1),r.getString(2)});
        return jdbc.queryForObject("SELECT * FROM response_order_policy WHERE policy_id='uav'"+(lock?" FOR UPDATE":""),
                (r,n)->new Policy(r.getLong("version"),r.getBoolean("enabled"),r.getString("minimum_risk"),r.getBoolean("prediction_enabled"),
                    r.getBoolean("observed_enabled"),r.getString("scope_mode"),scope.stream().map(s->s[0]).toList(),scope.stream().map(s->s[1]).toList(),
                    r.getString("schedule_mode"),r.getString("start_time"),r.getString("end_time"),"Asia/Shanghai",r.getInt("max_wait_seconds"),false));
    }
    public void update(Input p,long now) {
        if(jdbc.update("UPDATE response_order_policy SET version=version+1,enabled=?,minimum_risk=?,prediction_enabled=?,observed_enabled=?,scope_mode=?,schedule_mode=?,start_time=?,end_time=?,max_wait_seconds=?,updated_at=? WHERE policy_id='uav' AND version=?",
                p.enabled(),p.minimumRisk(),p.predictionEnabled(),p.observedEnabled(),p.scopeMode(),p.scheduleMode(),p.startTime(),p.endTime(),p.maxWaitSeconds(),now,p.expectedVersion())!=1)
            throw new IllegalStateException("Response order policy changed while locked");
        jdbc.update("DELETE FROM response_order_scope WHERE policy_id='uav'");
        if("AIRSPACES".equals(p.scopeMode()))for(int i=0;i<p.airspaceIds().size();i++)
            jdbc.update("INSERT INTO response_order_scope(policy_id,airspace_id,ordinal) VALUES('uav',?,?)",p.airspaceIds().get(i),i);
    }
    public boolean airspaceExists(String id) {return jdbc.queryForObject("SELECT COUNT(*) FROM airspace WHERE airspace_id=? AND deleted_at IS NULL",Long.class,id)==1;}
    public void history(String id,long version,String actorId,String actor,long now,String before,String after) {
        jdbc.update("INSERT INTO response_order_change VALUES(?,?,?,?,?,?,?)",id,version,actorId,actor,now,before,after);
    }
    public record ChangeRow(String id,long version,String actor,long at,String before,String after) { }
    public List<ChangeRow> history(int page,int size) {return jdbc.query("SELECT * FROM response_order_change ORDER BY version DESC LIMIT ? OFFSET ?",
            (r,n)->new ChangeRow(r.getString("change_id"),r.getLong("version"),r.getString("actor"),r.getLong("created_at"),r.getString("before_json"),r.getString("after_json")),size,(page-1)*size);}
    public long historyCount() {return jdbc.queryForObject("SELECT COUNT(*) FROM response_order_change",Long.class);}
    public record Window(String riskKey,long version,long startedAt,long deadlineAt,int maxWaitSeconds) { }
    public Window window(String riskKey) {return jdbc.query("SELECT * FROM response_order_window WHERE risk_key=?",
            (r,n)->new Window(r.getString("risk_key"),r.getLong("policy_version"),r.getLong("started_at"),r.getLong("deadline_at"),r.getInt("max_wait_seconds")),riskKey).stream().findFirst().orElse(null);}
    /** Caller holds the risk/event lock. No config change or lifecycle stage resets this row. */
    public void start(String key,String event,Policy policy,long now) {
        if(window(key)!=null)return;
        jdbc.update("INSERT INTO response_order_window VALUES(?,?,?,?,?,?)",key,event,policy.version(),now,now+policy.maxWaitSeconds()*1000L,policy.maxWaitSeconds());
    }
    public List<String> candidates(String after,int limit) {
        // Only confirmed risks can start the priority clock. Keep unknown/cross-day risks eligible
        // for re-evaluation; their current evidence and latest policy are checked under the case lock.
        return jdbc.queryForList("SELECT e.event_id FROM uav_event e WHERE e.event_id>? AND ("
                + "EXISTS(SELECT 1 FROM uav_risk_case_member m JOIN uav_risk_case c ON c.risk_case_id=m.risk_case_id"
                + " AND c.superseded_by IS NULL JOIN uav_event root_event ON root_event.event_id=c.root_event_id"
                + " WHERE m.event_id=e.event_id AND "+UavRiskCaseIdentity.VERIFICATION+"='CONFIRMED')"
                + " OR (e.state_code='CONFIRMED' AND NOT EXISTS(SELECT 1 FROM uav_risk_case_member m"
                + " JOIN uav_risk_case c ON c.risk_case_id=m.risk_case_id AND c.superseded_by IS NULL"
                + " WHERE m.event_id=e.event_id))) ORDER BY e.event_id LIMIT ?",String.class,after,limit);
    }
    public String severity(String eventId) {
        return jdbc.query("SELECT COALESCE(esc.severity_after,a.severity) FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id AND a.owner_org_id=e.owner_org_id AND a.district_id=e.district_id LEFT JOIN alarm_escalation esc ON esc.alarm_id=a.alarm_id AND NOT EXISTS(SELECT 1 FROM alarm_escalation newer WHERE newer.alarm_id=esc.alarm_id AND newer.seq>esc.seq) WHERE e.event_id=?",
                (r,n)->r.getString(1),eventId).stream().findFirst().orElse(null);
    }
    /** Only a currently scoped device catalog's definite absence is a no-device fallback. */
    public boolean noCounterDevice(String org,String district,String sourceMode) {
        if(!java.util.Set.of("live","replay","mock").contains(sourceMode))return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM ops_device d JOIN device_business_scope s ON s.ops_device_id=d.device_id WHERE d.deleted_at IS NULL AND d.enabled=TRUE AND d.device_type_code='countermeasure' AND s.owner_org_id=? AND s.district_id=? AND d.simulated=?",
                Long.class,org,district,!"live".equals(sourceMode))==0;
    }
    public record StartEvidence(boolean started,boolean failed) { }
    public StartEvidence startEvidence(String caseId,List<String> eventIds,boolean unique,long since,long now) {
        if(eventIds.isEmpty())return new StartEvidence(false,false);
        String marks=String.join(",",java.util.Collections.nCopies(eventIds.size(),"?"));
        var args=new java.util.ArrayList<Object>(eventIds);args.add(new java.sql.Timestamp(since));
        String scope="a.subject_kind='UAV_EVENT' AND a.subject_id IN ("+marks+") AND a.requested_at>=? AND a.action_type IN ('COUNTERMEASURE','JAMMING')";
        if(caseId!=null){scope+=" AND (a.risk_case_id=?"+(unique?" OR (a.risk_case_id IS NULL AND (SELECT COUNT(*) FROM uav_risk_case_member m JOIN uav_risk_case rc ON rc.risk_case_id=m.risk_case_id WHERE m.event_id=a.subject_id AND rc.superseded_by IS NULL)=1)":"")+")";args.add(caseId);}
        var rows=jdbc.query("SELECT c.status,c.issued_at,c.completed_at,EXISTS(SELECT 1 FROM command_receipt r WHERE r.command_id=c.command_id AND r.receipt_kind='COMPLETED' AND r.received_at>=? AND r.received_at<=?) AS receipt,EXISTS(SELECT 1 FROM countermeasure_start_attempt att WHERE att.command_id=c.command_id) AS attempted FROM disposal_authorization a LEFT JOIN disposal_device_run run ON run.authorization_id=a.authorization_id JOIN device_command c ON c.command_id=COALESCE(run.on_command_id,a.execution_command_id) LEFT JOIN countermeasure_4ch_command four ON four.command_id=c.command_id WHERE "+scope+" AND (c.command_type<>'COUNTERMEASURE_4CH' OR four.action='CHANNEL_ON' OR (four.action='SET_MASK' AND four.mask>0))",
                (r,n)->new Object[]{r.getString("status"),r.getObject("issued_at",Long.class),r.getObject("completed_at",Long.class),r.getBoolean("receipt"),r.getBoolean("attempted")},
                java.util.stream.Stream.concat(java.util.stream.Stream.of(since,now),args.stream()).toArray());
        boolean started=false,failed=false;
        for(var row:rows){String status=(String)row[0];Long completed=(Long)row[2];boolean receipt=(Boolean)row[3];
            if(completed==null||completed<since||completed>now)continue;
            if("SUCCEEDED".equals(status)&&receipt)started=true;
            // A timeout/unknown is never a negative receipt. Unsent definite failures are also safe fallback.
            if("FAILED".equals(status)&&(receipt||row[1]==null&&!((Boolean)row[4])))failed=true;
        }
        return new StartEvidence(started,failed);
    }
}
