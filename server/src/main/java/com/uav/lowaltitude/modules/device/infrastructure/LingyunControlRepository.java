package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LingyunControlRepository {
    private final JdbcTemplate jdbc;
    public LingyunControlRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String commandId, String commandNo, String deviceId, String requestedBy, String reason,
                       String sourceMode, boolean simulated, long deadline, long now, int operationType, int operationCmd,
                       String paramsJson, String authorizationId) {
        jdbc.update("""
                INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,
                    source_mode,simulated,deadline_at,created_at,updated_at,authorization_id)
                VALUES (?,?,?,?,'LINGYUN_CONTROL',?,'QUEUED',?,?,?,?,?,?)
                """, commandId, commandNo, deviceId, requestedBy, reason, sourceMode, simulated, deadline, now, now, authorizationId);
        jdbc.update("""
                INSERT INTO lingyun_control_command(command_id,msg_no,operation_type,operation_cmd,params_json,authorization_id)
                VALUES (?,?,?,?,?,?)
                """, commandId, commandNo, operationType, operationCmd, paramsJson, authorizationId);
    }

    public Map<String, Object> byMsgNo(String msgNo) {
        return jdbc.queryForList("""
                SELECT c.*, x.operation_type,x.operation_cmd,x.params_json,x.authorization_id AS control_authorization_id,
                       m.broker_id,m.provider_code,m.device_type_abbr,m.external_device_id,m.device_id AS standard_device_id
                FROM device_command c
                JOIN lingyun_control_command x ON x.command_id=c.command_id
                JOIN mqtt_device_binding m ON m.ops_device_id=c.device_id
                WHERE x.msg_no=?
                """, msgNo).stream().findFirst().orElse(null);
    }

    public Map<String, Object> control(String commandId) {
        return jdbc.queryForList("""
                SELECT c.*, x.operation_type,x.operation_cmd,x.params_json,x.authorization_id AS control_authorization_id,
                       m.broker_id,m.provider_code,m.device_type_abbr,m.external_device_id,m.device_id AS standard_device_id
                FROM device_command c
                JOIN lingyun_control_command x ON x.command_id=c.command_id
                JOIN mqtt_device_binding m ON m.ops_device_id=c.device_id
                WHERE c.command_id=?
                """, commandId).stream().findFirst().orElse(null);
    }

    public int updateCommand(String commandId, String expected, String status, long now, String code, String detail) {
        return jdbc.update("""
                UPDATE device_command SET status=?,issued_at=CASE WHEN ?='SENT' THEN ? ELSE issued_at END,
                    completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN ? ELSE completed_at END,
                    result_code=?,result_detail=?,updated_at=? WHERE command_id=? AND status=?
                """, status, status, now, status, now, code, detail, now, commandId, expected);
    }

    public void addReceipt(String commandId, String commandNo, String resultCode, long now, String payload) {
        String inboxId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at) VALUES (?,?,?,?)",
                inboxId, "control-resp:" + commandNo, commandNo, now);
        jdbc.update("""
                INSERT INTO command_receipt (receipt_id,command_id,inbox_id,receipt_kind,device_result_code,occurred_at,received_at,payload)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), commandId, inboxId, "PROTOCOL_B", resultCode, now, now, payload);
    }

    public void addEvent(String deviceId, String type, String level, String message, long now, boolean simulated) {
        jdbc.update("INSERT INTO device_event_log(event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), deviceId, type, level, message, now, simulated);
    }

    public void addOutbox(String id, String commandId, long now) {
        jdbc.update("INSERT INTO outbox_event(outbox_id,topic,payload,created_at,available_at,attempt_count) VALUES (?,?,?,?,?,0)",
                id, "device.control.lingyun", commandId, now, now);
    }
}
