package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.NoticeAttempt;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.platform.security.AuthUser;

@Repository
public class DeviceMaintenanceNoticeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public DeviceMaintenanceNoticeRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public List<NoticeAttempt> forTask(String taskId){
        return jdbc.query("SELECT * FROM ops_device_maintenance_notice_attempt WHERE task_id=? ORDER BY attempt_no DESC",
                (r,n)->view(r),taskId);
    }
    public Submission submission(String actor,String key){
        var rows=jdbc.query("SELECT task_id,expected_attempt_no,reason FROM ops_device_maintenance_notice_attempt WHERE requested_by=? AND request_key=?",
                (r,n)->new Submission(r.getString(1),r.getInt(2),r.getString(3)),actor,key);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void insert(String id,String taskId,int number,AuthUser actor,String key,Integer expected,long at,String reason,
            RecipientSnapshot target,DeliveryOutcome result,String outcomeState){
        jdbc.update("""
            INSERT INTO ops_device_maintenance_notice_attempt(attempt_id,task_id,attempt_no,requested_by,
                requested_by_name,request_key,expected_attempt_no,requested_at,reason,recipient_snapshot,
                notification_setting_id,delivery_status,receipt_status,receipt_result,blocked_reason,
                submitted_at,delivered_at,acknowledged_at,outcome_state,historical)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE)
            """,id,taskId,number,actor.userId(),actor.name()==null?actor.account():actor.name(),key,expected,at,reason,
                encode(target),target.settingId(),result.deliveryStatus(),result.receiptStatus(),result.receiptResult(),
                result.blockedReason(),epoch(result.submittedAt()),epoch(result.deliveredAt()),epoch(result.acknowledgedAt()),outcomeState);
    }
    private NoticeAttempt view(ResultSet r)throws SQLException{
        return new NoticeAttempt(r.getString("attempt_id"),r.getInt("attempt_no"),r.getLong("requested_at"),
                r.getString("requested_by_name"),r.getString("reason"),decode(r.getString("recipient_snapshot")),
                r.getString("delivery_status"),r.getString("receipt_status"),r.getString("receipt_result"),r.getString("blocked_reason"),
                number(r,"submitted_at"),number(r,"delivered_at"),number(r,"acknowledged_at"),r.getString("outcome_state"),r.getBoolean("historical"));
    }
    /** Lock the owner task before matching its current immutable notification attempt. */
    public boolean completeSimulatorReceipt(String attemptId,String taskId,String marker,DeliveryOutcome result,String state) {
        var locked=jdbc.queryForList("SELECT task_id FROM ops_device_maintenance_task WHERE task_id=? FOR UPDATE",String.class,taskId);
        if(locked.isEmpty())return false;
        int changed=jdbc.update("""
            UPDATE ops_device_maintenance_notice_attempt SET delivery_status=?,receipt_status=?,blocked_reason=?,
                delivered_at=?,acknowledged_at=?,outcome_state=?
            WHERE attempt_id=? AND task_id=? AND blocked_reason=?
                AND attempt_no=(SELECT MAX(a.attempt_no) FROM ops_device_maintenance_notice_attempt a WHERE a.task_id=?)
            """,result.deliveryStatus(),result.receiptStatus(),result.blockedReason(),epoch(result.deliveredAt()),epoch(result.acknowledgedAt()),state,attemptId,taskId,marker,taskId);
        if(changed!=1)return false;
        // Keep the compatibility summary tied to the same original message, never overwrite another attempt.
        jdbc.update("UPDATE ops_device_maintenance_task SET notification_delivery_status=?,notification_receipt_status=?,notification_blocked_reason=? WHERE task_id=? AND notification_blocked_reason=?",
                result.deliveryStatus(),result.receiptStatus(),result.blockedReason(),taskId,marker);
        return true;
    }
    private String encode(RecipientSnapshot value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("不能保存通知对象快照",e);}}
    private RecipientSnapshot decode(String value){if(value==null)return null;try{return json.readValue(value,RecipientSnapshot.class);}catch(JsonProcessingException e){throw new IllegalStateException("不能读取通知对象快照",e);}}
    private static Long number(ResultSet r,String key)throws SQLException{long v=r.getLong(key);return r.wasNull()?null:v;}
    private static Long epoch(java.time.OffsetDateTime time){return time==null?null:time.toInstant().toEpochMilli();}
    public record Submission(String taskId,int expectedAttemptNo,String reason) { }
}
