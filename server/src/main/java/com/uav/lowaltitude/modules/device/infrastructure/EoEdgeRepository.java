package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;

@Repository
public class EoEdgeRepository {
    private static final String BINDING_SELECT = """
            SELECT m.*,d.enabled,s.owner_org_id,s.district_id FROM eo_device_binding m
            JOIN ops_device d ON d.device_id=m.ops_device_id
            JOIN device_business_scope s ON s.ops_device_id=d.device_id
            """;
    private final JdbcTemplate jdbc;
    public EoEdgeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Binding binding(String opsDeviceId, boolean lock) {
        if (lock) jdbc.queryForList("SELECT ops_device_id FROM eo_device_binding WHERE ops_device_id=? FOR UPDATE", opsDeviceId);
        return jdbc.query(BINDING_SELECT + " WHERE m.ops_device_id=?", this::binding, opsDeviceId).stream().findFirst().orElse(null);
    }
    public Binding bindingByExternal(String edgeId, String externalDeviceId, boolean lock) {
        if (lock) jdbc.queryForList("SELECT ops_device_id FROM eo_device_binding WHERE edge_id=? AND external_device_id=? FOR UPDATE",
                edgeId, externalDeviceId);
        return jdbc.query(BINDING_SELECT + " WHERE m.edge_id=? AND m.external_device_id=?", this::binding, edgeId, externalDeviceId)
                .stream().findFirst().orElse(null);
    }
    public List<Binding> bindings(String brokerId) {
        return jdbc.query(BINDING_SELECT + " WHERE m.broker_id=? ORDER BY m.ops_device_id", this::binding, brokerId);
    }
    public List<String> reportingTopics(String brokerId) {
        return jdbc.queryForList("""
                SELECT DISTINCT m.edge_id FROM eo_device_binding m JOIN ops_device d ON d.device_id=m.ops_device_id
                WHERE m.broker_id=? AND d.enabled=TRUE
                """, String.class, brokerId).stream().map(id -> "iot-reporting/cmlc/edge/" + id).toList();
    }
    private Binding binding(ResultSet r, int row) throws SQLException {
        return new Binding(r.getString("ops_device_id"), r.getString("device_id"), r.getString("ops_source_id"),
                r.getString("source_id"), r.getString("edge_id"), r.getString("broker_id"), r.getString("external_device_id"),
                r.getString("source_mode"), r.getBoolean("enabled"), r.getObject("last_heartbeat_at", Long.class),
                r.getObject("work_state", Integer.class), r.getString("owner_org_id"), r.getString("district_id"));
    }

