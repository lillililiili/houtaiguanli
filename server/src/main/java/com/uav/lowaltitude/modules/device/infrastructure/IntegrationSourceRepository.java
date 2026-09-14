package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

@Repository
public class IntegrationSourceRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public IntegrationSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<Map<String, Object>> list(int offset, int size) {
        return jdbc.queryForList("""
                SELECT s.*, (SELECT COUNT(*) FROM ops_device d WHERE d.source_id=s.source_id) AS device_count
                FROM ops_integration_source s ORDER BY s.created_at DESC,s.source_code
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, offset, size);
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM ops_integration_source", Long.class);
        return value == null ? 0 : value;
    }

    public Map<String, Object> find(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.*, (SELECT COUNT(*) FROM ops_device d WHERE d.source_id=s.source_id) AS device_count
                FROM ops_integration_source s WHERE s.source_id=?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(Map<String, Object> p) {
        named.update("""
                INSERT INTO ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,
                    source_mode,enabled,credential_ref,allowed_cidrs,simulated,version,created_at,updated_at)
                VALUES (:source_id,:source_code,:name,:protocol_code,:protocol_version,'live',FALSE,
                    :credential_ref,:allowed_cidrs,FALSE,0,:created_at,:updated_at)
                """, p);
    }

    public int update(String id, long version, Map<String, Object> p) {
        Map<String, Object> values = new HashMap<>(p);
        values.put("source_id", id);
        values.put("version", version);
        return named.update("""
                UPDATE ops_integration_source SET source_code=:source_code,name=:name,protocol_code=:protocol_code,
                    protocol_version=:protocol_version,credential_ref=:credential_ref,allowed_cidrs=:allowed_cidrs,
                    version=version+1,updated_at=:updated_at
                WHERE source_id=:source_id AND version=:version AND source_mode='live'
                """, values);
    }

    public int setEnabled(String id, long version, boolean enabled, long now) {
        return jdbc.update("""
                UPDATE ops_integration_source SET enabled=?,version=version+1,updated_at=?
                WHERE source_id=? AND version=? AND source_mode='live'
                """, enabled, now, id, version);
    }

    public void ensureStandardLiveRadar(String sourceCode, String name, String protocolVersion, boolean enabled, long now) {
        java.sql.Timestamp time = new java.sql.Timestamp(now);
        String version = protocolVersion == null || protocolVersion.isBlank() ? "3.0.0" : protocolVersion.trim();
        jdbc.update("""
                INSERT INTO integration_source (source_id,source_code,name,protocol_code,protocol_version,source_mode,
                    enabled,source_type,created_at,updated_at)
                SELECT ?,?,?,?,?,'live',?,'RADAR',?,?
                WHERE NOT EXISTS (SELECT 1 FROM integration_source WHERE source_code=?)
                """, java.util.UUID.randomUUID().toString(), sourceCode, name, DeviceProtocolCodes.RADAR_TCP_V3_0_0,
                version, enabled, time, time, sourceCode);
        jdbc.update("""
                UPDATE integration_source SET enabled=?,name=?,protocol_code=?,protocol_version=?,source_type='RADAR',updated_at=?
                WHERE source_code=? AND source_mode='live'
                """, enabled, name, DeviceProtocolCodes.RADAR_TCP_V3_0_0, version, time, sourceCode);
    }

    public List<Map<String, Object>> assignedDevices(String sourceId) {
        return jdbc.queryForList("""
                SELECT d.device_id,d.device_no,d.enabled,p.host,p.port,p.transport,
                       r.device_id AS radar_profile_device_id,r.recognition_code_ref,
                       c.device_id AS counter_profile_device_id
                FROM ops_device d LEFT JOIN device_connection_profile p ON p.device_id=d.device_id
                LEFT JOIN radar_v3_profile r ON r.device_id=d.device_id
                LEFT JOIN countermeasure_4ch_profile c ON c.device_id=d.device_id
                WHERE d.source_id=?
                """, sourceId);
    }
}
