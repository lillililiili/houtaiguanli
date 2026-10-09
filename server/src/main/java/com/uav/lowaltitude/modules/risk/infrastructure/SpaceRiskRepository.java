package com.uav.lowaltitude.modules.risk.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/**
 * 空间安全风险的读写 SQL：细类字典、空间事实、评估运行记录与汇总。
 * 汇总与列表共用同一范围谓词（与 {@link RiskRepository#scope} 同形）：越权数据不能通过计数泄露。
 */
@Repository
public class SpaceRiskRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public SpaceRiskRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = isPostgres(dataSource);
    }

    // ---- 细类字典 ----

    public List<SubtypeRow> listSubtypes() {
        return jdbc.query("SELECT subtype_code,display_name,aliases,enabled FROM space_object_subtype ORDER BY sort_order ASC, subtype_code ASC",
                Map.of(), (rs, i) -> new SubtypeRow(rs.getString("subtype_code"), rs.getString("display_name"),
                        jsonText(rs.getObject("aliases")), rs.getBoolean("enabled")));
    }

    public String targetSourceMode(String targetId) {
        return jdbc.queryForObject("SELECT source_mode FROM target WHERE target_id=:id", Map.of("id", targetId), String.class);
    }

    // ---- 空间事实 ----

    public SpaceFactRow findFact(String riskId) {
        List<SpaceFactRow> rows = jdbc.query(factSelect() + " WHERE f.risk_id=:id", Map.of("id", riskId), SpaceRiskRepository::fact);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 批量取事实，避免列表逐行查询；返回 risk_id → 事实。 */
    public Map<String, SpaceFactRow> facts(List<String> riskIds) {
        Map<String, SpaceFactRow> byRisk = new LinkedHashMap<>();
        if (riskIds == null || riskIds.isEmpty()) return byRisk;
        for (SpaceFactRow row : jdbc.query(factSelect() + " WHERE f.risk_id IN (:ids)", Map.of("ids", riskIds), SpaceRiskRepository::fact)) {
            byRisk.put(row.riskId(), row);
        }
        return byRisk;
    }

    public void insertFact(SpaceFactRow row) {
        Map<String, Object> p = new HashMap<>();
        p.put("risk", row.riskId()); p.put("subtype", row.subtypeCode()); p.put("rule_version", row.ruleVersionId());
        p.put("rule_set_version", row.ruleSetVersionId()); p.put("distance", row.distanceToRouteM()); p.put("relation", row.corridorRelation());
        p.put("band", row.altitudeBand()); p.put("datum", row.altitudeDatum()); p.put("count", row.objectCount());
        p.put("trend", row.trend()); p.put("from", row.windowFrom()); p.put("to", row.windowTo()); p.put("created", row.createdAt());
        p.put("unknown", row.unknownReasonsJson() == null ? "[]" : row.unknownReasonsJson());
        // 位置快照：经纬度必须成对才写点，缺一个就整体留空——半个坐标不是坐标。
        p.put("geom", row.longitude() == null || row.latitude() == null ? null
                : "SRID=4326;POINT (" + row.longitude() + " " + row.latitude() + ")");
        p.put("alt_raw", row.targetAltitudeRaw());
        jdbc.update("INSERT INTO space_risk_fact (risk_id,subtype_code,rule_version_id,rule_set_version_id,distance_to_route_m,corridor_relation,"
                + "altitude_band,altitude_datum,object_count,trend,unknown_reasons,target_location,target_altitude_raw,window_from,window_to,created_at)"
                + " VALUES (:risk,:subtype,:rule_version,:rule_set_version,:distance,:relation,:band,:datum,:count,:trend,CAST(:unknown AS JSON),"
                + "CAST(:geom AS GEOMETRY),:alt_raw,:from,:to,:created)", p);
    }

    public boolean factExists(String riskId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM space_risk_fact WHERE risk_id=:id", Map.of("id", riskId), Long.class);
        return count != null && count > 0;
    }

    /**
     * 同一计划、同一（存活）目标上还没有解除依据的 C04 风险；没有返回 null。
     * 异物持续停在航线上时每轮评估都会命中，它就是"这一次"的风险，不能每轮再造一条；
     * 有了解除依据（已离开）之后再进入，或换了新目标（新一批），才算新的一次。人工排除的风险同样算已处理，不再重复生成。
     */
    public String openC04Risk(String planId, String targetId) {
        return openSpaceRisk("C04", planId, targetId);
    }

    /**
     * 同 {@link #openC04Risk}，按规则代码取：C05（机场区域异物）也是"同一任务、同一目标只记一次"（2026-10-08 新-27）。
     * C05 目前没有自动解除依据，所以一个异物在一次任务里最多一条机场区域风险。
     */
    public String openSpaceRisk(String ruleCode, String planId, String targetId) {
        List<String> rows = jdbc.queryForList("SELECT r.risk_id FROM flight_risk r"
                + " JOIN space_risk_fact f ON f.risk_id=r.risk_id"
                + " JOIN rule_version v ON v.rule_version_id=f.rule_version_id AND v.rule_code=:rule"
                + " LEFT JOIN target_current_alias alias ON alias.historical_target_id=r.target_id"
                + " WHERE r.risk_type='SPACE_OBJECT' AND r.plan_id=:plan AND COALESCE(alias.current_target_id,r.target_id)=:target"
                + " AND NOT EXISTS (SELECT 1 FROM risk_clearance_evidence e WHERE e.risk_id=r.risk_id)"
                + " ORDER BY r.received_at DESC, r.risk_id ASC FETCH FIRST 1 ROWS ONLY",
                Map.of("plan", planId, "target", targetId, "rule", ruleCode), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 先锁既有引擎来源再检查活动风险，人工评估和定时评估不能跨窗口同时建两条。 */
    public void lockRiskSource(String sourceId, String mode) {
        List<String> rows = jdbc.queryForList("SELECT source_id FROM integration_source WHERE source_id=:id AND source_mode=:mode AND enabled=TRUE FOR UPDATE",
                Map.of("id", sourceId, "mode", mode), String.class);
        if (rows.isEmpty()) throw new IllegalStateException("空间风险来源未启用");
    }

    public String memberVersionId(String setVersionId, String ruleCode) {
        List<String> rows = jdbc.queryForList("SELECT v.rule_version_id FROM rule_set_member m JOIN rule_version v ON v.rule_version_id=m.rule_version_id"
                + " WHERE m.rule_set_version_id=:set AND v.rule_code=:rule AND m.enabled=TRUE ORDER BY m.priority,v.rule_version_id",
                Map.of("set", setVersionId, "rule", ruleCode), String.class);
        if (rows.size() != 1) throw new IllegalStateException("空间风险规则成员缺失或冲突: " + ruleCode);
        return rows.get(0);
    }

    /** 风险的空间事实挂在哪条规则下（C04 / C05）；没有空间事实（天气、作业风险）返回 null。 */
    public String ruleCodeOf(String riskId) {
        List<String> rows = jdbc.queryForList("SELECT v.rule_code FROM space_risk_fact f"
                + " JOIN rule_version v ON v.rule_version_id=f.rule_version_id WHERE f.risk_id=:risk", Map.of("risk", riskId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- 评估历史（P03） ----

    private static final String SEGMENT_COLUMNS = "segment_id,risk_id,segment_no,first_evaluated_at,last_evaluated_at,evaluation_count,"
            + "first_observed_at,last_observed_at,distance_band_m,min_distance_m,max_distance_m,corridor_relation,altitude_band,"
            + "risk_present,severity,rule_set_version_id,from_detection";

    /** 这条风险最近的一段评估历史；还没有返回 null。 */
    public EvaluationSegmentRow latestSegment(String riskId) {
        List<EvaluationSegmentRow> rows = jdbc.query("SELECT " + SEGMENT_COLUMNS + " FROM space_risk_evaluation_segment WHERE risk_id=:risk"
                + " ORDER BY segment_no DESC FETCH FIRST 1 ROWS ONLY", Map.of("risk", riskId), SpaceRiskRepository::segment);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新开一段；(risk_id, segment_no) 唯一，并发评估抢同一个段号时后到的一方失败，不会出现两段同号。 */
    public void insertSegment(EvaluationSegmentRow row, OffsetDateTime now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.segmentId()); p.put("risk", row.riskId()); p.put("no", row.segmentNo());
        p.put("first_at", row.firstEvaluatedAt()); p.put("last_at", row.lastEvaluatedAt()); p.put("count", row.evaluationCount());
        p.put("first_observed", row.firstObservedAt()); p.put("last_observed", row.lastObservedAt());
        p.put("band", row.distanceBandM()); p.put("min", row.minDistanceM()); p.put("max", row.maxDistanceM());
        p.put("relation", row.corridorRelation()); p.put("altitude", row.altitudeBand()); p.put("present", row.riskPresent());
        p.put("severity", row.severity()); p.put("rule_set_version", row.ruleSetVersionId()); p.put("detection", row.fromDetection());
        p.put("now", now);
        jdbc.update("INSERT INTO space_risk_evaluation_segment (" + SEGMENT_COLUMNS + ",created_at,updated_at)"
                + " VALUES (:id,:risk,:no,:first_at,:last_at,:count,:first_observed,:last_observed,:band,:min,:max,:relation,:altitude,"
                + ":present,:severity,:rule_set_version,:detection,:now,:now)", p);
    }

    /**
     * 把一次评估并进已有的一段：次数加一、最近评估与观测时刻后移、距离范围放宽。
     * 按读到的次数做条件更新，返回受影响行数：0 表示这段刚被另一轮评估改过，调用方不得当成已记上。
     */
    public int extendSegment(EvaluationSegmentRow last, OffsetDateTime evaluatedAt, OffsetDateTime observedAt, BigDecimal distanceM,
            OffsetDateTime now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", last.segmentId()); p.put("expected", last.evaluationCount()); p.put("last_at", evaluatedAt);
        p.put("last_observed", observedAt == null ? last.lastObservedAt() : observedAt);
        p.put("min", distanceM == null || last.minDistanceM() == null ? last.minDistanceM() : distanceM.min(last.minDistanceM()));
        p.put("max", distanceM == null || last.maxDistanceM() == null ? last.maxDistanceM() : distanceM.max(last.maxDistanceM()));
        p.put("now", now);
        return jdbc.update("UPDATE space_risk_evaluation_segment SET evaluation_count=evaluation_count+1,last_evaluated_at=:last_at,"
                + "last_observed_at=:last_observed,min_distance_m=:min,max_distance_m=:max,updated_at=:now"
                + " WHERE segment_id=:id AND evaluation_count=:expected", p);
    }

    /** 评估历史的合计：段数、评估次数、最早与最近的评估时刻、是否从发现那次评估开始记。 */
    public EvaluationSummaryRow evaluationSummary(String riskId) {
        return jdbc.queryForObject("SELECT COUNT(*) AS segments,COALESCE(SUM(evaluation_count),0) AS evaluations,"
                + "MIN(first_evaluated_at) AS first_at,MAX(last_evaluated_at) AS last_at,"
                + "COALESCE(MAX(CASE WHEN from_detection THEN 1 ELSE 0 END),0) AS detection"
                + " FROM space_risk_evaluation_segment WHERE risk_id=:risk", Map.of("risk", riskId),
                (rs, i) -> new EvaluationSummaryRow(rs.getLong("segments"), rs.getLong("evaluations"), time(rs, "first_at"),
                        time(rs, "last_at"), rs.getInt("detection") == 1));
    }

    /** 按时间先后分页取段（段号就是先后次序）。 */
    public List<EvaluationSegmentRow> segments(String riskId, int offset, int size) {
        Map<String, Object> p = new HashMap<>();
        p.put("risk", riskId); p.put("offset", offset); p.put("size", size);
        return jdbc.query("SELECT " + SEGMENT_COLUMNS + " FROM space_risk_evaluation_segment WHERE risk_id=:risk"
                + " ORDER BY segment_no ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", p, SpaceRiskRepository::segment);
    }

    /** 有没有启用的机场：没有就不必每分钟跑一轮 C05、留一条空的运行记录。 */
    public boolean anyEnabledAirport() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM airport WHERE enabled = TRUE)", Map.of(), Boolean.class));
    }

    // ---- 评估运行 ----

    public void insertRun(RunRow row) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.runId()); p.put("code", row.ruleCode()); p.put("trigger", row.triggerKind()); p.put("from", row.windowFrom());
        p.put("to", row.windowTo()); p.put("status", row.status()); p.put("seen", row.targetsSeen()); p.put("created", row.risksCreated());
        p.put("dedup", row.risksDeduplicated()); p.put("message", row.message()); p.put("actor", row.actorId());
        p.put("started", row.startedAt()); p.put("finished", row.finishedAt());
        jdbc.update("INSERT INTO rule_evaluation_run (run_id,rule_code,trigger_kind,window_from,window_to,status,targets_seen,risks_created,"
                + "risks_deduplicated,message,actor_id,started_at,finished_at)"
                + " VALUES (:id,:code,:trigger,:from,:to,:status,:seen,:created,:dedup,:message,:actor,:started,:finished)", p);
    }

    /** 只允许把 RUNNING 收尾一次；受影响行数不为 1 说明并发收尾，调用方不得把结果当成功。 */
    public int finishRun(String runId, String status, int targetsSeen, int risksCreated, int risksDeduplicated, String message, OffsetDateTime finishedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", runId); p.put("status", status); p.put("seen", targetsSeen); p.put("created", risksCreated);
        p.put("dedup", risksDeduplicated); p.put("message", message); p.put("finished", finishedAt);
        return jdbc.update("UPDATE rule_evaluation_run SET status=:status,targets_seen=:seen,risks_created=:created,risks_deduplicated=:dedup,"
                + "message=:message,finished_at=:finished WHERE run_id=:id AND status='RUNNING'", p);
    }

    public long countRuns(String ruleCode) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM rule_evaluation_run WHERE 1=1");
        if (ruleCode != null) { sql.append(" AND rule_code=:code"); p.put("code", ruleCode); }
        Long count = jdbc.queryForObject(sql.toString(), p, Long.class);
        return count == null ? 0 : count;
    }

    public List<RunRow> listRuns(String ruleCode, int offset, int size) {
        Map<String, Object> p = new HashMap<>();
        p.put("offset", offset); p.put("size", size);
        StringBuilder sql = new StringBuilder("SELECT run_id,rule_code,trigger_kind,window_from,window_to,status,targets_seen,risks_created,"
                + "risks_deduplicated,message,actor_id,started_at,finished_at FROM rule_evaluation_run WHERE 1=1");
        if (ruleCode != null) { sql.append(" AND rule_code=:code"); p.put("code", ruleCode); }
        sql.append(" ORDER BY started_at DESC, run_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY");
        return jdbc.query(sql.toString(), p, SpaceRiskRepository::run);
    }

    public RunRow findRun(String runId) {
        List<RunRow> rows = jdbc.query("SELECT run_id,rule_code,trigger_kind,window_from,window_to,status,targets_seen,risks_created,"
                + "risks_deduplicated,message,actor_id,started_at,finished_at FROM rule_evaluation_run WHERE run_id=:id", Map.of("id", runId), SpaceRiskRepository::run);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- 汇总 ----

    /** 按维度计数；维度列由调用方从固定白名单给出，绝不接受外部输入拼接。 */
    public List<CountRow> countBy(String dimension, SummaryQuery query, AccessDecision access) {
        Where where = summaryWhere(query, access);
        String column = switch (dimension) {
            case "subtype" -> "f.subtype_code";
            case "severity" -> "r.severity";
            case "state" -> "r.state_code";
            case "altitude_band" -> "f.altitude_band";
            default -> throw new IllegalArgumentException("unsupported summary dimension: " + dimension);
        };
        return jdbc.query("SELECT " + column + " AS bucket, COUNT(*) AS total" + SUMMARY_FROM + where.sql
                + " GROUP BY " + column + " ORDER BY " + column + " ASC", where.params,
                (rs, i) -> new CountRow(rs.getString("bucket"), rs.getLong("total")));
    }

    /** 涉及航线数：同一条航线上的多起风险只算一次。 */
    public long routesInvolved(SummaryQuery query, AccessDecision access) {
        Where where = summaryWhere(query, access);
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT r.route_version_id)" + SUMMARY_FROM + where.sql, where.params, Long.class);
        return count == null ? 0 : count;
    }

    public long countRisks(SummaryQuery query, AccessDecision access) {
        Where where = summaryWhere(query, access);
        Long count = jdbc.queryForObject("SELECT COUNT(*)" + SUMMARY_FROM + where.sql, where.params, Long.class);
        return count == null ? 0 : count;
    }

    /** 当前生效的空间风险规则集版本（页面徽标显示"第 N 版 · 演示参数"）。 */
    public RuleVersionRow activeRuleSetVersion(String ruleSetCode) {
        List<RuleVersionRow> rows = jdbc.query("SELECT s.rule_set_code, v.rule_set_version_id, v.version_no, v.param_status"
                + " FROM rule_set s JOIN rule_set_version v ON v.rule_set_version_id=s.active_version_id WHERE s.rule_set_code=:code",
                Map.of("code", ruleSetCode), (rs, i) -> new RuleVersionRow(rs.getString("rule_set_code"), rs.getString("rule_set_version_id"),
                        rs.getInt("version_no"), rs.getString("param_status")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final String SUMMARY_FROM = " FROM space_risk_fact f JOIN flight_risk r ON r.risk_id=f.risk_id";
    /**
     * 几何列的读法按后端分支（决策 8-23）：PostgreSQL 上 CAST(geometry AS VARCHAR) 回的是 EWKB 十六进制，
     * 解析不出坐标，页面会静默丢掉标记点；因此 PG 直接用 ST_X/ST_Y，H2 才走 EWKT 文本解析。
     * 写入两端一致（CAST(? AS GEOMETRY) + EWKT），不需要分支。
     */
    private String factSelect() {
        String location = postgis
                ? "CASE WHEN f.target_location IS NOT NULL AND ST_SRID(f.target_location)=4326 THEN ST_X(f.target_location) END AS longitude,"
                        + "CASE WHEN f.target_location IS NOT NULL AND ST_SRID(f.target_location)=4326 THEN ST_Y(f.target_location) END AS latitude,"
                        + "CAST(NULL AS VARCHAR) AS location_text"
                : "CAST(NULL AS NUMERIC) AS longitude,CAST(NULL AS NUMERIC) AS latitude,CAST(f.target_location AS VARCHAR) AS location_text";
        return "SELECT f.risk_id,f.subtype_code,t.display_name AS subtype_name,f.rule_version_id,f.rule_set_version_id,"
                + "v.version_no AS rule_set_version_no,f.distance_to_route_m,f.corridor_relation,f.altitude_band,f.altitude_datum,f.object_count,f.trend,"
                + "f.unknown_reasons,f.target_altitude_raw," + location + ","
                + "f.window_from,f.window_to,f.created_at FROM space_risk_fact f"
                + " JOIN space_object_subtype t ON t.subtype_code=f.subtype_code"
                + " JOIN rule_set_version v ON v.rule_set_version_id=f.rule_set_version_id";
    }

    private static Where summaryWhere(SummaryQuery query, AccessDecision access) {
        Where where = new Where();
        // 与风险列表同一范围谓词：归属目录必须存在且启用，ASSIGNED 必须命中同一条授权元组。
        where.sql.append(" WHERE r.owner_org_id IS NOT NULL AND r.district_id IS NOT NULL")
                .append(" AND EXISTS (SELECT 1 FROM app_org o JOIN app_district d ON d.district_id=r.district_id")
                .append(" WHERE o.org_id=r.owner_org_id AND o.enabled=TRUE AND d.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            where.sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs JOIN app_org so ON so.org_id=gs.org_id AND so.enabled=TRUE")
                    .append(" JOIN app_district sd ON sd.district_id=gs.district_id AND sd.enabled=TRUE WHERE gs.user_id=:scope_user")
                    .append(" AND gs.org_id=r.owner_org_id AND gs.district_id=r.district_id)");
            where.params.put("scope_user", access.userId());
        } else if (access.scopeMode() != ScopeMode.ALL) {
            where.sql.append(" AND 1=0");
        }
        if (query.excludeDemoSamples()) where.sql.append(RiskRepository.EXCLUDE_DEMO_SAMPLES_SQL);
        if (query.from() != null) {
            where.sql.append(" AND r.received_at>=:from AND r.received_at<:to");
            where.params.put("from", query.from()); where.params.put("to", query.to());
        }
        if (query.ownerOrgId() != null) { where.sql.append(" AND r.owner_org_id=:owner"); where.params.put("owner", query.ownerOrgId()); }
        if (query.districtId() != null) { where.sql.append(" AND r.district_id=:district"); where.params.put("district", query.districtId()); }
        return where;
    }

    private static SpaceFactRow fact(ResultSet rs, int ignored) throws SQLException {
        Integer count = (Integer) rs.getObject("object_count");
        BigDecimal[] point = coordinates(rs.getBigDecimal("longitude"), rs.getBigDecimal("latitude"), rs.getString("location_text"));
        return new SpaceFactRow(rs.getString("risk_id"), rs.getString("subtype_code"), rs.getString("subtype_name"), rs.getString("rule_version_id"),
                rs.getString("rule_set_version_id"), rs.getInt("rule_set_version_no"), rs.getBigDecimal("distance_to_route_m"),
                rs.getString("corridor_relation"), rs.getString("altitude_band"), rs.getString("altitude_datum"), count, rs.getString("trend"),
                jsonText(rs.getObject("unknown_reasons")), point[0], point[1], rs.getBigDecimal("target_altitude_raw"),
                time(rs, "window_from"), time(rs, "window_to"), time(rs, "created_at"));
    }

    /** 非 4326 或无法解析的几何一律当作没有坐标：页面宁可不画点，也不能把风险标在错误位置。 */
    private static BigDecimal[] coordinates(BigDecimal longitude, BigDecimal latitude, String text) {
        if (longitude != null && latitude != null) return new BigDecimal[] { longitude, latitude };
        if (text == null) return new BigDecimal[] { null, null };
        int point = text.toUpperCase(java.util.Locale.ROOT).indexOf("POINT");
        int open = text.indexOf('(', point), close = text.indexOf(')', open);
        if (point < 0 || open < 0 || close < 0 || !text.substring(0, point).contains("4326")) return new BigDecimal[] { null, null };
        String[] parts = text.substring(open + 1, close).trim().split("\\s+");
        if (parts.length != 2) return new BigDecimal[] { null, null };
        try { return new BigDecimal[] { new BigDecimal(parts[0]), new BigDecimal(parts[1]) }; }
        catch (NumberFormatException ex) { return new BigDecimal[] { null, null }; }
    }

    private static EvaluationSegmentRow segment(ResultSet rs, int ignored) throws SQLException {
        return new EvaluationSegmentRow(rs.getString("segment_id"), rs.getString("risk_id"), rs.getInt("segment_no"),
                time(rs, "first_evaluated_at"), time(rs, "last_evaluated_at"), rs.getInt("evaluation_count"),
                time(rs, "first_observed_at"), time(rs, "last_observed_at"), rs.getObject("distance_band_m", Integer.class),
                rs.getBigDecimal("min_distance_m"), rs.getBigDecimal("max_distance_m"), rs.getString("corridor_relation"),
                rs.getString("altitude_band"), rs.getBoolean("risk_present"), rs.getString("severity"), rs.getString("rule_set_version_id"),
                rs.getBoolean("from_detection"));
    }

    private static RunRow run(ResultSet rs, int ignored) throws SQLException {
        return new RunRow(rs.getString("run_id"), rs.getString("rule_code"), rs.getString("trigger_kind"), time(rs, "window_from"),
                time(rs, "window_to"), rs.getString("status"), rs.getInt("targets_seen"), rs.getInt("risks_created"),
                rs.getInt("risks_deduplicated"), rs.getString("message"), rs.getString("actor_id"), time(rs, "started_at"), time(rs, "finished_at"));
    }

    public static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime t) return t;
        if (value instanceof java.time.ZonedDateTime t) return t.toOffsetDateTime();
        if (value instanceof java.sql.Timestamp t) return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (value instanceof java.time.LocalDateTime t) return t.atOffset(java.time.ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    /** JSON 列在 H2 上回读为 byte[]，PostgreSQL 为文本；统一成文本交由应用层解析。 */
    public static String jsonText(Object stored) {
        if (stored == null) return null;
        if (stored instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return String.valueOf(stored);
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }

    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final Map<String, Object> params = new HashMap<>();
    }

    public record SubtypeRow(String subtypeCode, String displayName, String aliasesJson, boolean enabled) { }
    public record SpaceFactRow(String riskId, String subtypeCode, String subtypeName, String ruleVersionId, String ruleSetVersionId,
            int ruleSetVersionNo, BigDecimal distanceToRouteM, String corridorRelation, String altitudeBand, String altitudeDatum,
            Integer objectCount, String trend, String unknownReasonsJson, BigDecimal longitude, BigDecimal latitude,
            BigDecimal targetAltitudeRaw, OffsetDateTime windowFrom, OffsetDateTime windowTo, OffsetDateTime createdAt) { }
    public record RunRow(String runId, String ruleCode, String triggerKind, OffsetDateTime windowFrom, OffsetDateTime windowTo, String status,
            int targetsSeen, int risksCreated, int risksDeduplicated, String message, String actorId, OffsetDateTime startedAt, OffsetDateTime finishedAt) { }
    /** 评估历史的一段（P03）：distanceBandM 是距离档下沿，没有距离时三项距离都为 null；不构成风险时 severity 为 null。 */
    public record EvaluationSegmentRow(String segmentId, String riskId, int segmentNo, OffsetDateTime firstEvaluatedAt,
            OffsetDateTime lastEvaluatedAt, int evaluationCount, OffsetDateTime firstObservedAt, OffsetDateTime lastObservedAt,
            Integer distanceBandM, BigDecimal minDistanceM, BigDecimal maxDistanceM, String corridorRelation, String altitudeBand,
            boolean riskPresent, String severity, String ruleSetVersionId, boolean fromDetection) { }
    public record EvaluationSummaryRow(long segments, long evaluations, OffsetDateTime firstEvaluatedAt, OffsetDateTime lastEvaluatedAt,
            boolean fromDetection) { }
    public record CountRow(String bucket, long total) { }
    public record SummaryQuery(OffsetDateTime from, OffsetDateTime to, String ownerOrgId, String districtId, boolean excludeDemoSamples) {
        public SummaryQuery(OffsetDateTime from, OffsetDateTime to, String ownerOrgId, String districtId) {
            this(from, to, ownerOrgId, districtId, false);
        }
    }
    public record RuleVersionRow(String ruleSetCode, String ruleSetVersionId, int versionNo, String paramStatus) { }
}
