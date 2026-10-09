package com.uav.lowaltitude.modules.flight.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.flight.api.FlightVerificationDtos.ScheduledCheck;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;

@Repository
public class FlightCheckScheduleRepository {
    private final JdbcTemplate jdbc;
    public FlightCheckScheduleRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<String> due(long now,int limit) {
        return jdbc.queryForList("""
            SELECT p.plan_id FROM flight_plan p
            JOIN route_version rv ON rv.route_version_id=p.route_version_id
            JOIN route r ON r.route_id=rv.route_id AND r.owner_org_id=p.owner_org_id AND r.district_id=p.district_id
            JOIN app_org o ON o.org_id=p.owner_org_id AND o.enabled=TRUE
            JOIN app_district d ON d.district_id=p.district_id AND d.enabled=TRUE
            LEFT JOIN flight_device_check_schedule s ON s.plan_id=p.plan_id
            WHERE p.status_code<>'CANCELLED' AND p.start_at<=? AND p.end_at>?
              AND NOT EXISTS(SELECT 1 FROM flight_plan_duplicate d WHERE d.duplicate_plan_id=p.plan_id)
              AND (s.plan_id IS NULL OR s.next_check_at<=? OR s.plan_version<>p.version)
            ORDER BY COALESCE(s.next_check_at,0),p.plan_id FETCH FIRST ? ROWS ONLY
            """,String.class,new Timestamp(now),new Timestamp(now),now,limit);
    }
    public boolean eligible(PlanRow p,long now) {
        return p!=null && !"CANCELLED".equals(p.statusCode()) && p.startAt()!=null && p.endAt()!=null
            && p.startAt().toInstant().toEpochMilli()<=now && p.endAt().toInstant().toEpochMilli()>now
            && jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_duplicate WHERE duplicate_plan_id=?",Long.class,p.planId())==0;
    }
    public State state(String id) {
        var rows=jdbc.query("SELECT * FROM flight_device_check_schedule WHERE plan_id=?",(r,n)->new State(
            r.getString("scope_key"),r.getLong("plan_version"),r.getLong("next_check_at"),r.getString("state"),
            r.getString("fingerprint"),r.getString("verification_id")),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void ensure(PlanRow p,String scope) {
        if(state(p.planId())==null)jdbc.update("INSERT INTO flight_device_check_schedule(plan_id,scope_key,plan_version,next_check_at,state) VALUES(?,?,?,0,'PENDING')",p.planId(),scope,p.version());
    }
    public boolean matched(PlanRow p) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM rule_evaluation e JOIN target t ON t.target_id=e.target_id
            LEFT JOIN target_attribute_selection a ON a.target_id=t.target_id
            WHERE e.plan_id=? AND e.route_version_id=? AND e.mode='ACTIVE' AND e.plan_match_code='FULL'
              AND e.owner_org_id=? AND e.district_id=? AND e.source_mode=?
              AND (CASE WHEN t.unified THEN a.identity_clue ELSE t.uav_sn END)=?
              AND e.evaluated_at>=(SELECT updated_at FROM flight_plan WHERE plan_id=?)
              AND e.evaluated_at>=? AND e.evaluated_at<?
            """,Long.class,p.planId(),p.routeVersionId(),p.ownerOrgId(),p.districtId(),p.sourceMode(),p.uavSn(),p.planId(),p.startAt(),p.endAt())>0;
    }
    public void save(PlanRow p,String scope,long now,long next,String state,String fingerprint,String snapshot,String verification,String failure) {
        jdbc.update("UPDATE flight_device_check_schedule SET scope_key=?,plan_version=?,last_checked_at=?,next_check_at=?,state=?,fingerprint=?,snapshot_json=?,verification_id=?,failure_code=? WHERE plan_id=?",
            scope,p.version(),now,next,state,fingerprint,snapshot,verification,failure,p.planId());
    }
    public void version(PlanRow p) { jdbc.update("UPDATE flight_device_check_schedule SET plan_version=? WHERE plan_id=?",p.version(),p.planId()); }
    public void stopMatched(PlanRow p,String scope) {
        jdbc.update("UPDATE flight_device_check_schedule SET scope_key=?,plan_version=?,next_check_at=?,state='MATCHED',failure_code=NULL WHERE plan_id=?",scope,p.version(),Long.MAX_VALUE,p.planId());
    }
    public Fault fault(String plan,String device) {
        var rows=jdbc.query("SELECT * FROM flight_device_check_fault WHERE plan_id=? AND device_id=?",(r,n)->new Fault(r.getString("episode_id"),r.getString("incident_key"),r.getBoolean("active"),r.getString("task_id")),plan,device);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void fault(String plan,String device,String episode,String incidents,boolean active,String task) {
        if(fault(plan,device)==null)jdbc.update("INSERT INTO flight_device_check_fault(plan_id,device_id,episode_id,incident_key,active,task_id) VALUES(?,?,?,?,?,?)",plan,device,episode,incidents,active,task);
        else jdbc.update("UPDATE flight_device_check_fault SET episode_id=?,incident_key=?,active=?,task_id=? WHERE plan_id=? AND device_id=?",episode,incidents,active,task,plan,device);
    }
    public ScheduledCheck view(String plan) {
        var rows=jdbc.query("SELECT * FROM flight_device_check_schedule WHERE plan_id=?",(r,n)->new ScheduledCheck(
            r.getObject("last_checked_at",Long.class),"MATCHED".equals(r.getString("state"))?null:r.getLong("next_check_at"),
            r.getString("state"),r.getString("failure_code"),r.getString("verification_id"),
            jdbc.queryForList("SELECT task_id FROM flight_device_check_fault WHERE plan_id=? AND task_id IS NOT NULL ORDER BY device_id",String.class,plan),
            jdbc.query("SELECT t.task_id,t.device_name,t.workflow_state FROM flight_device_check_fault f JOIN ops_device_maintenance_task t ON t.task_id=f.task_id WHERE f.plan_id=? ORDER BY f.device_id",
                (task,index)->new com.uav.lowaltitude.modules.flight.api.FlightVerificationDtos.MaintenanceTaskState(task.getString(1),task.getString(2),task.getString(3)),plan)),plan);
        return rows.isEmpty()?null:rows.get(0);
    }
    public record State(String scope,long version,long next,String state,String fingerprint,String verification) { }
    public record Fault(String episode,String incidents,boolean active,String task) { }
}
