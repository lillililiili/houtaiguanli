package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportingRepository {

    private final NamedParameterJdbcTemplate named;

    public ReportingRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    public Map<String, Object> targetSummary(LocalDate from, LocalDate to, Scope scope) {
        return named.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN legal_status='非法' THEN 1 ELSE 0 END) AS illegal,
                       SUM(CASE WHEN legal_status='异常' THEN 1 ELSE 0 END) AS abnormal,
                       SUM(CASE WHEN object_type='无人机' THEN 1 ELSE 0 END) AS uav,
                       SUM(CASE WHEN risk_level IN ('高风险','超高风险') THEN 1 ELSE 0 END) AS high_risk
                FROM report_airborne_target t
                """ + where(scope, "t"), params(from, to, scope));
    }

    public int caseCount(LocalDate from, LocalDate to, Scope scope) {
        Integer count = named.queryForObject(
                "SELECT COUNT(*) FROM report_penalty_case c" + where(scope, "c"),
                params(from, to, scope), Integer.class);
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> targetsByDay(LocalDate from, LocalDate to, Scope scope) {
        return named.queryForList("""
                SELECT occurred_on,
                       COUNT(*) AS total,
                       SUM(CASE WHEN legal_status='非法' THEN 1 ELSE 0 END) AS illegal,
                       SUM(CASE WHEN risk_level IN ('高风险','超高风险') THEN 1 ELSE 0 END) AS high_risk
                FROM report_airborne_target t
                """ + where(scope, "t") + """
                GROUP BY occurred_on
                ORDER BY occurred_on
                """, params(from, to, scope));
    }

    public List<Map<String, Object>> casesByDay(LocalDate from, LocalDate to, Scope scope) {
        return named.queryForList("""
                SELECT occurred_on, COUNT(*) AS punish
                FROM report_penalty_case c
                """ + where(scope, "c") + """
                GROUP BY occurred_on
                ORDER BY occurred_on
                """, params(from, to, scope));
    }

    public List<Map<String, Object>> targetsByRegion(LocalDate from, LocalDate to, Scope scope) {
        return named.queryForList("""
                SELECT district_name AS name,
                       COUNT(*) AS total,
                       SUM(CASE WHEN legal_status='非法' THEN 1 ELSE 0 END) AS illegal,
                       SUM(CASE WHEN risk_level IN ('高风险','超高风险') THEN 1 ELSE 0 END) AS high_risk
                FROM report_airborne_target t
                """ + where(scope, "t") + """
                GROUP BY district_name
                """, params(from, to, scope));
    }

    public List<Map<String, Object>> casesByRegion(LocalDate from, LocalDate to, Scope scope) {
        return named.queryForList("""
                SELECT district_name AS name, COUNT(*) AS punish
                FROM report_penalty_case c
                """ + where(scope, "c") + """
                GROUP BY district_name
                """, params(from, to, scope));
    }

    public List<Map<String, Object>> namedCounts(String sqlFragment, LocalDate from, LocalDate to, Scope scope) {
        return named.queryForList(
                "SELECT " + sqlFragment + " AS name, COUNT(*) AS item_count FROM report_airborne_target t"
                        + where(scope, "t") + " GROUP BY " + sqlFragment,
                params(from, to, scope));
    }

    public List<Map<String, Object>> penaltyCounts(LocalDate from, LocalDate to, Scope scope) {
        return named.queryForList("""
                SELECT penalty_type AS name, COUNT(*) AS item_count
                FROM report_penalty_case c
                """ + where(scope, "c") + """
                GROUP BY penalty_type
                """, params(from, to, scope));
    }

    public List<Map<String, Object>> partnerRanks(LocalDate from, LocalDate to, Scope scope) {
        Map<String, Object> params = params(from, to, scope);
        params.put("limit", 5);
        return named.queryForList("""
                SELECT partner_name AS name,
                       COUNT(*) AS case_count,
                       SUM(CASE WHEN penalty_type='罚款' THEN fine_amount ELSE 0 END) AS fine
                FROM report_penalty_case c
                """ + where(scope, "c") + """
                GROUP BY partner_name
                ORDER BY case_count DESC, fine DESC, partner_name
                OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
                """, params);
    }

    public Map<String, Object> sourceSummary(LocalDate from, LocalDate to, Scope scope) {
        return named.queryForMap("""
                SELECT COUNT(*) AS n,
                       SUM(CASE WHEN source_mode='mock' THEN 1 ELSE 0 END) AS mock_n,
                       SUM(CASE WHEN source_mode='live' THEN 1 ELSE 0 END) AS live_n,
                       SUM(CASE WHEN simulated=TRUE THEN 1 ELSE 0 END) AS simulated_n
                FROM (
                    SELECT source_mode, simulated FROM report_airborne_target t
                    """ + where(scope, "t") + """
                    UNION ALL
                    SELECT source_mode, simulated FROM report_penalty_case c
                    """ + where(scope, "c") + """
                ) x
                """, params(from, to, scope));
    }

    /** 设备口径与设备管理页一致：运维台账 ops_device（含消息接入设备），不是阶段 2 的感知设备目录。 */
    public Map<String, Object> deviceCounts() {
        return named.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN s.connectivity='ONLINE' THEN 1 ELSE 0 END) AS online
                FROM ops_device d LEFT JOIN ops_device_state s ON s.device_id=d.device_id
                """, Map.of());
    }

    private static String where(Scope scope, String alias) {
        String sql = " WHERE " + alias + ".occurred_on BETWEEN :from_on AND :to_on ";
        if (!scope.allScope()) {
            sql += "AND EXISTS (SELECT 1 FROM app_user_data_scope s WHERE s.user_id=:user_id"
                    + " AND s.org_id=" + alias + ".owner_org_id AND s.district_id=" + alias + ".district_id) ";
        }
        return sql;
    }

    private static Map<String, Object> params(LocalDate from, LocalDate to, Scope scope) {
        Map<String, Object> params = new HashMap<>();
        params.put("from_on", Date.valueOf(from));
        params.put("to_on", Date.valueOf(to));
        if (!scope.allScope()) params.put("user_id", scope.userId());
        return params;
    }

    public record Scope(boolean allScope, String userId) { }
}
