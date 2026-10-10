package com.uav.lowaltitude.modules.alarm.infrastructure;

import com.uav.lowaltitude.platform.report.BusinessReportSource.Dataset;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Range;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;

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

    /**
     * 告警升级（2026-10-06，BUG-11/BUG-16）：alarm 行只增不改，升级记在只增的 alarm_escalation 上。
     * 最近一次升级 = 同一告警 seq 最大的一行；当前等级取它的结果，没有升级就是告警原值。
     * 列表、详情、筛选、排序、导出与报表一律按当前等级，页面看到的等级与筛出来、排出来的一致。
     */
    static final String LATEST_ESCALATION = " LEFT JOIN alarm_escalation esc ON esc.alarm_id=a.alarm_id"
            + " AND NOT EXISTS (SELECT 1 FROM alarm_escalation newer_esc WHERE newer_esc.alarm_id=esc.alarm_id AND newer_esc.seq>esc.seq)";
    static final String CURRENT_SEVERITY = "COALESCE(esc.severity_after,a.severity)";

    public Dataset reportDataset(ReportDatasetReader reader, Range range, AccessDecision access) {
        Where w = where(AlarmQuery.empty(), access);
        String time = reader.epoch("a.occurred_at");
        String sql = "SELECT a.alarm_id AS id,COALESCE(a.alarm_no,a.source_alarm_id) AS label," + time + " AS at_ms,"
            + "e.state_code AS state,a.alarm_type AS kind," + CURRENT_SEVERITY + " AS severity,dist_ref.name AS region,a.source_mode,"
            + "CAST(NULL AS VARCHAR) AS related,CAST(NULL AS VARCHAR) AS result,CAST(NULL AS VARCHAR) AS note" + from() + w.sql;
        return ReportDatasetReader.window(sql, w.parameters, range, time);
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final com.uav.lowaltitude.platform.time.AppClock clock;
    private final com.uav.lowaltitude.modules.fusion.application.FusionConfigService fusionConfig;

    public AlarmReadRepository(JdbcTemplate jdbcTemplate, com.uav.lowaltitude.platform.time.AppClock clock,
            com.uav.lowaltitude.modules.fusion.application.FusionConfigService fusionConfig) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.clock = clock;
        this.fusionConfig = fusionConfig;
    }

    /** 使用当前融合终止时长；一次查询固定同一个时间点，恰好到期即下沉。 */
    private void putAttentionTime(Map<String, Object> parameters) {
        var now = clock.now();
        long lifetime = fusionConfig.params(null).integer("identity", "terminate_after_ms");
        if (lifetime <= 0) throw new IllegalStateException("Invalid target observation lifetime");
        parameters.put("attention_now", now.atOffset(ZoneOffset.UTC));
        parameters.put("observation_since", now.minusMillis(lifetime).atOffset(ZoneOffset.UTC));
    }

    public long count(AlarmQuery query, AccessDecision access) {
        Where where = where(query, access);
        if (query.attentionGroups() != null) putAttentionTime(where.parameters);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from() + where.sql, where.parameters, Long.class);
        return total == null ? 0 : total;
    }

    public List<AlarmRow> list(AlarmQuery query, AccessDecision access, int offset, int size, String sort, String order) {
        Where where = where(query, access);
        where.parameters.put("offset", offset);
        putAttentionTime(where.parameters);
        where.parameters.put("size", size);
        return jdbc.query(select() + from() + where.sql + orderBy(sort, order)
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.parameters, AlarmReadRepository::alarm);
    }

    /** 导出：不分页，但由调用方先用 count 卡上限；次序与列表完全一致，导出与页面看到的顺序不能不同。 */
    public List<AlarmRow> listForExport(AlarmQuery query, AccessDecision access, int limit, String sort, String order) {
        Where where = where(query, access);
        where.parameters.put("size", limit);
        putAttentionTime(where.parameters);
        return jdbc.query(select() + from() + where.sql + orderBy(sort, order)
                + " FETCH NEXT :size ROWS ONLY", where.parameters, AlarmReadRepository::alarm);
    }

    /**
     * 排序键白名单（决策 15-6）。列名不来自请求，只能是这张表里的常量——
     * 直接把参数拼进 ORDER BY 就是注入面。次序键后**恒附 alarm_id**：
     * received_at 之类可能重复，没有唯一兜底的话翻页会漏行或重复行。
     */
    private static String orderBy(String sort, String order) {
        if (sort == null || "priority".equals(sort)) return PRIORITY_ORDER;
        String column = switch (sort) {
            case "occurred_at" -> "a.occurred_at";
            // 等级是枚举字符串，按它排是字典序；等级序号见 SeverityOrder（决策 15-30）。按升级后的当前等级排。
            case "severity" -> com.uav.lowaltitude.platform.query.SeverityOrder.rank(CURRENT_SEVERITY);
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
        putAttentionTime(where.parameters);
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
                + " LEFT JOIN target tg ON tg.target_id=a.target_id"
                + " LEFT JOIN target_latest_state obs ON obs.target_id=tg.target_id" + LATEST_ESCALATION;
    }

    /** detail 只在服务层取 violation_reasons 摘要，不原样外露；升级过的告警以最近一次升级的累计原因为准。 */
    private static String select() {
        return "SELECT a.alarm_id,a.target_id,a.alarm_type," + CURRENT_SEVERITY + " AS severity,a.severity AS original_severity,"
                + "a.occurred_at,a.received_at,a.source_mode,a.owner_org_id,a.district_id,s.source_code,e.event_id,e.state_code,"
                + "a.source_alarm_id,s.name AS source_name,org_ref.name AS owner_org_name,dist_ref.name AS district_name,tg.target_no,a.alarm_no,"
                + "a.detail,esc.reasons_after,esc.seq AS escalation_count,esc.created_at AS escalated_at,"
                + AlarmAttentionSql.OBSERVATION_STATUS + " AS observation_status,"
                + AlarmAttentionSql.GROUP + " AS attention_group";
    }

    /** 升级记录按次序从早到晚；调用方先用 find 确认告警可见（同核实历史）。 */
    public long countEscalations(String alarmId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM alarm_escalation x WHERE x.alarm_id=:alarm_id", Map.of("alarm_id", alarmId), Long.class);
        return total == null ? 0 : total;
    }

    /** 只读实际升级引用，不把合并窗口里的后续普通研判当成触发历史。调用前须鉴权告警。 */
    public List<AirspaceEvaluationReference> airspaceEvaluationReferences(String alarmId) {
        return jdbc.query("SELECT x.evaluation_id,x.seq FROM alarm_escalation x JOIN alarm a ON a.alarm_id=x.alarm_id"
                + " AND a.owner_org_id=x.owner_org_id AND a.district_id=x.district_id"
                + " WHERE x.alarm_id=:alarm ORDER BY x.seq", Map.of("alarm", alarmId),
                (rs, i) -> new AirspaceEvaluationReference(rs.getString("evaluation_id"), rs.getInt("seq")));
    }
    public record AirspaceEvaluationReference(String evaluationId, int occurrence) { }

    public List<EscalationRow> escalations(String alarmId, int offset, int size) {
        return jdbc.query("SELECT x.escalation_id,x.seq,x.trigger_kind,x.severity_before,x.severity_after,x.reasons_added,x.reasons_after,"
                + "x.note,x.actor_id,au.name AS actor_name,x.created_at FROM alarm_escalation x LEFT JOIN app_user au ON au.user_id=x.actor_id"
                + " WHERE x.alarm_id=:alarm_id ORDER BY x.seq ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                Map.of("alarm_id", alarmId, "offset", offset, "size", size),
                (rs, i) -> new EscalationRow(rs.getString("escalation_id"), rs.getInt("seq"), rs.getString("trigger_kind"),
                        rs.getString("severity_before"), rs.getString("severity_after"), rs.getString("reasons_added"), rs.getString("reasons_after"),
                        rs.getString("note"), rs.getString("actor_id"), rs.getString("actor_name"), time(rs, "created_at")));
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
            java.util.Set.of("priority", "received_at", "occurred_at", "severity", "state");

    /** 当前事项 → 观测过期/未知待确认 → 历史；前两组等级高、新到优先，历史新到优先。 */
    private static final String PRIORITY_ORDER = " ORDER BY " + AlarmAttentionSql.GROUP_RANK + " ASC,"
            + " CASE WHEN (" + AlarmAttentionSql.GROUP_RANK + ")<2 THEN "
            + com.uav.lowaltitude.platform.query.SeverityOrder.rank(CURRENT_SEVERITY) + " ELSE 0 END DESC,"
            + " a.received_at DESC,a.alarm_id ASC";

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
        add(sql, parameters, CURRENT_SEVERITY, "severity", query.severity());
        add(sql, parameters, "a.target_id", "target_id", query.targetId());
        add(sql, parameters, "a.owner_org_id", "owner_org_id", query.ownerOrgId());
        add(sql, parameters, "a.district_id", "district_id", query.districtId());
        add(sql, parameters, "a.source_mode", "source_mode", query.sourceMode());
        add(sql, parameters, "a.alarm_type", "alarm_type", query.alarmType());
        if (query.attentionGroups() != null) {
            // 与每行返回的 attention_group 同一个分类表达式，筛出来的和页面分组一致；时刻参数由调用方放入。
            sql.append(" AND (").append(AlarmAttentionSql.GROUP).append(") IN (:attention_groups)");
            parameters.put("attention_groups", query.attentionGroups());
        }
        if (query.violationReason() != null) {
            // 当前违规原因与列表展示同源：升级过取最近一次升级的累计原因，否则取告警明细的 violation_reasons。
            // JSON 列只做 CAST 后的字符串匹配（PG 没有 jsonb LIKE）；H2 把 CAST(文本 AS JSON) 存成带转义的 JSON 字符串，
            // 先去掉 \" 转义，再按带引号的完整代码匹配，PLAN_ALTITUDE_EXCEEDED 不会命中 AIRSPACE_ALTITUDE_EXCEEDED。
            sql.append(" AND REPLACE(CASE WHEN esc.alarm_id IS NULL THEN CAST(a.detail AS VARCHAR) ELSE CAST(esc.reasons_after AS VARCHAR) END,")
                    .append(" '\\\"', '\"') LIKE :violation_reason ESCAPE '\\'");
            parameters.put("violation_reason", "%\"" + query.violationReason().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "\"%");
        }
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
                rs.getString("target_no"), rs.getString("alarm_no"), rs.getString("original_severity"), rs.getString("detail"),
                rs.getString("reasons_after"), rs.getInt("escalation_count"), time(rs, "escalated_at"),
                rs.getString("observation_status"), rs.getString("attention_group"));
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
            String alarmType,
            /* 2026-10-07：按关注分组筛（待处置统计、状态筛选）；null 表示不限。 */
            List<String> attentionGroups,
            /* 2026-10-07：按当前违规原因代码筛（告警页"违规类别"）；null 表示不限。 */
            String violationReason) {
        public static AlarmQuery empty() {
            return new AlarmQuery(null, null, null, null, null, null, null, null, null, null, null);
        }
    }
    /**
     * severity 是升级后的当前等级，originalSeverity 是告警产生时的等级；escalationCount 为 0 表示没升级过。
     * detailJson/escalatedReasonsJson 只供服务层取违规原因，不外露。
     */
    public record AlarmRow(String alarmId, String targetId, String eventId, String state, String alarmType,
            String severity, OffsetDateTime occurredAt, OffsetDateTime receivedAt, String sourceCode,
            String sourceMode, String ownerOrgId, String districtId,
            String sourceAlarmId, String sourceName, String ownerOrgName, String districtName, String targetNo, String alarmNo,
            String originalSeverity, String detailJson, String escalatedReasonsJson, int escalationCount, OffsetDateTime escalatedAt,
            String observationStatus, String attentionGroup) {
        /** 页面上的告警编号：平台编号优先，没有就用来源编号。 */
        public String displayNo() { return alarmNo != null ? alarmNo : sourceAlarmId; }
    }
    public record EscalationRow(String escalationId, int seq, String triggerKind, String severityBefore, String severityAfter,
            String reasonsAddedJson, String reasonsAfterJson, String note, String actorId, String actorName, OffsetDateTime createdAt) { }
}
