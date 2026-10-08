package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.device.api.LocalQaDeviceStatusDtos.Input;

@Repository
public class LocalQaDeviceStatusRepository {
    private final JdbcTemplate jdbc;
    public LocalQaDeviceStatusRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Device(String sourceId,String sourceMode,boolean simulated,boolean enabled,boolean sourceEnabled,Long observedAt) { }
    public record Previous(String hash,long receivedAt) { }
    /** Catalog lock serializes enable/source edits; state lock shares ordering with protocol reports. */
    public Device lock(String id){
        var found=jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_id=? AND deleted_at IS NULL FOR UPDATE",id);
        if(found.isEmpty())return null;
        List<Device> rows=jdbc.query("""
                SELECT d.source_id,d.source_mode,d.simulated,d.enabled,s.enabled AS source_enabled,st.observed_at
                FROM ops_device d JOIN ops_integration_source s ON s.source_id=d.source_id
                JOIN mqtt_device_binding b ON b.ops_device_id=d.device_id AND b.ops_source_id=d.source_id
                LEFT JOIN ops_device_state st ON st.device_id=d.device_id WHERE d.device_id=?
                """,(r,n)->new Device(r.getString("source_id"),r.getString("source_mode"),r.getBoolean("simulated"),
                    r.getBoolean("enabled"),r.getBoolean("source_enabled"),(Long)r.getObject("observed_at")),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Previous previous(String source,String message){
        var rows=jdbc.query("SELECT payload_sha256,received_at FROM inbox_message WHERE source=? AND source_msg_id=?",
                (r,n)->new Previous(r.getString(1),r.getLong(2)),source,message);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Long currentObserved(String id){return jdbc.queryForObject("SELECT observed_at FROM ops_device_state WHERE device_id=?",Long.class,id);}
    public void save(Input input,String source,String hash,byte[] raw,long receivedAt){
        jdbc.update("""
                INSERT INTO inbox_message(inbox_id,source,source_msg_id,received_at,ops_source_id,
                    protocol_message_key,payload_sha256,payload_bytes,processing_status,ops_processed_at)
                VALUES(?,?,?,?,?,?,?,?,'PROCESSED',?)
                """,UUID.randomUUID().toString(),source,input.messageId(),receivedAt,input.sourceId(),input.messageId(),hash,raw,receivedAt);
        jdbc.update("""
                UPDATE ops_device_state SET connectivity=?,work_state_code='QA_STATUS',health_code=?,has_alarm=?,
                    observed_at=?,received_at=?,last_heartbeat_at=?,simulated=TRUE,
                    unknown_reason='LOCAL_QA_STATUS：独立模拟健康输入，非协议 A 工参',version=version+1
                WHERE device_id=?
                """,input.connectivity(),input.healthCode(),input.hasAlarm(),input.observedAt(),receivedAt,
                    "ONLINE".equals(input.connectivity())?input.observedAt():null,input.deviceId());
    }
}
