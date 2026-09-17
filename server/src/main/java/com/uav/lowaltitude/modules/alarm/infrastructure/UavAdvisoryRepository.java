package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Record;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Action;

@Repository
public class UavAdvisoryRepository {
    private final JdbcTemplate jdbc;
    private final com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository directory;
    public UavAdvisoryRepository(JdbcTemplate jdbc,com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository directory) { this.jdbc = jdbc;this.directory=directory; }
    /** 内部受控读取：调用方先完成事件范围校验或已授权材料组装。 */
    public List<Record> records(String eventId) {
        return jdbc.query("SELECT a.record_id,a.kind,a.created_at,COALESCE(a.actor_label,u.name) AS actor_name,a.recipient_name,a.contact_basis,a.content,a.outcome,a.danger,a.note,a.urgent,a.simulated,a.delivery_status,a.trigger_mode,a.policy_code,a.recipient_snapshot,a.event_version FROM uav_event_advisory a LEFT JOIN app_user u ON u.user_id=a.actor_id WHERE a.event_id=? UNION ALL SELECT v.record_id,'VOICE_SIMULATED',v.created_at,'自动电话录音服务',COALESCE(v.recipient_name,'演示飞手'),CASE WHEN v.recipient_snapshot IS NULL THEN '本地演示接收端，不对应真实手机号' ELSE '关联计划执行飞手；模拟通道' END,v.recording_name || '：' || v.transcript,NULL,NULL,NULL,FALSE,TRUE,v.delivery_status,'AUTO',v.policy_code,v.recipient_snapshot,v.event_version FROM uav_event_voice_advisory v WHERE v.event_id=? ORDER BY event_version", (r,n) -> new Record(r.getString("record_id"),r.getString("kind"),r.getLong("created_at"),r.getString("actor_name"),r.getString("recipient_name"),r.getString("contact_basis"),r.getString("content"),r.getString("outcome"),r.getString("danger"),r.getString("note"),r.getBoolean("urgent"),r.getBoolean("simulated"),r.getString("delivery_status"),r.getString("trigger_mode"),r.getString("policy_code"),directory.decodeSnapshot(r.getString("recipient_snapshot"))),eventId,eventId);
    }
    /** 仅供已经锁定且已授权的设备关联执行链调用，不作为外部读接口。 */
    public String counterBlockReason(String eventId) {
        String state=jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,eventId);
        return com.uav.lowaltitude.modules.alarm.domain.UavAdvisoryRules.counterBlockReason(state,records(eventId));
    }
    public void append(String id, String eventId, long version, String actor, long at, Action a, boolean simulated, String delivery) {
        jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,outcome,danger,note,urgent,simulated,delivery_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,eventId,version,a.kind(),at,actor,a.recipientName(),a.contactBasis(),a.content(),a.outcome(),a.danger(),a.note(),Boolean.TRUE.equals(a.urgent()),simulated,delivery);
    }
    public void appendAutomatic(String id,String eventId,long version,long at,String content,String policy) {
        jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated,delivery_status,trigger_mode,actor_label,policy_code) VALUES(?,?,?,'SMS_SIMULATED',?,NULL,'演示飞手','本地演示接收端，不对应真实手机号',?,FALSE,TRUE,'SIMULATED_DELIVERED','AUTO','自动短信服务',?)",id,eventId,version,at,content,policy);
    }
    public Replay replay(String actor, String key) {
        var rows=jdbc.query("SELECT request_hash,event_id,response_text FROM uav_event_advisory_request WHERE actor_id=? AND request_key=?",(r,n)->new Replay(r.getString(1),r.getString(2),r.getString(3)),actor,key);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void saveReplay(String actor,String key,String hash,String event,String response) {
        jdbc.update("INSERT INTO uav_event_advisory_request(actor_id,request_key,request_hash,event_id,response_text) VALUES(?,?,?,?,?)",actor,key,hash,event,response);
    }
    public record Replay(String hash,String eventId,String response) { }
}
