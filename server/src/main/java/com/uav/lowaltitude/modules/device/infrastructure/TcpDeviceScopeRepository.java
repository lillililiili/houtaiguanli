package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** TCP registration's explicit business scope and standard radar device identity. */
@Repository
public class TcpDeviceScopeRepository {
    private final JdbcTemplate jdbc;
    public TcpDeviceScopeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Object> device(String id) {
        return jdbc.queryForMap("""
                SELECT d.*,s.source_code,s.protocol_code,s.protocol_version,s.enabled AS source_enabled
                FROM ops_device d JOIN ops_integration_source s ON s.source_id=d.source_id
                WHERE d.device_id=? FOR UPDATE
                """,id);
    }
    public Map<String,Object> scope(String id) {
        var rows=jdbc.queryForList("SELECT * FROM device_business_scope WHERE ops_device_id=?",id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> standardDevice(String id) {
        var rows=jdbc.queryForList("SELECT * FROM device WHERE device_id=?",id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<Map<String,Object>> validTuple(String org,String district) {
        return jdbc.queryForList("""
                SELECT o.name AS owner_name,d.name AS region_name FROM app_org o CROSS JOIN app_district d
                WHERE o.org_id=? AND d.district_id=? AND o.enabled=TRUE AND d.enabled=TRUE
                """,org,district);
    }
    public String standardSource(String code) {
        return jdbc.queryForObject("SELECT source_id FROM integration_source WHERE source_code=? AND source_mode='live' AND source_type='RADAR'",String.class,code);
    }
    public long devicesOnSource(String source) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE source_id=?",Long.class,source);
    }
    public void saveScope(String id,String org,String district,String ownerName,String regionName,long now) {
        Timestamp time=new Timestamp(now);
        jdbc.update("""
                INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at)
                SELECT ?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM device_business_scope WHERE ops_device_id=?)
                """,id,org,district,time,time,id);
        jdbc.update("UPDATE ops_device SET owner_name=?,region_name=? WHERE device_id=?",ownerName,regionName,id);
    }
    public void saveRadar(Map<String,Object> d,String source,String org,String district,long now) {
        Object id=d.get("device_id"); Timestamp time=new Timestamp(now);
        jdbc.update("""
                INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,
                    model,vendor,source_mode,owner_org_id,district_id,enabled,created_at,updated_at)
                SELECT ?,?,?,?,?,'RADAR',?,?,'live',?,?,?,?,?
                WHERE NOT EXISTS(SELECT 1 FROM device WHERE device_id=?)
                """,id,source,d.get("external_device_id"),d.get("device_no"),d.get("name"),d.get("model"),d.get("vendor"),
                org,district,d.get("enabled"),time,time,id);
        jdbc.update("UPDATE device SET name=?,model=?,vendor=?,enabled=?,updated_at=? WHERE device_id=?",
                d.get("name"),d.get("model"),d.get("vendor"),d.get("enabled"),time,id);
    }
    public void enabled(String id,boolean enabled,long now) {
        jdbc.update("""
                UPDATE device SET enabled=?,updated_at=? WHERE device_id=?
                  AND EXISTS(SELECT 1 FROM ops_device d JOIN ops_integration_source s ON s.source_id=d.source_id
                    WHERE d.device_id=? AND s.protocol_code='RADAR_TCP_V3_0_0')
                """,enabled,new Timestamp(now),id,id);
    }
}
