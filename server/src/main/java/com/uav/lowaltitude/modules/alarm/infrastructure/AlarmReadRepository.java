package com.uav.lowaltitude.modules.alarm.infrastructure;

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

/** 告警范围以 alarm 自身完整组织/区域元组为真源，event 不能借目标的范围扩大可见性。 */
@Repository
public class AlarmReadRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AlarmReadRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public long count(AlarmQuery query, AccessDecision access) {
        Where where = where(query, access);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from() + where.sql, where.parameters, Long.class);
        return total == null ? 0 : total;
    }

    public List<AlarmRow> list(AlarmQuery query, AccessDecision access, int offset, int size, String sort, String order) {
        Where where = where(query, access);
        where.parameters.put("offset", offset);
        where.parameters.put("size", size);
        return jdbc.query(select() + from() + where.sql + orderBy(sort, order)
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.parameters, AlarmReadRepository::alarm);
    }

    /** 导出：不分页，但由调用方先用 count 卡上限；次序与列表完全一致，导出与页面看到的顺序不能不同。 */
    public List<AlarmRow> listForExport(AlarmQuery query, AccessDecision access, int limit, String sort, String order) {
        Where where = where(query, access);
        where.parameters.put("size", limit);
        return jdbc.query(select() + from() + where.sql + orderBy(sort, order)
                + " FETCH NEXT :size ROWS ONLY", where.parameters, AlarmReadRepository::alarm);
    }

    /**
     * 排序键白名单（决策 15-6）。列名不来自请求，只能是这张表里的常量——
     * 直接把参数拼进 ORDER BY 就是注入面。次序键后**恒附 alarm_id**：
     * received_at 之类可能重复，没有唯一兜底的话翻页会漏行或重复行。
     */
    private static String orderBy(String sort, String order) {
        String column = switch (sort == null ? "received_at" : sort) {
            case "occurred_at" -> "a.occurred_at";
            // a.severity 是枚举字符串，按它排是字典序；等级序号见 SeverityOrder（决策 15-30）。
            case "severity" -> com.uav.lowaltitude.platform.query.SeverityOrder.rank("a.severity");
            // 状态在 uav_event 上（LEFT JOIN e），alarm 表根本没有 state 列——
            // 原先写 a.state，白名单放行、SQL 必炸，GET /alarms?sort=state 稳定 500（决策 15-30）。
            case "state" -> "e.state_code";
            default -> "a.received_at";
        };
        String direction = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
        return " ORDER BY " + column + " " + direction + ",a.alarm_id " + direction;
    }

    public AlarmRow find(String alarmId, AccessDecision access) {
        Where where = where(AlarmQuery.empty(), access);
        where.sql.append(" AND a.alarm_id=:alarm_id");
        where.parameters.put("alarm_id", alarmId);
        List<AlarmRow> rows = jdbc.query(select() + from() + where.sql, where.parameters, AlarmReadRepository::alarm);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean targetVisible(String targetId, String alarmOrgId, String alarmDistrictId, AccessDecision access) {
        if (targetId == null) return false;
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("target_id", targetId);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM target t WHERE t.target_id=:target_id AND t.owner_org_id IS NOT NULL AND t.district_id IS NOT NULL");
        // ALL 只是不受用户 grant 限制，不能绕过已停用或不存在的组织、区域目录记录。
        sql.append(" AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=t.owner_org_id AND o.enabled=TRUE)");
        sql.append(" AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=t.district_id AND d.enabled=TRUE)");
        if (alarmOrgId != null && alarmDistrictId != null) {
            parameters.put("alarm_org_id", alarmOrgId); parameters.put("alarm_district_id", alarmDistrictId);
            sql.append(" AND t.owner_org_id=:alarm_org_id AND t.district_id=:alarm_district_id");
        }
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 目标引用单独按目标完整元组校验，不能借已可见 alarm 的范围跨域泄露 target_id。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE WHERE ds.user_id=:target_scope_user_id AND ds.org_id=t.owner_org_id AND ds.district_id=t.district_id)");
            parameters.put("target_scope_user_id", access.userId());
        }
        Long count = jdbc.queryForObject(sql.toString(), parameters, Long.class);
        return count != null && count > 0;
    }

    public boolean targetReadable(String targetId, AccessDecision access) { return targetVisible(targetId, null, null, access); }

    private static String from() {
        return " FROM alarm a JOIN integration_source s ON s.source_id=a.source_id LEFT JOIN uav_event e ON e.alarm_id=a.alarm_id"
                + " LEFT JOIN app_org org_ref ON org_ref.org_id=a.owner_org_id LEFT JOIN app_district dist_ref ON dist_ref.district_id=a.district_id"
                + " LEFT JOIN target tg ON tg.target_id=a.target_id";
    }

    private static String select() {
        return "SELECT a.alarm_id,a.target_id,a.alarm_type,a.severity,a.occurred_at,a.received_at,a.source_mode,a.owner_org_id,a.district_id,s.source_code,e.event_id,e.state_code,"
                + "a.source_alarm_id,s.name AS source_name,org_ref.name AS owner_org_name,dist_ref.name AS district_name,tg.target_no,a.alarm_no";
    }

    /** 排序白名单，供服务层在解析阶段拒绝非法值（400），而不是悄悄回落到默认次序。 */
    /**
     * 调用者范围内**实际出现过**的区域（决策 15-22）。
     *
     * 复用列表那套 where：区域清单必须与他能看到的告警同一口径，
     * 否则筛选框里会出现选了就是空结果的区域——那等于告诉他那边有数据，只是看不到。
     */
    public java.util.List<DistrictOptionRow> districts(AccessDecision access) {
        Where where = where(AlarmQuery.empty(), access);
        return jdbc.query("SELECT DISTINCT a.district_id, d.name" + from()
                + " JOIN app_district d ON d.district_id=a.district_id" + where.sql
                + " ORDER BY d.name ASC, a.district_id ASC", where.parameters,
                (rs, i) -> new DistrictOptionRow(rs.getString("district_id"), rs.getString("name")));
    }

    public record DistrictOptionRow(String districtId, String name) { }

    public static final java.util.Set<String> SORT_KEYS =
            java.util.Set.of("received_at", "occurred_at", "severity", "state");

    private static Where where(AlarmQuery query, AccessDecision access) {
        StringBuilder sql = new StringBuilder(" WHERE a.owner_org_id IS NOT NULL AND a.district_id IS NOT NULL");
        Map<String, Object> parameters = new HashMap<>();
        // 目录启用性是对象可见性的共同前提；ALL 仅跳过 grant，不得读取失效归属的数据。
        sql.append(" AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=a.owner_org_id AND o.enabled=TRUE)");
        sql.append(" AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=a.district_id AND d.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // EXISTS 让同一条授权记录同时绑定 org/district，禁止两条 grant 笛卡尔拼接。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE WHERE ds.user_id=:scope_user_id AND ds.org_id=a.owner_org_id AND ds.district_id=a.district_id)");
            parameters.put("scope_user_id", access.userId());
        }
        add(sql, parameters, "e.state_code", "state", query.state());
        add(sql, parameters, "a.severity", "severity", query.severity());
        add(sql, parameters, "a.target_id", "target_id", query.targetId());
        add(sql, parameters, "a.owner_org_id", "owner_org_id", query.ownerOrgId());
        add(sql, parameters, "a.district_id", "district_id", query.districtId());
        add(sql, parameters, "a.source_mode", "source_mode", query.sourceMode());
        add(sql, parameters, "a.alarm_type", "alarm_type", query.alarmType());
        if (query.targetId() != null) {
            // filter 也只接受与每条 alarm 同域的目标，错连 target 不能影响列表 total。
            sql.append(" AND EXISTS (SELECT 1 FROM target filter_target WHERE filter_target.target_id=a.target_id AND filter_target.target_id=:target_id AND filter_target.owner_org_id=a.owner_org_id AND filter_target.district_id=a.district_id)");
        }
        if (query.occurredFrom() != null) {
            sql.append(" AND a.occurred_at IS NOT NULL AND a.occurred_at>=:occurred_from AND a.occurred_at<:occurred_to");
            parameters.put("occurred_from", query.occurredFrom());
            parameters.put("occurred_to", query.occurredTo());
        }
        return new Where(sql, parameters);
    }

    private static void add(StringBuilder sql, Map<String, Object> parameters, String column, String name, String value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append("=:").append(name);
        parameters.put(name, value);
    }

    private static AlarmRow alarm(ResultSet rs, int ignored) throws SQLException {
        return new AlarmRow(rs.getString("alarm_id"), rs.getString("target_id"), rs.getString("event_id"),
                rs.getString("state_code"), rs.getString("alarm_type"), rs.getString("severity"),
                time(rs, "occurred_at"), time(rs, "received_at"), rs.getString("source_code"),
                rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getString("source_alarm_id"), rs.getString("source_name"), rs.getString("owner_org_name"), rs.getString("district_name"),
                rs.getString("target_no"), rs.getString("alarm_no"));
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time;
        if (value instanceof ZonedDateTime time) return time.toOffsetDateTime();
        if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    private record Where(StringBuilder sql, Map<String, Object> parameters) { }
    public record AlarmQuery(String state, String severity, String targetId, OffsetDateTime occurredFrom,
            OffsetDateTime occurredTo, String ownerOrgId, String districtId, String sourceMode,
            /* 阶段 15（决策 15-7）：按告警类别筛。 */
            String alarmType) {
        public static AlarmQuery empty() {
            return new AlarmQuery(null, null, null, null, null, null, null, null, null);
        }
    }
    public record AlarmRow(String alarmId, String targetId, String eventId, String state, String alarmType,
            String severity, OffsetDateTime occurredAt, OffsetDateTime receivedAt, String sourceCode,
            String sourceMode, String ownerOrgId, String districtId,
            String sourceAlarmId, String sourceName, String ownerOrgName, String districtName, String targetNo, String alarmNo) {
        /** 页面上的告警编号：平台编号优先，没有就用来源编号。 */
        public String displayNo() { return alarmNo != null ? alarmNo : sourceAlarmId; }
    }
}
