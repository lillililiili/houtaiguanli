package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryEligibilityService.Eligibility;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording;

@Repository
public class AutoVoiceRepository {
    private final JdbcTemplate jdbc;
    public AutoVoiceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<String> callingCandidates() {
        return jdbc.queryForList("SELECT event_id FROM uav_auto_voice_task WHERE status='CALLING' ORDER BY updated_at FETCH FIRST 200 ROWS ONLY",String.class);
    }
    public List<String> candidates(long since) {
        return jdbc.queryForList("SELECT e.event_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id LEFT JOIN uav_auto_voice_task t ON t.event_id=e.event_id WHERE (t.status='CALLING' OR ((t.event_id IS NULL OR t.status IN ('WAITING','BLOCKED','UNAVAILABLE')) AND a.received_at>=?)) ORDER BY CASE WHEN t.status='CALLING' THEN 0 ELSE 1 END,COALESCE(t.updated_at,0),e.created_at ASC FETCH FIRST 200 ROWS ONLY",String.class,new Timestamp(since));
    }
    public Task find(String id) {
        var rows=jdbc.query("SELECT * FROM uav_auto_voice_task WHERE event_id=?",(r,n)->new Task(r.getString("event_id"),r.getString("status"),r.getString("reason"),r.getString("trigger_source"),number(r,"evaluated_at"),number(r,"data_updated_at"),number(r,"triggered_at"),r.getLong("updated_at"),r.getInt("attempt_count"),number(r,"lease_until"),r.getString("claim_token"),r.getString("provider_key"),r.getString("recording_id"),r.getString("recording_name"),r.getString("recording_sha256"),r.getString("recording_transcript"),number(r,"answered_at"),number(r,"playback_completed_at")),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void initialize(String id,long now,String policy) {
        if(find(id)!=null)return;
        jdbc.update("INSERT INTO uav_auto_voice_task(event_id,status,policy_code,reason,updated_at,provider_key) VALUES(?,'WAITING',?,'等待后台检查电话通知条件',?,?)",id,policy,now,"auto-advisory-voice:"+id);
    }
    public void block(String id,Eligibility e,long now) {
        jdbc.update("UPDATE uav_auto_voice_task SET status=?,reason=?,trigger_source=?,evaluation_id=?,evaluated_at=?,data_updated_at=?,updated_at=?,claim_token=NULL,lease_until=NULL WHERE event_id=?",e.status(),e.reason(),e.source(),e.evaluation()==null?null:e.evaluation().id(),e.evaluation()==null?null:e.evaluation().evaluatedAt(),e.observedAt(),now,id);
    }
    public void claim(String id,String token,Eligibility e,Recording recording,long now) {
        jdbc.update("UPDATE uav_auto_voice_task SET status='CALLING',reason='后台正在模拟电话接通与录音播放，不实际拨号',trigger_source=?,evaluation_id=?,evaluated_at=?,data_updated_at=?,triggered_at=COALESCE(triggered_at,?),updated_at=?,attempt_count=attempt_count+1,claim_token=?,lease_until=?,recording_id=?,recording_name=?,recording_sha256=?,recording_transcript=? WHERE event_id=?",e.source(),e.evaluation()==null?null:e.evaluation().id(),e.evaluation()==null?null:e.evaluation().evaluatedAt(),e.observedAt(),now,now,token,now+60000,recording.id(),recording.name(),recording.sha256(),recording.transcript(),id);
    }
    public void finish(String id,String token,String status,String reason,String recordId,String providerCallId,Long answered,Long completed,long now) {
        int count=jdbc.update("UPDATE uav_auto_voice_task SET status=?,reason=?,delivery_record_id=?,provider_call_id=?,answered_at=?,playback_completed_at=?,updated_at=?,claim_token=NULL,lease_until=NULL WHERE event_id=? AND claim_token=? AND status='CALLING'",status,reason,recordId,providerCallId,answered,completed,now,id,token);
        if(count!=1)throw new IllegalStateException("Automatic voice claim changed");
    }
    public void queueRetry(String id,long now) {
        if(jdbc.update("UPDATE uav_auto_voice_task SET status='WAITING',reason='已登记补呼，等待后台检查后执行',updated_at=? WHERE event_id=? AND status='FAILED'",now,id)!=1)
            throw new IllegalStateException("Automatic voice retry state changed");
    }
    public void append(String recordId,String eventId,long version,long now,Recording recording,String callId,long answered,long completed,String policy) {
        jdbc.update("INSERT INTO uav_event_voice_advisory(record_id,event_id,event_version,created_at,recording_id,recording_name,recording_sha256,transcript,provider_call_id,answered_at,playback_completed_at,policy_code,simulated,delivery_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,TRUE,'SIMULATED_PLAYED')",recordId,eventId,version,now,recording.id(),recording.name(),recording.sha256(),recording.transcript(),callId,answered,completed,policy);
    }
    private static Long number(ResultSet r,String c)throws SQLException {long v=r.getLong(c);return r.wasNull()?null:v;}
    public record Task(String eventId,String status,String reason,String triggerSource,Long evaluatedAt,Long dataUpdatedAt,Long triggeredAt,long updatedAt,int attempts,Long leaseUntil,String token,String providerKey,String recordingId,String recordingName,String recordingSha256,String recordingTranscript,Long answeredAt,Long playbackCompletedAt) {
        public boolean recordingMatches(Recording recording) {
            return recordingId==null||(recording!=null&&recordingId.equals(recording.id())&&recordingName.equals(recording.name())&&recordingSha256.equals(recording.sha256())&&recordingTranscript.equals(recording.transcript()));
        }
    }
}
