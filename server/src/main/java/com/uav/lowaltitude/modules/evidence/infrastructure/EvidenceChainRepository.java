package com.uav.lowaltitude.modules.evidence.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceChainRepository {
    static final int FETCH = 101;
    private final NamedParameterJdbcTemplate jdbc;

    public EvidenceChainRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public EventRef findEvent(String eventId) {
        List<EventRef> rows = jdbc.query("""
                SELECT e.event_id, e.alarm_id, e.owner_org_id, e.district_id, a.target_id
                FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id
                WHERE e.event_id=:id
                """, Map.of("id", eventId), (rs, i) -> new EventRef(rs.getString("event_id"), rs.getString("alarm_id"),
                rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("target_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public CaseRef findCase(String caseId) {
        List<CaseRef> rows = jdbc.query("""
                SELECT case_id, case_no, event_id, owner_org_id, district_id FROM punishment_case WHERE case_id=:id
                """, Map.of("id", caseId), (rs, i) -> new CaseRef(rs.getString("case_id"), rs.getString("case_no"),
                rs.getString("event_id"), rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TargetRef findTarget(String targetId) {
        List<TargetRef> rows = jdbc.query("""
                SELECT target_id, target_no, owner_org_id, district_id FROM target WHERE target_id=:id
                """, Map.of("id", targetId), (rs, i) -> new TargetRef(rs.getString("target_id"), rs.getString("target_no"),
                rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<AliasRow> aliasesTouching(Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT historical_target_id, current_target_id FROM target_current_alias
                WHERE historical_target_id IN (:ids) OR current_target_id IN (:ids)
                """, Map.of("ids", ids), (rs, i) -> new AliasRow(rs.getString("historical_target_id"),
                rs.getString("current_target_id")));
    }

    public List<TrackRow> tracks(Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT t.track_id, t.layer, t.started_at, t.ended_at,
                    (SELECT COUNT(*) FROM track_point p WHERE p.track_id=t.track_id) AS point_count
                FROM track t WHERE t.target_id IN (:ids)
                ORDER BY CASE t.layer WHEN 'FUSED' THEN 0 ELSE 1 END, t.started_at ASC NULLS LAST, t.track_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("ids", targetIds, "limit", FETCH), (rs, i) -> new TrackRow(
                rs.getString("track_id"), rs.getString("layer"), time(rs, "started_at"), time(rs, "ended_at"),
                rs.getInt("point_count")));
    }

    public List<FileRow> files(String eventId, Collection<String> targetIds, String caseId) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.evidence_id, f.evidence_no, f.kind_code, f.original_name, f.status, f.sha256,
                    f.size_bytes, f.captured_at, f.stored_at
                FROM evidence_file f
                WHERE f.evidence_id IN (
                    SELECT l.evidence_id FROM evidence_link l WHERE (
                """);
        Map<String, Object> params = new HashMap<>();
        boolean any = false;
        if (eventId != null) {
            sql.append(" (l.subject_kind='EVENT' AND l.subject_id=:eventId)");
            params.put("eventId", eventId);
            any = true;
        }
        if (!targetIds.isEmpty()) {
            if (any) sql.append(" OR");
            sql.append(" (l.subject_kind='TARGET' AND l.subject_id IN (:targetIds))");
            params.put("targetIds", targetIds);
            any = true;
        }
        if (caseId != null) {
            if (any) sql.append(" OR");
            sql.append(" (l.subject_kind='CASE' AND l.subject_id=:caseId)");
            params.put("caseId", caseId);
            any = true;
        }
        if (!any) return List.of();
        sql.append("""
                    )
                )
                ORDER BY f.captured_at ASC NULLS LAST, f.evidence_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """);
        params.put("limit", FETCH);
        return jdbc.query(sql.toString(), params, (rs, i) -> new FileRow(
                rs.getString("evidence_id"), rs.getString("evidence_no"), rs.getString("kind_code"),
                rs.getString("original_name"), rs.getString("status"), rs.getString("sha256"),
                longOrNull(rs, "size_bytes"), time(rs, "captured_at"), time(rs, "stored_at")));
    }

    public List<AlarmRow> alarms(String alarmId, Collection<String> targetIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT alarm_id, target_id, alarm_type, severity, received_at, source_mode
                FROM alarm WHERE (
                """);
        Map<String, Object> params = new HashMap<>();
        boolean any = false;
        if (alarmId != null) {
            sql.append(" alarm_id=:alarmId");
            params.put("alarmId", alarmId);
            any = true;
        }
        if (!targetIds.isEmpty()) {
            if (any) sql.append(" OR");
            sql.append(" target_id IN (:targetIds)");
            params.put("targetIds", targetIds);
            any = true;
        }
        if (!any) return List.of();
        sql.append("""
                )
                ORDER BY received_at ASC, alarm_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """);
        params.put("limit", FETCH);
        return jdbc.query(sql.toString(), params, (rs, i) -> new AlarmRow(
                rs.getString("alarm_id"), rs.getString("target_id"), rs.getString("alarm_type"),
                rs.getString("severity"), time(rs, "received_at"), rs.getString("source_mode")));
    }

    public List<JudgmentRow> judgments(Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT assessment_id, target_id, conclusion_code, assessed_at, rule_version_id
                FROM assessment_result WHERE target_id IN (:ids)
                ORDER BY assessed_at ASC, assessment_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("ids", targetIds, "limit", FETCH), (rs, i) -> new JudgmentRow(
                rs.getString("assessment_id"), rs.getString("target_id"), rs.getString("conclusion_code"),
                time(rs, "assessed_at"), rs.getString("rule_version_id")));
    }

    public List<JudgmentRow> judgmentsAtOrBefore(Collection<String> targetIds, Instant at) {
        if (targetIds.isEmpty() || at == null) return List.of();
        return jdbc.query("""
                SELECT assessment_id, target_id, conclusion_code, assessed_at, rule_version_id
                FROM assessment_result WHERE target_id IN (:ids) AND assessed_at <= :at
                ORDER BY assessed_at DESC, assessment_id DESC
                """, Map.of("ids", targetIds, "at", Timestamp.from(at)), (rs, i) -> new JudgmentRow(
                rs.getString("assessment_id"), rs.getString("target_id"), rs.getString("conclusion_code"),
                time(rs, "assessed_at"), rs.getString("rule_version_id")));
    }

    public List<CommandRow> linkedCommands(String eventId, Collection<String> targetIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT c.command_id, c.command_no, c.authorization_id, c.status, c.created_at
                FROM device_command c JOIN evidence_link l ON l.command_id=c.command_id
                WHERE c.authorization_id IS NOT NULL AND TRIM(c.authorization_id) <> '' AND (
                """);
        Map<String, Object> params = new HashMap<>();
        boolean any = false;
        if (eventId != null) {
            sql.append(" (l.subject_kind='EVENT' AND l.subject_id=:eventId)");
            params.put("eventId", eventId);
            any = true;
        }
        if (!targetIds.isEmpty()) {
            if (any) sql.append(" OR");
            sql.append(" (l.subject_kind='TARGET' AND l.subject_id IN (:targetIds))");
            params.put("targetIds", targetIds);
            any = true;
        }
        if (!any) return List.of();
        sql.append("""
                )
                ORDER BY c.created_at ASC, c.command_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """);
        params.put("limit", FETCH);
        return jdbc.query(sql.toString(), params, (rs, i) -> new CommandRow(
                rs.getString("command_id"), rs.getString("command_no"), rs.getString("authorization_id"),
                rs.getString("status"), longOrNull(rs, "created_at")));
    }

    public List<CommandRow> linkedAuthorizations(String eventId, Collection<String> targetIds) {
        if (eventId == null && targetIds.isEmpty()) return List.of();
        Map<String, Object> params = new HashMap<>();
        params.put("eventId", eventId);
        params.put("limit", FETCH);
        String targetClause = "";
        if (!targetIds.isEmpty()) {
            params.put("targets", targetIds);
            targetClause = " OR (subject_kind='TARGET' AND subject_id IN (:targets))";
        }
        return jdbc.query("SELECT authorization_id,authorization_no,status,created_at FROM disposal_authorization"
                + " WHERE (subject_kind='UAV_EVENT' AND subject_id=:eventId)" + targetClause
                + " ORDER BY created_at,authorization_id FETCH FIRST :limit ROWS ONLY", params,
                (rs, i) -> new CommandRow(rs.getString("authorization_id"), rs.getString("authorization_no"),
                        rs.getString("authorization_id"), rs.getString("status"), rs.getTimestamp("created_at").getTime()));
    }

    public List<VerificationRow> verifications(Collection<String> eventIds) {
        if (eventIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT history_id, event_id, version, conclusion, resulting_state, created_at
                FROM uav_event_verification WHERE event_id IN (:ids)
                ORDER BY created_at ASC, history_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("ids", eventIds, "limit", FETCH), (rs, i) -> new VerificationRow(
                rs.getString("history_id"), rs.getString("event_id"), rs.getLong("version"),
                rs.getString("conclusion"), rs.getString("resulting_state"), time(rs, "created_at")));
    }

    public List<HandoffRow> handoffs(Collection<String> eventIds) {
        if (eventIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT h.handoff_id, h.event_id, h.handoff_type, h.source_version, h.created_at,
                    (SELECT d.delivery_status FROM handoff_delivery d WHERE d.handoff_id=h.handoff_id
                     AND d.attempt_no=(SELECT MAX(d2.attempt_no) FROM handoff_delivery d2 WHERE d2.handoff_id=h.handoff_id)) AS delivery_status
                FROM handoff h WHERE h.source_kind='UAV_EVENT' AND h.event_id IN (:ids)
                ORDER BY h.created_at ASC, h.handoff_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("ids", eventIds, "limit", FETCH), (rs, i) -> new HandoffRow(
                rs.getString("handoff_id"), rs.getString("event_id"), rs.getString("handoff_type"),
                rs.getLong("source_version"), time(rs, "created_at"), rs.getString("delivery_status")));
    }

    public List<String> eventIdsForTargets(Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT e.event_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id
                WHERE a.target_id IN (:ids) ORDER BY e.created_at ASC, e.event_id ASC
                """, Map.of("ids", targetIds), (rs, i) -> rs.getString("event_id"));
    }

    public List<AuditRow> audits(Collection<String> objectIds, Collection<String> objectTypes) {
        if (objectIds.isEmpty() || objectTypes.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT audit_id, action, result, module_code, object_type, object_id, occurred_at
                FROM audit_log WHERE object_id IN (:ids) AND object_type IN (:types)
                ORDER BY occurred_at ASC, audit_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("ids", objectIds, "types", objectTypes, "limit", FETCH), (rs, i) -> new AuditRow(
                rs.getString("audit_id"), rs.getString("action"), rs.getString("result"),
                rs.getString("module_code"), rs.getString("object_type"), rs.getString("object_id"),
                longOrNull(rs, "occurred_at")));
    }

    public List<LineageRow> lineage(Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT lineage_id, op, occurred_at, survivor_target_id, origin_target_id,
                    member_target_ids, snapshots
                FROM target_lineage
                WHERE survivor_target_id IN (:ids) OR origin_target_id IN (:ids)
                ORDER BY occurred_at ASC, lineage_id ASC
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, Map.of("ids", targetIds, "limit", FETCH), (rs, i) -> new LineageRow(
                rs.getString("lineage_id"), rs.getString("op"), time(rs, "occurred_at"),
                rs.getString("survivor_target_id"), rs.getString("origin_target_id"),
                jsonText(rs.getObject("member_target_ids")), jsonText(rs.getObject("snapshots"))));
    }

    /** JSON 列在 H2 上经 JDBC 取回是 byte[]，PostgreSQL 是 PGobject/文本。 */
    private static String jsonText(Object stored) {
        if (stored == null) return null;
        if (stored instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        try {
            Object value = stored.getClass().getMethod("getValue").invoke(stored);
            if (value != null) return value.toString();
        } catch (ReflectiveOperationException ignored) {
            // H2 字符串或其它驱动类型走 toString。
        }
        return String.valueOf(stored);
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record EventRef(String eventId, String alarmId, String ownerOrgId, String districtId, String targetId) { }
    public record TargetRef(String targetId, String targetNo, String ownerOrgId, String districtId) { }
    public record CaseRef(String caseId, String caseNo, String eventId, String ownerOrgId, String districtId) { }
    public record AliasRow(String historicalTargetId, String currentTargetId) { }
    public record TrackRow(String trackId, String layer, Instant startedAt, Instant endedAt, int pointCount) { }
    public record FileRow(String evidenceId, String evidenceNo, String kindCode, String originalName, String status,
            String sha256, Long sizeBytes, Instant capturedAt, Instant storedAt) { }
    public record AlarmRow(String alarmId, String targetId, String alarmType, String severity, Instant receivedAt,
            String sourceMode) { }
    public record JudgmentRow(String assessmentId, String targetId, String conclusionCode, Instant assessedAt,
            String ruleVersionId) { }
    public record CommandRow(String commandId, String commandNo, String authorizationId, String status, Long createdAt) { }
    public record VerificationRow(String historyId, String eventId, long version, String conclusion,
            String resultingState, Instant createdAt) { }
    public record HandoffRow(String handoffId, String eventId, String handoffType, long sourceVersion,
            Instant createdAt, String deliveryStatus) { }
    public record AuditRow(String auditId, String action, String result, String moduleCode, String objectType,
            String objectId, Long occurredAt) { }
    public record LineageRow(String lineageId, String op, Instant occurredAt, String survivorTargetId,
            String originTargetId, String memberTargetIdsJson, String snapshotsJson) { }
}
