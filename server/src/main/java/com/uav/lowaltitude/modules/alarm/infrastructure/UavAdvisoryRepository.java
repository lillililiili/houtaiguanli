package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Record;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Action;

@Repository
public class UavAdvisoryRepository {
    private final JdbcTemplate jdbc;
    public UavAdvisoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 内部受控读取：调用方先完成事件范围校验或已授权材料组装。 */
    public List<Record> records(String eventId) {
        return jdbc.query("SELECT a.*,u.name AS actor_name FROM uav_event_advisory a JOIN app_user u ON u.user_id=a.actor_id WHERE a.event_id=? ORDER BY a.event_version", (r,n) -> new Record(r.getString("record_id"),r.getString("kind"),r.getLong("created_at"),r.getString("actor_name"),r.getString("recipient_name"),r.getString("contact_basis"),r.getString("content"),r.getString("outcome"),r.getString("danger"),r.getString("note"),r.getBoolean("urgent"),r.getBoolean("simulated"),r.getString("delivery_status")),eventId);
    }
    /** 仅供已经锁定且已授权的设备关联执行链调用，不作为外部读接口。 */
    public String counterBlockReason(String eventId) {
        String state=jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,eventId);
        return com.uav.lowaltitude.modules.alarm.domain.UavAdvisoryRules.counterBlockReason(state,records(eventId));
    }
    public void append(String id, String eventId, long version, String actor, long at, Action a, boolean simulated, String delivery) {
        jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,outcome,danger,note,urgent,simulated,delivery_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,eventId,version,a.kind(),at,actor,a.recipientName(),a.contactBasis(),a.content(),a.outcome(),a.danger(),a.note(),Boolean.TRUE.equals(a.urgent()),simulated,delivery);
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
