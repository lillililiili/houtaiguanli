package com.uav.lowaltitude.modules.disposal.infrastructure;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable stop intent and follow-up tasks. Called within the event lock transaction. */
@Repository
public class EmergencyStopRepository {
    private final JdbcTemplate jdbc;
    public EmergencyStopRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void lockActor(String id) {
        jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",id);
    }
    public String lockAuthorizationStatus(String id) {
        List<String> rows=jdbc.queryForList("SELECT status FROM disposal_authorization WHERE authorization_id=? FOR UPDATE",
                String.class,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void lockEvent(String id) {
        jdbc.queryForList("SELECT event_id FROM uav_event WHERE event_id=? FOR UPDATE", id);
    }
    public List<String> authorizationIds(String eventId) {
        return jdbc.queryForList("SELECT authorization_id FROM disposal_authorization WHERE subject_kind='UAV_EVENT'"
                + " AND subject_id=? AND action_type IN ('COUNTERMEASURE','JAMMING') ORDER BY authorization_id", String.class, eventId);
    }
    public boolean covered(String authorizationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM disposal_emergency_stop_authorization WHERE authorization_id=?",
                Long.class, authorizationId) > 0;
    }
    public String parent(String authorizationId) {
        List<String> rows=jdbc.queryForList("SELECT chained_from_authorization_id FROM disposal_authorization"
                + " WHERE authorization_id=?",String.class,authorizationId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<String> coveredIds(String stopId) {
        return jdbc.queryForList("SELECT authorization_id FROM disposal_emergency_stop_authorization WHERE stop_id=?",
                String.class,stopId);
    }
    public boolean unresolved(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM disposal_emergency_stop_device d JOIN disposal_emergency_stop s"
                + " ON s.stop_id=d.stop_id WHERE s.event_id=? AND d.confirmed_at IS NULL AND d.stop_status<>'NOT_REQUIRED'", Long.class, eventId) > 0;
    }
    public void lockDevice(String id) { jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_id=? FOR UPDATE", id); }
    public boolean deviceUnresolved(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM disposal_emergency_stop_device WHERE device_id=?"
                + " AND confirmed_at IS NULL AND stop_status<>'NOT_REQUIRED'",Long.class,id)>0;
    }
    public boolean shared(String deviceId, String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE device_id=?"
                + " AND NOT (subject_kind='UAV_EVENT' AND subject_id=?)"
                + " AND status IN ('APPROVED','EXECUTING')", Long.class, deviceId, eventId) > 0;
    }
    public Map<String,Object> latest(String eventId) {
        return first(jdbc.queryForList("SELECT s.*,u.name AS requested_by_name FROM disposal_emergency_stop s"
                + " JOIN app_user u ON u.user_id=s.requested_by WHERE s.event_id=? ORDER BY requested_at DESC,stop_id DESC LIMIT 1", eventId));
    }
    public Map<String,Object> stop(String eventId, String stopId) {
        return first(jdbc.queryForList("SELECT * FROM disposal_emergency_stop WHERE event_id=? AND stop_id=?", eventId, stopId));
    }
    public List<Map<String,Object>> tasks(String stopId) {
        return jdbc.queryForList("SELECT d.*,u.name AS confirmed_by_name,c.status AS command_status,c.result_detail AS command_detail"
                + " FROM disposal_emergency_stop_device d LEFT JOIN app_user u ON u.user_id=d.confirmed_by"
                + " LEFT JOIN device_command c ON c.command_id=d.command_id WHERE d.stop_id=? ORDER BY d.device_id", stopId);
    }
    public List<Map<String,Object>> events(String stopId) {
        return jdbc.queryForList("SELECT e.*,u.name AS actor_name FROM disposal_emergency_stop_event e"
                + " JOIN app_user u ON u.user_id=e.actor_id WHERE e.stop_id=? ORDER BY occurred_at,event_id", stopId);
    }
    public Map<String,Object> request(String actor, String key) {
        return first(jdbc.queryForList("SELECT * FROM disposal_emergency_stop_request WHERE actor_id=? AND request_key=?", actor, key));
    }
    public void request(String actor, String key, String operation, String note, String stopId) {
        jdbc.update("INSERT INTO disposal_emergency_stop_request(actor_id,request_key,operation,request_note,stop_id) VALUES (?,?,?,?,?)",
                actor,key,operation,note,stopId);
    }
    public void insert(String stopId, String eventId, String actor, long at, String note) {
        jdbc.update("INSERT INTO disposal_emergency_stop(stop_id,event_id,requested_by,requested_at,reason_pending,note) VALUES (?,?,?,?,TRUE,?)",
                stopId,eventId,actor,at,note);
    }
    public void cover(String stopId, String auth) {
        jdbc.update("INSERT INTO disposal_emergency_stop_authorization(stop_id,authorization_id) VALUES (?,?)",stopId,auth);
    }
    public void task(String stopId,String deviceId,String auth,String name,String channel,String mode,boolean simulated,
            String commandId,String status,String detail) {
        jdbc.update("INSERT INTO disposal_emergency_stop_device(stop_id,device_id,authorization_id,device_name,channel,source_mode,"
                + "simulated,command_id,stop_status,detail) VALUES (?,?,?,?,?,?,?,?,?,?)",
                stopId,deviceId,auth,name,channel,mode,simulated,commandId,status,detail);
    }
    public void retry(String stopId,String deviceId,String commandId,String status,String detail) {
        jdbc.update("UPDATE disposal_emergency_stop_device SET command_id=?,stop_status=?,detail=?,attempt=attempt+1 WHERE stop_id=? AND device_id=?",
                commandId,status,detail,stopId,deviceId);
    }
    public void confirm(String stopId,String deviceId,String actor,long at,String note) {
        jdbc.update("UPDATE disposal_emergency_stop_device SET confirmed_by=?,confirmed_at=?,confirmation_note=? WHERE stop_id=? AND device_id=?",
                actor,at,note,stopId,deviceId);
    }
    public void note(String stopId,String note) {
        jdbc.update("UPDATE disposal_emergency_stop SET reason_pending=FALSE,note=? WHERE stop_id=?",note,stopId);
    }
    public void event(String id,String stopId,String kind,String actor,long at,String note) {
        jdbc.update("INSERT INTO disposal_emergency_stop_event(event_id,stop_id,kind,actor_id,occurred_at,note) VALUES (?,?,?,?,?,?)",
                id,stopId,kind,actor,at,note);
    }
    private static Map<String,Object> first(List<Map<String,Object>> rows) { return rows.isEmpty()?null:rows.get(0); }
}
