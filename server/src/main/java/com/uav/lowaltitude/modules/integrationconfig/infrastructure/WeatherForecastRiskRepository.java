package com.uav.lowaltitude.modules.integrationconfig.infrastructure;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/** 预报规则只读计划范围，命中结果作为风险事实留痕。 */
@Repository
public class WeatherForecastRiskRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public WeatherForecastRiskRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public record PlanCandidate(String planId, String routeVersionId, String sourceMode,
            long startAt, long endAt, String districtName) { }

    public List<PlanCandidate> plans(String areaName, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("area", normalize(areaName));
        StringBuilder sql = new StringBuilder("""
                SELECT p.plan_id,p.route_version_id,p.source_mode,
                       EXTRACT(EPOCH FROM p.start_at)*1000 AS start_at,
                       EXTRACT(EPOCH FROM p.end_at)*1000 AS end_at,d.name AS district_name
                  FROM flight_plan p
                  JOIN route_version rv ON rv.route_version_id=p.route_version_id
                  JOIN route r ON r.route_id=rv.route_id
                       AND r.owner_org_id=p.owner_org_id AND r.district_id=p.district_id
                  JOIN app_org o ON o.org_id=p.owner_org_id AND o.enabled=TRUE
                  JOIN app_district d ON d.district_id=p.district_id AND d.enabled=TRUE
                 WHERE p.source_mode IN ('mock','replay')
                   AND (LOWER(REPLACE(d.name,' ','')) LIKE '%' || :area || '%'
                        OR :area LIKE '%' || LOWER(REPLACE(d.name,' ','')) || '%')
                """);
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append("""
                   AND EXISTS (
                       SELECT 1 FROM app_user_data_scope grant_scope
                        WHERE grant_scope.user_id=:scope_user
                          AND grant_scope.org_id=p.owner_org_id
                          AND grant_scope.district_id=p.district_id)
                """);
            params.put("scope_user", access.userId());
        } else if (access.scopeMode() == ScopeMode.NONE) {
            return List.of();
        }
        sql.append(" ORDER BY p.start_at,p.plan_id FETCH FIRST 1000 ROWS ONLY");
        return jdbc.query(sql.toString(), params, (rs, row) -> new PlanCandidate(
                rs.getString("plan_id"), rs.getString("route_version_id"), rs.getString("source_mode"),
                rs.getLong("start_at"), rs.getLong("end_at"), rs.getString("district_name")));
    }

    public void insertFact(String riskId, String messageId, String areaName, OffsetDateTime publishedAt,
            OffsetDateTime validFrom, OffsetDateTime validTo, String summary, double windSpeedMs, double gustMs,
            int precipitationProbabilityPct, String ruleCode, String ruleVersion, String sourceMode) {
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM weather_forecast_risk_fact WHERE risk_id=:risk_id",
                Map.of("risk_id", riskId), Long.class);
        if (existing != null && existing > 0) return;
        Map<String, Object> params = new HashMap<>();
        params.put("risk_id", riskId);
        params.put("message_id", messageId);
        params.put("area_name", areaName);
        params.put("published_at", publishedAt);
        params.put("valid_from", validFrom);
        params.put("valid_to", validTo);
        params.put("summary", summary);
        params.put("wind_speed", windSpeedMs);
        params.put("gust", gustMs);
        params.put("precipitation", precipitationProbabilityPct);
        params.put("rule_code", ruleCode);
        params.put("rule_version", ruleVersion);
        params.put("source_mode", sourceMode);
        jdbc.update("""
                INSERT INTO weather_forecast_risk_fact
                    (risk_id,forecast_message_id,area_name,published_at,valid_from,valid_to,summary,
                     wind_speed_mps,gust_mps,precipitation_probability_pct,rule_code,rule_version,source_mode)
                VALUES (:risk_id,:message_id,:area_name,:published_at,:valid_from,:valid_to,:summary,
                        :wind_speed,:gust,:precipitation,:rule_code,:rule_version,:source_mode)
                """, params);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
