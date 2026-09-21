package com.uav.lowaltitude.modules.device.infrastructure;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.device.api.WeatherSensorDtos.*;
@Repository
public class WeatherSensorRepository {
    private final JdbcTemplate jdbc;
    public WeatherSensorRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Sensor find(String id) {
        var rows=jdbc.query("""
            SELECT d.*,s.owner_org_id,s.district_id FROM ops_device d
            JOIN device_business_scope s ON s.ops_device_id=d.device_id
            WHERE d.device_id=? AND d.device_type_code='weather_sensor' AND d.deleted_at IS NULL
            """,(r,n)->new Sensor(r.getString("device_id"),r.getString("device_no"),r.getString("name"),
            r.getString("vendor"),r.getString("model"),r.getString("address"),r.getString("owner_org_id"),
            r.getString("district_id"),r.getLong("version")),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public boolean activeScope(String org,String district) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_org o CROSS JOIN app_district d WHERE o.org_id=? AND d.district_id=? AND o.enabled=TRUE AND d.enabled=TRUE",Long.class,org,district)==1;
    }
    public void insert(String id,Input p,long now) {
        jdbc.update("""
            INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,simulated,version,created_at,updated_at)
            VALUES (?,?,?,'WEATHER_PENDING','live',FALSE,FALSE,0,?,?)
            """,id,"weather-"+id,p.name(),now,now);
        jdbc.update("""
            INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,
                channel,vendor,model,address,owner_name,region_name,enabled,source_mode,simulated,version,created_at,updated_at)
            VALUES (?,?,?,?,?,'weather_sensor','天气传感器','待接入',?,?,?,
                (SELECT name FROM app_org WHERE org_id=?),(SELECT name FROM app_district WHERE district_id=?),FALSE,'live',FALSE,0,?,?)
            """,id,id,p.deviceNo(),p.deviceNo(),p.name(),p.vendor(),p.model(),p.address(),p.ownerOrgId(),p.districtId(),now,now);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?)",
            id,p.ownerOrgId(),p.districtId(),new Timestamp(now),new Timestamp(now));
    }
    public int update(String id,Input p,long now) {
        return jdbc.update("""
            UPDATE ops_device SET name=?,vendor=?,model=?,address=?,version=version+1,updated_at=?
            WHERE device_id=? AND device_type_code='weather_sensor' AND version=? AND enabled=FALSE AND deleted_at IS NULL
            """,p.name(),p.vendor(),p.model(),p.address(),now,id,p.version());
    }
}
