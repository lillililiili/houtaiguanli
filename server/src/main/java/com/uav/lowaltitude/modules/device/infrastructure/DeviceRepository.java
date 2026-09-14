package com.uav.lowaltitude.modules.device.infrastructure;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeviceRepository {

    private static final String DEVICE_SELECT = """
            SELECT d.*, s.connectivity, s.work_state_code, s.has_alarm, s.health_code,
                   s.observed_at, s.received_at, s.last_heartbeat_at, s.metrics_json,
                   s.unknown_reason, s.version AS state_version,
                   src.source_code, src.name AS source_name, src.protocol_code, src.protocol_version,
                   src.allowed_cidrs, src.credential_ref AS source_credential_ref,
                   src.enabled AS source_enabled
            FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id = d.device_id
            LEFT JOIN ops_integration_source src ON src.source_id=d.source_id
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ops_device", Long.class);
        return count == null ? 0 : count;
    }

    public long countEnabledOnSource(String sourceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE source_id=? AND enabled=TRUE", Long.class, sourceId);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> list(DeviceQuery query, int offset, int size, String orderBy) {
        SqlWhere where = where(query);
        where.params.put("offset", offset);
        where.params.put("size", size);
        return named.queryForList(DEVICE_SELECT + where.sql + " ORDER BY " + orderBy + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.params);
    }

    public long count(DeviceQuery query) {
        SqlWhere where = where(query);
        Long count = named.queryForObject(
                "SELECT COUNT(*) FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id " + where.sql,
                where.params,
                Long.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> listForTree(DeviceQuery query, int limit) {
        SqlWhere where = where(query);
        where.params.put("limit", limit);
        return named.queryForList(DEVICE_SELECT + where.sql
                + " ORDER BY d.channel, d.device_type_name, d.device_no OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY", where.params);
    }

    public Map<String, Object> find(String deviceId) {
        Map<String,Object> params=new HashMap<>();
        params.put("device_id",deviceId);
        List<Map<String, Object>> rows = named.queryForList(
                DEVICE_SELECT + " WHERE d.device_id=:device_id" + mqttScope(params), params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findProfile(String deviceId) {
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT * FROM device_connection_profile WHERE device_id=:device_id",
                Map.of("device_id", deviceId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findIntegrationSource(String sourceId) {
        if (sourceId == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ops_integration_source WHERE source_id=?", sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findProtocolProfile(String deviceId, String protocolCode) {
        String table = switch (protocolCode == null ? "" : protocolCode) {
            case "RADAR_TCP_V3_0_0" -> "radar_v3_profile";
            case "COUNTERMEASURE_TCP_4CH_V2_0" -> "countermeasure_4ch_profile";
            default -> null;
        };
        if (table == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE device_id=?", deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> distinct(String column) {
        String safe = switch (column) {
            case "type" -> "device_type_name";
            case "channel" -> "channel";
            case "region" -> "region_name";
            case "vendor" -> "vendor";
            default -> throw new IllegalArgumentException("unsupported option column");
        };
        Map<String,Object> params=new HashMap<>();
        return named.queryForList("SELECT DISTINCT " + safe + " FROM ops_device d WHERE " + safe
                + " IS NOT NULL AND " + safe + "<>''" + mqttScope(params) + " ORDER BY " + safe,params,String.class);
    }

    public Map<String, Object> overview() {
        Map<String,Object> params=new HashMap<>();
        return named.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN s.connectivity='ONLINE' THEN 1 ELSE 0 END) AS online,
                       SUM(CASE WHEN s.connectivity='OFFLINE' THEN 1 ELSE 0 END) AS offline,
                       SUM(CASE WHEN s.connectivity='ABNORMAL' THEN 1 ELSE 0 END) AS abnormal,
                       SUM(CASE WHEN s.connectivity IS NULL OR s.connectivity='UNKNOWN' THEN 1 ELSE 0 END) AS unknown_count,
                       SUM(CASE WHEN s.has_alarm=TRUE THEN 1 ELSE 0 END) AS alarm,
                       COUNT(DISTINCT CASE WHEN d.vendor IS NOT NULL AND d.vendor<>'' THEN d.vendor END) AS vendor_count,
                       COUNT(DISTINCT CASE WHEN d.model IS NOT NULL AND d.model<>'' THEN d.model END) AS model_count
                       ,SUM(CASE WHEN d.source_mode='live' THEN 1 ELSE 0 END) AS live_count
                       ,SUM(CASE WHEN d.simulated=TRUE THEN 1 ELSE 0 END) AS simulated_count
                FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id
                """ + " WHERE 1=1" + mqttScope(params),params);
    }

    public List<Map<String, Object>> overviewGroups(String groupColumn) {
        Map<String,Object> params=new HashMap<>();
        String safe = "channel".equals(groupColumn) ? "d.channel" : "d.device_type_name";
        return named.queryForList("SELECT " + safe + " AS group_name, COUNT(*) AS total, "
                + "SUM(CASE WHEN s.connectivity='ONLINE' THEN 1 ELSE 0 END) AS online, "
                + "SUM(CASE WHEN s.connectivity='OFFLINE' THEN 1 ELSE 0 END) AS offline, "
                + "SUM(CASE WHEN s.connectivity='ABNORMAL' THEN 1 ELSE 0 END) AS abnormal, "
                + "SUM(CASE WHEN s.connectivity IS NULL OR s.connectivity='UNKNOWN' THEN 1 ELSE 0 END) AS unknown_count "
                + "FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id "
                + "WHERE 1=1" + mqttScope(params) + " GROUP BY " + safe + " ORDER BY " + safe,params);
    }

    public void insertDevice(Map<String, Object> values) {
        named.update("""
                INSERT INTO ops_device (
                    device_id, source_id, external_device_id, device_no, name, device_type_code,
                    device_type_name, channel, model, vendor, owner_name, region_name, address,
                    longitude, latitude, coordinate_system, altitude_m, altitude_datum,
                    firmware_version, installed_at, enabled, source_mode, simulated, version,
                    created_at, updated_at)
                VALUES (
                    :device_id, :source_id, :external_device_id, :device_no, :name, :device_type_code,
                    :device_type_name, :channel, :model, :vendor, :owner_name, :region_name, :address,
                    :longitude, :latitude, :coordinate_system, :altitude_m, :altitude_datum,
                    :firmware_version, :installed_at, TRUE, :source_mode, :simulated, 0,
                    :created_at, :updated_at)
                """, values);
    }

    public int updateDevice(String deviceId, long expectedVersion, Map<String, Object> values) {
        Map<String, Object> p = new HashMap<>(values);
        p.put("device_id", deviceId);
        p.put("expected_version", expectedVersion);
        return named.update("""
                UPDATE ops_device SET source_id=:source_id, external_device_id=:external_device_id,
                    device_no=:device_no, name=:name, device_type_code=:device_type_code,
                    device_type_name=:device_type_name, channel=:channel, model=:model, vendor=:vendor,
                    owner_name=:owner_name, region_name=:region_name, address=:address,
                    longitude=:longitude, latitude=:latitude, coordinate_system=:coordinate_system,
                    altitude_m=:altitude_m, altitude_datum=:altitude_datum,
                    firmware_version=:firmware_version, installed_at=:installed_at,
                    source_mode=:source_mode, simulated=:simulated,
                    version=version+1, updated_at=:updated_at
                WHERE device_id=:device_id AND version=:expected_version
                """, p);
    }

    public int setEnabled(String deviceId, long expectedVersion, boolean enabled, long now) {
        return jdbc.update("UPDATE ops_device SET enabled=?, version=version+1, updated_at=? WHERE device_id=? AND version=?",
                enabled, now, deviceId, expectedVersion);
    }

    public void upsertProfile(String deviceId, Map<String, Object> p, long now) {
        jdbc.update("DELETE FROM device_connection_profile WHERE device_id=?", deviceId);
        Map<String, Object> values = new HashMap<>(p);
        values.put("device_id", deviceId);
        values.put("updated_at", now);
        named.update("""
                INSERT INTO device_connection_profile (
                    device_id, transport, host, port, path, data_format, charset_name, auth_mode, credential_ref,
                    heartbeat_interval_seconds, report_interval_millis, sampling_rate_hz,
                    compression_enabled, retransmission_enabled, timeout_millis, retry_count,
                    longitude_offset_deg, latitude_offset_deg, altitude_offset_m, time_sync_mode,
                    time_server, timezone_name, time_sync_interval_seconds, version, updated_at)
                VALUES (
                    :device_id, :transport, :host, :port, :path, :data_format, :charset_name, :auth_mode, :credential_ref,
                    :heartbeat_interval_seconds, :report_interval_millis, :sampling_rate_hz,
                    :compression_enabled, :retransmission_enabled, :timeout_millis, :retry_count,
                    :longitude_offset_deg, :latitude_offset_deg, :altitude_offset_m, :time_sync_mode,
                    :time_server, :timezone_name, :time_sync_interval_seconds, 0, :updated_at)
                """, values);
    }

    public void replaceProtocolProfile(String deviceId, String protocolCode, Map<String, Object> values, long now) {
        jdbc.update("DELETE FROM radar_v3_profile WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM countermeasure_4ch_profile WHERE device_id=?", deviceId);
        if ("RADAR_TCP_V3_0_0".equals(protocolCode)) {
            named.update("""
                    INSERT INTO radar_v3_profile (device_id,login_role,recognition_code_ref,rtk_enabled,
                        coordinate_transform_enabled,version,updated_at)
                    VALUES (:device_id,:login_role,:recognition_code_ref,:rtk_enabled,
                        :coordinate_transform_enabled,0,:updated_at)
                    """, withDevice(values, deviceId, now));
        } else if ("COUNTERMEASURE_TCP_4CH_V2_0".equals(protocolCode)) {
            named.update("""
                    INSERT INTO countermeasure_4ch_profile (device_id,device_address,wire_encoding,
                        poll_interval_millis,version,updated_at)
                    VALUES (:device_id,:device_address,:wire_encoding,:poll_interval_millis,0,:updated_at)
                    """, withDevice(values, deviceId, now));
        }
    }

    private static Map<String, Object> withDevice(Map<String, Object> values, String deviceId, long now) {
        Map<String, Object> out = new HashMap<>(values);
        out.put("device_id", deviceId);
        out.put("updated_at", now);
        return out;
    }

    public List<Map<String, Object>> stateHistory(String deviceId, String metricCode, long from, long to, int limit) {
        return named.queryForList("""
                SELECT state_id, device_id, connectivity, observed_at, received_at,
                       metric_code, metric_value, metric_unit, simulated
                FROM ops_device_state_history
                WHERE device_id=:device_id AND metric_code=:metric_code
                  AND received_at>=:from_time AND received_at<=:to_time
                ORDER BY received_at DESC, state_id DESC OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("device_id", deviceId, "metric_code", metricCode, "from_time", from, "to_time", to, "limit", limit));
    }

    public List<Map<String, Object>> incidents(String deviceId, String severity, String stage, int offset, int size) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT i.*, d.device_no, d.name AS device_name FROM device_incident i JOIN ops_device d ON d.device_id=i.device_id WHERE 1=1");
        add(sql, p, "i.device_id", "device_id", deviceId);
        add(sql, p, "i.severity", "severity", severity);
        add(sql, p, "i.stage", "stage", stage);
        p.put("offset", offset); p.put("size", size);
        sql.append(" ORDER BY i.detected_at DESC, i.incident_id OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY");
        return named.queryForList(sql.toString(), p);
    }

    public long countIncidents(String deviceId, String severity, String stage) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM device_incident i WHERE 1=1");
        add(sql, p, "i.device_id", "device_id", deviceId);
        add(sql, p, "i.severity", "severity", severity);
        add(sql, p, "i.stage", "stage", stage);
        Long count = named.queryForObject(sql.toString(), p, Long.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> events(String deviceId, long afterSeq, int limit) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT e.*, d.device_no, d.name AS device_name FROM device_event_log e LEFT JOIN ops_device d ON d.device_id=e.device_id WHERE e.event_seq>:after_seq");
        p.put("after_seq", afterSeq); p.put("limit", limit);
        add(sql, p, "e.device_id", "device_id", deviceId);
        sql.append(" ORDER BY e.event_seq OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY");
        return named.queryForList(sql.toString(), p);
    }

    public void addEvent(String eventId, String deviceId, String type, String level, String message, long now, boolean simulated) {
        jdbc.update("INSERT INTO device_event_log (event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,?)",
                eventId, deviceId, type, level, message, now, simulated);
    }

    public Map<String, Object> findIdempotency(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM idempotency_request WHERE idem_key=?", key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertIdempotency(String key, String userId, String hash, String responseBody, long now) {
        jdbc.update("INSERT INTO idempotency_request (idem_key,user_id,request_hash,response_body,created_at) VALUES (?,?,?,?,?)",
                key, userId, hash, responseBody, now);
    }

    public void insertCommand(Map<String, Object> p) {
        named.update("""
                INSERT INTO device_command (command_id,command_no,device_id,requested_by,command_type,reason,status,
                    source_mode,simulated,deadline_at,created_at,updated_at)
                VALUES (:command_id,:command_no,:device_id,:requested_by,'REBOOT',:reason,'QUEUED',
                    :source_mode,:simulated,:deadline_at,:created_at,:updated_at)
                """, p);
    }

    public Map<String, Object> findCommand(String commandId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.*, d.device_no, d.name AS device_name
                FROM device_command c JOIN ops_device d ON d.device_id=c.device_id WHERE c.command_id=?
                """, commandId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> commandReceipts(String commandId) {
        return jdbc.queryForList("""
                SELECT receipt_id,receipt_kind,device_result_code,occurred_at,received_at,payload
                FROM command_receipt WHERE command_id=? ORDER BY received_at,receipt_id
                """, commandId);
    }

    public List<Map<String, Object>> expiredCommands(long now, int limit) {
        return jdbc.queryForList("""
                SELECT c.command_id,c.device_id,c.status,c.command_type FROM device_command c
                WHERE c.status IN ('QUEUED','SENT','ACCEPTED') AND c.deadline_at IS NOT NULL AND c.deadline_at<?
                ORDER BY c.deadline_at OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY
                """, now, limit);
    }

    public int updateCommand(String commandId, String expectedStatus, String status, long now, String code, String detail) {
        return jdbc.update("""
                UPDATE device_command SET status=?, issued_at=CASE WHEN ?='SENT' THEN ? ELSE issued_at END,
                    completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN ? ELSE completed_at END,
                    result_code=?, result_detail=?, updated_at=?
                WHERE command_id=? AND status=?
                """, status, status, now, status, now, code, detail, now, commandId, expectedStatus);
    }

    public void addOutbox(String id, String topic, String payload, long now, long availableAt) {
        jdbc.update("INSERT INTO outbox_event (outbox_id,topic,payload,created_at,available_at,attempt_count) VALUES (?,?,?,?,?,0)",
                id, topic, payload, now, availableAt);
    }

    public List<Map<String, Object>> dueOutbox(long now, int limit) {
        return jdbc.queryForList("SELECT * FROM outbox_event WHERE processed_at IS NULL AND available_at<=? AND topic IN ('device.reboot','commission.connect','commission.run','eo.track.begin','eo.track.end','eo.camera.status','device.control.lingyun','device.control.countermeasure') ORDER BY available_at,created_at OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY",
                now, limit);
    }

    public int claimOutbox(String id, long oldAvailableAt, long leaseUntil) {
        return jdbc.update("UPDATE outbox_event SET available_at=?, attempt_count=attempt_count+1 WHERE outbox_id=? AND processed_at IS NULL AND available_at=?",
                leaseUntil, id, oldAvailableAt);
    }

    public void completeOutbox(String id, long now) {
        jdbc.update("UPDATE outbox_event SET processed_at=?, last_error=NULL WHERE outbox_id=?", now, id);
    }

    public void failOutbox(String id, long nextAt, String error) {
        jdbc.update("UPDATE outbox_event SET available_at=?, last_error=? WHERE outbox_id=? AND processed_at IS NULL",
                nextAt, error == null ? "adapter failure" : error.substring(0, Math.min(error.length(), 500)), id);
    }

    public void addReceipt(String receiptId, String commandId, String inboxId, String resultCode, long now, String payload) {
        jdbc.update("INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at) VALUES (?,?,?,?)",
                inboxId, "mock-device-adapter", "receipt:" + commandId, now);
        jdbc.update("INSERT INTO command_receipt (receipt_id,command_id,inbox_id,receipt_kind,device_result_code,occurred_at,received_at,payload) VALUES (?,?,?,?,?,?,?,?)",
                receiptId, commandId, inboxId, "COMPLETED", resultCode, now, now, payload);
    }

    public void markDeviceRecovered(String deviceId, long now) {
        jdbc.update("""
                UPDATE ops_device_state SET connectivity='ONLINE', has_alarm=FALSE, health_code='GOOD',
                    observed_at=?, received_at=?, last_heartbeat_at=?, unknown_reason=NULL, version=version+1
                WHERE device_id=?
                """, now, now, now, deviceId);
    }

    public Map<String, Object> findIncident(String incidentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.*, d.device_no, d.name AS device_name
                FROM device_incident i JOIN ops_device d ON d.device_id=i.device_id
                WHERE i.incident_id=?
                """, incidentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁状态行串行化建单与心跳更新；从未收到心跳、停用设备都不视作离线异常。 */
    public List<Map<String, Object>> lockMqttConnectivity() {
        return jdbc.queryForList("""
                SELECT s.device_id,s.connectivity,s.last_heartbeat_at,d.source_mode
                FROM ops_device_state s JOIN ops_device d ON d.device_id=s.device_id
                WHERE d.enabled=TRUE AND s.last_heartbeat_at IS NOT NULL
                  AND (EXISTS (SELECT 1 FROM mqtt_device_binding b JOIN mqtt_broker m ON m.broker_id=b.broker_id
                               WHERE b.ops_device_id=d.device_id AND m.enabled=TRUE)
                    OR EXISTS (SELECT 1 FROM eo_device_binding b JOIN mqtt_broker m ON m.broker_id=b.broker_id
                               WHERE b.ops_device_id=d.device_id AND m.enabled=TRUE))
                ORDER BY s.device_id FOR UPDATE
                """);
    }

    public boolean openMqttIncident(String deviceId, long now, boolean simulated) {
        String id = UUID.randomUUID().toString();
        return jdbc.update("""
                INSERT INTO device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated,block_reason)
                SELECT ?,?,?,'MQTT_HEARTBEAT_TIMEOUT','HIGH','PENDING',?,?,?,?
                WHERE NOT EXISTS (SELECT 1 FROM device_incident WHERE device_id=?
                    AND incident_type='MQTT_HEARTBEAT_TIMEOUT' AND stage<>'RECOVERED')
                """, id, deviceId, "INC-" + id.replace("-", ""), now,
                "有效 MQTT 心跳超时；请检查设备电源、网络和发布进程", simulated,
                "协议未定义重启指令；恢复心跳后执行平台恢复核验", deviceId) == 1;
    }

    public int startIncidentReboot(String incidentId, String commandId) {
        return jdbc.update("""
                UPDATE device_incident SET stage='PROCESSING', reboot_command_id=?, block_reason=NULL
                WHERE incident_id=? AND stage='PENDING'
                """, commandId, incidentId);
    }

    public int advanceIncidentByCommand(String commandId, String fromStage, String toStage) {
        return jdbc.update("UPDATE device_incident SET stage=? WHERE reboot_command_id=? AND stage=?",
                toStage, commandId, fromStage);
    }

    public int closeIncident(String incidentId, String expectedStage, long closedAt) {
        return jdbc.update("UPDATE device_incident SET stage='RECOVERED', closed_at=? WHERE incident_id=? AND stage=?",
                closedAt, incidentId, expectedStage);
    }

    public void insertRecoveryCheck(String checkId, String incidentId, String checkedBy, long checkedAt,
                                    String result, String ruleVersion, String snapshot, String reason) {
        jdbc.update("""
                INSERT INTO device_recovery_check (check_id,incident_id,checked_by,checked_at,result,rule_version,state_snapshot,reason)
                VALUES (?,?,?,?,?,?,?,?)
                """, checkId, incidentId, checkedBy, checkedAt, result, ruleVersion, snapshot, reason);
    }

    public Map<String, Object> latestRecoveryCheck(String incidentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM device_recovery_check WHERE incident_id=?
                ORDER BY checked_at DESC, check_id DESC OFFSET 0 ROWS FETCH FIRST 1 ROW ONLY
                """, incidentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findRecoveryCheck(String checkId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM device_recovery_check WHERE check_id=?", checkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SqlWhere where(DeviceQuery q) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        sql.append(mqttScope(p));
        if (q.keyword != null && !q.keyword.isBlank()) {
            sql.append(" AND (LOWER(d.device_no) LIKE :keyword OR LOWER(d.name) LIKE :keyword)");
            p.put("keyword", "%" + q.keyword.toLowerCase() + "%");
        }
        add(sql, p, "d.device_type_code", "type_code", q.typeCode);
        add(sql, p, "d.channel", "channel", q.channel);
        add(sql, p, "d.region_name", "region", q.region);
        add(sql, p, "d.vendor", "vendor", q.vendor);
        add(sql, p, "s.connectivity", "connectivity", q.connectivity);
        if (q.enabled != null) {
            sql.append(" AND d.enabled=:enabled");
            p.put("enabled", q.enabled);
        }
        return new SqlWhere(sql.toString(), p);
    }

    private static void add(StringBuilder sql, Map<String, Object> p, String column, String key, String value) {
        if (value == null || value.isBlank()) return;
        sql.append(" AND ").append(column).append("=:").append(key);
        p.put(key, value);
    }

    // Preserve legacy device visibility while enforcing explicit tuple scope for the new MQTT registrations.
    private String mqttScope(Map<String,Object> params) {
        var actor=com.uav.lowaltitude.platform.security.AuthContext.get();
        if(actor==null || "ALL".equals(actor.scopeMode())) return "";
        params.put("mqtt_actor",actor.userId());
        return """
                 AND (NOT EXISTS(SELECT 1 FROM mqtt_device_binding mb WHERE mb.ops_device_id=d.device_id)
                 OR EXISTS(SELECT 1 FROM device_business_scope bs JOIN app_user_data_scope us
                     ON us.org_id=bs.owner_org_id AND us.district_id=bs.district_id
                     WHERE bs.ops_device_id=d.device_id AND us.user_id=:mqtt_actor))
                """;
    }

    public record DeviceQuery(String keyword, String typeCode, String channel, String region,
                              String vendor, String connectivity, Boolean enabled) {
    }

    private record SqlWhere(String sql, Map<String, Object> params) {
    }
}
