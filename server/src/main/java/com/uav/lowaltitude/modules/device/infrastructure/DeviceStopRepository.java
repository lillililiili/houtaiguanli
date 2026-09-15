package com.uav.lowaltitude.modules.device.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeviceStopRepository {
    private final JdbcTemplate jdbc;
    public DeviceStopRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lock(String commandId) {
        jdbc.queryForList("SELECT command_id FROM device_command WHERE command_id=? FOR UPDATE",commandId);
    }
    public void cancelDelivery(String commandId,long at) {
        lock(commandId);
        jdbc.update("UPDATE device_command SET status='CANCELLED',result_code='EMERGENCY_STOP',"
                + "result_detail='本事件急停，停止投递和重发；此前已发送的设备动作仍需核查',completed_at=?,updated_at=?"
                + " WHERE command_id=? AND status IN ('QUEUED','SENT','ACCEPTED')",at,at,commandId);
    }
    public java.util.List<String> authorizationStarts(String authorizationId) {
        java.util.List<String> ids=new java.util.ArrayList<>(jdbc.queryForList(
                "SELECT command_id FROM countermeasure_4ch_command WHERE authorization_id=?"
                + " AND (action='CHANNEL_ON' OR (action='SET_MASK' AND mask<>0))",String.class,authorizationId));
        ids.addAll(jdbc.queryForList("SELECT command_id FROM lingyun_control_command WHERE authorization_id=?"
                + " AND operation_type<>0",String.class,authorizationId));
        return ids.stream().distinct().sorted().toList();
    }
}
