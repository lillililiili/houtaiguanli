package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AutoSmsRepository {
    private final JdbcTemplate jdbc;
    public AutoSmsRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** 告警事件建立后即进入自动短信，不再按接收时间把旧事件排除。已送达和已失败的任务留在原状态。 */
    public List<String> candidates() {
        return jdbc.queryForList("SELECT e.event_id FROM uav_event e LEFT JOIN uav_auto_sms_task t ON t.event_id=e.event_id WHERE t.status='SENDING' OR t.event_id IS NULL OR t.status IN ('WAITING','BLOCKED','UNAVAILABLE') ORDER BY CASE WHEN t.status='SENDING' THEN 0 ELSE 1 END,e.created_at DESC FETCH FIRST 200 ROWS ONLY",String.class);
    }
    public Facts facts(String eventId) {
        return jdbc.queryForObject("SELECT a.received_at,t.object_type_code,s.observed_at, (SELECT MAX(v.created_at) FROM uav_event_verification v WHERE v.event_id=e.event_id AND v.conclusion='CONFIRMED') AS confirmed_at FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id JOIN app_org o ON o.org_id=e.owner_org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=e.district_id AND d.enabled=TRUE LEFT JOIN target t ON t.target_id=a.target_id AND t.owner_org_id=e.owner_org_id AND t.district_id=e.district_id LEFT JOIN target_latest_state s ON s.target_id=t.target_id WHERE e.event_id=?",(r,n)->new Facts(time(r,"received_at"),r.getString("object_type_code"),time(r,"observed_at"),time(r,"confirmed_at")),eventId);
    }
    /** 按目标取最新 ACTIVE 结论，不能用旧违法结论覆盖后来合法/未知的结论。 */
    public Evaluation latestEvaluation(String eventId) {
        var rows=jdbc.query("SELECT r.evaluation_id,r.alarm_id,r.legal_status,r.freshness_code,r.unknown_reasons,r.evaluated_at,r.observed_at,r.mode FROM rule_evaluation r JOIN uav_event e ON e.event_id=? JOIN alarm a ON a.alarm_id=e.alarm_id WHERE r.target_id=a.target_id AND r.owner_org_id=e.owner_org_id AND r.district_id=e.district_id AND r.mode='ACTIVE' ORDER BY r.evaluated_at DESC,r.evaluation_id DESC FETCH FIRST 1 ROWS ONLY",(r,n)->new Evaluation(r.getString("evaluation_id"),r.getString("alarm_id"),r.getString("legal_status"),r.getString("freshness_code"),r.getString("unknown_reasons"),time(r,"evaluated_at"),time(r,"observed_at")),eventId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Long deliveredAt(String eventId) {
        var rows = jdbc.query("SELECT updated_at FROM uav_auto_sms_task WHERE event_id=? AND status='SIMULATED_DELIVERED'", (r, n) -> r.getLong(1), eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Task find(String eventId) {
        var rows=jdbc.query("SELECT * FROM uav_auto_sms_task WHERE event_id=?",(r,n)->new Task(r.getString("event_id"),r.getString("status"),r.getString("reason"),r.getString("trigger_source"),r.getString("evaluation_id"),number(r,"evaluated_at"),number(r,"data_updated_at"),number(r,"triggered_at"),r.getLong("updated_at"),r.getInt("attempt_count"),number(r,"lease_until"),r.getString("claim_token"),r.getString("provider_key")),eventId);
        return rows.isEmpty()?null:rows.get(0);
    }
    /** 调用者先锁事件；主键兜底跨实例只能有一条自动通知任务。 */
    public void initialize(String eventId,long now,String policy) {
        if(find(eventId)!=null)return;
        jdbc.update("INSERT INTO uav_auto_sms_task(event_id,status,policy_code,reason,updated_at,provider_key) VALUES(?,'WAITING',?,'等待后台检查触发条件',?,?)",eventId,policy,now,"auto-advisory:"+eventId);
    }
    public void block(String eventId,String status,String reason,String source,Evaluation evaluation,Long observed,long now) {
        jdbc.update("UPDATE uav_auto_sms_task SET status=?,reason=?,trigger_source=?,evaluation_id=?,evaluated_at=?,data_updated_at=?,updated_at=?,claim_token=NULL,lease_until=NULL WHERE event_id=?",status,reason,source,evaluation==null?null:evaluation.id(),evaluation==null?null:evaluation.evaluatedAt(),observed,now,eventId);
    }
    public void claim(String eventId,String token,String source,Evaluation evaluation,Long observed,long now) {
        jdbc.update("UPDATE uav_auto_sms_task SET status='SENDING',reason='后台正在调用模拟短信接口',trigger_source=?,evaluation_id=?,evaluated_at=?,data_updated_at=?,triggered_at=COALESCE(triggered_at,?),updated_at=?,attempt_count=attempt_count+1,claim_token=?,lease_until=? WHERE event_id=?",source,evaluation==null?null:evaluation.id(),evaluation==null?null:evaluation.evaluatedAt(),observed,now,now,token,now+60000,eventId);
    }
    public void finish(String eventId,String token,String status,String reason,String recordId,long now) {
        if(jdbc.update("UPDATE uav_auto_sms_task SET status=?,reason=?,delivery_record_id=?,updated_at=?,claim_token=NULL,lease_until=NULL WHERE event_id=? AND claim_token=? AND status='SENDING'",status,reason,recordId,now,eventId,token)!=1)throw new IllegalStateException("Automatic SMS claim changed");
    }
    public void queueRetry(String eventId,long now) {
        jdbc.update("UPDATE uav_auto_sms_task SET status='WAITING',reason='已登记补发，等待后台发送',updated_at=?,claim_token=NULL,lease_until=NULL WHERE event_id=?",now,eventId);
    }
    private static Long time(ResultSet r,String c)throws SQLException {var t=r.getTimestamp(c);return t==null?null:t.getTime();}
    private static Long number(ResultSet r,String c)throws SQLException {long v=r.getLong(c);return r.wasNull()?null:v;}
    public record Facts(Long receivedAt,String objectType,Long observedAt,Long confirmedAt) { }
    public record Evaluation(String id,String alarmId,String legalStatus,String freshness,String unknowns,Long evaluatedAt,Long observedAt) { }
    public record Task(String eventId,String status,String reason,String triggerSource,String evaluationId,Long evaluatedAt,Long dataUpdatedAt,Long triggeredAt,long updatedAt,int attempts,Long leaseUntil,String token,String providerKey) { }
}
