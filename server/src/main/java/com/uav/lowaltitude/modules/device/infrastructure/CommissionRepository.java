package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CommissionRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CommissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void insertTask(Map<String, Object> p) {
        named.update("""
                INSERT INTO commission_task (commission_id,commission_no,previous_task_id,device_id,requested_by,
                    status,protocol_code,protocol_version,protocol_configuration_json,allowed_cidrs_snapshot,
                    source_credential_ref_snapshot,source_mode,simulated,version,created_at,updated_at)
                VALUES (:commission_id,:commission_no,:previous_task_id,:device_id,:requested_by,
                    'CREATED',:protocol_code,:protocol_version,:protocol_configuration_json,:allowed_cidrs_snapshot,
                    :source_credential_ref_snapshot,:source_mode,:simulated,0,:created_at,:updated_at)
                """, p);
    }

    public Map<String, Object> findTask(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.*, d.device_no, d.name AS device_name, d.device_type_name, d.channel,
                       d.source_mode AS device_source_mode,
                       COALESCE(t.protocol_code,s.protocol_code) AS resolved_protocol_code,
                       COALESCE(t.protocol_version,s.protocol_version) AS resolved_protocol_version,
                       COALESCE(t.allowed_cidrs_snapshot,s.allowed_cidrs) AS resolved_allowed_cidrs,
                       COALESCE(t.source_credential_ref_snapshot,s.credential_ref) AS resolved_source_credential_ref
                FROM commission_task t JOIN ops_device d ON d.device_id=t.device_id
                LEFT JOIN ops_integration_source s ON s.source_id=d.source_id
                WHERE t.commission_id=?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listTasks(String deviceId, String status, int offset, int size) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT t.*, d.device_no, d.name AS device_name, d.device_type_name, d.channel,
                       d.source_mode AS device_source_mode,
                       COALESCE(t.protocol_code,s.protocol_code) AS resolved_protocol_code,
                       COALESCE(t.protocol_version,s.protocol_version) AS resolved_protocol_version
                FROM commission_task t JOIN ops_device d ON d.device_id=t.device_id
                LEFT JOIN ops_integration_source s ON s.source_id=d.source_id WHERE 1=1
                """);
        add(sql, p, "t.device_id", "device_id", deviceId);
        add(sql, p, "t.status", "status", status);
        p.put("offset", offset); p.put("size", size);
        sql.append(" ORDER BY t.created_at DESC,t.commission_id OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY");
        return named.queryForList(sql.toString(), p);
    }

    public long countTasks(String deviceId, String status) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM commission_task t WHERE 1=1");
        add(sql, p, "t.device_id", "device_id", deviceId);
        add(sql, p, "t.status", "status", status);
        Long count = named.queryForObject(sql.toString(), p, Long.class);
        return count == null ? 0 : count;
    }

    public int transition(String id, long version, String from, String to, long now) {
        return jdbc.update("UPDATE commission_task SET status=?,version=version+1,updated_at=? WHERE commission_id=? AND version=? AND status=?",
                to, now, id, version, from);
    }

    public int saveConfiguration(String id, long version, String json, long now) {
        return jdbc.update("""
                UPDATE commission_task SET configuration_json=?,status='READY',version=version+1,updated_at=?
                WHERE commission_id=? AND version=? AND status='CONNECTED'
                """, json, now, id, version);
    }

    public int cancel(String id, long version, long now) {
        return jdbc.update("""
                UPDATE commission_task SET status='CANCELLED',finished_at=?,version=version+1,updated_at=?
                WHERE commission_id=? AND version=? AND status IN ('CREATED','CONNECTING','CONNECTED','READY','RUNNING')
                """, now, now, id, version);
    }

    public int completeConnect(String id, boolean success, long now, String detail) {
        return jdbc.update("""
                UPDATE commission_task SET status=?,results_json=?,version=version+1,updated_at=?,
                    finished_at=CASE WHEN ? THEN NULL ELSE ? END
                WHERE commission_id=? AND status='CONNECTING'
                """, success ? "CONNECTED" : "UNTESTABLE", success ? null : detail, now, success, now, id);
    }

    public int completeRun(String id, String status, String criteriaJson, String resultsJson, long now) {
        return jdbc.update("""
                UPDATE commission_task SET status=?,criteria_snapshot=?,results_json=?,finished_at=?,
                    version=version+1,updated_at=? WHERE commission_id=? AND status='RUNNING'
                """, status, criteriaJson, resultsJson, now, now, id);
    }

    public void markStarted(String id, long now) {
        jdbc.update("UPDATE commission_task SET started_at=COALESCE(started_at,?) WHERE commission_id=?", now, id);
    }

    public void addEvent(String eventId, String taskId, String stage, String level, String message, long now, boolean simulated) {
        jdbc.update("INSERT INTO commission_task_event (event_id,commission_id,stage_code,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,?)",
                eventId, taskId, stage, level, message, now, simulated);
    }

    public List<Map<String, Object>> events(String taskId, long afterSeq, int limit) {
        return jdbc.queryForList("""
                SELECT * FROM commission_task_event WHERE commission_id=? AND event_seq>?
                ORDER BY event_seq OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """, taskId, afterSeq, limit);
    }

    private static void add(StringBuilder sql, Map<String, Object> p, String column, String key, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND ").append(column).append("=:").append(key);
        p.put(key, value);
    }
}
