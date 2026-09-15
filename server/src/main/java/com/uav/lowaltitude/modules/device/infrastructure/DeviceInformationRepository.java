package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeviceInformationRepository {
    private final JdbcTemplate jdbc;
    public DeviceInformationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> mqtt(String deviceId, boolean eo) {
        String table = eo ? "eo_device_binding" : "mqtt_device_binding";
        return first(jdbc.queryForList("SELECT m.*,b.name AS broker_name,b.host,b.port,b.tls,b.enabled AS broker_enabled,"
                + "l.connection_state,l.last_error,l.lease_until FROM " + table + " m JOIN mqtt_broker b ON b.broker_id=m.broker_id "
                + "LEFT JOIN mqtt_session_lease l ON l.broker_id=m.broker_id WHERE m.ops_device_id=?", deviceId));
    }
    public Map<String, Object> latestSense(String source, Long ptTime, Long msgCnt) {
        if (ptTime == null || msgCnt == null) return Map.of();
        return first(jdbc.queryForList("SELECT CAST(payload AS VARCHAR) AS payload_json,received_at FROM inbox_message "
                + "WHERE source=? AND source_msg_id=?", source, ptTime + ":" + msgCnt));
    }
    public Map<String, Object> pointSummary(String deviceId) {
        return first(jdbc.queryForList("SELECT * FROM radar_point_summary WHERE device_id=?", deviceId));
    }
    public Map<String, Object> latestRtk(String deviceId) {
        return first(jdbc.queryForList("SELECT * FROM radar_rtk_sample WHERE device_id=? "
                + "ORDER BY received_at DESC,sample_id DESC FETCH FIRST 1 ROWS ONLY", deviceId));
    }
    public Map<String, Object> latestControlResponse(String deviceId) {
        return first(jdbc.queryForList("""
                SELECT c.command_no,c.command_type,c.status,r.device_result_code,r.occurred_at,r.received_at
                FROM device_command c JOIN command_receipt r ON r.command_id=c.command_id
                WHERE c.device_id=? ORDER BY r.received_at DESC,r.receipt_id DESC FETCH FIRST 1 ROWS ONLY
                """, deviceId));
    }
    public boolean inScope(String deviceId, String userId, String mode) {
        if ("ALL".equals(mode)) return true;
        if (!"ASSIGNED".equals(mode)) return false;
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM device_business_scope s
                JOIN app_user_data_scope u ON u.org_id=s.owner_org_id AND u.district_id=s.district_id
                JOIN app_org o ON o.org_id=s.owner_org_id AND o.enabled=TRUE
                JOIN app_district d ON d.district_id=s.district_id AND d.enabled=TRUE
                WHERE s.ops_device_id=? AND u.user_id=?
                """, Long.class, deviceId, userId) > 0;
    }
    private static Map<String, Object> first(List<Map<String, Object>> rows) { return rows.isEmpty() ? Map.of() : rows.get(0); }
}
