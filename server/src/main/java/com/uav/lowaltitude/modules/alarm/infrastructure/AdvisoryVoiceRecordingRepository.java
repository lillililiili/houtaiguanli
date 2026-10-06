package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 后台上传的电话通知录音与当前选用；文件字节在对象存储，这里只存元数据与真实内容哈希。 */
@Repository
public class AdvisoryVoiceRecordingRepository {
    /** 用于过电话通知（任务领取或成功记录）的录音保留，便于日后核对当时播放的内容。 */
    private static final String USED="CASE WHEN EXISTS (SELECT 1 FROM uav_auto_voice_task t WHERE t.recording_id=r.recording_id)"
            +" OR EXISTS (SELECT 1 FROM uav_event_voice_advisory v WHERE v.recording_id=r.recording_id) THEN TRUE ELSE FALSE END AS used";
    private final JdbcTemplate jdbc;
    public AdvisoryVoiceRecordingRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Row> list() {
        return jdbc.query("SELECT r.*,"+USED+" FROM advisory_voice_recording r ORDER BY r.uploaded_at DESC,r.recording_id",this::row);
    }
    public Row find(String id) {
        var rows=jdbc.query("SELECT r.*,"+USED+" FROM advisory_voice_recording r WHERE r.recording_id=?",this::row,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    /** 后台当前选用的录音；没有选用时返回 null。 */
    public Row active() {
        var rows=jdbc.query("SELECT r.*,FALSE AS used FROM advisory_voice_recording_setting s JOIN advisory_voice_recording r ON r.recording_id=s.active_recording_id WHERE s.setting_id='global'",this::row);
        return rows.isEmpty()?null:rows.get(0);
    }
    public String findNameBySha256(String sha256) {
        var rows=jdbc.queryForList("SELECT name FROM advisory_voice_recording WHERE sha256=?",String.class,sha256);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Setting setting(boolean lock) {
        return jdbc.queryForObject("SELECT active_recording_id,updated_at,version FROM advisory_voice_recording_setting WHERE setting_id='global'"+(lock?" FOR UPDATE":""),
                (r,n)->new Setting(r.getString("active_recording_id"),number(r,"updated_at"),r.getInt("version")));
    }
    public void insert(Row row) {
        jdbc.update("INSERT INTO advisory_voice_recording(recording_id,name,transcript,original_name,content_type,object_key,size_bytes,sha256,duration_millis,sample_rate,channels,uploaded_by,uploaded_by_name,uploaded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.recordingId(),row.name(),row.transcript(),row.originalName(),row.contentType(),row.objectKey(),row.sizeBytes(),row.sha256(),
                row.durationMillis(),row.sampleRate(),row.channels(),row.uploadedBy(),row.uploadedByName(),row.uploadedAt());
    }
    /** 条件更新：版本不符说明已被他人修改，返回 0。recordingId 为 null 表示不再选用上传录音。 */
    public int updateActive(String recordingId,String userId,long now,int expectedVersion) {
        return jdbc.update("UPDATE advisory_voice_recording_setting SET active_recording_id=?,updated_by=?,updated_at=?,version=version+1 WHERE setting_id='global' AND version=?",
                recordingId,userId,now,expectedVersion);
    }
    public int delete(String id) {return jdbc.update("DELETE FROM advisory_voice_recording WHERE recording_id=?",id);}
    private Row row(ResultSet r,int n)throws SQLException {
        return new Row(r.getString("recording_id"),r.getString("name"),r.getString("transcript"),r.getString("original_name"),r.getString("content_type"),
                r.getString("object_key"),r.getLong("size_bytes"),r.getString("sha256"),r.getLong("duration_millis"),r.getInt("sample_rate"),r.getInt("channels"),
                r.getString("uploaded_by"),r.getString("uploaded_by_name"),r.getLong("uploaded_at"),r.getBoolean("used"));
    }
    private static Long number(ResultSet r,String c)throws SQLException {long v=r.getLong(c);return r.wasNull()?null:v;}
    public record Row(String recordingId,String name,String transcript,String originalName,String contentType,String objectKey,long sizeBytes,String sha256,
            long durationMillis,int sampleRate,int channels,String uploadedBy,String uploadedByName,long uploadedAt,boolean used) { }
    public record Setting(String activeRecordingId,Long updatedAt,int version) { }
}
