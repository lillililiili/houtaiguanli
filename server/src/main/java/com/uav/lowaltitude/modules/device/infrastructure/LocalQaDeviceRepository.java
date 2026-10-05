package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only invoked inside the guarded QA provisioning transaction. No equipment observations are manufactured. */
@Repository
public class LocalQaDeviceRepository {
    private final JdbcTemplate jdbc;
    public LocalQaDeviceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Map<String,Object>> existing(String sourceCode) {
        return jdbc.queryForList("""
            SELECT s.source_id,s.protocol_code,s.source_mode,s.simulated AS source_simulated,
                   s.enabled AS source_enabled,s.allowed_cidrs,d.device_id,d.device_no,
                   d.external_device_id,d.source_mode AS device_source_mode,d.simulated AS device_simulated,
                   d.enabled AS device_enabled,d.deleted_at,s.version AS source_version,d.version AS device_version,
                   p.transport,p.host,p.port,
                   b.owner_org_id,b.district_id
            FROM ops_integration_source s
            LEFT JOIN ops_device d ON d.source_id=s.source_id
            LEFT JOIN device_connection_profile p ON p.device_id=d.device_id
            LEFT JOIN device_business_scope b ON b.ops_device_id=d.device_id
            WHERE s.source_code=?
            """,sourceCode);
    }
    public boolean restoreDevice(String deviceId, long expectedVersion, long now) {
        return jdbc.update("""
                UPDATE ops_device SET enabled=TRUE, deleted_at=NULL, version=version+1, updated_at=?
                WHERE device_id=? AND version=? AND source_mode='live' AND simulated=TRUE
                """, now, deviceId, expectedVersion) == 1;
    }
    public void markNewSourceSimulated(String id) {
        if(jdbc.update("UPDATE ops_integration_source SET simulated=TRUE WHERE source_id=? AND enabled=FALSE AND version=0",id)!=1)
            throw new IllegalStateException("new QA source changed before registration");
    }
    public void bindScope(String id,String org,String district,long now) {
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?)",
            id,org,district,new Timestamp(now),new Timestamp(now));
    }
}
