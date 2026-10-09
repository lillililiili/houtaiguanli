package com.uav.lowaltitude.modules.evidence.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceCommandVisibility;

/** One authorized, filtered relation owns list, count and statistics. No synthetic files or receipts. */
@Repository
public class EvidenceLedgerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public EvidenceLedgerRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }
    public record Query(String category, String status, String custody, String subjectKind, String subjectId,
            String text, String sourceKind, String sourceId) { }
    public record Relation(String sql, Map<String, Object> params) { }
    public record LedgerRow(String sourceKind, String sourceId, String category, String evidenceNo,
            String originalName, String kindCode, String status, Long capturedAt, Long storedAt,
            String sourceMode, String layer, Long startedAt, Long endedAt, Long sizeBytes,
            boolean held, String custody, int linkCount, Long retainUntil, Long pointCount) { }

    private static String millis(String column) { return "CAST(EXTRACT(EPOCH FROM " + column + ")*1000 AS BIGINT)"; }
    private static String scope(String alias, AccessDecision access) {
        String sql = " EXISTS(SELECT 1 FROM app_org o WHERE o.org_id=" + alias + ".owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS(SELECT 1 FROM app_district d WHERE d.district_id=" + alias + ".district_id AND d.enabled=TRUE)";
        if (access.scopeMode() == ScopeMode.NONE) return "1=0";
        if (access.scopeMode() == ScopeMode.ASSIGNED) sql += " AND EXISTS(SELECT 1 FROM app_user_data_scope ds WHERE ds.user_id=:user"
                + " AND ds.org_id=" + alias + ".owner_org_id AND ds.district_id=" + alias + ".district_id)";
        return sql;
    }

    public Relation relation(Query q, AccessDecision access, boolean ingest, boolean tracks, EvidenceCommandVisibility commands, long now) {
        Map<String, Object> p = new HashMap<>();
        p.put("user", access.userId()); p.put("now", now); p.put("nearing", now + 30L*86400000L);
        String held = "EXISTS(SELECT 1 FROM evidence_hold h WHERE h.evidence_id=f.evidence_id AND h.released_at IS NULL)";
        String files = "SELECT 'FILE' AS source_kind,f.evidence_id AS source_id,CASE f.kind_code WHEN 'EO_VIDEO' THEN 'VIDEO'"
                + " WHEN 'TRACK_SNAPSHOT' THEN 'TRACK' WHEN 'COMMAND_LOG' THEN 'COMMAND' WHEN 'EO_STILL' THEN 'IMAGE' WHEN 'SCENE_PHOTO' THEN 'IMAGE' ELSE NULL END AS category,"
                + "f.evidence_no,f.original_name,f.kind_code,f.status," + millis("f.captured_at") + " AS captured_at,"
                + millis("f.stored_at") + " AS stored_at,f.source_mode,CAST(NULL AS VARCHAR(16)) AS layer,"
                + "CAST(NULL AS BIGINT) AS started_at,CAST(NULL AS BIGINT) AS ended_at,f.size_bytes," + held + " AS held,"
                + "CASE WHEN " + held + " THEN 'HELD' WHEN " + millis("f.retain_until") + "<=:now THEN 'DUE'"
                + " WHEN " + millis("f.retain_until") + "<=:nearing THEN 'NEARING' ELSE 'KEPT' END AS custody,"
                + "(SELECT COUNT(*) FROM evidence_link l WHERE l.evidence_id=f.evidence_id) AS link_count," + millis("f.retain_until") + " AS retain_until,CAST(NULL AS BIGINT) AS point_count"
                + " FROM evidence_file f WHERE " + scope("f", access)
                + ("FILE".equals(q.sourceKind()) && q.sourceId()!=null ? "" : " AND f.kind_code IN ('EO_VIDEO','EO_STILL','SCENE_PHOTO','TRACK_SNAPSHOT','COMMAND_LOG')")
                + (ingest ? "" : " AND EXISTS(SELECT 1 FROM evidence_link l WHERE l.evidence_id=f.evidence_id)");
        String track = "SELECT 'TRACK',tr.track_id,'TRACK',tr.track_id,t.target_no,CAST(NULL AS VARCHAR(32)),"
                + "CASE WHEN EXISTS(SELECT 1 FROM track_point tp WHERE tp.track_id=tr.track_id) THEN 'OBSERVED' ELSE 'NO_POINTS' END,"
                + millis("tr.started_at") + "," + millis("tr.created_at") + ",t.source_mode,tr.layer,"
                + millis("tr.started_at") + "," + millis("tr.ended_at") + ",CAST(NULL AS BIGINT),FALSE,CAST(NULL AS VARCHAR(16)),1,CAST(NULL AS BIGINT),(SELECT COUNT(*) FROM track_point tp WHERE tp.track_id=tr.track_id)"
                + " FROM track tr JOIN target t ON t.target_id=tr.target_id"
                + " LEFT JOIN target_source_link l ON l.link_id=tr.link_id AND l.target_id=tr.target_id"
                + " LEFT JOIN integration_source src ON src.source_id=l.source_id AND src.source_mode=t.source_mode"
                + " LEFT JOIN device dev ON dev.device_id=l.device_id AND dev.source_id=l.source_id AND dev.source_mode=t.source_mode"
                + " WHERE (tr.link_id IS NULL OR (l.link_id IS NOT NULL AND src.source_id IS NOT NULL AND (l.device_id IS NULL OR dev.device_id IS NOT NULL))) AND "
                + (tracks ? scope("t", access) : "1=0");
        String command = "SELECT 'COMMAND',c.command_id,'COMMAND',c.command_no,c.command_type,CAST(NULL AS VARCHAR(32)),"
                + "c.status,c.created_at,c.created_at,c.source_mode,CAST(NULL AS VARCHAR(16)),CAST(NULL AS BIGINT),CAST(NULL AS BIGINT),"
                + "CAST(NULL AS BIGINT),FALSE,CAST(NULL AS VARCHAR(16)),0,CAST(NULL AS BIGINT),CAST(NULL AS BIGINT) FROM device_command c"
                + " JOIN device_business_scope s ON s.ops_device_id=c.device_id WHERE " + (commands.any() ? scope("s", access) : "1=0")
                + " AND (" + commandVisibility(commands, access) + ")"
                + " AND (c.authorization_id IS NULL OR EXISTS(SELECT 1 FROM disposal_authorization a WHERE a.authorization_id=c.authorization_id AND " + scope("a", access) + "))";
        StringBuilder sql = new StringBuilder("SELECT * FROM (" + files + " UNION ALL " + track + " UNION ALL " + command + ") e WHERE 1=1");
        add(sql,p,"category",q.category()); add(sql,p,"status",q.status()); add(sql,p,"custody",q.custody());
        add(sql,p,"source_kind",q.sourceKind()); add(sql,p,"source_id",q.sourceId());
        if(q.text()!=null) { sql.append(" AND (LOWER(e.original_name) LIKE :text OR LOWER(e.evidence_no) LIKE :text OR LOWER(e.source_id) LIKE :text)"); p.put("text","%"+q.text().toLowerCase(java.util.Locale.ROOT)+"%"); }
        if(q.subjectKind()!=null) {
            p.put("subject_kind",q.subjectKind()); p.put("subject_id",q.subjectId());
            String exact = q.subjectId()==null ? "" : " AND l.subject_id=:subject_id";
            String inherited = "";
            if (q.subjectId()!=null && q.subjectKind().equals("EVENT")) inherited = " OR (l.subject_kind='TARGET' AND l.subject_id IN (SELECT al.target_id FROM uav_event ev JOIN alarm al ON al.alarm_id=ev.alarm_id WHERE ev.event_id=:subject_id))";
            if (q.subjectId()!=null && q.subjectKind().equals("CASE")) inherited = " OR (l.subject_kind='EVENT' AND l.subject_id IN (SELECT event_id FROM punishment_case WHERE case_id=:subject_id)) OR (l.subject_kind='TARGET' AND l.subject_id IN (SELECT al.target_id FROM punishment_case pc JOIN uav_event ev ON ev.event_id=pc.event_id JOIN alarm al ON al.alarm_id=ev.alarm_id WHERE pc.case_id=:subject_id))";
            if(q.subjectKind().equals("COMMAND")) inherited += q.subjectId()==null ? " OR l.command_id IS NOT NULL" : " OR l.command_id=:subject_id";
            String fileLink = "e.source_kind='FILE' AND EXISTS(SELECT 1 FROM evidence_link l WHERE l.evidence_id=e.source_id AND ((l.subject_kind=:subject_kind"+exact+")"+inherited+"))";
            String trackLink = trackSubject(q.subjectKind(),q.subjectId()!=null);
            String cmdLink = commandSubject(q.subjectKind(),q.subjectId()!=null);
            sql.append(" AND ((").append(fileLink).append(") OR (e.source_kind='TRACK' AND ").append(trackLink)
                    .append(") OR (e.source_kind='COMMAND' AND ").append(cmdLink).append("))");
            // Related subjects must remain in the same readable scope even for kind-only filters.
            String subject = switch(q.subjectKind()) {
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
            // Exact contexts were checked by the service; kind-only searches must check each relation.
            if(q.subjectId()==null) {
                String linkedFile="e.source_kind='FILE' AND EXISTS(SELECT 1 FROM evidence_link l WHERE l.evidence_id=e.source_id AND l.subject_kind=:subject_kind AND l.subject_id=visible_subject.id)";
                String linkedTrack=trackSubject(q.subjectKind(),true).replace(":subject_id","visible_subject.id");
                String linkedCommand=commandSubject(q.subjectKind(),true).replace(":subject_id","visible_subject.id");
                sql.append(" AND EXISTS(SELECT 1 FROM (").append(subject).append(") visible_subject WHERE ")
                        .append(scope("visible_subject",access)).append(" AND ((").append(linkedFile)
                        .append(") OR (e.source_kind='TRACK' AND ").append(linkedTrack)
                        .append(") OR (e.source_kind='COMMAND' AND ").append(linkedCommand).append(")))");
            }
        }
        return new Relation(sql.toString(),p);
    }
    private static void add(StringBuilder sql,Map<String,Object> p,String key,String value) {
        if(value!=null){ sql.append(" AND e.").append(key).append("=:").append(key);p.put(key,value); }
    }
    private static String commandVisibility(EvidenceCommandVisibility visibility, AccessDecision access) {
        if (visibility.monitoring()) return "1=1";
        String disposal = visibility.disposal() ? "c.authorization_id IS NOT NULL" : "1=0";
        String tracking = visibility.tracking()
                ? "EXISTS(SELECT 1 FROM eo_tracking_task eo JOIN target et ON et.target_id=eo.target_id"
                    + " WHERE eo.ops_device_id=c.device_id AND (eo.begin_command_id=c.command_id OR eo.end_command_id=c.command_id)"
                    + " AND et.source_mode=c.source_mode AND " + scope("et", access) + ")"
                : "1=0";
        return disposal + " OR " + tracking;
    }
    private static String trackSubject(String kind,boolean exact) {
        String end = exact ? " AND x.subject_id=:subject_id" : "";
        String relation = switch(kind) {
            case "TARGET" -> "SELECT track_id,target_id AS subject_id FROM track";
            case "EVENT" -> "SELECT tr.track_id,ev.event_id AS subject_id FROM track tr JOIN alarm al ON al.target_id=tr.target_id JOIN uav_event ev ON ev.alarm_id=al.alarm_id";
            case "CASE" -> "SELECT tr.track_id,pc.case_id AS subject_id FROM track tr JOIN alarm al ON al.target_id=tr.target_id JOIN uav_event ev ON ev.alarm_id=al.alarm_id JOIN punishment_case pc ON pc.event_id=ev.event_id";
            case "PLAN" -> "SELECT track_id,plan_id AS subject_id FROM assessment_result WHERE plan_id IS NOT NULL AND track_id IS NOT NULL";
            default -> null;
        };
        return relation==null ? "1=0" : "EXISTS(SELECT 1 FROM ("+relation+") x WHERE x.track_id=e.source_id"+end+")";
    }
    private static String commandRelations() {
        return "SELECT command_id,'COMMAND' AS kind,command_id AS id FROM device_command"
                + " UNION SELECT command_id,'DEVICE',device_id FROM device_command"
                + " UNION SELECT command_id,'AUTHORIZATION',authorization_id FROM device_command WHERE authorization_id IS NOT NULL"
                + " UNION SELECT c.command_id,CASE WHEN a.subject_kind='UAV_EVENT' THEN 'EVENT' ELSE a.subject_kind END,a.subject_id FROM device_command c JOIN disposal_authorization a ON a.authorization_id=c.authorization_id"
                + " UNION SELECT c.command_id,'TARGET',a.target_id FROM device_command c JOIN disposal_authorization a ON a.authorization_id=c.authorization_id WHERE a.target_id IS NOT NULL"
                + " UNION SELECT c.command_id,'CASE',pc.case_id FROM device_command c JOIN disposal_authorization a ON a.authorization_id=c.authorization_id JOIN punishment_case pc ON a.subject_kind='UAV_EVENT' AND a.subject_id=pc.event_id"
                + " UNION SELECT begin_command_id,'TARGET',target_id FROM eo_tracking_task WHERE begin_command_id IS NOT NULL AND target_id IS NOT NULL"
                + " UNION SELECT end_command_id,'TARGET',target_id FROM eo_tracking_task WHERE end_command_id IS NOT NULL AND target_id IS NOT NULL"
                + " UNION SELECT command_id,subject_kind,subject_id FROM evidence_link WHERE command_id IS NOT NULL";
    }
    private static String commandSubject(String kind,boolean exact) {
        return "EXISTS(SELECT 1 FROM ("+commandRelations()+") x WHERE x.command_id=e.source_id AND x.kind=:subject_kind"
                +(exact?" AND x.id=:subject_id":"")+")";
    }
    public long count(Relation r) { return jdbc.queryForObject("SELECT COUNT(*) FROM ("+r.sql()+") ledger",r.params(),Long.class); }
    public List<LedgerRow> list(Relation r,int offset,int size) {
        Map<String,Object> p=new HashMap<>(r.params());p.put("offset",offset);p.put("size",size);
        return jdbc.query(r.sql()+" ORDER BY COALESCE(captured_at,stored_at) DESC NULLS LAST,source_kind,source_id OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",p,EvidenceLedgerRepository::entry);
    }
    public Map<String,Long> counts(Relation r,String column) {
        if(!List.of("category","status","custody").contains(column)) throw new IllegalArgumentException("Invalid statistic");
        Map<String,Long> result=new java.util.LinkedHashMap<>();
        jdbc.query("SELECT "+column+",COUNT(*) AS n FROM ("+r.sql()+") ledger WHERE "+column+" IS NOT NULL"+(column.equals("status")?" AND source_kind='FILE'":"")+" GROUP BY "+column,r.params(),rs->{result.put(rs.getString(1),rs.getLong(2));});
        return result;
    }
    public List<Map<String,Object>> trackLinks(String id) {
        return jdbc.queryForList("SELECT 'TARGET' AS subject_kind,t.target_id AS subject_id,t.target_no AS subject_no FROM track tr JOIN target t ON t.target_id=tr.target_id WHERE tr.track_id=:id"
                + " UNION SELECT 'EVENT',ev.event_id,CAST(NULL AS VARCHAR(128)) FROM track tr JOIN alarm al ON al.target_id=tr.target_id JOIN uav_event ev ON ev.alarm_id=al.alarm_id WHERE tr.track_id=:id",Map.of("id",id));
    }
    public record SubjectLink(String kind,String id) { }
    public List<SubjectLink> commandLinks(String id) {
        return jdbc.query("SELECT DISTINCT kind,id FROM ("+commandRelations()+") x WHERE command_id=:id",
                Map.of("id",id),(rs,n)->new SubjectLink(rs.getString("kind"),rs.getString("id")));
    }
    private static Long number(ResultSet rs,String column)throws SQLException {long n=rs.getLong(column);return rs.wasNull()?null:n;}
    private static LedgerRow entry(ResultSet rs,int ignored)throws SQLException {
        return new LedgerRow(rs.getString("source_kind"),rs.getString("source_id"),rs.getString("category"),rs.getString("evidence_no"),
                rs.getString("original_name"),rs.getString("kind_code"),rs.getString("status"),number(rs,"captured_at"),number(rs,"stored_at"),
                rs.getString("source_mode"),rs.getString("layer"),number(rs,"started_at"),number(rs,"ended_at"),number(rs,"size_bytes"),
                rs.getBoolean("held"),rs.getString("custody"),rs.getInt("link_count"),number(rs,"retain_until"),number(rs,"point_count"));
    }
}
