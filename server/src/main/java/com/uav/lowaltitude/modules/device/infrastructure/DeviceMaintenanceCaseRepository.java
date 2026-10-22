package com.uav.lowaltitude.modules.device.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.*;

@Repository
public class DeviceMaintenanceCaseRepository {
    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final com.uav.lowaltitude.platform.time.AppClock clock;
    private final long discoveryFreshnessMillis;

    /** Related labels in the restricted maintenance workspace remain exact-unit, independent of flight menu grants. */
    public List<RelatedPlan> relatedPlansInUnit(String taskId,String orgId) {
        return jdbc.query("SELECT p.plan_id,p.plan_no FROM flight_plan p JOIN app_org o ON o.org_id=p.owner_org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=p.district_id AND d.enabled=TRUE WHERE p.owner_org_id=? AND (EXISTS(SELECT 1 FROM ops_maintenance_plan l WHERE l.task_id=? AND l.plan_id=p.plan_id) OR EXISTS(SELECT 1 FROM ops_device_maintenance_task t WHERE t.task_id=? AND t.plan_id=p.plan_id)) ORDER BY p.plan_no,p.plan_id",
                (r,i)->new RelatedPlan(r.getString(1),r.getString(2)),orgId,taskId,taskId);
    }
    public DeviceMaintenanceCaseRepository(JdbcTemplate jdbc,com.fasterxml.jackson.databind.ObjectMapper json,
            com.uav.lowaltitude.platform.time.AppClock clock,
            @org.springframework.beans.factory.annotation.Value("${app.device-maintenance.recovery-max-age-seconds:300}") long freshnessSeconds){
        if(freshnessSeconds<1)throw new IllegalArgumentException("Device evidence freshness must be positive");
        this.jdbc=jdbc;this.json=json;this.clock=clock;this.discoveryFreshnessMillis=freshnessSeconds*1000;
    }
    public String activeKey(String device) {
        var facts=jdbc.queryForMap("SELECT source_id,source_mode,simulated FROM ops_device WHERE device_id=?",device);
        return "v2:"+hash(device+"|"+facts.get("source_id")+"|"+facts.get("source_mode")+"|"+facts.get("simulated"));
    }
    public static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public Details details(String id){return jdbc.queryForObject("""
        SELECT workflow_version,workflow_source_id,recovery_since_at,recovery_snapshot,recovery_fingerprint,
               recovery_result,recovery_reason,recovery_checked_at,assigned_to_name
        FROM ops_device_maintenance_task WHERE task_id=?
        """,(r,i)->new Details(r.getInt(1),r.getString(2),r.getObject(3)==null?null:r.getLong(3),r.getString(4),r.getString(5),
            recovery(r.getString(4),r.getString(6),r.getString(7),r.getLong(8)),r.getString(9)),id);}
    private Recovery recovery(String snapshot,String result,String reason,long checkedAt){
        if(result==null)return null;
        if(snapshot!=null){
            try{
                var saved=json.readValue(snapshot,com.uav.lowaltitude.modules.device.application.DeviceRecoveryService.Evaluation.class);
                return new Recovery(saved.result(),saved.reason(),saved.checkedAt(),saved.reasonCodes(),saved.ruleVersion(),CurrentDeviceState.from(saved.currentState()));
            }catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Invalid saved recovery evidence",error);}
        }
        return new Recovery(result,reason,checkedAt);
    }
    public String sourceMode(String id){return jdbc.queryForObject("SELECT workflow_source_mode FROM ops_device_maintenance_task WHERE task_id=?",String.class,id);}
    public List<String> planIds(String id){return jdbc.queryForList("SELECT plan_id FROM ops_maintenance_plan WHERE task_id=? UNION SELECT plan_id FROM ops_device_maintenance_task WHERE task_id=? AND plan_id IS NOT NULL",String.class,id,id);}
    public boolean submittedFor(String actor,String key,String plan){return jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_submission WHERE actor_id=? AND request_key=? AND plan_id IS NOT DISTINCT FROM ?",Long.class,actor,key,plan)>0;}
    public void attach(String task,String plan,String actor,String actorName,String reason,long at,String reportKey){
        if(plan!=null)jdbc.update("INSERT INTO ops_maintenance_plan(task_id,plan_id,first_reported_at) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM ops_maintenance_plan WHERE task_id=? AND plan_id=?)",task,plan,at,task,plan);
        jdbc.update("""
            INSERT INTO ops_maintenance_report(report_id,task_id,plan_id,actor_id,actor_name,reason,reported_at,report_key)
            SELECT ?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM ops_maintenance_report WHERE task_id=? AND report_key=?)
            """,UUID.randomUUID().toString(),task,plan,actor,actorName,reason,at,reportKey,task,reportKey);
        attachIncidents(task);
    }
    public void attachIncidents(String task){attachIncidents(List.of(task));}
    public void attachIncidents(List<String> tasks){
        if(tasks.isEmpty())return;
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).update("""
        INSERT INTO ops_maintenance_incident(task_id,incident_id)
        SELECT t.task_id,i.incident_id FROM ops_device_maintenance_task t JOIN device_incident i ON i.device_id=t.device_id
        JOIN ops_device d ON d.device_id=t.device_id
        WHERE t.task_id IN (:tasks) AND i.closed_at IS NULL AND i.simulated=t.simulated
          AND (t.workflow_version=1 OR (t.workflow_source_id IS NOT DISTINCT FROM d.source_id
               AND t.workflow_source_mode=d.source_mode AND t.simulated=d.simulated))
          AND NOT EXISTS(
          SELECT 1 FROM ops_maintenance_incident l WHERE l.task_id=t.task_id AND l.incident_id=i.incident_id)
          AND NOT EXISTS(SELECT 1 FROM ops_maintenance_incident old_link
            JOIN ops_device_maintenance_task old_case ON old_case.task_id=old_link.task_id
            WHERE old_link.incident_id=i.incident_id AND old_case.workflow_version=2
              AND (old_case.workflow_source_id IS DISTINCT FROM t.workflow_source_id
                   OR old_case.workflow_source_mode IS DISTINCT FROM t.workflow_source_mode
                   OR old_case.simulated<>t.simulated))
        """,Map.of("tasks",tasks));}
    public List<RelatedIncident> incidents(String task){return jdbc.query("SELECT i.incident_id,i.reason,i.stage,i.detected_at,i.closed_at FROM ops_maintenance_incident l JOIN device_incident i ON i.incident_id=l.incident_id WHERE l.task_id=? ORDER BY i.detected_at,i.incident_id",
        (r,i)->new RelatedIncident(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getObject(5)==null?null:r.getLong(5)),task);}
    public List<RelatedReport> reports(String task){return jdbc.query("SELECT report_id,plan_id,actor_name,reason,reported_at FROM ops_maintenance_report WHERE task_id=? ORDER BY reported_at,report_id",
        (r,i)->new RelatedReport(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)),task);}
    /** Select only explicitly human-authored reports within the reader's visible plan scope. */
    public RelatedReport latestManualReport(String task,List<String> visiblePlans){
        var parameters=new HashMap<String,Object>();parameters.put("task",task);
        String planScope="plan_id IS NULL";
        if(!visiblePlans.isEmpty()){parameters.put("plans",visiblePlans);planScope+=" OR plan_id IN (:plans)";}
        var rows=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
            "SELECT report_id,plan_id,actor_name,reason,reported_at FROM ops_maintenance_report"
                +" WHERE task_id=:task AND actor_id IS NOT NULL AND ("+planScope+")"
                +" ORDER BY reported_at DESC,report_id DESC FETCH FIRST 1 ROW ONLY",parameters,
            (r,i)->new RelatedReport(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)));
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<String> due(long now){return jdbc.queryForList("SELECT task_id FROM ops_device_maintenance_task WHERE status='PENDING' AND (next_recovery_check_at IS NULL OR next_recovery_check_at<=?) ORDER BY COALESCE(next_recovery_check_at,0),reported_at,task_id FETCH FIRST 200 ROWS ONLY",String.class,now);}
    /** Round-robin eligible cases as busy rows cannot safely be updated merely to move them off page one. */
    public List<String> due(long now,String afterTaskId){
        String order=afterTaskId==null?"task_id":"CASE WHEN task_id>? THEN 0 ELSE 1 END,task_id";
        String sql="SELECT task_id FROM ops_device_maintenance_task WHERE status='PENDING' AND (next_recovery_check_at IS NULL OR next_recovery_check_at<=?) ORDER BY "+order+" FETCH FIRST 200 ROWS ONLY";
        return afterTaskId==null?jdbc.queryForList(sql,String.class,now):jdbc.queryForList(sql,String.class,now,afterTaskId);
    }
    /** Page devices, not historical reports: one device's old reports cannot consume an entire round. */
    public List<String> dueDevices(long now,String afterDeviceId){
        String order=afterDeviceId==null?"device_id":"CASE WHEN device_id>? THEN 0 ELSE 1 END,device_id";
        String sql="SELECT device_id FROM ops_device_maintenance_task WHERE status='PENDING'"
                +" AND (next_recovery_check_at IS NULL OR next_recovery_check_at<=?) GROUP BY device_id ORDER BY "
                +order+" FETCH FIRST 200 ROWS ONLY";
        return afterDeviceId==null?jdbc.queryForList(sql,String.class,now):jdbc.queryForList(sql,String.class,now,afterDeviceId);
    }
    /** Catalog and current-state locks are held first. A busy case does not block its sibling cases. */
    public List<AutomaticCase> lockDueCases(String device,long now){return jdbc.query("""
        SELECT task_id,version,reported_at,simulated,workflow_state,assigned_to_name,workflow_version,
               workflow_source_id,workflow_source_mode,recovery_since_at,recovery_result,recovery_fingerprint
        FROM ops_device_maintenance_task WHERE device_id=? AND status='PENDING'
          AND (next_recovery_check_at IS NULL OR next_recovery_check_at<=?)
        ORDER BY COALESCE(next_recovery_check_at,0),task_id FETCH FIRST 1000 ROWS ONLY FOR UPDATE SKIP LOCKED
        """,(r,i)->new AutomaticCase(r.getString(1),r.getLong(2),r.getLong(3),r.getBoolean(4),r.getString(5),
                r.getString(6),r.getInt(7),r.getString(8),r.getString(9),
                r.getObject(10)==null?null:r.getLong(10),r.getString(11),r.getString(12)),device,now);}
    public void automaticSnapshots(List<AutomaticUpdate> updates){
        if(updates.isEmpty())return;
        var values=updates.stream().map(u->new Object[]{u.snapshot(),u.fingerprint(),u.recovery().result(),
                u.recovery().reason(),u.recovery().checkedAt(),u.nextCheckAt(),u.since(),u.state(),
                u.changed()?1:0,u.taskId(),u.version()}).toList();
        int[] counts=jdbc.batchUpdate("""
            UPDATE ops_device_maintenance_task SET recovery_snapshot=?,recovery_fingerprint=?,recovery_result=?,
              recovery_reason=?,recovery_checked_at=?,next_recovery_check_at=?,recovery_since_at=COALESCE(?,recovery_since_at),
              workflow_state=?,version=version+? WHERE task_id=? AND version=? AND status='PENDING'
            """,values);
        for(int count:counts)if(count==0||count==java.sql.Statement.EXECUTE_FAILED)
            throw new IllegalStateException("MAINTENANCE_TASK_CHANGED_DURING_AUTOMATIC_CHECK");
    }
    // Discovery uses only explicit current-source abnormal facts or an existing anomaly event.
    // A completed case's older facts cannot create another case. Legacy reports are never merged here.
    private static final String DISCOVERABLE_ANOMALIES="""
        WITH current_facts AS (
          SELECT d.device_id,d.source_id,d.source_mode,d.simulated,s.health_code,s.has_alarm,
                 s.health_observed_at,s.health_source_id,s.health_source_mode,s.health_simulated,
                 s.alarm_observed_at,s.alarm_source_id,s.alarm_source_mode,s.alarm_simulated,
                 COALESCE((SELECT MAX(t.handled_at) FROM ops_device_maintenance_task t
                    WHERE t.device_id=d.device_id AND t.workflow_version=2 AND t.status='HANDLED'
                      AND t.workflow_source_id IS NOT DISTINCT FROM d.source_id
                      AND t.workflow_source_mode=d.source_mode AND t.simulated=d.simulated),0) AS completed_at
          FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id
          LEFT JOIN ops_integration_source src ON src.source_id=d.source_id
          WHERE d.deleted_at IS NULL AND d.enabled=TRUE AND (src.enabled IS NULL OR src.enabled=TRUE)
            AND (:include_active=TRUE OR NOT EXISTS(SELECT 1 FROM ops_device_maintenance_task t
                WHERE t.device_id=d.device_id AND t.workflow_version=2 AND t.status='PENDING'
                  AND t.workflow_source_id IS NOT DISTINCT FROM d.source_id
                  AND t.workflow_source_mode=d.source_mode AND t.simulated=d.simulated))
        ), anomalies AS (
          SELECT f.device_id,
            CASE WHEN f.health_code IN ('BAD','DEGRADED')
                      AND f.health_observed_at BETWEEN :fresh_after AND :now AND f.health_observed_at>f.completed_at
                      AND f.health_source_id IS NOT DISTINCT FROM f.source_id
                      AND f.health_source_mode=f.source_mode AND f.health_simulated=f.simulated
                 THEN TRUE ELSE FALSE END AS health_failed,
            CASE WHEN f.has_alarm=TRUE
                      AND f.alarm_observed_at BETWEEN :fresh_after AND :now AND f.alarm_observed_at>f.completed_at
                      AND f.alarm_source_id IS NOT DISTINCT FROM f.source_id
                      AND f.alarm_source_mode=f.source_mode AND f.alarm_simulated=f.simulated
                 THEN TRUE ELSE FALSE END AS alarm_active,
            (SELECT i.reason FROM device_incident i
               WHERE i.device_id=f.device_id AND i.simulated=f.simulated
                 AND i.closed_at IS NULL AND i.stage<>'RECOVERED'
                 AND i.detected_at>f.completed_at AND i.detected_at<=:now
                 AND NOT EXISTS(SELECT 1 FROM ops_maintenance_incident l
                   JOIN ops_device_maintenance_task linked ON linked.task_id=l.task_id WHERE l.incident_id=i.incident_id
                     AND (:include_active=FALSE OR linked.status<>'PENDING'
                       OR linked.workflow_source_id IS DISTINCT FROM f.source_id
                       OR linked.workflow_source_mode IS DISTINCT FROM f.source_mode OR linked.simulated<>f.simulated))
               ORDER BY i.detected_at DESC,i.incident_id FETCH FIRST 1 ROW ONLY) AS incident_reason
          FROM current_facts f
        )
        SELECT device_id,CASE WHEN health_failed=TRUE AND alarm_active=TRUE THEN '设备健康异常且仍有设备告警'
                    WHEN health_failed=TRUE THEN '设备健康异常'
                    WHEN alarm_active=TRUE THEN '设备仍有告警' ELSE incident_reason END AS anomaly_reason
        FROM anomalies WHERE (health_failed=TRUE OR alarm_active=TRUE OR incident_reason IS NOT NULL)
        """;
    public List<String> undispatchedDevices(){
        return undispatchedDevices(null);
    }
    public List<String> undispatchedDevices(String afterDeviceId){
        long now=clock.nowMillis();
        var parameters=new HashMap<String,Object>();parameters.put("now",now);parameters.put("fresh_after",now-discoveryFreshnessMillis);parameters.put("include_active",false);
        if(afterDeviceId!=null)parameters.put("after_device",afterDeviceId);
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
            DISCOVERABLE_ANOMALIES+(afterDeviceId==null?" ORDER BY device_id":" ORDER BY CASE WHEN device_id>:after_device THEN 0 ELSE 1 END,device_id")+" FETCH FIRST 200 ROWS ONLY",
            parameters,(r,i)->r.getString("device_id"));
    }
    public String incidentReason(String device){
        return incidentReason(device,false);
    }
    /** The map reports the same explicit anomaly facts as discovery, including an existing active case. */
    public String reportableReason(String device){return incidentReason(device,true);}
    private String incidentReason(String device,boolean includeActive){
        long now=clock.nowMillis();
        var reasons=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
            DISCOVERABLE_ANOMALIES+" AND device_id=:device",
            Map.of("device",device,"now",now,"fresh_after",now-discoveryFreshnessMillis,"include_active",includeActive),(r,i)->r.getString("anomaly_reason"));
        return reasons.isEmpty()?null:reasons.get(0);
    }
    public void snapshot(String id,String snapshot,String fingerprint,Recovery recovery,long next,Long since){jdbc.update("""
        UPDATE ops_device_maintenance_task SET recovery_snapshot=?,recovery_fingerprint=?,recovery_result=?,recovery_reason=?,
            recovery_checked_at=?,next_recovery_check_at=?,recovery_since_at=COALESCE(?,recovery_since_at) WHERE task_id=?
        """,snapshot,fingerprint,recovery.result(),recovery.reason(),recovery.checkedAt(),next,since,id);}
    public void defer(String id,long next){jdbc.update("""
        UPDATE ops_device_maintenance_task SET next_recovery_check_at=?
        WHERE task_id IN (SELECT task_id FROM ops_device_maintenance_task
          WHERE task_id=? AND status='PENDING' FOR UPDATE SKIP LOCKED)
        """,next,id);}
    public record Details(int version,String sourceId,Long since,String snapshot,String fingerprint,Recovery recovery,String assignee) { }
    public record AutomaticCase(String taskId,long version,long reportedAt,boolean simulated,String state,
            String assignee,int workflowVersion,String sourceId,String sourceMode,Long since,String previousResult,String fingerprint) { }
    public record AutomaticUpdate(String taskId,long version,String snapshot,String fingerprint,Recovery recovery,
            long nextCheckAt,Long since,String state,boolean changed,String action) { }
}
