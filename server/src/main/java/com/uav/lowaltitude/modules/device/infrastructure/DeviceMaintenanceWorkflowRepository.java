package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.*;
import com.uav.lowaltitude.platform.security.AuthUser;

@Repository
public class DeviceMaintenanceWorkflowRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public DeviceMaintenanceWorkflowRepository(JdbcTemplate jdbc){this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);}
    public void lock(String taskId){jdbc.queryForList("SELECT task_id FROM ops_device_maintenance_task WHERE task_id=? FOR UPDATE",taskId);}
    public void lockCurrentState(String deviceId){jdbc.queryForList("SELECT device_id FROM ops_device_state WHERE device_id=? FOR UPDATE",deviceId);}
    public Metadata metadata(String taskId){return jdbc.queryForObject("SELECT workflow_state,workflow_source_mode,assigned_to_name,recovery_result,recovery_reason,recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",(r,i)->new Metadata(r.getString(1),r.getString(2),r.getString(3),r.getString(4)==null?null:new Recovery(r.getString(4),r.getString(5),r.getLong(6))),taskId);}
    public List<WorkflowEvent> events(String taskId){return jdbc.query("SELECT event_id,action,note,actor_name,occurred_at FROM ops_maintenance_workflow_event WHERE task_id=? ORDER BY task_version",(r,i)->new WorkflowEvent(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)),taskId);}
    public List<CommissionLink> commissions(String taskId){return jdbc.query("SELECT c.commission_id,c.status,c.simulated FROM ops_maintenance_commission l JOIN commission_task c ON c.commission_id=l.commission_id WHERE l.task_id=? ORDER BY c.created_at,c.commission_id",(r,i)->new CommissionLink(r.getString(1),r.getString(2),r.getBoolean(3)),taskId);}
    public void link(String taskId,String commissionId){jdbc.update("INSERT INTO ops_maintenance_commission(task_id,commission_id) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM ops_maintenance_commission WHERE task_id=? AND commission_id=?)",taskId,commissionId,taskId,commissionId);}
    public int transition(String taskId,long version,String next,AuthUser actor,String note,long now,Recovery recovery){
        boolean completed="COMPLETED".equals(next);
        return jdbc.update("""
            UPDATE ops_device_maintenance_task SET workflow_state=?,version=version+1,
              assigned_to=COALESCE(assigned_to,?),assigned_to_name=COALESCE(assigned_to_name,?),
              status=CASE WHEN ? THEN 'HANDLED' ELSE status END,
              active_key=CASE WHEN ? THEN NULL ELSE active_key END,
              handled_by=CASE WHEN ? THEN ? ELSE handled_by END,
              handled_by_name=CASE WHEN ? THEN ? ELSE handled_by_name END,
              handled_at=CASE WHEN ? THEN ? ELSE handled_at END,
              handling_note=CASE WHEN ? THEN ? ELSE handling_note END,
              recovery_result=?,recovery_reason=?,recovery_checked_at=?
            WHERE task_id=? AND version=? AND status='PENDING'
            """,next,actor.userId(),actorName(actor),completed,completed,completed,actor.userId(),completed,actorName(actor),
                completed,now,completed,note,recovery==null?null:recovery.result(),recovery==null?null:recovery.reason(),recovery==null?null:recovery.checkedAt(),taskId,version);
    }
    public void event(String eventId,String taskId,String action,String note,AuthUser actor,long now,long version){jdbc.update("INSERT INTO ops_maintenance_workflow_event(event_id,task_id,action,note,actor_id,actor_name,occurred_at,task_version) VALUES(?,?,?,?,?,?,?,?)",eventId,taskId,action,note,actor.userId(),actorName(actor),now,version);}
    public Replay replay(String actor,String key){var rows=jdbc.query("SELECT task_id,request_body,response_body FROM ops_maintenance_workflow_request WHERE actor_id=? AND request_key=?",(r,i)->new Replay(r.getString(1),r.getString(2),r.getString(3)),actor,key);return rows.isEmpty()?null:rows.get(0);}
    public void remember(String actor,String key,String taskId,String request,String response){jdbc.update("INSERT INTO ops_maintenance_workflow_request(actor_id,request_key,task_id,request_body,response_body) VALUES(?,?,?,?,?)",actor,key,taskId,request,response);}
    public boolean unresolvedIncidents(String device){return jdbc.queryForObject("SELECT COUNT(*) FROM device_incident WHERE device_id=? AND (closed_at IS NULL OR stage<>'RECOVERED')",Long.class,device)>0;}
    public List<String> unresolvedIncidentIds(String device){return jdbc.queryForList("SELECT incident_id FROM device_incident WHERE device_id=? AND (closed_at IS NULL OR stage<>'RECOVERED') ORDER BY detected_at,incident_id",String.class,device);}
    public MessagePage messages(AuthUser actor,int page,int size,boolean unreadOnly){
        Map<String,Object> p=new HashMap<>();p.put("actor",actor.userId());p.put("offset",(page-1)*size);p.put("size",size);
        String from=" FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id LEFT JOIN ops_maintenance_message_read r ON r.task_id=t.task_id AND r.actor_id=:actor"+DeviceMaintenanceRepository.scope(actor,p);
        String filter=unreadOnly?" AND r.read_at IS NULL":"";
        long total=named.queryForObject("SELECT COUNT(*)"+from+filter,p,Long.class);
        long unread=named.queryForObject("SELECT COUNT(*)"+from+" AND r.read_at IS NULL",p,Long.class);
        var items=named.query("SELECT t.task_id,t.device_id,t.device_name,t.reason,t.reported_at,r.read_at,t.workflow_state"+from+filter+" ORDER BY t.reported_at DESC,t.task_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",p,(r,i)->new Message(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getObject(6)==null?null:r.getLong(6),r.getString(7)));
        return new MessagePage(items,total,page,size,unread);
    }
    public ReadReceipt read(String actor,String taskId,long now){
        jdbc.update("INSERT INTO ops_maintenance_message_read(actor_id,task_id,read_at) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM ops_maintenance_message_read WHERE actor_id=? AND task_id=?)",actor,taskId,now,actor,taskId);
        return new ReadReceipt(taskId,jdbc.queryForObject("SELECT read_at FROM ops_maintenance_message_read WHERE actor_id=? AND task_id=?",Long.class,actor,taskId));
    }
    public record Metadata(String state,String sourceMode,String assignedToName,Recovery recovery) { }
    public record Replay(String taskId,String request,String response) { }
    private static String actorName(AuthUser actor){return actor.name()==null?actor.account():actor.name();}
}
