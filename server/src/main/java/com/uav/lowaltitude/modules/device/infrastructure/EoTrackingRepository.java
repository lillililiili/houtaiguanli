package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Current facts only; no replay of an old fusion event's coordinates. */
@Repository
public class EoTrackingRepository {
    private final JdbcTemplate jdbc;
    private final boolean postgres;
    private final DeviceRepository devices;
    public EoTrackingRepository(JdbcTemplate jdbc,DeviceRepository devices) {
        this.jdbc = jdbc;
        this.devices=devices;
        this.postgres = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                c.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
    }
    public boolean deviceScope(String id,String user,String scope) {return devices.canDeleteInScope(id,user,scope);}
    public boolean roleEnabled(String role) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM app_role WHERE role_code=?",Boolean.class,role));}
    /**
     * 自动轮询锁目标：目标行正被别的事务（主要是融合写目标）锁着时跳过、返回 false，下一轮轮询再看。
     * 调度有多个线程（ZT-06），融合一帧按自己的次序更新多个目标，这里按候选次序逐个加锁，两边都等锁就会互相卡住，
     * PostgreSQL 判死锁后回滚一方；回滚的若是融合，那一帧记为失败、数据就丢了（2026-10-07 QA 默认开启自动跟踪后发现）。
     */
    public boolean tryLock(String target) {
        return !jdbc.queryForList("SELECT target_id FROM target WHERE target_id=? FOR UPDATE SKIP LOCKED", target).isEmpty();
    }
    public Map<String,Object> snapshot(String target) {
        String location = postgres ? "ST_AsText(s.location)" : "CAST(s.location AS VARCHAR)";
        return jdbc.queryForList("SELECT t.*,s.observed_at,s.received_at,s.altitude_amsl_m,s.speed_mps,s.heading_deg,"
                + "s.classification_confidence,CAST(s.unknown_fields AS VARCHAR) AS unknown_fields," + location + " AS position,"
                + (postgres ? "ST_SRID(s.location)" : "4326") + " AS srid,ts.status AS track_status "
                + "FROM target t LEFT JOIN target_latest_state s ON s.target_id=t.target_id "
                + "LEFT JOIN target_track_status ts ON ts.target_id=t.target_id WHERE t.target_id=?", target)
                .stream().findFirst().orElse(null);
    }
    public List<String> candidates(long cutoff, long now, int batch) {
        return jdbc.queryForList("""
                SELECT t.target_id FROM target t JOIN target_latest_state s ON s.target_id=t.target_id
                WHERE s.observed_at>=? AND s.observed_at<=?
                AND NOT EXISTS (SELECT 1 FROM eo_tracking_task x WHERE x.target_id=t.target_id AND x.status IN ('OPEN','ENDING'))
                AND NOT EXISTS (SELECT 1 FROM eo_target_control c WHERE c.target_id=t.target_id AND c.auto_paused=TRUE)
                AND (EXISTS (SELECT 1 FROM alarm a JOIN uav_event u ON u.alarm_id=a.alarm_id WHERE a.target_id=t.target_id
                     AND u.state_code IN ('PENDING_VERIFICATION','EVIDENCE_REQUIRED','CONFIRMED'))
                  OR EXISTS (SELECT 1 FROM flight_risk r WHERE r.target_id=t.target_id AND r.state_code<>'EXCLUDED' AND r.risk_type<>'WEATHER')
                  OR EXISTS (SELECT 1 FROM rule_evaluation e LEFT JOIN legality_review v ON v.evaluation_id=e.evaluation_id
                     WHERE e.target_id=t.target_id AND e.mode='ACTIVE' AND COALESCE(v.manual_status,e.legal_status) IN ('ILLEGAL','ABNORMAL')))
                ORDER BY CASE WHEN EXISTS (SELECT 1 FROM flight_risk r WHERE r.target_id=t.target_id AND r.state_code<>'EXCLUDED'
                     AND r.severity IN ('HIGH','CRITICAL')) THEN 0 ELSE 1 END,s.observed_at DESC,t.target_id
                """, String.class, new Timestamp(cutoff), new Timestamp(now));
    }
    public List<Map<String,Object>> alarms(String target, long cutoff, long now) {
        return jdbc.queryForList("""
                SELECT ue.event_id,ue.state_code,COALESCE(a.occurred_at,a.received_at) AS basis_at FROM uav_event ue JOIN alarm a ON a.alarm_id=ue.alarm_id
                JOIN target t ON t.target_id=a.target_id
                WHERE a.target_id=? AND a.source_mode=t.source_mode AND COALESCE(a.occurred_at,a.received_at)<=?
                AND a.owner_org_id=t.owner_org_id AND a.district_id=t.district_id
                AND ue.state_code IN ('PENDING_VERIFICATION','EVIDENCE_REQUIRED','CONFIRMED')
                """, target, new Timestamp(now));
    }
    public Map<String,Object> evaluation(String target) {
        return jdbc.queryForList("""
                SELECT COALESCE(v.manual_status,e.legal_status) AS legal_status,e.evaluated_at,e.observed_at,CAST(e.violation_reasons AS VARCHAR) AS reasons
                FROM rule_evaluation e JOIN target t ON t.target_id=e.target_id LEFT JOIN legality_review v ON v.evaluation_id=e.evaluation_id
                WHERE e.target_id=? AND e.mode='ACTIVE' AND e.subject_kind='TARGET' AND e.source_mode=t.source_mode
                AND e.owner_org_id=t.owner_org_id AND e.district_id=t.district_id
                ORDER BY e.evaluated_at DESC,e.evaluation_id DESC FETCH FIRST 1 ROWS ONLY
                """, target).stream().findFirst().orElse(null);
    }
    public List<Map<String,Object>> risks(String target, long cutoff, long now) {
        return jdbc.queryForList("""
                SELECT r.risk_type,r.reason_code,r.severity FROM flight_risk r JOIN target t ON t.target_id=r.target_id
                WHERE r.target_id=? AND r.source_mode=t.source_mode AND r.state_code<>'EXCLUDED'
                AND r.owner_org_id=t.owner_org_id AND r.district_id=t.district_id
                AND COALESCE(r.occurred_at,r.received_at)<=?
                """, target, new Timestamp(now));
    }
    public boolean paused(String target) {
        return Boolean.TRUE.equals(jdbc.queryForList("SELECT auto_paused FROM eo_target_control WHERE target_id=?", Boolean.class, target)
                .stream().findFirst().orElse(false));
    }
    public void pause(String target, boolean paused, String actor, long now) {
        if(jdbc.update("UPDATE eo_target_control SET auto_paused=?,updated_by=?,updated_at=? WHERE target_id=?", paused,actor,now,target)==0)
            jdbc.update("INSERT INTO eo_target_control(target_id,auto_paused,updated_by,updated_at) VALUES (?,?,?,?)",target,paused,actor,now);
    }
    public Map<String,Object> request(String actor, String key) {
        return jdbc.queryForList("SELECT target_id,action FROM eo_control_request WHERE actor_id=? AND request_key=?", actor,key)
                .stream().findFirst().orElse(null);
    }
    public void lockActor(String actor) { jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",actor); }
    public void saveRequest(String actor,String key,String target,String action,long now) {
        jdbc.update("INSERT INTO eo_control_request(actor_id,request_key,target_id,action,created_at) VALUES (?,?,?,?,?)",actor,key,target,action,now);
    }
    public List<Map<String,Object>> openAutomaticTasks(int batch) {
        return jdbc.queryForList("SELECT * FROM eo_tracking_task WHERE origin='AUTO' AND status='OPEN' ORDER BY created_at");
    }
    public void automatic(String task) { jdbc.update("UPDATE eo_tracking_task SET origin='AUTO' WHERE task_id=?",task); }
    public String deviceName(String device) { return jdbc.queryForObject("SELECT name FROM ops_device WHERE device_id=?",String.class,device); }
}
