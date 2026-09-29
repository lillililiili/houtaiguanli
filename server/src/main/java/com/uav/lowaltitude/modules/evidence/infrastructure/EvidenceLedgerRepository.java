package com.uav.lowaltitude.modules.evidence.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.Entry;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.Link;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

@Repository
public class EvidenceLedgerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public EvidenceLedgerRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    // Raw track linkage has the same source/device/mode checks as TargetReadRepository.
    public List<Entry> tracks(AccessDecision access) {
        return jdbc.query("""
                SELECT tr.track_id,t.target_no,t.source_mode,tr.layer,tr.started_at,tr.ended_at,
                  (SELECT COUNT(*) FROM track_point p WHERE p.track_id=tr.track_id) AS point_count
                FROM track tr JOIN target t ON t.target_id=tr.target_id
                LEFT JOIN target_source_link l ON l.link_id=tr.link_id AND l.target_id=tr.target_id
                LEFT JOIN integration_source s ON s.source_id=l.source_id AND s.source_mode=t.source_mode
                LEFT JOIN device d ON d.device_id=l.device_id AND d.source_id=l.source_id AND d.source_mode=t.source_mode
                WHERE (tr.link_id IS NULL OR (l.link_id IS NOT NULL AND s.source_id IS NOT NULL
                  AND (l.device_id IS NULL OR d.device_id IS NOT NULL)))
                """ + scope("t", access), Map.of("user", access.userId()), (rs, i) -> {
            long points = rs.getLong("point_count");
            return new Entry("TRACK", rs.getString("track_id"), "TRACK", rs.getString("track_id"),
                    rs.getString("target_no"), null, points == 0 ? "NO_POINTS" : "OBSERVED",
                    millis(rs, "started_at"), null, null, null, rs.getString("source_mode"),
                    rs.getString("layer"), millis(rs, "started_at"), millis(rs, "ended_at"), points);
        });
    }

    public List<Entry> commands(AccessDecision access) {
        return jdbc.query("""
                SELECT c.* FROM device_command c
                JOIN device_business_scope s ON s.ops_device_id=c.device_id WHERE 1=1
                """ + scope("s", access), Map.of("user", access.userId()), (rs, i) ->
                new Entry("COMMAND", rs.getString("command_id"), "COMMAND", rs.getString("command_no"),
                        rs.getString("command_type"), null, rs.getString("status"), rs.getLong("created_at"),
                        null, null, null, rs.getString("source_mode"), null, null, null, null));
    }

    public List<Link> links(String kind, String id) {
        String sql = switch (kind) {
            case "FILE" -> "SELECT subject_kind,subject_id FROM evidence_link WHERE evidence_id=:id";
            case "TRACK" -> """
                    SELECT 'TARGET' AS subject_kind,target_id AS subject_id FROM track WHERE track_id=:id
                    UNION SELECT 'EVENT',e.event_id FROM track tr JOIN alarm a ON a.target_id=tr.target_id
                    JOIN uav_event e ON e.alarm_id=a.alarm_id WHERE tr.track_id=:id
                    """;
            case "COMMAND" -> """
                    SELECT 'DEVICE' AS subject_kind,device_id AS subject_id FROM device_command WHERE command_id=:id
                    UNION SELECT 'COMMAND',command_id FROM device_command WHERE command_id=:id
                    UNION SELECT 'AUTHORIZATION',authorization_id FROM device_command WHERE command_id=:id AND authorization_id IS NOT NULL
                    UNION SELECT CASE WHEN a.subject_kind='UAV_EVENT' THEN 'EVENT' ELSE a.subject_kind END,a.subject_id
                      FROM device_command c JOIN disposal_authorization a ON a.authorization_id=c.authorization_id WHERE c.command_id=:id
                    UNION SELECT 'TARGET',target_id FROM eo_tracking_task
                      WHERE (begin_command_id=:id OR end_command_id=:id) AND target_id IS NOT NULL
                    UNION SELECT l.subject_kind,l.subject_id FROM evidence_link l WHERE l.command_id=:id
                    """;
            default -> throw new IllegalArgumentException("Unknown ledger source");
        };
        return jdbc.query(sql, Map.of("id", id), (rs, i) -> new Link(rs.getString(1), rs.getString(2), null));
    }

    /** Batch relationship filtering: never issue one subject query per historical track. */
    public Set<String> relatedIds(String sourceKind, String subjectKind, String subjectId, AccessDecision access) {
        String refs = switch (sourceKind) {
            case "FILE" -> "SELECT evidence_id AS source_id,subject_kind,subject_id FROM evidence_link";
            case "TRACK" -> """
                    SELECT track_id AS source_id,'TARGET' AS subject_kind,target_id AS subject_id FROM track
                    UNION SELECT tr.track_id,'EVENT',e.event_id FROM track tr JOIN alarm a ON a.target_id=tr.target_id
                    JOIN uav_event e ON e.alarm_id=a.alarm_id
                    """;
            case "COMMAND" -> """
                    SELECT command_id AS source_id,'DEVICE' AS subject_kind,device_id AS subject_id FROM device_command
                    UNION SELECT command_id,'COMMAND',command_id FROM device_command
                    UNION SELECT command_id,'AUTHORIZATION',authorization_id FROM device_command WHERE authorization_id IS NOT NULL
                    UNION SELECT c.command_id,CASE WHEN a.subject_kind='UAV_EVENT' THEN 'EVENT' ELSE a.subject_kind END,a.subject_id
                      FROM device_command c JOIN disposal_authorization a ON a.authorization_id=c.authorization_id
                    UNION SELECT begin_command_id,'TARGET',target_id FROM eo_tracking_task WHERE begin_command_id IS NOT NULL AND target_id IS NOT NULL
                    UNION SELECT end_command_id,'TARGET',target_id FROM eo_tracking_task WHERE end_command_id IS NOT NULL AND target_id IS NOT NULL
                    UNION SELECT command_id,subject_kind,subject_id FROM evidence_link WHERE command_id IS NOT NULL
                    """;
            default -> throw new IllegalArgumentException("Unknown ledger source");
        };
        String subject = switch (subjectKind) {
            case "EVENT" -> "SELECT event_id AS id,owner_org_id,district_id FROM uav_event";
            case "TARGET" -> "SELECT target_id AS id,owner_org_id,district_id FROM target";
            case "PLAN" -> "SELECT plan_id AS id,owner_org_id,district_id FROM flight_plan";
            case "CASE" -> "SELECT case_id AS id,owner_org_id,district_id FROM punishment_case";
            case "AUTHORIZATION" -> "SELECT authorization_id AS id,owner_org_id,district_id FROM disposal_authorization";
            case "DEVICE" -> "SELECT ops_device_id AS id,owner_org_id,district_id FROM device_business_scope";
            case "COMMAND" -> "SELECT c.command_id AS id,s.owner_org_id,s.district_id FROM device_command c JOIN device_business_scope s ON s.ops_device_id=c.device_id";
            case "COMMISSION" -> "SELECT c.commission_id AS id,s.owner_org_id,s.district_id FROM commission_task c JOIN device_business_scope s ON s.ops_device_id=c.device_id";
            default -> throw new IllegalArgumentException("Unknown subject");
        };
        Map<String,Object> p = new HashMap<>(); p.put("user", access.userId()); p.put("kind", subjectKind);
        String sql = "SELECT DISTINCT r.source_id FROM (" + refs + ") r JOIN (" + subject + ") s ON s.id=r.subject_id WHERE r.subject_kind=:kind" + scope("s", access);
        if (subjectId != null) { sql += " AND r.subject_id=:subject"; p.put("subject", subjectId); }
        return new HashSet<>(jdbc.queryForList(sql, p, String.class));
    }

    private static String scope(String alias, AccessDecision access) {
        if (access.scopeMode() == ScopeMode.NONE) return " AND 1=0";
        String sql = " AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=" + alias + ".owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=" + alias + ".district_id AND d.enabled=TRUE)";
        if (access.scopeMode() == ScopeMode.ASSIGNED) sql += " AND EXISTS (SELECT 1 FROM app_user_data_scope ds WHERE ds.user_id=:user"
                + " AND ds.org_id=" + alias + ".owner_org_id AND ds.district_id=" + alias + ".district_id)";
        return sql;
    }

    private static Long millis(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().toEpochMilli();
    }
}
