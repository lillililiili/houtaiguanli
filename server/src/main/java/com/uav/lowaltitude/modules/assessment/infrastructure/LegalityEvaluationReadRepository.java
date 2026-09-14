package com.uav.lowaltitude.modules.assessment.infrastructure;

import java.math.BigDecimal;
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

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/**
 * 引擎研判、复核历史与规则效果事实的只读查询。范围谓词以研判行自带的 (owner_org_id, district_id) 为真源，
 * 列表、详情、count、历史与 allowed_actions 共用同一谓词（含组织/区域目录启用检查，同 AlarmReadRepository.where）。
 */
@Repository
public class LegalityEvaluationReadRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LegalityEvaluationReadRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public long count(EvaluationQuery query, AccessDecision access) {
        Where where = where(query, access);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from() + where.sql, where.parameters, Long.class);
        return total == null ? 0 : total;
    }

    public List<EvaluationRow> list(EvaluationQuery query, AccessDecision access, int offset, int size) {
        Where where = where(query, access);
        where.parameters.put("offset", offset); where.parameters.put("size", size);
        return jdbc.query(select(false) + from() + where.sql + " ORDER BY e.evaluated_at DESC,e.evaluation_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters, (rs, i) -> row(rs, false));
    }

    public EvaluationRow find(String evaluationId, AccessDecision access) {
        Where where = where(EvaluationQuery.empty(), access);
        where.sql.append(" AND e.evaluation_id=:evaluation_id"); where.parameters.put("evaluation_id", evaluationId);
        List<EvaluationRow> rows = jdbc.query(select(true) + from() + where.sql, where.parameters, (rs, i) -> row(rs, true));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 复核历史必须通过研判行的范围谓词，越权者连 total 也不能探测。 */
    public long countRevisions(String evaluationId, AccessDecision access) {
        Where where = where(EvaluationQuery.empty(), access);
        where.sql.append(" AND e.evaluation_id=:evaluation_id"); where.parameters.put("evaluation_id", evaluationId);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM legality_review_history h JOIN rule_evaluation e ON e.evaluation_id=h.evaluation_id" + where.sql, where.parameters, Long.class);
        return total == null ? 0 : total;
    }

    public List<RevisionRow> revisions(String evaluationId, AccessDecision access, int offset, int size) {
        Where where = where(EvaluationQuery.empty(), access);
        where.sql.append(" AND e.evaluation_id=:evaluation_id"); where.parameters.put("evaluation_id", evaluationId);
        where.parameters.put("offset", offset); where.parameters.put("size", size);
        return jdbc.query("SELECT h.history_id,h.version,h.previous_state,h.resulting_state,h.conclusion,h.status_before,h.status_after,h.note,h.actor_id,au.name AS actor_name,"
                + "h.related_evaluation_id,h.related_alarm_id,h.created_at FROM legality_review_history h JOIN rule_evaluation e ON e.evaluation_id=h.evaluation_id"
                + " LEFT JOIN app_user au ON au.user_id=h.actor_id" + where.sql + " ORDER BY h.version ASC,h.history_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters, LegalityEvaluationReadRepository::revision);
    }

    /** 目标引用：目标必须存在、目录启用、与研判同一元组，ASSIGNED 再按用户授权元组校验（同 AlarmReadRepository.targetVisible）。 */
    public boolean targetVisible(String targetId, String orgId, String districtId, AccessDecision access) {
        if (targetId == null) return false;
        return referenceVisible("target t", "t.target_id", "t.owner_org_id", "t.district_id", targetId, orgId, districtId, access);
    }

    public boolean planVisible(String planId, String orgId, String districtId, AccessDecision access) {
        if (planId == null) return false;
        return referenceVisible("flight_plan t", "t.plan_id", "t.owner_org_id", "t.district_id", planId, orgId, districtId, access);
    }

    public boolean alarmVisible(String alarmId, String orgId, String districtId, AccessDecision access) {
        if (alarmId == null) return false;
        return referenceVisible("alarm t", "t.alarm_id", "t.owner_org_id", "t.district_id", alarmId, orgId, districtId, access);
    }

    public String eventIdOfAlarm(String alarmId) {
        if (alarmId == null) return null;
        List<String> rows = jdbc.queryForList("SELECT event_id FROM uav_event WHERE alarm_id=:alarm", Map.of("alarm", alarmId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- 规则效果事实（v_rule_effect_fact） ----

    public long countFacts(FactQuery query, AccessDecision access) {
        Where where = factWhere(query, access);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM v_rule_effect_fact f" + where.sql, where.parameters, Long.class);
        return total == null ? 0 : total;
    }

    public List<FactRow> facts(FactQuery query, AccessDecision access, int offset, int size) {
        Where where = factWhere(query, access);
        where.parameters.put("offset", offset); where.parameters.put("size", size);
        return jdbc.query("SELECT f.evaluation_id,f.evaluated_at,f.mode,f.subject_kind,f.target_id,f.plan_id,f.rule_set_version_id,f.param_status,f.legal_status,"
                + "f.manual_status,f.review_state,f.has_alarm,f.merge_kind,f.alarm_id,f.group_id,f.supersedes_evaluation_id,f.source_mode,f.owner_org_id,f.district_id"
                + " FROM v_rule_effect_fact f" + where.sql + " ORDER BY f.evaluated_at DESC,f.evaluation_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.parameters, LegalityEvaluationReadRepository::fact);
    }

    /** 汇总口径全部在 SQL 中用条件计数表达；分母是否为 0 由应用层判断，这里不做除法。 */
    public SummaryCounts summary(FactQuery query, AccessDecision access) {
        Where where = factWhere(query, access);
        return jdbc.queryForObject("SELECT COUNT(*) AS evaluations,"
                + " SUM(CASE WHEN f.mode='ACTIVE' AND f.legal_status IN ('ABNORMAL','ILLEGAL') THEN 1 ELSE 0 END) AS alarm_worthy,"
                + " SUM(CASE WHEN f.merge_kind IN ('CREATED','UPGRADED') THEN 1 ELSE 0 END) AS alarms_created,"
                + " SUM(CASE WHEN f.merge_kind IN ('MERGED','DOWNGRADED') THEN 1 ELSE 0 END) AS alarms_merged,"
                + " SUM(CASE WHEN f.review_state IN ('CONFIRMED','REJECTED','OVERRIDDEN') THEN 1 ELSE 0 END) AS reviewed,"
                + " SUM(CASE WHEN f.review_state='REJECTED' THEN 1 ELSE 0 END) AS rejected,"
                + " SUM(CASE WHEN f.review_state='OVERRIDDEN' THEN 1 ELSE 0 END) AS overridden,"
                + " SUM(CASE WHEN f.review_state='OVERRIDDEN' AND f.legal_status IN ('LEGAL','UNDETERMINED') AND f.manual_status IN ('ILLEGAL','ABNORMAL') THEN 1 ELSE 0 END) AS missed"
                + " FROM v_rule_effect_fact f" + where.sql, where.parameters,
                (rs, i) -> new SummaryCounts(rs.getLong("evaluations"), rs.getLong("alarm_worthy"), rs.getLong("alarms_created"), rs.getLong("alarms_merged"),
                        rs.getLong("reviewed"), rs.getLong("rejected"), rs.getLong("overridden"), rs.getLong("missed")));
    }

    private boolean referenceVisible(String table, String idColumn, String orgColumn, String districtColumn, String id, String orgId, String districtId, AccessDecision access) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + "=:id AND " + orgColumn + " IS NOT NULL AND " + districtColumn + " IS NOT NULL");
        // ALL 只是不受用户 grant 限制，不能绕过已停用或不存在的组织、区域目录记录。
        sql.append(" AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=" + orgColumn + " AND o.enabled=TRUE)");
        sql.append(" AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=" + districtColumn + " AND d.enabled=TRUE)");
        if (orgId != null && districtId != null) {
            parameters.put("ref_org_id", orgId); parameters.put("ref_district_id", districtId);
            sql.append(" AND " + orgColumn + "=:ref_org_id AND " + districtColumn + "=:ref_district_id");
        }
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 关联对象单独按其完整元组校验，不能借已可见研判的范围跨域泄露引用 ID。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE WHERE ds.user_id=:ref_scope_user_id AND ds.org_id=" + orgColumn + " AND ds.district_id=" + districtColumn + ")");
            parameters.put("ref_scope_user_id", access.userId());
        }
        Long count = jdbc.queryForObject(sql.toString(), parameters, Long.class);
        return count != null && count > 0;
    }

    private static String from() {
        return " FROM rule_evaluation e JOIN rule_set_version v ON v.rule_set_version_id=e.rule_set_version_id JOIN rule_set rs ON rs.rule_set_id=v.rule_set_id"
                + " JOIN rule_run run ON run.run_id=e.run_id LEFT JOIN legality_review r ON r.evaluation_id=e.evaluation_id"
                + " LEFT JOIN alarm_merge_member m ON m.evaluation_id=e.evaluation_id LEFT JOIN target tg ON tg.target_id=e.target_id"
                + " LEFT JOIN flight_plan p ON p.plan_id=e.plan_id LEFT JOIN app_org org_ref ON org_ref.org_id=e.owner_org_id"
                + " LEFT JOIN app_district dist_ref ON dist_ref.district_id=e.district_id";
    }

    private static String select(boolean withHits) {
        return "SELECT e.evaluation_id,e.run_id,rs.rule_set_code,e.rule_set_version_id,v.version_no,v.param_status,e.mode,run.trigger_kind,e.subject_kind,e.target_id,tg.target_no,"
                + "e.track_id,e.plan_id,p.plan_no,e.route_version_id,e.observed_at,e.as_of,e.evaluated_at,e.freshness_code,e.plan_match_code,e.legal_status,e.score,e.grade,"
                + "e.violation_reasons,e.unknown_reasons,e.evidence_references," + (withHits ? "e.hit_details" : "CAST(NULL AS VARCHAR(1)) AS hit_details") + ","
                + "r.review_state,r.manual_status,r.version AS review_version,e.supersedes_evaluation_id,"
                + "(SELECT n.evaluation_id FROM rule_evaluation n WHERE n.supersedes_evaluation_id=e.evaluation_id ORDER BY n.evaluated_at DESC,n.evaluation_id DESC FETCH FIRST 1 ROWS ONLY) AS superseded_by_evaluation_id,"
                + "e.alarm_id AS engine_alarm_id,m.alarm_id AS member_alarm_id,"
                // 人工转告警不回写只增的研判行，其告警引用只存在于复核历史。
                + "(SELECT h.related_alarm_id FROM legality_review_history h WHERE h.evaluation_id=e.evaluation_id AND h.conclusion='ESCALATE' AND h.related_alarm_id IS NOT NULL ORDER BY h.version DESC FETCH FIRST 1 ROWS ONLY) AS manual_alarm_id,"
                + "e.alarm_outcome,m.member_kind,e.assessment_id,e.owner_org_id,org_ref.name AS owner_org_name,e.district_id,dist_ref.name AS district_name,e.source_mode";
    }

    private static Where where(EvaluationQuery query, AccessDecision access) {
        Where where = scope(access, "e");
        add(where, "e.mode", "mode", query.mode());
        add(where, "e.legal_status", "legal_status", query.legalStatus());
        add(where, "e.plan_match_code", "plan_match", query.planMatch());
        add(where, "e.subject_kind", "subject_kind", query.subjectKind());
        add(where, "e.target_id", "target_id", query.targetId());
        add(where, "e.plan_id", "plan_id", query.planId());
        add(where, "e.owner_org_id", "owner_org_id", query.ownerOrgId());
        add(where, "e.district_id", "district_id", query.districtId());
        add(where, "e.source_mode", "source_mode", query.sourceMode());
        if (query.reviewState() != null) {
            where.sql.append(" AND r.review_state=:review_state"); where.parameters.put("review_state", query.reviewState());
        }
        if (query.from() != null) {
            where.sql.append(" AND e.evaluated_at>=:from AND e.evaluated_at<:to");
            where.parameters.put("from", query.from()); where.parameters.put("to", query.to());
        }
        if (query.latestOnly()) {
            // 队列只看每个主体在该模式下最近一条研判；被重算取代的旧研判自然被更新的一条压掉。
            where.sql.append(" AND NOT EXISTS (SELECT 1 FROM rule_evaluation n WHERE n.mode=e.mode AND n.subject_kind=e.subject_kind"
                    + " AND ((e.subject_kind='TARGET' AND n.target_id=e.target_id) OR (e.subject_kind='PLAN' AND n.plan_id=e.plan_id))"
                    + " AND (n.evaluated_at>e.evaluated_at OR (n.evaluated_at=e.evaluated_at AND n.evaluation_id>e.evaluation_id)))");
        }
        return where;
    }

    private static Where factWhere(FactQuery query, AccessDecision access) {
        Where where = scope(access, "f");
        add(where, "f.mode", "mode", query.mode());
        add(where, "f.legal_status", "legal_status", query.legalStatus());
        add(where, "f.review_state", "review_state", query.reviewState());
        add(where, "f.owner_org_id", "owner_org_id", query.ownerOrgId());
        add(where, "f.district_id", "district_id", query.districtId());
        add(where, "f.source_mode", "source_mode", query.sourceMode());
        if (query.from() != null) {
            where.sql.append(" AND f.evaluated_at>=:from AND f.evaluated_at<:to");
            where.parameters.put("from", query.from()); where.parameters.put("to", query.to());
        }
        return where;
    }

    private static Where scope(AccessDecision access, String alias) {
        StringBuilder sql = new StringBuilder(" WHERE " + alias + ".owner_org_id IS NOT NULL AND " + alias + ".district_id IS NOT NULL");
        Map<String, Object> parameters = new HashMap<>();
        // 目录启用性是对象可见性的共同前提；ALL 仅跳过 grant，不得读取失效归属的数据。
        sql.append(" AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=" + alias + ".owner_org_id AND o.enabled=TRUE)");
        sql.append(" AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=" + alias + ".district_id AND d.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // EXISTS 让同一条授权记录同时绑定 org/district，禁止两条 grant 笛卡尔拼接。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE"
                    + " WHERE ds.user_id=:scope_user_id AND ds.org_id=" + alias + ".owner_org_id AND ds.district_id=" + alias + ".district_id)");
            parameters.put("scope_user_id", access.userId());
        }
        return new Where(sql, parameters);
    }

    private static void add(Where where, String column, String name, String value) {
        if (value == null) return;
        where.sql.append(" AND ").append(column).append("=:").append(name);
        where.parameters.put(name, value);
    }

    private static EvaluationRow row(ResultSet rs, boolean withHits) throws SQLException {
        return new EvaluationRow(rs.getString("evaluation_id"), rs.getString("run_id"), rs.getString("rule_set_code"), rs.getString("rule_set_version_id"),
                rs.getInt("version_no"), rs.getString("param_status"), rs.getString("mode"), rs.getString("trigger_kind"), rs.getString("subject_kind"),
                rs.getString("target_id"), rs.getString("target_no"), rs.getString("track_id"), rs.getString("plan_id"), rs.getString("plan_no"),
                rs.getString("route_version_id"), time(rs, "observed_at"), time(rs, "as_of"), time(rs, "evaluated_at"), rs.getString("freshness_code"),
                rs.getString("plan_match_code"), rs.getString("legal_status"), rs.getBigDecimal("score"), rs.getString("grade"),
                rs.getString("violation_reasons"), rs.getString("unknown_reasons"), rs.getString("evidence_references"), withHits ? rs.getString("hit_details") : null,
                rs.getString("review_state"), rs.getString("manual_status"), rs.getObject("review_version") == null ? null : rs.getLong("review_version"),
                rs.getString("supersedes_evaluation_id"), rs.getString("superseded_by_evaluation_id"), rs.getString("engine_alarm_id"),
                rs.getString("member_alarm_id"), rs.getString("manual_alarm_id"), rs.getString("alarm_outcome"), rs.getString("member_kind"),
                rs.getString("assessment_id"), rs.getString("owner_org_id"), rs.getString("owner_org_name"), rs.getString("district_id"),
                rs.getString("district_name"), rs.getString("source_mode"));
    }

    private static RevisionRow revision(ResultSet rs, int i) throws SQLException {
        return new RevisionRow(rs.getString("history_id"), rs.getLong("version"), rs.getString("previous_state"), rs.getString("resulting_state"),
                rs.getString("conclusion"), rs.getString("status_before"), rs.getString("status_after"), rs.getString("note"), rs.getString("actor_id"),
                rs.getString("actor_name"), rs.getString("related_evaluation_id"), rs.getString("related_alarm_id"), time(rs, "created_at"));
    }

    private static FactRow fact(ResultSet rs, int i) throws SQLException {
        return new FactRow(rs.getString("evaluation_id"), time(rs, "evaluated_at"), rs.getString("mode"), rs.getString("subject_kind"), rs.getString("target_id"),
                rs.getString("plan_id"), rs.getString("rule_set_version_id"), rs.getString("param_status"), rs.getString("legal_status"), rs.getString("manual_status"),
                rs.getString("review_state"), rs.getBoolean("has_alarm"), rs.getString("merge_kind"), rs.getString("alarm_id"), rs.getString("group_id"),
                rs.getString("supersedes_evaluation_id"), rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"));
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); if (value == null) return null;
        if (value instanceof OffsetDateTime t) return t; if (value instanceof ZonedDateTime t) return t.toOffsetDateTime();
        if (value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC); if (value instanceof LocalDateTime t) return t.atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    private record Where(StringBuilder sql, Map<String, Object> parameters) { }

    public record EvaluationQuery(String mode, boolean latestOnly, String legalStatus, String planMatch, String reviewState, String subjectKind,
            String targetId, String planId, OffsetDateTime from, OffsetDateTime to, String ownerOrgId, String districtId, String sourceMode) {
        public static EvaluationQuery empty() { return new EvaluationQuery(null, false, null, null, null, null, null, null, null, null, null, null, null); }
    }

    public record FactQuery(OffsetDateTime from, OffsetDateTime to, String mode, String legalStatus, String reviewState, String sourceMode,
            String ownerOrgId, String districtId) { }

    public record EvaluationRow(String evaluationId, String runId, String ruleSetCode, String ruleSetVersionId, int ruleSetVersionNo, String paramStatus,
            String mode, String triggerKind, String subjectKind, String targetId, String targetNo, String trackId, String planId, String planNo,
            String routeVersionId, OffsetDateTime observedAt, OffsetDateTime asOf, OffsetDateTime evaluatedAt, String freshnessCode, String planMatchCode,
            String legalStatus, BigDecimal score, String grade, String violationReasons, String unknownReasons, String evidenceReferences, String hitDetails,
            String reviewState, String manualStatus, Long reviewVersion, String supersedesEvaluationId, String supersededByEvaluationId,
            String engineAlarmId, String memberAlarmId, String manualAlarmId, String alarmOutcome, String memberKind, String assessmentId,
            String ownerOrgId, String ownerOrgName, String districtId, String districtName, String sourceMode) {
        /** 引擎回填 > 合并成员 > 人工转告警历史；三者都空才算“无告警”。 */
        public String alarmId() { return engineAlarmId != null ? engineAlarmId : memberAlarmId != null ? memberAlarmId : manualAlarmId; }
    }

    public record RevisionRow(String historyId, long version, String previousState, String resultingState, String conclusion, String statusBefore,
            String statusAfter, String note, String actorId, String actorName, String relatedEvaluationId, String relatedAlarmId, OffsetDateTime createdAt) { }

    public record FactRow(String evaluationId, OffsetDateTime evaluatedAt, String mode, String subjectKind, String targetId, String planId,
            String ruleSetVersionId, String paramStatus, String legalStatus, String manualStatus, String reviewState, boolean hasAlarm, String mergeKind,
            String alarmId, String groupId, String supersedesEvaluationId, String sourceMode, String ownerOrgId, String districtId) { }

    public record SummaryCounts(long evaluations, long alarmWorthy, long alarmsCreated, long alarmsMerged, long reviewed, long rejected, long overridden, long missed) { }
}
