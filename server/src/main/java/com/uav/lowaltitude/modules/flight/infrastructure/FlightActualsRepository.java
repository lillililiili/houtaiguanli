package com.uav.lowaltitude.modules.flight.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * 计划与实际对照的研判读取：只取该计划最近一条 ACTIVE 研判。
 * 研判行自带 (owner_org_id, district_id)，因此这里套的是研判读权限的范围谓词，而不是借用计划的可见性——
 * 有 flight:read 不等于有权看引擎结论。
 */
@Repository
public class FlightActualsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FlightActualsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** 最近一条 ACTIVE 研判；同一时刻并列时按 evaluation_id 取大者，保证结果稳定。 */
    public EvaluationRow findLatestActiveEvaluation(String planId, AccessDecision access) {
        StringBuilder sql = new StringBuilder("SELECT e.evaluation_id,e.target_id,e.plan_match_code,e.legal_status,e.evaluated_at,e.hit_details,"
                + "v.param_status"
                // 参数状态是规则集版本级的事实：DEMO 版本的结论不能被当成已确认口径使用，页面要能标出来。
                + " FROM rule_evaluation e JOIN rule_set_version v ON v.rule_set_version_id=e.rule_set_version_id"
                + " WHERE e.plan_id=:plan_id AND e.mode='ACTIVE'"
                + " AND e.owner_org_id IS NOT NULL AND e.district_id IS NOT NULL");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("plan_id", planId);
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 同一授权行必须同时命中组织和行政区，不能把两个范围元组拼成越权访问。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope granted_scope"
                    + " JOIN app_org scope_org ON scope_org.org_id=granted_scope.org_id AND scope_org.enabled=TRUE"
                    + " JOIN app_district scope_district ON scope_district.district_id=granted_scope.district_id AND scope_district.enabled=TRUE"
                    + " WHERE granted_scope.user_id=:scope_user_id AND granted_scope.org_id=e.owner_org_id"
                    + " AND granted_scope.district_id=e.district_id)");
            parameters.put("scope_user_id", access.userId());
        }
        sql.append(" ORDER BY e.evaluated_at DESC,e.evaluation_id DESC FETCH FIRST 1 ROWS ONLY");
        List<EvaluationRow> rows = jdbc.query(sql.toString(), parameters, FlightActualsRepository::evaluation);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static EvaluationRow evaluation(ResultSet rs, int rowNum) throws SQLException {
        return new EvaluationRow(rs.getString("evaluation_id"), rs.getString("plan_match_code"), rs.getString("legal_status"),
                time(rs, "evaluated_at"), rs.getString("param_status"), rs.getString("hit_details"), rs.getString("target_id"));
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time;
        if (value instanceof ZonedDateTime time) return time.toOffsetDateTime();
        if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC);
        if (value instanceof Number time) return Instant.ofEpochMilli(time.longValue()).atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    public record EvaluationRow(String evaluationId, String planMatchCode, String legalStatus,
            OffsetDateTime evaluatedAt, String paramStatus, String hitDetailsJson, String targetId) { }
}
