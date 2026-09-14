package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * C06 持久化：可信告警插入、合并组与成员。alarm 表在这里只有 INSERT/SELECT，永远没有 UPDATE；
 * 合并窗口状态全部落在 alarm_merge_group 上。
 */
@Repository
public class AlarmMergeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AlarmMergeRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    /** 以来源根行串行化同一来源的入库，并要求来源启用且模式一致（与 RiskRepository.lockSource 同语义）。 */
    public boolean lockSource(String sourceId, String sourceMode) {
        List<String> rows = jdbc.queryForList("SELECT source_id FROM integration_source WHERE source_id=:id AND source_mode=:mode AND enabled=TRUE FOR UPDATE",
                Map.of("id", sourceId, "mode", sourceMode), String.class);
        return rows.size() == 1;
    }

    /** 目标必须存在、模式一致，且其组织/区域目录启用；停用目录下不产生新告警。 */
    public TargetScope targetScope(String targetId, String sourceMode) {
        List<TargetScope> rows = jdbc.query("SELECT t.target_id,t.owner_org_id,t.district_id FROM target t"
                + " JOIN app_org o ON o.org_id=t.owner_org_id AND o.enabled=TRUE"
                + " JOIN app_district d ON d.district_id=t.district_id AND d.enabled=TRUE"
                + " WHERE t.target_id=:target AND t.source_mode=:mode",
                Map.of("target", targetId, "mode", sourceMode),
                (rs, i) -> new TargetScope(rs.getString("target_id"), rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁目标行：同一目标的合并决策串行化，避免并发研判各自建组。 */
    public boolean lockTarget(String targetId) {
        return jdbc.queryForList("SELECT target_id FROM target WHERE target_id=:target FOR UPDATE", Map.of("target", targetId), String.class).size() == 1;
    }

    public AlarmLink findAlarmBySource(String sourceId, String sourceAlarmId) {
        List<AlarmLink> rows = jdbc.query("SELECT a.alarm_id,e.event_id FROM alarm a LEFT JOIN uav_event e ON e.alarm_id=a.alarm_id"
                + " WHERE a.source_id=:source AND a.source_alarm_id=:source_alarm",
                Map.of("source", sourceId, "source_alarm", sourceAlarmId), AlarmMergeRepository::link);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String eventIdOfAlarm(String alarmId) {
        List<String> rows = jdbc.queryForList("SELECT event_id FROM uav_event WHERE alarm_id=:alarm", Map.of("alarm", alarmId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertAlarm(AlarmInsert row) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", row.alarmId()); params.put("target", row.targetId()); params.put("source", row.sourceId());
        params.put("source_alarm", row.sourceAlarmId()); params.put("type", row.alarmType()); params.put("severity", row.severity());
        params.put("occurred", row.occurredAt()); params.put("received", row.receivedAt()); params.put("detail", row.detailJson());
        params.put("mode", row.sourceMode()); params.put("org", row.ownerOrgId()); params.put("district", row.districtId());
        params.put("created", row.receivedAt()); params.put("alarm_no", row.alarmNo());
        // 显式 JSON 转换同时兼容 H2 与 PostgreSQL JSONB。
        jdbc.update("INSERT INTO alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,detail,source_mode,owner_org_id,district_id,created_at,alarm_no)"
                + " VALUES (:id,:target,:source,:source_alarm,:type,:severity,:occurred,:received,CAST(:detail AS JSON),:mode,:org,:district,:created,:alarm_no)", params);
    }

    public GroupRow lockOpenGroup(String targetId, String alarmType) {
        List<GroupRow> rows = jdbc.query(groupSelect() + " WHERE g.target_id=:target AND g.alarm_type=:type AND g.state='OPEN' ORDER BY g.window_opened_at DESC,g.group_id ASC FOR UPDATE",
                Map.of("target", targetId, "type", alarmType), AlarmMergeRepository::group);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<GroupRow> lockOpenGroupsExpiredBefore(OffsetDateTime threshold) {
        return jdbc.query(groupSelect() + " WHERE g.state='OPEN' AND g.window_expires_at<:threshold ORDER BY g.window_expires_at ASC,g.group_id ASC FOR UPDATE",
                Map.of("threshold", threshold), AlarmMergeRepository::group);
    }

    public void insertGroup(GroupRow g) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", g.groupId()); params.put("target", g.targetId()); params.put("type", g.alarmType()); params.put("rule_set", g.ruleSetId());
        params.put("severity", g.currentSeverity()); params.put("first", g.firstAlarmId()); params.put("latest", g.latestAlarmId());
        params.put("hits", g.hitCount()); params.put("opened", g.windowOpenedAt()); params.put("expires", g.windowExpiresAt()); params.put("last_hit", g.lastHitAt());
        params.put("org", g.ownerOrgId()); params.put("district", g.districtId()); params.put("at", g.lastHitAt());
        jdbc.update("INSERT INTO alarm_merge_group (group_id,target_id,alarm_type,rule_set_id,state,current_severity,first_alarm_id,latest_alarm_id,hit_count,"
                + "window_opened_at,window_expires_at,last_hit_at,owner_org_id,district_id,version,created_at,updated_at)"
                + " VALUES (:id,:target,:type,:rule_set,'OPEN',:severity,:first,:latest,:hits,:opened,:expires,:last_hit,:org,:district,0,:at,:at)", params);
    }

    /** 条件更新：受影响行数不为 1 代表并发改动，调用方必须视为冲突而不是继续写成员。 */
    public int recordHit(String groupId, long expectedVersion, String severity, String latestAlarmId, OffsetDateTime expiresAt, OffsetDateTime hitAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", groupId); params.put("version", expectedVersion); params.put("severity", severity);
        params.put("latest", latestAlarmId); params.put("expires", expiresAt); params.put("hit", hitAt);
        return jdbc.update("UPDATE alarm_merge_group SET current_severity=:severity,latest_alarm_id=:latest,hit_count=hit_count+1,window_expires_at=:expires,"
                + "last_hit_at=:hit,updated_at=:hit,version=version+1 WHERE group_id=:id AND version=:version AND state='OPEN'", params);
    }

    public int closeGroup(String groupId, long expectedVersion, String reason, OffsetDateTime at) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", groupId); params.put("version", expectedVersion); params.put("reason", reason); params.put("at", at);
        return jdbc.update("UPDATE alarm_merge_group SET state='AUTO_CLOSED',closed_at=:at,closed_reason=:reason,updated_at=:at,version=version+1"
                + " WHERE group_id=:id AND version=:version AND state='OPEN'", params);
    }

    public void insertMember(String memberId, String groupId, String evaluationId, String alarmId, String kind, String severityBefore, String severityAfter, OffsetDateTime at) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", memberId); params.put("group", groupId); params.put("evaluation", evaluationId); params.put("alarm", alarmId);
        params.put("kind", kind); params.put("before", severityBefore); params.put("after", severityAfter); params.put("at", at);
        jdbc.update("INSERT INTO alarm_merge_member (member_id,group_id,evaluation_id,alarm_id,member_kind,severity_before,severity_after,created_at)"
                + " VALUES (:id,:group,:evaluation,:alarm,:kind,:before,:after,:at)", params);
    }

    public MemberRow memberOfEvaluation(String evaluationId) {
        List<MemberRow> rows = jdbc.query("SELECT m.member_id,m.group_id,m.alarm_id,m.member_kind,m.severity_after FROM alarm_merge_member m WHERE m.evaluation_id=:evaluation",
                Map.of("evaluation", evaluationId), (rs, i) -> new MemberRow(rs.getString("member_id"), rs.getString("group_id"), rs.getString("alarm_id"), rs.getString("member_kind"), rs.getString("severity_after")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 自动关闭前看该目标最近一条 ACTIVE 研判：仍是 ABNORMAL/ILLEGAL 的组不能关。 */
    public String latestActiveLegalStatus(String targetId) {
        List<String> rows = jdbc.queryForList("SELECT legal_status FROM rule_evaluation WHERE target_id=:target AND mode='ACTIVE'"
                + " ORDER BY evaluated_at DESC,evaluation_id DESC FETCH FIRST 1 ROWS ONLY", Map.of("target", targetId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String groupSelect() {
        return "SELECT g.group_id,g.target_id,g.alarm_type,g.rule_set_id,g.state,g.current_severity,g.first_alarm_id,g.latest_alarm_id,g.hit_count,"
                + "g.window_opened_at,g.window_expires_at,g.last_hit_at,g.owner_org_id,g.district_id,g.version FROM alarm_merge_group g";
    }
    private static GroupRow group(ResultSet rs, int i) throws SQLException {
        return new GroupRow(rs.getString("group_id"), rs.getString("target_id"), rs.getString("alarm_type"), rs.getString("rule_set_id"), rs.getString("state"),
                rs.getString("current_severity"), rs.getString("first_alarm_id"), rs.getString("latest_alarm_id"), rs.getInt("hit_count"),
                time(rs, "window_opened_at"), time(rs, "window_expires_at"), time(rs, "last_hit_at"), rs.getString("owner_org_id"), rs.getString("district_id"), rs.getLong("version"));
    }
    private static AlarmLink link(ResultSet rs, int i) throws SQLException { return new AlarmLink(rs.getString("alarm_id"), rs.getString("event_id")); }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); if (value == null) return null;
        if (value instanceof OffsetDateTime t) return t; if (value instanceof ZonedDateTime t) return t.toOffsetDateTime();
        if (value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC); if (value instanceof LocalDateTime t) return t.atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    public record TargetScope(String targetId, String ownerOrgId, String districtId) { }
    public record AlarmLink(String alarmId, String eventId) { }
    public record AlarmInsert(String alarmId, String targetId, String sourceId, String sourceAlarmId, String alarmType, String severity,
            OffsetDateTime occurredAt, OffsetDateTime receivedAt, String detailJson, String sourceMode, String ownerOrgId, String districtId, String alarmNo) { }
    public record GroupRow(String groupId, String targetId, String alarmType, String ruleSetId, String state, String currentSeverity,
            String firstAlarmId, String latestAlarmId, int hitCount, OffsetDateTime windowOpenedAt, OffsetDateTime windowExpiresAt,
            OffsetDateTime lastHitAt, String ownerOrgId, String districtId, long version) { }
    public record MemberRow(String memberId, String groupId, String alarmId, String memberKind, String severityAfter) { }
}