    public String register(Registration p, long now) {
        String opsId = uuid(), deviceId = uuid(), opsSource = uuid(), source = uuid();
        boolean simulated = p.sourceMode().equals("replay");
        Timestamp time = new Timestamp(now);
        ensureEdge(p, now);
        jdbc.update("""
                INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,
                    enabled,simulated,created_at,updated_at) VALUES (?,?,?,?,'20250826',?,TRUE,?,?,?)
                """, opsSource, "eo-" + opsSource, p.name(), EoEdgeEnvelope.PROTOCOL, p.sourceMode(), simulated, now, now);
        jdbc.update("""
                INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,
                    enabled,source_type,created_at,updated_at) VALUES (?,?,?,?,'20250826',?,TRUE,'EO',?,?)
                """, source, "eo-" + source, p.name(), EoEdgeEnvelope.PROTOCOL, p.sourceMode(), time, time);
        jdbc.update("""
                INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,
                    channel,model,vendor,source_mode,simulated,created_at,updated_at,owner_name,region_name)
                SELECT ?,?,?,?,?,'EO','光电','凌云光电边端',?,?,?,?,?,?,o.name,d.name FROM app_org o CROSS JOIN app_district d
                WHERE o.org_id=? AND d.district_id=?
                """, opsId, opsSource, p.externalDeviceId(), p.deviceNo(), p.name(), p.model(), p.vendor(), p.sourceMode(),
                simulated, now, now, p.ownerOrgId(), p.districtId());
        jdbc.update("""
                INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,model,vendor,
                    source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,?,?,'EO',?,?,?,?,?,?,?)
                """, deviceId, source, p.externalDeviceId(), p.deviceNo(), p.name(), p.model(), p.vendor(), p.sourceMode(),
                p.ownerOrgId(), p.districtId(), time, time);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,?,?)",
                opsId, p.ownerOrgId(), p.districtId(), time, time);
        jdbc.update("""
                INSERT INTO eo_device_binding(ops_device_id,device_id,ops_source_id,source_id,edge_id,broker_id,
                    external_device_id,source_mode) VALUES (?,?,?,?,?,?,?,?)
                """, opsId, deviceId, opsSource, source, p.edgeId(), p.brokerId(), p.externalDeviceId(), p.sourceMode());
        jdbc.update("INSERT INTO ops_device_state(device_id,received_at,simulated,unknown_reason) VALUES (?,?,?,'尚未收到心跳')",
                opsId, now, simulated);
        return opsId;
    }

    private void ensureEdge(Registration p, long now) {
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM eo_edge WHERE edge_id=?", p.edgeId());
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO eo_edge(edge_id,broker_id,source_mode,owner_org_id,district_id,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, p.edgeId(), p.brokerId(), p.sourceMode(), p.ownerOrgId(), p.districtId(), now, now);
            return;
        }
        Map<String, Object> row = existing.get(0);
        if (!p.brokerId().equals(row.get("broker_id")) || !p.sourceMode().equals(row.get("source_mode"))
                || !p.ownerOrgId().equals(row.get("owner_org_id")) || !p.districtId().equals(row.get("district_id")))
            throw new IllegalArgumentException("EDGE_IDENTITY_CONFLICT");
    }

    public int updateDevice(Binding b, Registration p, long now) {
        int changed = jdbc.update("UPDATE ops_device SET name=?,vendor=?,model=?,version=version+1,updated_at=? WHERE device_id=? AND version=?",
                p.name(), p.vendor(), p.model(), now, b.opsDeviceId(), p.version());
        if (changed == 1) jdbc.update("UPDATE device SET name=?,vendor=?,model=?,version=version+1,updated_at=? WHERE device_id=?",
                p.name(), p.vendor(), p.model(), new Timestamp(now), b.deviceId());
        return changed;
    }
    public int enableDevice(Binding b, long version, boolean enabled, long now) {
        int changed = jdbc.update("UPDATE ops_device SET enabled=?,version=version+1,updated_at=? WHERE device_id=? AND version=?",
                enabled, now, b.opsDeviceId(), version);
        if (changed == 1) {
            jdbc.update("UPDATE device SET enabled=?,version=version+1,updated_at=? WHERE device_id=?", enabled, new Timestamp(now), b.deviceId());
            jdbc.update("UPDATE ops_integration_source SET enabled=?,version=version+1,updated_at=? WHERE source_id=?", enabled, now, b.opsSourceId());
            jdbc.update("UPDATE integration_source SET enabled=?,version=version+1,updated_at=? WHERE source_id=?", enabled, new Timestamp(now), b.sourceId());
            jdbc.update("UPDATE eo_device_binding SET subscribed=FALSE WHERE ops_device_id=?", b.opsDeviceId());
            if (!enabled) jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE',unknown_reason='设备已停用',last_heartbeat_at=NULL WHERE device_id=?",
                    b.opsDeviceId());
        }
        return changed;
    }
    public void subscribed(String brokerId, boolean subscribed) {
        jdbc.update("UPDATE eo_device_binding SET subscribed=? WHERE broker_id=?", subscribed, brokerId);
    }
    public void subscribedDevice(String opsDeviceId, boolean subscribed) {
        jdbc.update("UPDATE eo_device_binding SET subscribed=? WHERE ops_device_id=?", subscribed, opsDeviceId);
    }

    public String existingHash(String source, String key) {
        return jdbc.queryForList("SELECT payload_hash FROM inbox_message WHERE source=? AND source_msg_id=?", String.class, source, key)
                .stream().findFirst().orElse(null);
    }
    public String inbox(Binding b, EoEdgeEnvelope m, long received) {
        String id = uuid();
        return jdbc.update("""
                INSERT INTO inbox_message(inbox_id,source,source_msg_id,source_id,payload_hash,payload,received_at,status)
                SELECT ?,?,?,?,?,CAST(? AS JSON),?,'RECEIVED'
                WHERE NOT EXISTS (SELECT 1 FROM inbox_message WHERE source=? AND source_msg_id=?)
                """, id, b.source(), m.sourceMsgId(), b.sourceId(), m.hash(), m.json(), received, b.source(), m.sourceMsgId()) == 1
                ? id : null;
    }
    public void heartbeat(Binding b, EoEdgeEnvelope m, String cameraJson, long received) {
        jdbc.update("""
                UPDATE eo_device_binding SET last_heartbeat_at=?,work_state=?,camera_status_json=? WHERE ops_device_id=?
                """, received, m.workState(), cameraJson, b.opsDeviceId());
        jdbc.update("""
                UPDATE ops_device_state SET connectivity='ONLINE',work_state_code=?,observed_at=?,received_at=?,
                    last_heartbeat_at=?,unknown_reason=NULL,metrics_json=?,version=version+1 WHERE device_id=?
                """, m.workState() == null ? null : String.valueOf(m.workState()), m.timestamp(), received, received,
                cameraJson, b.opsDeviceId());
        upsertRuntime(b, m, cameraJson, received, "ONLINE");
    }
    public void camera(Binding b, String cameraJson, Integer workState, long received) {
        jdbc.update("UPDATE eo_device_binding SET camera_status_json=?,work_state=COALESCE(?,work_state) WHERE ops_device_id=?",
                cameraJson, workState, b.opsDeviceId());
        if (workState != null)
            jdbc.update("UPDATE ops_device_state SET work_state_code=?,version=version+1 WHERE device_id=?",
                    String.valueOf(workState), b.opsDeviceId());
        upsertRuntime(b, null, cameraJson, received, "ONLINE");
    }
    public void report(Binding b, long received) {
        jdbc.update("UPDATE eo_device_binding SET last_report_at=? WHERE ops_device_id=?", received, b.opsDeviceId());
    }
    public void counted(String opsDeviceId, String outcome) {
        if ("DUPLICATE".equals(outcome) || "CONFLICT".equals(outcome)) {
            String column = "DUPLICATE".equals(outcome) ? "duplicate_count" : "conflict_count";
            jdbc.update("UPDATE eo_device_binding SET " + column + "=" + column + "+1 WHERE ops_device_id=?", opsDeviceId);
        }
    }
    public void expire(long now, long timeoutMillis) {
        jdbc.update("""
                UPDATE ops_device_state SET connectivity='OFFLINE',unknown_reason='心跳超时未收到 HeartBeat',version=version+1
                WHERE connectivity='ONLINE' AND last_heartbeat_at<=?
                AND device_id IN (SELECT ops_device_id FROM eo_device_binding)
                """, now - timeoutMillis);
        jdbc.update("""
                UPDATE protocol_runtime_state SET connection_state='OFFLINE',updated_at=?,version=version+1
                WHERE connection_state='ONLINE' AND last_heartbeat_at<=?
                AND device_id IN (SELECT ops_device_id FROM eo_device_binding)
                """, now, now - timeoutMillis);
    }
    public Map<String, Object> status(String device) {
        Map<String, Object> result = jdbc.queryForMap("""
                SELECT m.*,b.enabled AS broker_enabled,l.connection_state,l.last_error,l.lease_until
                FROM eo_device_binding m JOIN mqtt_broker b ON b.broker_id=m.broker_id
                JOIN mqtt_session_lease l ON l.broker_id=m.broker_id WHERE m.ops_device_id=?
                """, device);
        result.put("recent_diagnostics", jdbc.queryForList("""
                SELECT outcome,reason,received_at,payload_hash FROM mqtt_receive_diagnostic WHERE ops_device_id=?
                ORDER BY received_at DESC,diagnostic_id DESC FETCH FIRST 20 ROWS ONLY
                """, device));
        Map<String, Object> task = jdbc.queryForList("""
                SELECT task_id,target_id,status,created_at FROM eo_tracking_task WHERE ops_device_id=?
                AND status IN ('OPEN','ENDING') ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY
                """, device).stream().findFirst().orElse(null);
        result.put("open_task", task);
        result.put("unsupported_events", "AdjustDeviceInfo / DetectToggle / TrackToggle / SetHome / MoveHome / AbsMoveByAngle");
        return result;
    }

    public void insertTask(String taskId, String targetId, String eventId, String opsDeviceId, String beginCommandId,
                           String notes, String bootstrap, long now) {
        jdbc.update("""
                INSERT INTO eo_tracking_task(task_id,target_id,fusion_event_id,ops_device_id,begin_command_id,status,notes,bootstrap_json,created_at)
                VALUES (?,?,?,?,?,'OPEN',?,?,?)
                """, taskId, targetId, eventId, opsDeviceId, beginCommandId, notes, bootstrap, now);
    }
    public Map<String, Object> openTask(String opsDeviceId) {
        return jdbc.queryForList("SELECT * FROM eo_tracking_task WHERE ops_device_id=? AND status IN ('OPEN','ENDING') ORDER BY created_at DESC",
                opsDeviceId).stream().findFirst().orElse(null);
    }
    public Map<String, Object> task(String taskId) {
        return jdbc.queryForList("SELECT * FROM eo_tracking_task WHERE task_id=?", taskId).stream().findFirst().orElse(null);
    }
    public Map<String, Object> taskByBegin(String commandId) {
        return jdbc.queryForList("SELECT * FROM eo_tracking_task WHERE begin_command_id=?", commandId).stream().findFirst().orElse(null);
    }
    public int updateTask(String taskId, String expected, String status, String endCommandId, long now) {
        return jdbc.update("""
                UPDATE eo_tracking_task SET status=?,end_command_id=COALESCE(?,end_command_id),
                    ended_at=CASE WHEN ? IN ('ENDED','FAILED') THEN ? ELSE ended_at END
                WHERE task_id=? AND status=?
                """, status, endCommandId, status, now, taskId, expected);
    }
    public Binding idleDevice(String org, String district) {
        return jdbc.query(BINDING_SELECT + """
                WHERE s.owner_org_id=? AND s.district_id=? AND d.enabled=TRUE
                AND EXISTS (SELECT 1 FROM mqtt_broker b WHERE b.broker_id=m.broker_id AND b.enabled=TRUE)
                AND EXISTS (SELECT 1 FROM ops_device_state ds WHERE ds.device_id=m.ops_device_id AND ds.connectivity='ONLINE')
                AND (m.work_state IS NULL OR m.work_state=0)
                AND NOT EXISTS (SELECT 1 FROM eo_tracking_task t WHERE t.ops_device_id=m.ops_device_id AND t.status IN ('OPEN','ENDING'))
                ORDER BY m.ops_device_id FETCH FIRST 1 ROWS ONLY
                """, this::binding, org, district).stream().findFirst().orElse(null);
    }
    public boolean targetHasOpenTask(String targetId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task WHERE target_id=? AND status IN ('OPEN','ENDING')",
                Integer.class, targetId);
        return count != null && count > 0;
    }
    public Map<String, Object> openTaskByTarget(String targetId) {
        return jdbc.queryForList("""
                SELECT * FROM eo_tracking_task WHERE target_id=? AND status IN ('OPEN','ENDING')
                ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY
                """, targetId).stream().findFirst().orElse(null);
    }
    public Binding idleDeviceById(String opsDeviceId, String org, String district) {
        return jdbc.query(BINDING_SELECT + """
                WHERE m.ops_device_id=? AND s.owner_org_id=? AND s.district_id=? AND d.enabled=TRUE
                AND EXISTS (SELECT 1 FROM mqtt_broker b WHERE b.broker_id=m.broker_id AND b.enabled=TRUE)
                AND EXISTS (SELECT 1 FROM ops_device_state ds WHERE ds.device_id=m.ops_device_id AND ds.connectivity='ONLINE')
                AND (m.work_state IS NULL OR m.work_state=0)
                AND NOT EXISTS (SELECT 1 FROM eo_tracking_task t WHERE t.ops_device_id=m.ops_device_id AND t.status IN ('OPEN','ENDING'))
                """, this::binding, opsDeviceId, org, district).stream().findFirst().orElse(null);
    }

    public Map<String, Object> lockCursor() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM eo_fusion_cursor WHERE cursor_name='default' FOR UPDATE");
        return rows.isEmpty() ? null : rows.get(0);
    }
    public void advanceCursor(OffsetDateTime createdAt, String eventId) {
        jdbc.update("UPDATE eo_fusion_cursor SET last_created_at=?,last_event_id=? WHERE cursor_name='default'",
                Timestamp.from(createdAt.toInstant()), eventId);
    }
    public List<Map<String, Object>> pendingStableEvents(OffsetDateTime createdAt, String eventId, int batch) {
        return jdbc.queryForList("""
                SELECT event_id,target_id,CAST(payload AS VARCHAR) AS payload_text,created_at,occurred_at
                FROM fusion_event WHERE event_type='STATUS_STABLE'
                AND (created_at>? OR (created_at=? AND event_id>?))
                ORDER BY created_at,event_id FETCH FIRST ? ROWS ONLY
                """, Timestamp.from(createdAt.toInstant()), Timestamp.from(createdAt.toInstant()), eventId, batch);
    }
    /**
     * 当前仍需要自动跟踪的目标，只取每个目标最新的稳定事件作为引导数据。
     * 直接读取当前告警/风险状态，避免稳定事件先产生、规则引擎稍后建告警时错过自动跟踪。
     * 没有空闲设备时记录会留在候选集中供下次轮询；同一稳定事件已经建过任务后不再重复执行。
     */
    public List<Map<String, Object>> autoTrackCandidates(int batch) {
        return jdbc.queryForList("""
                SELECT e.event_id,e.target_id,CAST(e.payload AS VARCHAR) AS payload_text,e.created_at,e.occurred_at
                FROM fusion_event e
                WHERE e.event_type='STATUS_STABLE'
                AND NOT EXISTS (
                    SELECT 1 FROM fusion_event newer
                    WHERE newer.event_type='STATUS_STABLE' AND newer.target_id=e.target_id
                    AND (newer.created_at>e.created_at OR (newer.created_at=e.created_at AND newer.event_id>e.event_id))
                )
                AND (
                    EXISTS (
                        SELECT 1 FROM uav_event ue JOIN alarm a ON a.alarm_id=ue.alarm_id
                        WHERE a.target_id=e.target_id AND ue.state_code='PENDING_VERIFICATION'
                    )
                    OR EXISTS (
                        SELECT 1 FROM flight_risk r
                        WHERE r.target_id=e.target_id AND r.state_code IN ('PENDING_VERIFICATION','PENDING_NOTIFICATION')
                        AND r.severity IN ('HIGH','CRITICAL')
                    )
                )
                AND NOT EXISTS (
                    SELECT 1 FROM eo_tracking_task t
                    WHERE t.fusion_event_id=e.event_id OR (t.target_id=e.target_id AND t.status IN ('OPEN','ENDING'))
                )
                ORDER BY e.created_at,e.event_id FETCH FIRST ? ROWS ONLY
                """, batch);
    }
    /** 在线设备上已不再满足告警/高风险条件的自动任务，由后台下发 EndTracking 释放设备。 */
    public List<Map<String, Object>> automaticTasksToEnd(int batch) {
        return jdbc.queryForList("""
                SELECT t.task_id,t.ops_device_id,t.target_id
                FROM eo_tracking_task t
                JOIN eo_device_binding m ON m.ops_device_id=t.ops_device_id
                JOIN mqtt_broker b ON b.broker_id=m.broker_id AND b.enabled=TRUE
                JOIN ops_device_state ds ON ds.device_id=t.ops_device_id AND ds.connectivity='ONLINE'
                WHERE t.status='OPEN' AND t.fusion_event_id IS NOT NULL
                AND NOT EXISTS (
                    SELECT 1 FROM uav_event ue JOIN alarm a ON a.alarm_id=ue.alarm_id
                    WHERE a.target_id=t.target_id AND ue.state_code='PENDING_VERIFICATION'
                )
                AND NOT EXISTS (
                    SELECT 1 FROM flight_risk r
                    WHERE r.target_id=t.target_id AND r.state_code IN ('PENDING_VERIFICATION','PENDING_NOTIFICATION')
                    AND r.severity IN ('HIGH','CRITICAL')
                )
                ORDER BY t.created_at,t.task_id FETCH FIRST ? ROWS ONLY
                """, batch);
    }
    public Map<String, Object> target(String targetId) {
        return jdbc.queryForList("SELECT target_id,target_no,owner_org_id,district_id FROM target WHERE target_id=?", targetId)
                .stream().findFirst().orElse(null);
    }

    public void insertCommand(String commandId, String commandNo, String deviceId, String requestedBy, String type,
                              String reason, String sourceMode, boolean simulated, long deadline, long now) {
        jdbc.update("""
                INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,
                    source_mode,simulated,deadline_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'QUEUED',?,?,?,?,?)
                """, commandId, commandNo, deviceId, requestedBy, type, reason, sourceMode, simulated, deadline, now, now);
    }
    public void addOutbox(String id, String topic, String payload, long now) {
        jdbc.update("INSERT INTO outbox_event(outbox_id,topic,payload,created_at,available_at,attempt_count) VALUES (?,?,?,?,?,0)",
                id, topic, payload, now, now);
    }
    public Map<String, Object> command(String commandId) {
        return jdbc.queryForList("SELECT * FROM device_command WHERE command_id=?", commandId).stream().findFirst().orElse(null);
    }
    public Map<String, Object> commandByType(String deviceId, String type) {
        return jdbc.queryForList("""
                SELECT * FROM device_command WHERE device_id=? AND command_type=? AND status IN ('QUEUED','SENT','ACCEPTED')
                ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY
                """, deviceId, type).stream().findFirst().orElse(null);
    }
    public int updateCommand(String commandId, String expected, String status, long now, String code, String detail) {
        return jdbc.update("""
                UPDATE device_command SET status=?,issued_at=CASE WHEN ?='SENT' THEN ? ELSE issued_at END,
                    completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN ? ELSE completed_at END,
                    result_code=?,result_detail=?,updated_at=? WHERE command_id=? AND status=?
                """, status, status, now, status, now, code, detail, now, commandId, expected);
    }
    public void addReceipt(String commandId, String inboxId, String resultCode, long now, String payload) {
        jdbc.update("""
                INSERT INTO command_receipt(receipt_id,command_id,inbox_id,receipt_kind,device_result_code,occurred_at,received_at,payload)
                VALUES (?,?,?,?,?,?,?,?)
                """, uuid(), commandId, inboxId, "PROTOCOL_C", resultCode, now, now, payload);
    }
    public void addEvent(String deviceId, String type, String level, String message, long now, boolean simulated) {
        jdbc.update("INSERT INTO device_event_log(event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,?)",
                uuid(), deviceId, type, level, message, now, simulated);
    }

    private void upsertRuntime(Binding b, EoEdgeEnvelope m, String cameraJson, long received, String state) {
        Integer work = m == null ? null : m.workState();
        int updated = jdbc.update("""
                UPDATE protocol_runtime_state SET protocol_code=?,connection_state=?,work_state=COALESCE(?,work_state),
                    camera_status_json=COALESCE(?,camera_status_json),last_heartbeat_at=?,updated_at=?,version=version+1
                WHERE device_id=?
                """, EoEdgeEnvelope.PROTOCOL, state, work, cameraJson, received, received, b.opsDeviceId());
        if (updated == 0) jdbc.update("""
                INSERT INTO protocol_runtime_state(device_id,protocol_code,connection_state,work_state,camera_status_json,
                    last_heartbeat_at,coordinate_reference_state,blocking_reason,updated_at,version)
                VALUES (?,?,?,?,?,?,'UNAVAILABLE',NULL,?,0)
                """, b.opsDeviceId(), EoEdgeEnvelope.PROTOCOL, state, work, cameraJson, received, received);
    }
    private static String uuid() { return UUID.randomUUID().toString(); }
}
