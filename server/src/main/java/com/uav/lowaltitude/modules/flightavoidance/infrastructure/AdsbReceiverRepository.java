package com.uav.lowaltitude.modules.flightavoidance.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.ReceiverProfile;

/** 航班接收机在设备台账里的登记。真实设备的接入方式要按厂家说明书确定，登记后保持待接入（停用）。 */
@Repository
public class AdsbReceiverRepository {
    public static final String TYPE = "adsb_receiver", TYPE_NAME = "ADS-B 航班接收机";
    private final JdbcTemplate jdbc;

    public AdsbReceiverRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ReceiverProfile find(String id) {
        var rows = jdbc.query("""
                SELECT d.*,s.owner_org_id,s.district_id,a.receive_range_km,a.station_guid,a.last_data_at FROM ops_device d
                JOIN device_business_scope s ON s.ops_device_id=d.device_id JOIN adsb_receiver a ON a.device_id=d.device_id
                WHERE d.device_id=? AND d.device_type_code='adsb_receiver' AND d.deleted_at IS NULL
                """, PROFILE, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按机身识别码找接收机（含停用的，数据先收着，启用后才参与检查）；没登记的识别码返回 null。 */
    public ReceiverProfile findByStationGuid(String stationGuid) {
        var rows = jdbc.query("""
                SELECT d.*,s.owner_org_id,s.district_id,a.receive_range_km,a.station_guid,a.last_data_at FROM ops_device d
                JOIN device_business_scope s ON s.ops_device_id=d.device_id JOIN adsb_receiver a ON a.device_id=d.device_id
                WHERE a.station_guid=? AND d.device_type_code='adsb_receiver' AND d.deleted_at IS NULL
                """, PROFILE, stationGuid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final org.springframework.jdbc.core.RowMapper<ReceiverProfile> PROFILE = (r, n) -> new ReceiverProfile(
            r.getString("device_id"), r.getString("device_no"), r.getString("name"), r.getString("vendor"), r.getString("model"),
            r.getString("address"), r.getString("owner_org_id"), r.getString("district_id"), r.getBigDecimal("longitude"),
            r.getBigDecimal("latitude"), r.getInt("receive_range_km"), r.getString("station_guid"),
            (Long) r.getObject("last_data_at", Long.class), r.getBoolean("enabled"), r.getBoolean("simulated"), r.getLong("version"));

    /** 同编号（含已删除）的设备行，模拟器重复登记时据此续用。 */
    public List<Map<String, Object>> byNumber(String deviceNo) {
        return jdbc.queryForList("""
                SELECT d.device_id,d.device_type_code,d.simulated,d.source_mode,d.enabled,d.deleted_at,s.owner_org_id,s.district_id
                FROM ops_device d LEFT JOIN device_business_scope s ON s.ops_device_id=d.device_id WHERE d.device_no=?
                """, deviceNo);
    }

    public boolean activeScope(String org, String district) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_org o CROSS JOIN app_district d WHERE o.org_id=? AND d.district_id=? AND o.enabled=TRUE AND d.enabled=TRUE",
                Long.class, org, district) == 1;
    }

    public void insert(String id, String deviceNo, String name, String vendor, String model, String address, String org,
            String district, BigDecimal longitude, BigDecimal latitude, int rangeKm, String stationGuid, boolean simulated, long now) {
        jdbc.update("""
                INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,0,?,?)
                """, id, "adsb-" + id, name, simulated ? "ADSB_SIMULATOR" : "ADSB_PENDING", simulated ? "replay" : "live", simulated,
                simulated, now, now);
        jdbc.update("""
                INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,
                    channel,vendor,model,address,owner_name,region_name,longitude,latitude,coordinate_system,enabled,source_mode,
                    simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'adsb_receiver',?,?,?,?,?,(SELECT name FROM app_org WHERE org_id=?),
                    (SELECT name FROM app_district WHERE district_id=?),?,?,'WGS-84',?,?,?,0,?,?)
                """, id, id, deviceNo, deviceNo, name, TYPE_NAME, simulated ? "设备模拟器" : "待接入", vendor, model, address,
                org, district, longitude, latitude, simulated, simulated ? "replay" : "live", simulated, now, now);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?)",
                id, org, district, new Timestamp(now), new Timestamp(now));
        jdbc.update("INSERT INTO adsb_receiver(device_id,receive_range_km,station_guid) VALUES(?,?,?)", id, rangeKm, stationGuid);
    }

    /** 只改资料、位置、接收范围和识别码；编号与归属不在这里改。 */
    public int update(String id, String name, String vendor, String model, String address, BigDecimal longitude,
            BigDecimal latitude, int rangeKm, String stationGuid, long version, long now) {
        int changed = jdbc.update("""
                UPDATE ops_device SET name=?,vendor=?,model=?,address=?,longitude=?,latitude=?,coordinate_system='WGS-84',
                    version=version+1,updated_at=?
                WHERE device_id=? AND device_type_code='adsb_receiver' AND version=? AND deleted_at IS NULL
                """, name, vendor, model, address, longitude, latitude, now, id, version);
        if (changed == 1) jdbc.update("UPDATE adsb_receiver SET receive_range_km=?,station_guid=? WHERE device_id=?", rangeKm, stationGuid, id);
        return changed;
    }

    /** 模拟结束：模拟接收机停用，之后的反制不再按它检查。 */
    public int disableSimulated(String id, long now) {
        int changed = jdbc.update("""
                UPDATE ops_device SET enabled=FALSE,version=version+1,updated_at=?
                WHERE device_id=? AND device_type_code='adsb_receiver' AND simulated=TRUE AND enabled=TRUE AND deleted_at IS NULL
                """, now, id);
        jdbc.update("UPDATE ops_integration_source SET enabled=FALSE,updated_at=? WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)",
                now, id);
        return changed;
    }

    /** 模拟器再次登记：位置、范围跟着场景走，停用的重新启用。 */
    public void refreshSimulated(String id, String name, BigDecimal longitude, BigDecimal latitude, int rangeKm, String stationGuid, long now) {
        jdbc.update("""
                UPDATE ops_device SET name=?,longitude=?,latitude=?,coordinate_system='WGS-84',enabled=TRUE,version=version+1,updated_at=?
                WHERE device_id=? AND device_type_code='adsb_receiver' AND simulated=TRUE AND deleted_at IS NULL
                """, name, longitude, latitude, now, id);
        jdbc.update("UPDATE ops_integration_source SET enabled=TRUE,updated_at=? WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)",
                now, id);
        jdbc.update("UPDATE adsb_receiver SET receive_range_km=?,station_guid=? WHERE device_id=?", rangeKm, stationGuid, id);
    }
}
