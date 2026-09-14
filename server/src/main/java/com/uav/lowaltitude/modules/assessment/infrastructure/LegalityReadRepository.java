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

@Repository
public class LegalityReadRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LegalityReadRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public long countForPlan(String planId, AccessDecision access) {
        Where where = where(access); where.parameters.put("plan_id", planId); where.sql.append(" AND ar.plan_id=:plan_id");
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from() + where.sql, where.parameters, Long.class);
        return total == null ? 0 : total;
    }

    public List<Row> forPlan(String planId, AccessDecision access, int offset, int size) {
        Where where = where(access); where.parameters.put("plan_id", planId); where.parameters.put("offset", offset); where.parameters.put("size", size);
        where.sql.append(" AND ar.plan_id=:plan_id");
        return jdbc.query(select() + from() + where.sql + " ORDER BY ar.assessed_at DESC,ar.assessment_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.parameters, LegalityReadRepository::row);
    }

    public Row find(String assessmentId, AccessDecision access) {
        Where where = where(access); where.parameters.put("assessment_id", assessmentId); where.sql.append(" AND ar.assessment_id=:assessment_id");
        List<Row> rows = jdbc.query(select() + from() + where.sql, where.parameters, LegalityReadRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean visiblePlan(String planId, AccessDecision access) {
        Where where = where(access); where.parameters.put("plan_id", planId); where.sql.append(" AND p.plan_id=:plan_id");
        // COUNT 在空结果时稳定返回 0；不能让越权和不存在因 EmptyResultDataAccessException 变成 500。
        Long present = jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan p JOIN route_version rv ON rv.route_version_id=p.route_version_id JOIN route r ON r.route_id=rv.route_id" + where.sql, where.parameters, Long.class);
        return present != null && present > 0;
    }

    private static String from() { return " FROM assessment_result ar JOIN flight_plan p ON p.plan_id=ar.plan_id JOIN route_version rv ON rv.route_version_id=p.route_version_id JOIN route r ON r.route_id=rv.route_id JOIN rule_version rule ON rule.rule_version_id=ar.rule_version_id"; }
    private static String select() { return """
            SELECT ar.assessment_id,ar.plan_id,
              -- assessment_result 不拥有范围；target/track 仅在其完整元组与计划相同后才返回，防止关联 ID 侧漏。
              CASE WHEN ar.target_id IS NOT NULL AND EXISTS (SELECT 1 FROM target checked_target WHERE checked_target.target_id=ar.target_id AND checked_target.owner_org_id=p.owner_org_id AND checked_target.district_id=p.district_id) THEN ar.target_id END AS target_id,
              CASE WHEN ar.track_id IS NOT NULL AND EXISTS (SELECT 1 FROM track checked_track JOIN target track_target ON track_target.target_id=checked_track.target_id WHERE checked_track.track_id=ar.track_id AND track_target.owner_org_id=p.owner_org_id AND track_target.district_id=p.district_id) THEN ar.track_id END AS track_id,
              ar.route_version_id,ar.rule_version_id,rule.rule_code,ar.assessed_at,ar.conclusion_code,ar.checks,ar.unknown_reasons,ar.evidence_references,ar.source_mode
            """; }

    private static Where where(AccessDecision access) {
        // 研判先钉住与计划完全同域的航线；ASSIGNED 再由同一授权记录同时匹配组织和区域，禁止跨元组拼接越权。
        StringBuilder sql = new StringBuilder(" WHERE p.owner_org_id IS NOT NULL AND p.district_id IS NOT NULL AND r.owner_org_id=p.owner_org_id AND r.district_id=p.district_id");
        Map<String, Object> parameters = new HashMap<>();
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE WHERE ds.user_id=:scope_user_id AND ds.org_id=p.owner_org_id AND ds.district_id=p.district_id)");
            parameters.put("scope_user_id", access.userId());
        }
        return new Where(sql, parameters);
    }

    private static Row row(ResultSet rs, int ignored) throws SQLException {
        return new Row(rs.getString("assessment_id"), rs.getString("plan_id"), rs.getString("target_id"), rs.getString("track_id"),
                rs.getString("route_version_id"), rs.getString("rule_version_id"), rs.getString("rule_code"), time(rs, "assessed_at"),
                rs.getString("conclusion_code"), rs.getString("checks"), rs.getString("unknown_reasons"), rs.getString("evidence_references"), rs.getString("source_mode"));
    }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); if (value instanceof OffsetDateTime time) return time; if (value instanceof ZonedDateTime time) return time.toOffsetDateTime(); if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC); if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC); return OffsetDateTime.parse(value.toString());
    }
    private record Where(StringBuilder sql, Map<String, Object> parameters) { }
    public record Row(String assessmentId, String planId, String targetId, String trackId, String routeVersionId, String ruleVersionId, String ruleVersionCode, OffsetDateTime assessedAt, String conclusionCode, String checks, String unknownReasons, String evidenceReferences, String sourceMode) { }
}
