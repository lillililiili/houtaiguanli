package com.uav.lowaltitude.modules.device.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared event persistence. Callers hold a transaction; lock order is state row, then window rows. */
@Repository
public class DeviceMonitoringEventRepository {
    public static final long WINDOW_MILLIS = 30_000;
    private static final Map<String,String> COUNTERS = Map.of("HEARTBEAT","heartbeat_count", "PARAMETERS","parameters_count",
            "SENSING","sensing_count", "STATUS","status_count");
    private final JdbcTemplate jdbc;
    public DeviceMonitoringEventRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Snapshot(String deviceId,String sourceMode,boolean simulated,String connectivity,String workState) { }

    /** TCP catalog registration has no observed state yet. Serialize first reports on the catalog row. */
    public Snapshot lockOrInitialize(String deviceId,long receivedAt) {
        requireTransaction();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_state WHERE device_id=?",Long.class,deviceId)==0) {
            var device=jdbc.queryForMap("SELECT source_mode,simulated FROM ops_device WHERE device_id=? FOR UPDATE",deviceId);
            boolean simulated=!"live".equals(device.get("source_mode"))||Boolean.TRUE.equals(device.get("simulated"));
            jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,has_alarm,health_code,simulated,received_at,version) SELECT ?,'UNKNOWN',FALSE,'UNKNOWN',?,?,0 WHERE NOT EXISTS(SELECT 1 FROM ops_device_state WHERE device_id=?)",
                    deviceId,simulated,receivedAt,deviceId);
        }
        return lock(deviceId);
    }

    public Snapshot lock(String deviceId) {
        requireTransaction();
        var states=jdbc.queryForList("SELECT connectivity,work_state_code FROM ops_device_state WHERE device_id=? FOR UPDATE",deviceId);
        if(states.isEmpty()) throw new IllegalStateException("DEVICE_STATE_MISSING");
        var device=jdbc.queryForMap("SELECT source_mode,simulated FROM ops_device WHERE device_id=?",deviceId);
        String mode=(String)device.get("source_mode");
        return new Snapshot(deviceId,mode,!"live".equals(mode) || Boolean.TRUE.equals(device.get("simulated")),
                (String)states.get(0).get("connectivity"),(String)states.get(0).get("work_state_code"));
    }

    public void accepted(Snapshot before,String category,long receivedAt) {
        requireTransaction();
        String counter=COUNTERS.get(category);
        if(counter==null) throw new IllegalArgumentException("Unsupported monitoring report category");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM device_report_cursor WHERE device_id=? AND source_mode=?",Long.class,
                before.deviceId(),before.sourceMode())==0) {
            jdbc.update("INSERT INTO device_report_cursor(device_id,source_mode,started_at) VALUES (?,?,?)",
                    before.deviceId(),before.sourceMode(),receivedAt);
            event(before,"REPORTING_STARTED","INFO","开始接收上报（启用监测记录后的首条有效报文，来源："+before.sourceMode()+"）",receivedAt);
        }
        changed(before,receivedAt);
        long start=Math.floorDiv(receivedAt,WINDOW_MILLIS)*WINDOW_MILLIS;
        long closedBefore=jdbc.queryForObject("SELECT closed_before FROM device_report_cursor WHERE device_id=? AND source_mode=?",Long.class,before.deviceId(),before.sourceMode());
        int updated=jdbc.update("UPDATE device_report_window SET "+counter+"="+counter+"+1,first_received_at=LEAST(first_received_at,?),last_received_at=GREATEST(last_received_at,?) WHERE device_id=? AND source_mode=? AND window_start=?",
                receivedAt,receivedAt,before.deviceId(),before.sourceMode(),start);
        if(updated==0) jdbc.update("INSERT INTO device_report_window(device_id,source_mode,window_start,"+counter+",first_received_at,last_received_at,simulated,late_arrival) VALUES (?,?,?,1,?,?,?,?)",
                before.deviceId(),before.sourceMode(),start,receivedAt,receivedAt,before.simulated(),start<closedBefore);
    }

    public void changed(Snapshot before,long at) {
        requireTransaction();
        var after=jdbc.queryForMap("SELECT connectivity,work_state_code FROM ops_device_state WHERE device_id=?",before.deviceId());
        String connection=(String)after.get("connectivity"),work=(String)after.get("work_state_code");
        if(!Objects.equals(before.connectivity(),connection)) {
            if("ONLINE".equals(connection)) {
                boolean recovered="OFFLINE".equals(before.connectivity());
                event(before,recovered?"RECOVERED":"CONNECTED","INFO",recovered?"收到有效上报，设备恢复在线":"收到有效上报，设备上线",at);
            } else if("ONLINE".equals(before.connectivity()) && "OFFLINE".equals(connection))
                event(before,"DISCONNECTED","WARN","设备离线：有效上报超时或协议会话断开",at);
        }
        if("ONLINE".equals(before.connectivity()) && "ONLINE".equals(connection)
                && before.workState()!=null && work!=null && !Objects.equals(before.workState(),work))
            event(before,"STATE_CHANGED","INFO","工作状态变化："+before.workState()+" → "+work,at);
    }

    public List<String> dueDevices(long now) {
        return jdbc.queryForList("SELECT DISTINCT device_id FROM device_report_window WHERE window_start<=? ORDER BY device_id FETCH FIRST 200 ROWS ONLY",
                String.class,now-WINDOW_MILLIS);
    }

    /** Protocol-specific reported work modes, independent of generic connection state. */
    public void workChanged(Snapshot before,String previous,String next,long at) {
        requireTransaction();
        if(previous!=null && next!=null && !Objects.equals(previous,next))
            event(before,"STATE_CHANGED","INFO","设备上报工作状态变化："+previous+" → "+next,at);
    }

    /** Per-device lock serializes receive/flush and concurrent worker instances. */
    public void flush(String deviceId,long now) {
        Snapshot current=lock(deviceId);
        var windows=jdbc.queryForList("SELECT * FROM device_report_window WHERE device_id=? AND window_start<=? ORDER BY window_start,source_mode FOR UPDATE",
                deviceId,now-WINDOW_MILLIS);
        for(var row:windows) {
            long start=((Number)row.get("window_start")).longValue();
            String mode=(String)row.get("source_mode");
            Snapshot source=new Snapshot(deviceId,mode,Boolean.TRUE.equals(row.get("simulated")),current.connectivity(),current.workState());
            boolean late=Boolean.TRUE.equals(row.get("late_arrival"));
            String message=(late?"排队报文补充汇总（仅本次新增计数） [":"上报汇总 [")+Instant.ofEpochMilli(start)+" ～ "+Instant.ofEpochMilli(start+WINDOW_MILLIS)+")：心跳 "+row.get("heartbeat_count")
                    +"，工参 "+row.get("parameters_count")+"，感知 "+row.get("sensing_count")+"，状态 "+row.get("status_count")
                    +"；最后接收 "+Instant.ofEpochMilli(((Number)row.get("last_received_at")).longValue())+"；来源："+mode;
            event(source,late?"REPORT_LATE_SUMMARY":"REPORT_SUMMARY","INFO",message,now);
            jdbc.update("UPDATE device_report_cursor SET closed_before=GREATEST(closed_before,?) WHERE device_id=? AND source_mode=?",start+WINDOW_MILLIS,deviceId,mode);
            jdbc.update("DELETE FROM device_report_window WHERE device_id=? AND source_mode=? AND window_start=?",deviceId,mode,start);
        }
    }

    private void event(Snapshot source,String type,String level,String message,long at) {
        jdbc.update("INSERT INTO device_event_log(event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),source.deviceId(),type,level,message,at,source.simulated());
    }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("MONITORING_EVENT_REQUIRES_TRANSACTION");
    }
}
