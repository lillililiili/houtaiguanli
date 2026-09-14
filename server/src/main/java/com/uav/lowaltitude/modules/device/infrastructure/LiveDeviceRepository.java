package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiveDeviceRepository {

    private final JdbcTemplate jdbc;

    public LiveDeviceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> enabledDevices() {
        return jdbc.queryForList("""
                SELECT d.device_id,d.device_no,d.source_id,s.source_code,s.protocol_code,s.allowed_cidrs,
                       s.credential_ref AS source_credential_ref,p.transport,p.host,p.port,p.timeout_millis,
                       p.credential_ref,r.login_role,r.recognition_code_ref,r.rtk_enabled,
                       r.coordinate_transform_enabled,c.device_address,c.wire_encoding,c.poll_interval_millis
                FROM ops_device d JOIN ops_integration_source s ON s.source_id=d.source_id
                JOIN device_connection_profile p ON p.device_id=d.device_id
                LEFT JOIN radar_v3_profile r ON r.device_id=d.device_id
                LEFT JOIN countermeasure_4ch_profile c ON c.device_id=d.device_id
                WHERE d.enabled=TRUE AND d.source_mode='live' AND s.enabled=TRUE AND s.source_mode='live'
                  AND s.protocol_code IN ('RADAR_TCP_V3_0_0','COUNTERMEASURE_TCP_4CH_V2_0')
                """);
    }

    public boolean stillEnabled(String deviceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ops_device d JOIN ops_integration_source s ON s.source_id=d.source_id
                WHERE d.device_id=? AND d.enabled=TRUE AND s.enabled=TRUE AND d.source_mode='live'
                """, Integer.class, deviceId);
        return count != null && count > 0;
    }

    public boolean claim(String deviceId, String owner, String token, long now, long leaseUntil) {
        int updated = jdbc.update("""
                UPDATE device_connection_lease SET owner_id=?,lease_token=?,lease_until=?,updated_at=?
                WHERE device_id=? AND (lease_until<? OR owner_id=?) AND (reconnect_at IS NULL OR reconnect_at<=?)
                """, owner, token, leaseUntil, now, deviceId, now, owner, now);
        if (updated == 1) return true;
        try {
            jdbc.update("""
                    INSERT INTO device_connection_lease (device_id,owner_id,lease_token,lease_until,failure_count,updated_at)
                    VALUES (?,?,?,?,0,?)
                    """, deviceId, owner, token, leaseUntil, now);
            return true;
        } catch (DataIntegrityViolationException occupied) { return false; }
    }

    public boolean renew(String deviceId, String owner, String token, long leaseUntil, long now) {
        return jdbc.update("""
                UPDATE device_connection_lease SET lease_until=?,updated_at=?
                WHERE device_id=? AND owner_id=? AND lease_token=?
                """, leaseUntil, now, deviceId, owner, token) == 1;
    }

    public void success(String deviceId, String owner, String token, long leaseUntil, long now) {
        jdbc.update("""
                UPDATE device_connection_lease SET lease_until=?,failure_count=0,last_error=NULL,reconnect_at=NULL,updated_at=?
                WHERE device_id=? AND owner_id=? AND lease_token=?
                """, leaseUntil, now, deviceId, owner, token);
    }

    public void failed(String deviceId, String owner, String token, long reconnectAt, String error, long now) {
        jdbc.update("""
                UPDATE device_connection_lease SET lease_until=0,reconnect_at=?,failure_count=failure_count+1,
                    last_error=?,updated_at=? WHERE device_id=? AND owner_id=? AND lease_token=?
                """, reconnectAt, truncate(error), now, deviceId, owner, token);
    }

    public void release(String deviceId, String owner, String token, long now) {
        jdbc.update("""
                UPDATE device_connection_lease SET lease_until=0,reconnect_at=NULL,updated_at=?
                WHERE device_id=? AND owner_id=? AND lease_token=?
                """, now, deviceId, owner, token);
    }

    public int failureCount(String deviceId) {
        List<Integer> rows = jdbc.query("SELECT failure_count FROM device_connection_lease WHERE device_id=?",
                (rs, row) -> rs.getInt(1), deviceId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.substring(0, Math.min(1000, value.length()));
    }
}
