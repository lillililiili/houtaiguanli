package com.uav.lowaltitude.modules.assessment.infrastructure;

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
 * legality_review 头行与只增历史的写入。头行用 version 做条件更新；受影响行数不为 1 一律视为冲突，
 * 调用方不得继续追加历史或成功审计。锁定查询只用内连接（FOR UPDATE 不能落在外连接可空侧）。
 */
@Repository
public class LegalityReviewRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LegalityReviewRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    /** 引擎钩子在研判落库后同事务建复核行；SHADOW 研判不建。 */
    public void insertPending(String evaluationId, String ownerOrgId, String districtId, OffsetDateTime at) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", evaluationId); params.put("org", ownerOrgId); params.put("district", districtId); params.put("at", at);
        jdbc.update("INSERT INTO legality_review (evaluation_id,review_state,manual_status,version,owner_org_id,district_id,created_at,updated_at)"
                + " VALUES (:id,'PENDING_REVIEW',NULL,0,:org,:district,:at,:at)", params);
    }

    public boolean exists(String evaluationId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM legality_review WHERE evaluation_id=:id", Map.of("id", evaluationId), Long.class);
        return count != null && count > 0;
    }

    /** 按范围锁定复核头行及其研判事实；越权与不存在同样返回 null（调用方统一 404）。 */
    public ReviewRow lock(String evaluationId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", evaluationId);
        // 只锁 legality_review 一行：FOR UPDATE 若落在与 rule_set/rule_set_version 的连接上，会让同一规则集下所有复核互相串行。
        StringBuilder sql = new StringBuilder("SELECT r.evaluation_id FROM legality_review r WHERE r.evaluation_id=:id"
                + " AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=r.owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=r.district_id AND d.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 锁定前也按同一授权元组过滤，避免可猜 evaluation_id 泄露或跨域锁竞争。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE"
                    + " WHERE ds.user_id=:scope_user_id AND ds.org_id=r.owner_org_id AND ds.district_id=r.district_id)");
            params.put("scope_user_id", access.userId());
        }
        sql.append(" FOR UPDATE");
        if (jdbc.queryForList(sql.toString(), params, String.class).isEmpty()) return null;
        return details(evaluationId);
    }

    /** 无范围的头行锁：研判已在同一事务内校验过范围时使用。 */
    public ReviewRow lockInternal(String evaluationId) {
        if (jdbc.queryForList("SELECT evaluation_id FROM legality_review WHERE evaluation_id=:id FOR UPDATE", Map.of("id", evaluationId), String.class).isEmpty()) return null;
        return details(evaluationId);
    }

    /** 锁定之后再读研判事实：行已被本事务持有，读到的版本与状态即为提交前的最终依据。 */
    private ReviewRow details(String evaluationId) {
        List<ReviewRow> rows = jdbc.query("SELECT r.evaluation_id,r.review_state,r.manual_status,r.version,r.owner_org_id,r.district_id,"
                + "e.mode,e.subject_kind,e.target_id,e.plan_id,e.legal_status,e.plan_match_code,e.grade,e.score,e.violation_reasons,e.as_of,e.observed_at,e.source_mode,"
                + "e.rule_set_version_id,v.rule_set_id,rs.rule_set_code,e.alarm_id"
                + " FROM legality_review r JOIN rule_evaluation e ON e.evaluation_id=r.evaluation_id"
                + " JOIN rule_set_version v ON v.rule_set_version_id=e.rule_set_version_id JOIN rule_set rs ON rs.rule_set_id=v.rule_set_id"
                + " WHERE r.evaluation_id=:id", Map.of("id", evaluationId), LegalityReviewRepository::review);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 条件更新：版本与当前状态都必须命中，否则视为并发冲突。 */
    public int transition(String evaluationId, long expectedVersion, String fromState, String toState, String manualStatus, OffsetDateTime at) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", evaluationId); params.put("version", expectedVersion); params.put("from", fromState);
        params.put("to", toState); params.put("manual", manualStatus); params.put("at", at);
        return jdbc.update("UPDATE legality_review SET review_state=:to,manual_status=:manual,updated_at=:at,version=version+1"
                + " WHERE evaluation_id=:id AND version=:version AND review_state=:from", params);
    }

    /** 只推进版本、不改状态（转告警）。 */
    public int bump(String evaluationId, long expectedVersion, OffsetDateTime at) {
        return jdbc.update("UPDATE legality_review SET updated_at=:at,version=version+1 WHERE evaluation_id=:id AND version=:version",
                Map.of("id", evaluationId, "version", expectedVersion, "at", at));
    }

    public void appendHistory(HistoryInsert h) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", h.historyId()); params.put("evaluation", h.evaluationId()); params.put("version", h.version());
        params.put("previous", h.previousState()); params.put("resulting", h.resultingState()); params.put("conclusion", h.conclusion());
        params.put("before", h.statusBefore()); params.put("after", h.statusAfter()); params.put("note", h.note()); params.put("actor", h.actorId());
        params.put("related_evaluation", h.relatedEvaluationId()); params.put("related_alarm", h.relatedAlarmId()); params.put("at", h.createdAt());
        jdbc.update("INSERT INTO legality_review_history (history_id,evaluation_id,version,previous_state,resulting_state,conclusion,status_before,status_after,"
                + "note,actor_id,related_evaluation_id,related_alarm_id,created_at) VALUES (:id,:evaluation,:version,:previous,:resulting,:conclusion,:before,:after,"
                + ":note,:actor,:related_evaluation,:related_alarm,:at)", params);
    }

    private static ReviewRow review(ResultSet rs, int i) throws SQLException {
        return new ReviewRow(rs.getString("evaluation_id"), rs.getString("review_state"), rs.getString("manual_status"), rs.getLong("version"),
                rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("mode"), rs.getString("subject_kind"), rs.getString("target_id"),
                rs.getString("plan_id"), rs.getString("legal_status"), rs.getString("plan_match_code"), rs.getString("grade"), rs.getBigDecimal("score"),
                rs.getString("violation_reasons"), time(rs, "as_of"), time(rs, "observed_at"), rs.getString("source_mode"), rs.getString("rule_set_version_id"),
                rs.getString("rule_set_id"), rs.getString("rule_set_code"), rs.getString("alarm_id"));
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); if (value == null) return null;
        if (value instanceof OffsetDateTime t) return t; if (value instanceof ZonedDateTime t) return t.toOffsetDateTime();
        if (value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC); if (value instanceof LocalDateTime t) return t.atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    public record ReviewRow(String evaluationId, String reviewState, String manualStatus, long version, String ownerOrgId, String districtId,
            String mode, String subjectKind, String targetId, String planId, String legalStatus, String planMatchCode, String grade,
            java.math.BigDecimal score, String violationReasons, OffsetDateTime asOf, OffsetDateTime observedAt, String sourceMode,
            String ruleSetVersionId, String ruleSetId, String ruleSetCode, String engineAlarmId) { }

    public record HistoryInsert(String historyId, String evaluationId, long version, String previousState, String resultingState, String conclusion,
            String statusBefore, String statusAfter, String note, String actorId, String relatedEvaluationId, String relatedAlarmId, OffsetDateTime createdAt) { }
}
