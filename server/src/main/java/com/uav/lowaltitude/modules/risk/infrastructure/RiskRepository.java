package com.uav.lowaltitude.modules.risk.infrastructure;

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

@Repository
public class RiskRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public RiskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public long count(RiskQuery query, AccessDecision access) {
        Where where = where(query, access);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from() + where.sql, where.params, Long.class);
        return total == null ? 0 : total;
    }

    public List<RiskRow> list(RiskQuery query, AccessDecision access, int offset, int size, String sort, String order) {
        Where where = where(query, access);
        where.params.put("offset", offset);
        where.params.put("size", size);
        return jdbc.query(select() + names() + from() + nameJoins() + where.sql + orderBy(sort, order)
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.params, RiskRepository::risk);
    }

    /** 导出：不分页，上限由调用方先用 count 卡；次序与列表完全一致。 */
    public List<RiskRow> listForExport(RiskQuery query, AccessDecision access, int limit, String sort, String order) {
        Where where = where(query, access);
        where.params.put("size", limit);
        return jdbc.query(select() + names() + from() + nameJoins() + where.sql + orderBy(sort, order)
                + " FETCH NEXT :size ROWS ONLY", where.params, RiskRepository::risk);
    }

    /** 排序键白名单（决策 15-6）；列名只能是常量，次序键后恒附 risk_id 保证翻页稳定。 */
    private static String orderBy(String sort, String order) {
        String column = switch (sort == null ? "received_at" : sort) {
            case "occurred_at" -> "r.occurred_at";
            // 与告警同一口径的等级序号，别一张表按序号、另一张按字典序（决策 15-30）。
            case "severity" -> com.uav.lowaltitude.platform.query.SeverityOrder.rank("r.severity");
            case "state" -> "r.state_code";
            default -> "r.received_at";
        };
        String direction = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
        return " ORDER BY " + column + " " + direction + ",r.risk_id " + direction;
    }

    /** 调用者范围内实际出现过的区域（决策 15-22）；与列表同一套 where，免得筛选框里出现选了就是空的区域。 */
    public java.util.List<DistrictOptionRow> districts(AccessDecision access) {
        Where where = where(RiskQuery.empty(), access);
        return jdbc.query("SELECT DISTINCT r.district_id, d.name" + from()
                + " JOIN app_district d ON d.district_id=r.district_id" + where.sql
                + " ORDER BY d.name ASC, r.district_id ASC", where.params,
                (rs, i) -> new DistrictOptionRow(rs.getString("district_id"), rs.getString("name")));
    }

    public record DistrictOptionRow(String districtId, String name) { }

    public static final java.util.Set<String> SORT_KEYS =
            java.util.Set.of("received_at", "occurred_at", "severity", "state");

    public RiskRow find(String riskId, AccessDecision access) {
        Where where = where(RiskQuery.empty(), access);
        where.sql.append(" AND r.risk_id=:risk_id");
        where.params.put("risk_id", riskId);
        List<RiskRow> rows = jdbc.query(select() + names() + from() + nameJoins() + where.sql, where.params, RiskRepository::risk);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RiskRow lock(String riskId, AccessDecision access) {
        Where where = where(RiskQuery.empty(), access);
        where.sql.append(" AND r.risk_id=:risk_id");
        where.params.put("risk_id", riskId);
        List<RiskRow> rows = jdbc.query(select() + nullNames() + from() + where.sql + " FOR UPDATE", where.params, RiskRepository::risk);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String riskId, long expectedVersion, String nextState, OffsetDateTime at) {
        return jdbc.update("UPDATE flight_risk SET state_code=:state,updated_at=:at,version=version+1"
                + " WHERE risk_id=:id AND version=:version AND state_code='PENDING_VERIFICATION'",
                Map.of("state", nextState, "at", at, "id", riskId, "version", expectedVersion));
    }

    /**
     * 通知回执确认已驱离后把风险推进到"已通知"（决策 18-14）。返回受影响行数，0 表示没推进。
     *
     * WHERE 里钉死 `PENDING_NOTIFICATION`：这一步只从"待通知"走，重放同一笔回执只会影响 0 行，
     * 也不会把已排除或已通知的风险重新拉回来。版本照既有写法 +1，否则两个并发操作分不出先后。
     */
    public int markNotified(String riskId, OffsetDateTime at) {
        return jdbc.update("UPDATE flight_risk SET state_code='NOTIFIED',updated_at=:at,version=version+1"
                + " WHERE risk_id=:id AND state_code='PENDING_NOTIFICATION'",
                Map.of("at", at, "id", riskId));
    }

    public int markNotified(String riskId, long expectedVersion, OffsetDateTime at) {
        return jdbc.update("UPDATE flight_risk SET state_code='NOTIFIED',updated_at=:at,version=version+1"
                + " WHERE risk_id=:id AND version=:version AND state_code='PENDING_NOTIFICATION'",
                Map.of("at", at, "id", riskId, "version", expectedVersion));
    }

    public int markAcknowledged(String riskId, long expectedVersion, OffsetDateTime at) {
        return jdbc.update("UPDATE flight_risk SET state_code='ACKNOWLEDGED',updated_at=:at,version=version+1"
                + " WHERE risk_id=:id AND version=:version AND state_code IN ('PENDING_NOTIFICATION','NOTIFIED')",
                Map.of("at", at, "id", riskId, "version", expectedVersion));
    }

    public boolean notificationRecorded(String riskId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM flight_risk WHERE risk_id=:id"
                + " AND state_code IN ('NOTIFIED','ACKNOWLEDGED')", Map.of("id", riskId), Long.class);
        return total != null && total > 0;
    }

    public long currentVersion(String riskId) {
        Long version = jdbc.queryForObject("SELECT version FROM flight_risk WHERE risk_id=:id", Map.of("id", riskId), Long.class);
        return version == null ? -1 : version;
    }

    public void appendVerification(String historyId, String riskId, String conclusion, String note,
            String fromState, String toState, long expectedVersion, String verifiedBy, OffsetDateTime at) {
        jdbc.update("INSERT INTO flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at)"
                + " VALUES (:history_id,:risk_id,:version,:previous_state,:resulting_state,:conclusion,:note,:actor_id,:created_at)",
                Map.of("history_id", historyId, "risk_id", riskId, "version", expectedVersion + 1,
                        "previous_state", fromState, "resulting_state", toState, "conclusion", conclusion,
                        "note", note, "actor_id", verifiedBy, "created_at", at));
    }

    public long countVerifications(String riskId, AccessDecision access) {
        Where where = verificationWhere(riskId, access);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM flight_risk_verification v JOIN flight_risk r ON r.risk_id=v.risk_id"
                + planJoin() + where.sql, where.params, Long.class);
        return total == null ? 0 : total;
    }

    public List<VerificationRow> verifications(String riskId, AccessDecision access, int offset, int size) {
        Where where = verificationWhere(riskId, access);
        where.params.put("offset", offset);
        where.params.put("size", size);
        return jdbc.query("SELECT v.history_id,v.version,v.previous_state,v.resulting_state,v.conclusion,v.note,v.actor_id,v.created_at,au.name AS actor_name"
                + " FROM flight_risk_verification v JOIN flight_risk r ON r.risk_id=v.risk_id"
                + planJoin() + " LEFT JOIN app_user au ON au.user_id=v.actor_id" + where.sql + " ORDER BY v.version ASC,v.history_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.params, RiskRepository::verification);
    }

    public IngestionPlanRow ingestionPlan(String planId) {
        List<IngestionPlanRow> rows = jdbc.query("SELECT p.plan_id,p.route_version_id,p.owner_org_id,p.district_id"
                + " FROM flight_plan p JOIN route_version rv ON rv.route_version_id=p.route_version_id"
                + " JOIN route route_root ON route_root.route_id=rv.route_id AND route_root.owner_org_id=p.owner_org_id AND route_root.district_id=p.district_id"
                + " JOIN app_org o ON o.org_id=p.owner_org_id AND o.enabled=TRUE"
                + " JOIN app_district d ON d.district_id=p.district_id AND d.enabled=TRUE WHERE p.plan_id=:id", Map.of("id", planId),
                (rs, ignored) -> new IngestionPlanRow(rs.getString("plan_id"), rs.getString("route_version_id"),
                        rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean lockSource(String sourceId, String sourceMode) {
        List<String> rows=jdbc.queryForList("SELECT source_id FROM integration_source WHERE source_id=:id AND source_mode=:mode AND enabled=TRUE FOR UPDATE",
                Map.of("id", sourceId, "mode", sourceMode),String.class);
        return rows.size()==1;
    }

    public boolean assessmentMatches(String assessmentId, String planId, String routeVersionId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_result WHERE assessment_id=:assessment AND plan_id=:plan"
                + " AND route_version_id=:route", Map.of("assessment", assessmentId, "plan", planId, "route", routeVersionId), Long.class);
        return value != null && value == 1;
    }

    public boolean targetMatchesScope(String targetId, String orgId, String districtId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM target WHERE target_id=:target AND owner_org_id=:org AND district_id=:district",
                Map.of("target", targetId, "org", orgId, "district", districtId), Long.class);
        return value != null && value == 1;
    }

    public boolean trackMatches(String trackId, String targetId, String orgId, String districtId) {
        Map<String, Object> params = new HashMap<>();
        params.put("track", trackId); params.put("org", orgId); params.put("district", districtId);
        String targetClause = "";
        if (targetId != null) { targetClause = " AND t.target_id=:target"; params.put("target", targetId); }
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM track tr JOIN target t ON t.target_id=tr.target_id"
                + " WHERE tr.track_id=:track AND t.owner_org_id=:org AND t.district_id=:district" + targetClause, params, Long.class);
        return value != null && value == 1;
    }

    public boolean planReferenceVisible(RiskRow row) {
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan p WHERE p.plan_id=:plan AND p.route_version_id=:route"
                +" AND p.owner_org_id=:org AND p.district_id=:district", referenceParams(row), Long.class);
        return value!=null&&value==1;
    }

    public boolean routeReferenceVisible(RiskRow row) {
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM route_version rv JOIN route route_root ON route_root.route_id=rv.route_id"
                +" WHERE rv.route_version_id=:route AND route_root.owner_org_id=:org AND route_root.district_id=:district",
                referenceParams(row),Long.class);
        return value!=null&&value==1;
    }

    public boolean assessmentReferenceVisible(RiskRow row) {
        if(row.assessmentId()==null)return false;
        Map<String,Object> params=referenceParams(row);params.put("assessment",row.assessmentId());
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM assessment_result WHERE assessment_id=:assessment AND plan_id=:plan AND route_version_id=:route",params,Long.class);
        return value!=null&&value==1;
    }

    public boolean targetReferenceVisible(RiskRow row) {
        return row.targetId()!=null&&targetMatchesScope(row.targetId(),row.ownerOrgId(),row.districtId());
    }

    public boolean trackReferenceVisible(RiskRow row) {
        return row.trackId()!=null&&trackMatches(row.trackId(),row.targetId(),row.ownerOrgId(),row.districtId());
    }

    private static Map<String,Object> referenceParams(RiskRow row){Map<String,Object> params=new HashMap<>();params.put("plan",row.planId());
        params.put("route",row.routeVersionId());params.put("org",row.ownerOrgId());params.put("district",row.districtId());return params;}

    public RiskRow findBySource(String sourceId, String sourceRiskId) {
        List<RiskRow> rows = jdbc.query(select() + names() + from() + nameJoins() + " WHERE r.source_id=:source AND r.source_risk_id=:source_risk",
                Map.of("source", sourceId, "source_risk", sourceRiskId), RiskRepository::risk);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(IngestRow row) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", row.riskId); params.put("source", row.sourceId); params.put("source_risk", row.sourceRiskId);
        params.put("plan", row.planId); params.put("route", row.routeVersionId); params.put("assessment", row.assessmentId);
        params.put("target", row.targetId); params.put("track", row.trackId); params.put("type", row.riskType);
        params.put("severity", row.severity); params.put("reason", row.reasonCode); params.put("text", row.reasonText);
        params.put("occurred", row.occurredAt); params.put("received", row.receivedAt); params.put("altitude", row.altitude);
        params.put("datum", row.altitudeDatum); params.put("mode", row.sourceMode); params.put("org", row.ownerOrgId);
        params.put("district", row.districtId); params.put("risk_no", row.riskNo);
        jdbc.update("INSERT INTO flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,assessment_id,target_id,track_id,"
                + "risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,observed_altitude_m,"
                + "observed_altitude_datum,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version,risk_no)"
                + " VALUES (:id,:source,:source_risk,:plan,:route,:assessment,:target,:track,:type,:severity,'PENDING_VERIFICATION',"
                + ":reason,:text,:occurred,:received,:altitude,:datum,'UNKNOWN',:mode,:org,:district,:received,:received,0,:risk_no)", params);
    }

    private Where where(RiskQuery query, AccessDecision access) {
        Where where = scope(access);
        add(where, "r.state_code", "state", query.state);
        add(where, "r.severity", "severity", query.severity);
        add(where, "r.plan_id", "plan", query.planId);
        add(where, "r.owner_org_id", "owner", query.ownerOrgId);
        add(where, "r.district_id", "district", query.districtId);
        add(where, "r.source_mode", "mode", query.sourceMode);
        add(where, "r.risk_type", "risk_type", query.riskType);
        if (query.targetType != null) {
            // 用 EXISTS 而不是引用 nameJoins 的 tg 别名：导出与列表都要能用，
            // 而 EXISTS 不依赖任何联表，改联表结构时也不会跟着坏。
            where.sql.append(" AND EXISTS (SELECT 1 FROM target ft WHERE ft.target_id=r.target_id"
                    + " AND ft.object_type_code=:target_type)");
            where.params.put("target_type", query.targetType);
        }
        if (query.objectSubtype != null) {
            // 细类过滤走空间事实表：没有空间事实的风险本来就没有细类，不该因为过滤而"看起来存在"。
            where.sql.append(" AND EXISTS (SELECT 1 FROM space_risk_fact sf WHERE sf.risk_id=r.risk_id AND sf.subtype_code=:object_subtype)");
            where.params.put("object_subtype", query.objectSubtype);
        }
        if (query.occurredFrom != null) {
            where.sql.append(" AND r.occurred_at IS NOT NULL AND r.occurred_at>=:occurred_from AND r.occurred_at<:occurred_to");
            where.params.put("occurred_from", query.occurredFrom); where.params.put("occurred_to", query.occurredTo);
        }
        return where;
    }

    private Where verificationWhere(String riskId, AccessDecision access) {
        Where where = scope(access);
        where.sql.append(" AND r.risk_id=:risk_id"); where.params.put("risk_id", riskId);
        return where;
    }

    private static Where scope(AccessDecision access) {
        Where where = new Where();
        // ALL 仅免授权元组，不免基础目录有效性；停用组织/区域下的风险对任何读取范围都不可见。
        where.sql.append(" WHERE r.owner_org_id IS NOT NULL AND r.district_id IS NOT NULL"
                +" AND EXISTS (SELECT 1 FROM app_org risk_org JOIN app_district risk_district ON risk_district.district_id=r.district_id"
                +" WHERE risk_org.org_id=r.owner_org_id AND risk_org.enabled=TRUE AND risk_district.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 同一授权行必须同时命中组织和区域，禁止 (org-a,dist-a)+(org-b,dist-b) 拼成交叉范围。
            where.sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs JOIN app_org o ON o.org_id=gs.org_id AND o.enabled=TRUE"
                    + " JOIN app_district d ON d.district_id=gs.district_id AND d.enabled=TRUE WHERE gs.user_id=:scope_user"
                    + " AND gs.org_id=r.owner_org_id AND gs.district_id=r.district_id)");
            where.params.put("scope_user", access.userId());
        }
        return where;
    }

    private static void add(Where where, String column, String name, String value) {
        if (value != null) { where.sql.append(" AND ").append(column).append("=:").append(name); where.params.put(name, value); }
    }
    private static String select() { return "SELECT r.risk_id,r.source_risk_id,r.risk_no,r.plan_id,r.route_version_id,r.assessment_id,r.target_id,r.track_id,"
            + "r.risk_type,r.severity,r.state_code,r.reason_code,r.reason_text,r.occurred_at,r.received_at,r.observed_altitude_m,"
            + "r.observed_altitude_datum,r.height_relation,s.source_code,r.source_mode,r.owner_org_id,r.district_id,r.created_at,r.updated_at,r.version,p.plan_no"; }
    /** 名称列只用于展示；FOR UPDATE 不能落在外连接可空侧，锁定查询改为同名空列。 */
    private static String names() { return ",s.name AS source_name,org_ref.name AS owner_org_name,dist_ref.name AS district_name,tg.target_no"; }
    private static String nullNames() { return ",CAST(NULL AS VARCHAR(128)) AS source_name,CAST(NULL AS VARCHAR(128)) AS owner_org_name,CAST(NULL AS VARCHAR(128)) AS district_name,CAST(NULL AS VARCHAR(64)) AS target_no"; }
    private static String nameJoins() { return " LEFT JOIN app_org org_ref ON org_ref.org_id=r.owner_org_id LEFT JOIN app_district dist_ref ON dist_ref.district_id=r.district_id LEFT JOIN target tg ON tg.target_id=r.target_id"; }
    private static String from() { return " FROM flight_risk r JOIN integration_source s ON s.source_id=r.source_id AND s.source_mode=r.source_mode" + planJoin(); }
    private static String planJoin() { return " JOIN flight_plan p ON p.plan_id=r.plan_id AND p.route_version_id=r.route_version_id"
            + " AND p.owner_org_id=r.owner_org_id AND p.district_id=r.district_id"; }
    private static RiskRow risk(ResultSet rs, int ignored) throws SQLException { return new RiskRow(rs.getString("risk_id"), rs.getString("source_risk_id"),
            rs.getString("plan_id"), rs.getString("route_version_id"), rs.getString("assessment_id"), rs.getString("target_id"), rs.getString("track_id"),
            rs.getString("risk_type"), rs.getString("severity"), rs.getString("state_code"), rs.getString("reason_code"), rs.getString("reason_text"),
            time(rs,"occurred_at"), time(rs,"received_at"), rs.getBigDecimal("observed_altitude_m"), rs.getString("observed_altitude_datum"),
            rs.getString("height_relation"), rs.getString("source_code"), rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"),
            time(rs,"created_at"), time(rs,"updated_at"), rs.getLong("version"),
            rs.getString("source_name"), rs.getString("owner_org_name"), rs.getString("district_name"), rs.getString("plan_no"), rs.getString("target_no"), rs.getString("risk_no")); }
    private static VerificationRow verification(ResultSet rs, int ignored) throws SQLException { return new VerificationRow(rs.getString("history_id"),
            rs.getLong("version"),rs.getString("previous_state"),rs.getString("resulting_state"),rs.getString("conclusion"),
            rs.getString("note"),rs.getString("actor_id"),time(rs,"created_at"),rs.getString("actor_name")); }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException { Object value=rs.getObject(column); if(value==null)return null;
        if(value instanceof OffsetDateTime t)return t; if(value instanceof ZonedDateTime t)return t.toOffsetDateTime(); if(value instanceof Timestamp t)return t.toInstant().atOffset(ZoneOffset.UTC);
        if(value instanceof LocalDateTime t)return t.atOffset(ZoneOffset.UTC); return OffsetDateTime.parse(value.toString()); }

    private static final class Where { final StringBuilder sql = new StringBuilder(); final Map<String,Object> params = new HashMap<>(); }
    public record RiskQuery(String state, String severity, String planId, OffsetDateTime occurredFrom, OffsetDateTime occurredTo,
            String ownerOrgId, String districtId, String sourceMode, String riskType, String objectSubtype,
            /* 阶段 15（决策 15-7）：按关联目标的类别筛。 */
            String targetType) {
        public static RiskQuery empty(){return new RiskQuery(null,null,null,null,null,null,null,null,null,null,null);} }
    public record RiskRow(String riskId,String sourceRiskId,String planId,String routeVersionId,String assessmentId,String targetId,String trackId,
            String riskType,String severity,String state,String reasonCode,String reasonText,OffsetDateTime occurredAt,OffsetDateTime receivedAt,
            BigDecimal observedAltitudeM,String observedAltitudeDatum,String heightRelation,String sourceCode,String sourceMode,String ownerOrgId,
            String districtId,OffsetDateTime createdAt,OffsetDateTime updatedAt,long version,
            String sourceName,String ownerOrgName,String districtName,String planNo,String targetNo,String riskNo) {
        /** 页面上的风险编号：平台编号优先，没有就用来源编号。 */
        public String displayNo() { return riskNo != null ? riskNo : sourceRiskId; }
    }
    public record VerificationRow(String historyId,long version,String previousState,String resultingState,String conclusion,String note,
            String actorId,OffsetDateTime createdAt,String actorName) { }
    public record IngestionPlanRow(String planId,String routeVersionId,String ownerOrgId,String districtId) { }
    public record IngestRow(String riskId,String sourceId,String sourceRiskId,String planId,String routeVersionId,String assessmentId,String targetId,
            String trackId,String riskType,String severity,String reasonCode,String reasonText,OffsetDateTime occurredAt,OffsetDateTime receivedAt,
            BigDecimal altitude,String altitudeDatum,String sourceMode,String ownerOrgId,String districtId,String riskNo) { }
}
