package com.uav.lowaltitude.modules.workbench.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.device.infrastructure.DeviceBusinessScopeRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/**
 * 工作台聚合的三类源事项在数据库里用一条 UNION ALL 语句拼成一张派生表，再统一排序、分页、count。
 * 每一支在进入 UNION 之前就按自己的源权限范围过滤：这样分页与 count 建立在同一谓词之上，
 * 不会出现“各取一页再在 Java 合并”导致的漏数、重复或跨页错序。
 */
@Repository
public class WorkbenchReadRepository {
    public static final String UAV_EVENT = "UAV_EVENT";
    public static final String RISK = "RISK";
    public static final String DEVICE_INCIDENT = "DEVICE_INCIDENT";
    /* 队列次序（决策 16-10，按原版工作台改回）：可操作的排前面（2），等回执的居中（1），终态沉底（0）；同档再按等级、接收时间。
       终态：误报、已通知、已回执、已排除、已恢复——它们已经没有"下一步"，不能占住队首。 */
    private static final String ORDER = " ORDER BY u.action_rank DESC, u.severity_rank DESC, u.received_at DESC, u.kind ASC, u.source_id DESC";

    private final NamedParameterJdbcTemplate jdbc;
    private final DeviceBusinessScopeRepository deviceScope;

    public WorkbenchReadRepository(JdbcTemplate jdbcTemplate, DeviceBusinessScopeRepository deviceScope) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.deviceScope = deviceScope;
    }

    /** 各类 count 与列表使用同一 UNION 与同一筛选；缺席的类别由调用方按可用性补 0 或 null。 */
    public Map<String, Long> countByKind(WorkbenchQuery query, Branches branches) {
        Map<String, Object> params = new HashMap<>();
        String union = union(query, branches, params);
        Map<String, Long> counts = new LinkedHashMap<>();
        if (union == null) return counts;
        jdbc.query("SELECT u.kind, COUNT(*) AS n FROM (" + union + ") u" + filters(query, params) + " GROUP BY u.kind", params,
                (ResultSet rs) -> { counts.put(rs.getString("kind"), rs.getLong("n")); });
        return counts;
    }

    public List<ItemRow> list(WorkbenchQuery query, Branches branches, int offset, int size) {
        Map<String, Object> params = new HashMap<>();
        String union = union(query, branches, params);
        if (union == null) return List.of();
        params.put("offset", offset);
        params.put("size", size);
        return jdbc.query("SELECT * FROM (" + union + ") u" + filters(query, params) + ORDER
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", params, WorkbenchReadRepository::item);
    }

    /** 详情只走对应的一支：同一 source_id 在不同 kind 下是两条不同事项，不能跨支查找。 */
    public ItemRow find(String kind, String sourceId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("source_id", sourceId);
        List<ItemRow> rows = jdbc.query("SELECT * FROM (" + branch(kind, access, params) + ") u WHERE u.source_id=:source_id",
                params, WorkbenchReadRepository::item);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 核实历史仍套用事件自身的范围谓词，而不是只凭 event_id 直查历史表。 */
    public List<VerificationRow> uavVerifications(String eventId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("source_id", eventId);
        return jdbc.query("SELECT h.history_id,h.version,h.previous_state,h.resulting_state,h.conclusion,h.note,h.actor_id,"
                + ms("h.created_at") + " AS created_at,au.name AS actor_name FROM uav_event_verification h LEFT JOIN app_user au ON au.user_id=h.actor_id WHERE h.event_id=:source_id"
                + " AND EXISTS (SELECT 1 FROM (" + uavBranch(access, params) + ") u WHERE u.source_id=h.event_id)"
                + " ORDER BY h.version ASC, h.history_id ASC", params, WorkbenchReadRepository::verification);
    }

    public List<VerificationRow> riskVerifications(String riskId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("source_id", riskId);
        return jdbc.query("SELECT h.history_id,h.version,h.previous_state,h.resulting_state,h.conclusion,h.note,h.actor_id,"
                + ms("h.created_at") + " AS created_at,au.name AS actor_name FROM flight_risk_verification h LEFT JOIN app_user au ON au.user_id=h.actor_id WHERE h.risk_id=:source_id"
                + " AND EXISTS (SELECT 1 FROM (" + riskBranch(access, params) + ") u WHERE u.source_id=h.risk_id)"
                + " ORDER BY h.version ASC, h.history_id ASC", params, WorkbenchReadRepository::verification);
    }

    /** 交接归属复制自源风险；只返回与当前可见风险同一元组的交接，投递状态取最新一次尝试。 */
    public List<HandoffRow> riskHandoffs(String riskId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("source_id", riskId);
        return jdbc.query("SELECT h.handoff_id,h.handoff_type,h.recipient_id,rc.display_name AS recipient_name,h.source_version,"
                + ms("h.created_at") + " AS created_at,"
                + " (SELECT d.delivery_status FROM handoff_delivery d WHERE d.handoff_id=h.handoff_id ORDER BY d.attempt_no DESC FETCH FIRST 1 ROW ONLY) AS delivery_status,"
                + " (SELECT d.blocked_reason FROM handoff_delivery d WHERE d.handoff_id=h.handoff_id ORDER BY d.attempt_no DESC FETCH FIRST 1 ROW ONLY) AS blocked_reason"
                + " FROM handoff h JOIN handoff_recipient rc ON rc.recipient_id=h.recipient_id"
                + " WHERE h.source_kind='RISK' AND h.source_id=:source_id"
                + " AND EXISTS (SELECT 1 FROM (" + riskBranch(access, params) + ") u WHERE u.source_id=h.source_id"
                + " AND u.owner_org_id=h.owner_org_id AND u.district_id=h.district_id)"
                + " ORDER BY h.created_at ASC, h.handoff_id ASC", params, WorkbenchReadRepository::handoff);
    }

    public boolean riskNoticeRecipientConfigured() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM handoff_recipient WHERE enabled=TRUE AND handoff_type='RISK_NOTICE'",
                Map.of(), Long.class);
        return count != null && count > 0;
    }

    private String union(WorkbenchQuery query, Branches branches, Map<String, Object> params) {
        List<String> parts = new ArrayList<>();
        // 没有某支权限就不把该支放进 UNION：这样它既不占分页名额，也不会在 GROUP BY 里泄露 count。
        if (branches.uav() != null && query.matchesKind(UAV_EVENT)) parts.add(uavBranch(branches.uav(), params));
        if (branches.risk() != null && query.matchesKind(RISK)) parts.add(riskBranch(branches.risk(), params));
        if (branches.device() != null && query.matchesKind(DEVICE_INCIDENT)) parts.add(deviceBranch(branches.device(), params));
        return parts.isEmpty() ? null : String.join(" UNION ALL ", parts);
    }

    private String branch(String kind, AccessDecision access, Map<String, Object> params) {
        return switch (kind) {
            case UAV_EVENT -> uavBranch(access, params);
            case RISK -> riskBranch(access, params);
            case DEVICE_INCIDENT -> deviceBranch(access, params);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** 事件范围以 alarm 与 uav_event 完全一致的组织/区域元组为真源；ALL 仅免授权元组，不免目录启用性。 */
    private static String uavBranch(AccessDecision access, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("SELECT CAST('UAV_EVENT' AS VARCHAR(32)) AS kind, e.event_id AS source_id, e.state_code AS state,"
                + " a.severity AS severity, " + rank("a.severity") + " AS severity_rank, " + ms("a.received_at") + " AS received_at, "
                + " CASE e.state_code WHEN 'FALSE_POSITIVE' THEN 0 ELSE 2 END AS action_rank, "
                + ms("a.occurred_at") + " AS occurred_at, " + ms("e.updated_at") + " AS updated_at, e.version AS version,"
                + " a.source_mode AS source_mode, a.owner_org_id AS owner_org_id, a.district_id AS district_id, a.alarm_type AS type_code,"
                + " CAST(NULL AS VARCHAR(2000)) AS reason_text, CAST(NULL AS VARCHAR(64)) AS device_no, CAST(NULL AS VARCHAR(128)) AS device_name,"
                + " a.alarm_id AS related_id, CAST(COALESCE(a.alarm_no, a.source_alarm_id) AS VARCHAR(64)) AS source_no"
                + " FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id AND a.owner_org_id=e.owner_org_id AND a.district_id=e.district_id"
                + " WHERE EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=e.owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=e.district_id AND d.enabled=TRUE)");
        assigned(sql, params, access, "e.owner_org_id", "e.district_id", "uav_scope_user_id");
        return sql.toString();
    }

    /** 与 RiskRepository 相同：风险必须仍挂在同一元组的计划与来源上，工作台不能比风险页看到更多。 */
    private static String riskBranch(AccessDecision access, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("SELECT CAST('RISK' AS VARCHAR(32)) AS kind, r.risk_id AS source_id, r.state_code AS state,"
                + " r.severity AS severity, " + rank("r.severity") + " AS severity_rank, " + ms("r.received_at") + " AS received_at, "
                + " CASE r.state_code WHEN 'NOTIFIED' THEN 0 WHEN 'ACKNOWLEDGED' THEN 0 WHEN 'EXCLUDED' THEN 0 ELSE 2 END AS action_rank, "
                + ms("r.occurred_at") + " AS occurred_at, " + ms("r.updated_at") + " AS updated_at, r.version AS version,"
                + " r.source_mode AS source_mode, r.owner_org_id AS owner_org_id, r.district_id AS district_id, r.risk_type AS type_code,"
                + " CAST(r.reason_text AS VARCHAR(2000)) AS reason_text, CAST(NULL AS VARCHAR(64)) AS device_no, CAST(NULL AS VARCHAR(128)) AS device_name,"
                + " CAST(NULL AS VARCHAR(36)) AS related_id, CAST(COALESCE(r.risk_no, r.source_risk_id) AS VARCHAR(64)) AS source_no"
                + " FROM flight_risk r JOIN integration_source s ON s.source_id=r.source_id AND s.source_mode=r.source_mode"
                + " JOIN flight_plan p ON p.plan_id=r.plan_id AND p.route_version_id=r.route_version_id"
                + " AND p.owner_org_id=r.owner_org_id AND p.district_id=r.district_id"
                + " WHERE EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=r.owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=r.district_id AND d.enabled=TRUE)");
        assigned(sql, params, access, "r.owner_org_id", "r.district_id", "risk_scope_user_id");
        return sql.toString();
    }

    /** 设备异常直接复用领导提供的已过滤派生表；映射、目录启用与精确元组谓词都已在其中。 */
    private String deviceBranch(AccessDecision access, Map<String, Object> params) {
        return "SELECT CAST('DEVICE_INCIDENT' AS VARCHAR(32)) AS kind, di.incident_id AS source_id, di.stage AS state,"
                + " di.severity AS severity, " + rank("di.severity") + " AS severity_rank, di.detected_at AS received_at,"
                + " CASE di.stage WHEN 'RECOVERED' THEN 0 WHEN 'PROCESSING' THEN 1 ELSE 2 END AS action_rank,"
                + " CAST(NULL AS BIGINT) AS occurred_at, di.closed_at AS updated_at, CAST(NULL AS BIGINT) AS version,"
                + " di.source_mode AS source_mode, di.owner_org_id AS owner_org_id, di.district_id AS district_id, di.incident_type AS type_code,"
                + " CAST(di.reason AS VARCHAR(2000)) AS reason_text, di.device_no AS device_no, di.device_name AS device_name, di.device_id AS related_id, CAST(di.device_no AS VARCHAR(64)) AS source_no"
                + " FROM (" + deviceScope.scopedIncidentSql(access, params) + ") di";
    }

    private static void assigned(StringBuilder sql, Map<String, Object> params, AccessDecision access, String orgColumn, String districtColumn, String param) {
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 同一条授权记录必须同时命中组织和区域，禁止两条 grant 拼成交叉元组。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org so ON so.org_id=ds.org_id AND so.enabled=TRUE")
               .append(" JOIN app_district sd ON sd.district_id=ds.district_id AND sd.enabled=TRUE WHERE ds.user_id=:").append(param)
               .append(" AND ds.org_id=").append(orgColumn).append(" AND ds.district_id=").append(districtColumn).append(")");
            params.put(param, access.userId());
        } else if (access.scopeMode() != ScopeMode.ALL) {
            sql.append(" AND 1=0");
        }
    }

    private static String filters(WorkbenchQuery query, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        add(sql, params, "u.state", "state", query.state());
        add(sql, params, "u.severity", "severity", query.severity());
        // severity_min：工作台"高等级事项"卡片要的是"高及以上"，等级序与排序用的 rank 同一口径。
        if (query.severityMin() != null) {
            sql.append(" AND u.severity_rank >= :severity_min_rank");
            params.put("severity_min_rank", switch (query.severityMin()) { case "CRITICAL" -> 4; case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; });
        }
        add(sql, params, "u.owner_org_id", "owner_org_id", query.ownerOrgId());
        add(sql, params, "u.district_id", "district_id", query.districtId());
        add(sql, params, "u.source_mode", "source_mode", query.sourceMode());
        if (query.occurredFrom() != null) {
            sql.append(" AND u.occurred_at IS NOT NULL AND u.occurred_at>=:occurred_from AND u.occurred_at<:occurred_to");
            params.put("occurred_from", query.occurredFrom());
            params.put("occurred_to", query.occurredTo());
        }
        return sql.toString();
    }

    private static void add(StringBuilder sql, Map<String, Object> params, String column, String name, String value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append("=:").append(name);
        params.put(name, value);
    }

    private static String rank(String column) {
        return "CASE " + column + " WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END";
    }
    /** 三支的时间字段类型不同（TIMESTAMPTZ 与 BIGINT 毫秒），统一转成毫秒后才能一起排序。 */
    private static String ms(String column) { return "CAST(EXTRACT(EPOCH FROM " + column + ") * 1000 AS BIGINT)"; }

    private static ItemRow item(ResultSet rs, int ignored) throws SQLException {
        return new ItemRow(rs.getString("kind"), rs.getString("source_id"), rs.getString("state"), rs.getString("severity"),
                rs.getLong("received_at"), nullable(rs, "occurred_at"), nullable(rs, "updated_at"), nullable(rs, "version"),
                rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("type_code"),
                rs.getString("reason_text"), rs.getString("device_no"), rs.getString("device_name"), rs.getString("related_id"), rs.getString("source_no"));
    }
    private static VerificationRow verification(ResultSet rs, int ignored) throws SQLException {
        return new VerificationRow(rs.getString("history_id"), rs.getLong("version"), rs.getString("previous_state"),
                rs.getString("resulting_state"), rs.getString("conclusion"), rs.getString("note"), rs.getString("actor_id"), rs.getLong("created_at"), rs.getString("actor_name"));
    }
    private static HandoffRow handoff(ResultSet rs, int ignored) throws SQLException {
        return new HandoffRow(rs.getString("handoff_id"), rs.getString("handoff_type"), rs.getString("recipient_id"),
                rs.getString("recipient_name"), rs.getLong("source_version"), rs.getLong("created_at"),
                rs.getString("delivery_status"), rs.getString("blocked_reason"));
    }
    private static Long nullable(ResultSet rs, String column) throws SQLException { long value = rs.getLong(column); return rs.wasNull() ? null : value; }

    /** 各支的范围决定；null 表示该支没有读权限（或未配置），不进入 UNION。 */
    public record Branches(AccessDecision uav, AccessDecision risk, AccessDecision device) { }
    public record WorkbenchQuery(String kind, String state, String severity, String severityMin, Long occurredFrom, Long occurredTo,
            String ownerOrgId, String districtId, String sourceMode) {
        boolean matchesKind(String candidate) { return kind == null || kind.equals(candidate); }
    }
    public record ItemRow(String kind, String sourceId, String state, String severity, long receivedAt, Long occurredAt, Long updatedAt,
            Long version, String sourceMode, String ownerOrgId, String districtId, String typeCode, String reasonText,
            String deviceNo, String deviceName, String relatedId, String sourceNo) { }
    public record VerificationRow(String historyId, long version, String previousState, String resultingState, String conclusion,
            String note, String actorId, long createdAt, String actorName) { }
    public record HandoffRow(String handoffId, String handoffType, String recipientId, String recipientName, long sourceVersion,
            long createdAt, String deliveryStatus, String blockedReason) { }
}
