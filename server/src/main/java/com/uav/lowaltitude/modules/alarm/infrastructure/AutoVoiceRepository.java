package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryEligibilityService.Eligibility;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording;

@Repository
public class AutoVoiceRepository {
    private com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity riskCases;
    @org.springframework.beans.factory.annotation.Autowired
    public void riskCases(com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity identity){this.riskCases=identity;}
    private String scope(String eventId){return riskCases==null?"":riskCases.taskScope(eventId,"VOICE");}
    private final JdbcTemplate jdbc;
    public AutoVoiceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public String eventForProviderKey(String key){var ids=jdbc.queryForList("SELECT event_id FROM uav_auto_voice_task WHERE provider_key=?",String.class,key);return ids.size()==1?ids.get(0):null;}
    public boolean sendStarted(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM uav_auto_voice_task WHERE event_id=? AND risk_scope=? AND send_started_at IS NOT NULL",Long.class,id,scope(id))>0;}
    public void prepareSend(String id,String token,Recording recording,long now){
        if(jdbc.update("UPDATE uav_auto_voice_task SET recording_id=?,recording_name=?,recording_sha256=?,recording_transcript=?,send_started_at=COALESCE(send_started_at,?) WHERE event_id=? AND risk_scope=? AND status='CALLING' AND claim_token=?",recording.id(),recording.name(),recording.sha256(),recording.transcript(),now,id,scope(id),token)!=1)
            throw new IllegalStateException("Automatic voice claim changed before dispatch");
    }
    public List<String> callingCandidates() {
        return jdbc.queryForList("SELECT event_id FROM uav_auto_voice_task WHERE status='CALLING' ORDER BY updated_at FETCH FIRST 200 ROWS ONLY",String.class);
    }
    /** 只跟已送达的飞手短信；尚未外呼的暂停任务重新核对当前条件，已尝试任务不自动补呼。 */
    public List<String> candidates() {
        return jdbc.queryForList("SELECT e.event_id FROM uav_event e JOIN uav_auto_sms_task s ON s.event_id=e.event_id AND s.status='SIMULATED_DELIVERED' LEFT JOIN uav_auto_voice_task t ON t.event_id=e.event_id WHERE t.departure_evaluated_at IS NULL AND (t.status='CALLING' OR t.event_id IS NULL OR t.status IN ('WAITING','UNAVAILABLE') OR (t.status='BLOCKED' AND t.attempt_count=0)) ORDER BY CASE WHEN t.status='CALLING' THEN 0 ELSE 1 END,COALESCE(t.updated_at,s.updated_at),e.event_id FETCH FIRST 200 ROWS ONLY",String.class);
    }
    public Task find(String id) {
        var rows=jdbc.query("SELECT * FROM uav_auto_voice_task WHERE event_id=? AND risk_scope=?",(r,n)->new Task(r.getString("event_id"),r.getString("status"),r.getString("reason"),r.getString("trigger_source"),number(r,"evaluated_at"),number(r,"data_updated_at"),number(r,"triggered_at"),r.getLong("updated_at"),r.getInt("attempt_count"),number(r,"lease_until"),r.getString("claim_token"),r.getString("provider_key"),r.getString("recording_id"),r.getString("recording_name"),r.getString("recording_sha256"),r.getString("recording_transcript"),number(r,"answered_at"),number(r,"playback_completed_at")),id,scope(id));
        return rows.isEmpty()?null:rows.get(0);
    }
    public void initialize(String id,long now,String policy) {
        if(find(id)!=null)return;
        String scope=scope(id);
        jdbc.update("INSERT INTO uav_auto_voice_task(event_id,status,policy_code,reason,updated_at,provider_key,risk_scope) VALUES(?,'WAITING',?,'等待后台检查电话通知条件',?,?,?)",id,policy,now,"auto-advisory-voice:"+id+(scope.isEmpty()?"":":"+scope),scope);
    }
    public com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation departure(String id) {
        var rows=jdbc.query("SELECT * FROM uav_auto_voice_task WHERE event_id=? AND departure_evaluated_at IS NOT NULL AND risk_scope=?",(r,n)->
            new com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation(id,r.getString("departure_channel"),"RECORDED","LEFT",
                r.getLong("departure_started_at"),r.getLong("departure_deadline_at"),r.getLong("departure_evaluated_at"),
                new com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Position(r.getLong("departure_observed_at"),r.getBigDecimal("departure_longitude"),r.getBigDecimal("departure_latitude")),r.getString("departure_basis")),id,scope(id));
        return rows.isEmpty()?null:rows.get(0);
    }
    public boolean recordDeparture(com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation value) {
        return jdbc.update("UPDATE uav_auto_voice_task SET departure_channel=?,departure_started_at=?,departure_deadline_at=?,departure_evaluated_at=?,departure_observed_at=?,departure_longitude=?,departure_latitude=?,departure_basis=? WHERE event_id=? AND departure_evaluated_at IS NULL AND risk_scope=?",
                value.channel(),value.startedAt(),value.deadlineAt(),value.evaluatedAt(),value.position().observedAt(),value.position().longitude(),value.position().latitude(),value.basis(),value.eventId(),scope(value.eventId()))==1;
    }
    public List<String> departureCandidates() {
        return jdbc.queryForList("SELECT event_id FROM uav_auto_voice_task WHERE status='SIMULATED_PLAYED' AND departure_evaluated_at IS NULL ORDER BY COALESCE(departure_checked_at,updated_at),event_id FETCH FIRST 200 ROWS ONLY",String.class);
    }
    public void checkedDeparture(String id,long now) {
        jdbc.update("UPDATE uav_auto_voice_task SET departure_checked_at=? WHERE event_id=? AND risk_scope=?",now,id,scope(id));
    }
    public void block(String id,Eligibility e,long now) {
        jdbc.update("UPDATE uav_auto_voice_task SET status=?,reason=?,trigger_source=?,evaluation_id=?,evaluated_at=?,data_updated_at=?,updated_at=?,claim_token=NULL,lease_until=NULL WHERE event_id=? AND risk_scope=?",e.status(),e.reason(),e.source(),e.evaluation()==null?null:e.evaluation().id(),e.evaluation()==null?null:e.evaluation().evaluatedAt(),e.observedAt(),now,id,scope(id));
    }
    public void claim(String id,String token,Eligibility e,Recording recording,long now) {
        jdbc.update("UPDATE uav_auto_voice_task SET status='CALLING',reason='后台正在模拟电话接通与录音播放，不实际拨号',trigger_source=?,evaluation_id=?,evaluated_at=?,data_updated_at=?,triggered_at=COALESCE(triggered_at,?),updated_at=?,attempt_count=attempt_count+1,claim_token=?,lease_until=?,recording_id=?,recording_name=?,recording_sha256=?,recording_transcript=? WHERE event_id=? AND risk_scope=?",e.source(),e.evaluation()==null?null:e.evaluation().id(),e.evaluation()==null?null:e.evaluation().evaluatedAt(),e.observedAt(),now,now,token,now+60000,recording.id(),recording.name(),recording.sha256(),recording.transcript(),id,scope(id));
    }
    public void finish(String id,String token,String status,String reason,String recordId,String providerCallId,Long answered,Long completed,long now) {
        int count=jdbc.update("UPDATE uav_auto_voice_task SET status=?,reason=?,delivery_record_id=?,provider_call_id=COALESCE(?,provider_call_id),answered_at=COALESCE(?,answered_at),playback_completed_at=?,updated_at=?,claim_token=CASE WHEN ?='UNKNOWN' THEN claim_token ELSE NULL END,lease_until=CASE WHEN ?='UNKNOWN' THEN lease_until ELSE NULL END WHERE event_id=? AND claim_token=? AND status IN ('CALLING','UNKNOWN') AND risk_scope=?",status,reason,recordId,providerCallId,answered,completed,now,status,status,id,token,scope(id));
        if(count!=1)throw new IllegalStateException("Automatic voice claim changed");
    }
    public void queueRetry(String id,long now) {
        if(jdbc.update("UPDATE uav_auto_voice_task SET status='WAITING',reason='已登记补呼，等待后台检查后执行',updated_at=? WHERE event_id=? AND status='FAILED' AND risk_scope=?",now,id,scope(id))!=1)
            throw new IllegalStateException("Automatic voice retry state changed");
    }
    public void answer(String id,String token,String callId,long answered,long now) {
        if(jdbc.update("UPDATE uav_auto_voice_task SET provider_call_id=?,answered_at=?,updated_at=?,reason='模拟器已回报接通，等待录音播放完成回执' WHERE event_id=? AND claim_token=? AND status IN ('CALLING','UNKNOWN') AND answered_at IS NULL AND risk_scope=?",callId,answered,now,id,token,scope(id))!=1)
            throw new IllegalStateException("Automatic voice claim changed");
    }
    public void append(String recordId,String eventId,long version,long now,Recording recording,String callId,long answered,long completed,String policy) {
        jdbc.update("INSERT INTO uav_event_voice_advisory(record_id,event_id,event_version,created_at,recording_id,recording_name,recording_sha256,transcript,provider_call_id,answered_at,playback_completed_at,policy_code,simulated,delivery_status,risk_case_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,TRUE,'SIMULATED_PLAYED',?)",recordId,eventId,version,now,recording.id(),recording.name(),recording.sha256(),recording.transcript(),callId,answered,completed,policy,riskCases==null?null:riskCases.caseId(eventId));
    }
    private static Long number(ResultSet r,String c)throws SQLException {long v=r.getLong(c);return r.wasNull()?null:v;}
    public record Task(String eventId,String status,String reason,String triggerSource,Long evaluatedAt,Long dataUpdatedAt,Long triggeredAt,long updatedAt,int attempts,Long leaseUntil,String token,String providerKey,String recordingId,String recordingName,String recordingSha256,String recordingTranscript,Long answeredAt,Long playbackCompletedAt) {
        public boolean recordingMatches(Recording recording) {
            return recordingId==null||(recording!=null&&recordingId.equals(recording.id())&&recordingName.equals(recording.name())&&recordingSha256.equals(recording.sha256())&&recordingTranscript.equals(recording.transcript()));
        }
    }
}
