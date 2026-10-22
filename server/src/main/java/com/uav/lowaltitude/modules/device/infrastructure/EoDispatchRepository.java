package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable wait order and device reservations; all writers hold the EO cursor lock. */
@Repository
public class EoDispatchRepository {
    private final JdbcTemplate jdbc;
    public EoDispatchRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<String,Object> action(String actor,String key) {
        return first("SELECT target_id,action FROM eo_dispatch_action WHERE actor_id=? AND request_key=?",actor,key);
    }
    public void saveAction(String actor,String key,String target,String action,long now) {
        jdbc.update("INSERT INTO eo_dispatch_action(actor_id,request_key,target_id,action,created_at) VALUES (?,?,?,?,?)",actor,key,target,action,now);
    }
    public Map<String,Object> pending(String target) {
        return first("SELECT * FROM eo_dispatch_request WHERE active_target_id=?",target);
    }
    public Map<String,Object> latest(String target) {
        return first("SELECT * FROM eo_dispatch_request WHERE target_id=? ORDER BY created_at DESC,request_id DESC",target);
    }
    public List<Map<String,Object>> waiting() {
        return jdbc.queryForList("SELECT * FROM eo_dispatch_request WHERE status IN ('WAITING','STOPPING') ORDER BY created_at,request_id");
    }
    public boolean reserved(String device) {
        return first("SELECT request_id FROM eo_dispatch_request WHERE status='STOPPING' AND device_id=?",device)!=null;
    }
    public Map<String,Object> reservation(String task) {
        return first("SELECT * FROM eo_dispatch_request WHERE status='STOPPING' AND stop_task_id=?",task);
    }
    public void create(String id,String target,String actor,long now) {
        jdbc.update("INSERT INTO eo_dispatch_request(request_id,target_id,active_target_id,origin,requested_by,status,created_at,updated_at) VALUES (?,?,?,?,?,'WAITING',?,?)",
                id,target,target,actor==null?"AUTO":"MANUAL",actor,now,now);
    }
    public void manual(String id,String actor,long now) {
        jdbc.update("UPDATE eo_dispatch_request SET origin='MANUAL',requested_by=?,updated_at=? WHERE request_id=? AND status='WAITING'",actor,now,id);
    }
    /** WAITING device_id is a preference, not a reservation; only STOPPING owns a device. */
    public void selectDevice(String id,String device,long now) {
        if(jdbc.update("UPDATE eo_dispatch_request SET device_id=?,updated_at=? WHERE request_id=? AND status='WAITING'",device,now,id)!=1)
            throw new IllegalStateException("EO_DISPATCH_CHANGED");
    }
    public void reserve(String id,String device,String task,long now) {
        if(jdbc.update("UPDATE eo_dispatch_request SET status='STOPPING',device_id=?,stop_task_id=?,updated_at=? WHERE request_id=? AND status='WAITING'",device,task,now,id)!=1)
            throw new IllegalStateException("EO_DISPATCH_CHANGED");
    }
    public void finish(String id,String status,String task,String message,long now) {
        jdbc.update("UPDATE eo_dispatch_request SET status=?,task_id=?,message=?,active_target_id=NULL,updated_at=? WHERE request_id=? AND status IN ('WAITING','STOPPING')",status,task,message,now,id);
    }
    public List<String> devices(String org,String district,String mode,long cutoff,long now) {
        return jdbc.queryForList("""
                SELECT b.ops_device_id FROM eo_device_binding b
                JOIN ops_device d ON d.device_id=b.ops_device_id
                JOIN device_business_scope s ON s.ops_device_id=b.ops_device_id
                JOIN mqtt_broker m ON m.broker_id=b.broker_id
                JOIN ops_device_state ds ON ds.device_id=b.ops_device_id
                WHERE d.enabled=TRUE AND m.enabled=TRUE AND ds.connectivity='ONLINE'
                AND s.owner_org_id=? AND s.district_id=? AND b.source_mode=?
                AND b.work_state IN (0,1) AND b.last_heartbeat_at>=? AND b.last_heartbeat_at<=?
                ORDER BY b.ops_device_id
                """,String.class,org,district,mode,cutoff,now);
    }
    /** Only actual device disposal protects its observation task; since 2026-10-14 an emergency stop needs no site check to observe. */
    public boolean protectedTarget(String target) {
        return first("""
                SELECT t.target_id FROM target t JOIN alarm a ON a.target_id=t.target_id
                JOIN uav_event e ON e.alarm_id=a.alarm_id
                WHERE t.target_id=? AND a.source_mode=t.source_mode AND a.owner_org_id=t.owner_org_id
                AND a.district_id=t.district_id AND e.owner_org_id=t.owner_org_id AND e.district_id=t.district_id
                AND EXISTS (SELECT 1 FROM disposal_authorization da WHERE da.subject_kind='UAV_EVENT'
                    AND da.subject_id=e.event_id AND da.source_mode=t.source_mode AND da.owner_org_id=t.owner_org_id
                    AND da.district_id=t.district_id AND da.action_type IN ('COUNTERMEASURE','JAMMING')
                    AND da.channel<>'MANUAL' AND da.status IN ('APPROVED','EXECUTING'))
                """,target)!=null;
    }
    /** Only recent facts rank new demand; historical severity must not steal a camera. */
    public int priority(String target,long since,long now) {
        if(protectedTarget(target)) return 100;
        var levels=jdbc.queryForList("""
                SELECT a.severity FROM alarm a JOIN target t ON t.target_id=a.target_id
                JOIN uav_event e ON e.alarm_id=a.alarm_id WHERE t.target_id=?
                AND a.source_mode=t.source_mode AND a.owner_org_id=t.owner_org_id AND a.district_id=t.district_id
                AND e.state_code IN ('PENDING_VERIFICATION','EVIDENCE_REQUIRED','CONFIRMED')
                AND COALESCE(a.occurred_at,a.received_at)>=? AND COALESCE(a.occurred_at,a.received_at)<=?
                UNION ALL
                SELECT r.severity FROM flight_risk r JOIN target t ON t.target_id=r.target_id WHERE t.target_id=?
                AND r.source_mode=t.source_mode AND r.owner_org_id=t.owner_org_id AND r.district_id=t.district_id
                AND r.state_code<>'EXCLUDED' AND r.risk_type<>'WEATHER'
                AND COALESCE(r.occurred_at,r.received_at)>=? AND COALESCE(r.occurred_at,r.received_at)<=?
                """,String.class,target,new Timestamp(since),new Timestamp(now),target,new Timestamp(since),new Timestamp(now));
        return levels.stream().mapToInt(v -> switch(v) {case "CRITICAL" -> 4;case "HIGH" -> 3;case "MEDIUM" -> 2;default -> 1;}).max().orElse(1);
    }
    private Map<String,Object> first(String sql,Object...args) {
        return jdbc.queryForList(sql,args).stream().findFirst().orElse(null);
    }
}
