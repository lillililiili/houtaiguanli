package com.uav.lowaltitude.modules.device.infrastructure;

import java.math.BigDecimal;
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
    public void lockScope(String org) {
        jdbc.queryForObject("SELECT org_id FROM app_org WHERE org_id=? FOR UPDATE",String.class,org);
    }
    public List<Map<String,Object>> existing(String sourceCode) {
        return jdbc.queryForList("""
            SELECT s.source_id,s.protocol_code,s.source_mode,s.simulated AS source_simulated,
                   s.enabled AS source_enabled,s.allowed_cidrs,d.device_id,d.device_no,
                   d.external_device_id,d.source_mode AS device_source_mode,d.simulated AS device_simulated,
                   d.enabled AS device_enabled,d.deleted_at,s.version AS source_version,d.version AS device_version,
                   d.longitude,d.latitude,
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
    /** 是否已有覆盖范围（不论来自设备管理还是之前的模拟器登记）。 */
    public boolean hasSensingProfile(String deviceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM device_sensing_profile WHERE device_id=?",Long.class,deviceId)>0;
    }
    /** 只给还没有覆盖范围的本机模拟设备补一个圆形作用半径；已有的（例如在设备管理里配过）一概不改。 */
    public boolean fillCircleCoverage(String deviceId, BigDecimal radiusM, String sourceLabel, long now) {
        return jdbc.update("""
                INSERT INTO device_sensing_profile(device_id,coverage_kind,radius_m,range_m,azimuth_deg,fov_deg,source_label,version,updated_at)
                SELECT d.device_id,'CIRCLE',?,NULL,NULL,NULL,?,0,? FROM ops_device d
                WHERE d.device_id=? AND d.source_mode='live' AND d.simulated=TRUE AND d.deleted_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM device_sensing_profile p WHERE p.device_id=d.device_id)
                """, radiusM, sourceLabel, now, deviceId) == 1;
    }
    /** Only a device without any position gets one; a position someone already set (设备管理) is never moved. */
    public boolean fillPosition(String deviceId, BigDecimal longitude, BigDecimal latitude, long now) {
        return jdbc.update("""
                UPDATE ops_device SET longitude=?, latitude=?, coordinate_system='WGS-84', version=version+1, updated_at=?
                WHERE device_id=? AND longitude IS NULL AND latitude IS NULL AND source_mode='live' AND simulated=TRUE
                """, longitude, latitude, now, deviceId) == 1;
    }
    public void markNewSourceSimulated(String id) {
        if(jdbc.update("UPDATE ops_integration_source SET simulated=TRUE WHERE source_id=? AND enabled=FALSE AND version=0",id)!=1)
            throw new IllegalStateException("new QA source changed before registration");
    }
    public void bindScope(String id,String org,String district,long now) {
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?)",
            id,org,district,new Timestamp(now),new Timestamp(now));
    }
    /** 启用中的单位与区县名称；任一不存在或已停用时返回 null。 */
    public String[] scopeNames(String org,String district) {
        var rows=jdbc.query("SELECT o.name,d.name FROM app_org o CROSS JOIN app_district d WHERE o.org_id=? AND d.district_id=? AND o.enabled=TRUE AND d.enabled=TRUE",
            (r,n)->new String[]{r.getString(1),r.getString(2)},org,district);
        return rows.isEmpty()?null:rows.get(0);
    }
}
