package com.uav.lowaltitude.modules.device.infrastructure;

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

/**
 * 运维设备（ops_device）只有单位/区域名称，没有组织/区域 ID。工作台与统计要按精确元组隔离，
 * 只能依赖显式映射表 device_business_scope；没有映射的设备一律不可见，禁止按名称推断归属。
 */
@Repository
public class DeviceBusinessScopeRepository {

    /** UNION ALL 各支共用同一个 params 容器，因此用户参数名固定且带前缀，避免与其他支的 :scope_user_id 冲突。 */
    public static final String USER_PARAM = "device_scope_user_id";

    private final NamedParameterJdbcTemplate jdbc;

    public DeviceBusinessScopeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** 映射表为空表示设备数据源尚未配置归属；调用方必须返回 UNCONFIGURED，不能把未知解释为“无异常”。 */
    public boolean configured() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_business_scope", Map.of(), Long.class);
        return count != null && count > 0;
    }

    /**
     * 返回已按范围过滤的设备异常派生表 SQL（不含外层括号）。过滤发生在数据库层，
     * 这样工作台的分页与 count 都建立在同一谓词之上，而不是取回后再在 Java 里裁剪。
     */
    public String scopedIncidentSql(AccessDecision access, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.incident_id, i.device_id, d.device_no, d.name AS device_name, i.incident_type, i.severity,
                       i.stage, i.detected_at, i.closed_at, i.reason, i.simulated, d.source_mode,
                       s.owner_org_id, s.district_id
                FROM device_incident i
                JOIN ops_device d ON d.device_id = i.device_id
                JOIN device_business_scope s ON s.ops_device_id = i.device_id
                JOIN app_org o ON o.org_id = s.owner_org_id AND o.enabled = TRUE
                JOIN app_district dd ON dd.district_id = s.district_id AND dd.enabled = TRUE
                WHERE 1 = 1""");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 同一条授权记录必须同时命中组织和区域；分成两个 IN 会把 (org-a,district-b) 这类交叉元组放进来。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope us")
               .append(" JOIN app_org uo ON uo.org_id = us.org_id AND uo.enabled = TRUE")
               .append(" JOIN app_district ud ON ud.district_id = us.district_id AND ud.enabled = TRUE")
               .append(" WHERE us.user_id = :").append(USER_PARAM)
               .append(" AND us.org_id = s.owner_org_id AND us.district_id = s.district_id)");
            params.put(USER_PARAM, access.userId());
        } else if (access.scopeMode() != ScopeMode.ALL) {
            // NONE 没有任何业务范围；显式给出恒假条件，而不是依赖调用方记得跳过。
            sql.append(" AND 1 = 0");
        }
        return sql.toString();
    }

    public long countIncidents(AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        String inner = scopedIncidentSql(access, params);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + inner + ") scoped", params, Long.class);
        return count == null ? 0 : count;
    }

    public List<ScopedIncident> listIncidents(AccessDecision access, int offset, int size) {
        Map<String, Object> params = new HashMap<>();
        String inner = scopedIncidentSql(access, params);
        params.put("offset", offset);
        params.put("size", size);
        return jdbc.query("SELECT * FROM (" + inner + ") scoped"
                + " ORDER BY scoped.detected_at DESC, scoped.incident_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                params, DeviceBusinessScopeRepository::incident);
    }

    public ScopedIncident findIncident(String incidentId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        String inner = scopedIncidentSql(access, params);
        params.put("incident_id", incidentId);
        List<ScopedIncident> rows = jdbc.query("SELECT * FROM (" + inner + ") scoped WHERE scoped.incident_id = :incident_id",
                params, DeviceBusinessScopeRepository::incident);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ScopedIncident incident(ResultSet rs, int ignored) throws SQLException {
        long closed = rs.getLong("closed_at");
        Long closedAt = rs.wasNull() ? null : closed;
        return new ScopedIncident(rs.getString("incident_id"), rs.getString("device_id"), rs.getString("device_no"),
                rs.getString("device_name"), rs.getString("incident_type"), rs.getString("severity"), rs.getString("stage"),
                rs.getLong("detected_at"), closedAt, rs.getString("reason"), rs.getBoolean("simulated"),
                rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"));
    }

    public record ScopedIncident(String incidentId, String deviceId, String deviceNo, String deviceName, String incidentType,
            String severity, String stage, long detectedAt, Long closedAt, String reason, boolean simulated,
            String sourceMode, String ownerOrgId, String districtId) { }
}
