package com.uav.lowaltitude.modules.assessment.engine;

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
import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;

/**
 * 引擎持久化：规则集/版本/成员读取、研判主体输入收集、rule_run / rule_evaluation / assessment_result 写入、租约。
 * 空间关系不在这里（见 {@link PostgisSpatialFactAdapter}）；这里的 SQL 必须同时能在 H2 与 PostgreSQL 上执行。
 */
@Repository
public class RuleEngineRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgis;

    public RuleEngineRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgis = databaseIsPostgres(dataSource);
    }

    // ---- 规则集与版本 ----

    public RuleSetRow findRuleSetByCode(String code) {
        List<RuleSetRow> rows = jdbc.query(ruleSetSelect() + " WHERE rule_set_code=:code", Map.of("code", code), RuleEngineRepository::ruleSet);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RuleSetRow lockRuleSetByCode(String code) {
        List<RuleSetRow> rows = jdbc.query(ruleSetSelect() + " WHERE rule_set_code=:code FOR UPDATE", Map.of("code", code), RuleEngineRepository::ruleSet);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 有生效或影子版本、且是"合法性规则集"（成员里含 C03 四态判定）的规则集：Worker 每个 tick 只对这些集合起运行。
     * 阶段 9 起 rule_set 表里还有空间风险规则集（SPACE-RISK-DEMO，成员只有 C04/C05），它由 SpaceRiskEvaluationJob 自己消费；
     * 合法性引擎若把它当作规则集运行，会因缺 C03.fresh_seconds 等参数而整轮失败（决策 9-28）。
     */
    public List<RuleSetRow> ruleSetsWithVersions() {
        return jdbc.query(ruleSetSelect() + " s WHERE (s.active_version_id IS NOT NULL OR s.shadow_version_id IS NOT NULL)"
                + " AND EXISTS (SELECT 1 FROM rule_set_member m JOIN rule_version rv ON rv.rule_version_id=m.rule_version_id"
                + " WHERE m.rule_set_version_id IN (s.active_version_id, s.shadow_version_id) AND rv.rule_code='C03')"
                + " ORDER BY s.rule_set_code", Map.of(), RuleEngineRepository::ruleSet);
    }

    public VersionRow findVersion(String versionId) {
        List<VersionRow> rows = jdbc.query("SELECT rule_set_version_id,rule_set_id,version_no,status_code,param_status,source_mode FROM rule_set_version WHERE rule_set_version_id=:id",
                Map.of("id", versionId), (rs, i) -> new VersionRow(rs.getString("rule_set_version_id"), rs.getString("rule_set_id"), rs.getInt("version_no"),
                        rs.getString("status_code"), rs.getString("param_status"), rs.getString("source_mode")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<MemberRow> members(String versionId) {
        return jdbc.query("SELECT m.rule_version_id,m.priority,m.enabled,rv.rule_code FROM rule_set_member m JOIN rule_version rv ON rv.rule_version_id=m.rule_version_id"
                + " WHERE m.rule_set_version_id=:id ORDER BY m.priority ASC,rv.rule_code ASC", Map.of("id", versionId),
                (rs, i) -> new MemberRow(rs.getString("rule_code"), rs.getString("rule_version_id"), rs.getInt("priority"), rs.getBoolean("enabled")));
    }

    // ---- 规则集管理（读取与头行条件更新） ----

    public long countRuleSets() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM rule_set", Map.of(), Long.class);
        return total == null ? 0 : total;
    }

    public List<RuleSetRow> listRuleSets(int offset, int size) {
        return jdbc.query(ruleSetSelect() + " ORDER BY rule_set_code ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", Map.of("offset", offset, "size", size), RuleEngineRepository::ruleSet);
    }

    public long countVersions(String ruleSetId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM rule_set_version WHERE rule_set_id=:id", Map.of("id", ruleSetId), Long.class);
        return total == null ? 0 : total;
    }

    public List<VersionDetailRow> listVersions(String ruleSetId, int offset, int size) {
        return jdbc.query(versionSelect() + " WHERE v.rule_set_id=:id ORDER BY v.version_no DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                Map.of("id", ruleSetId, "offset", offset, "size", size), RuleEngineRepository::versionDetail);
    }

    public VersionDetailRow findVersionDetail(String versionId) {
        List<VersionDetailRow> rows = jdbc.query(versionSelect() + " WHERE v.rule_set_version_id=:id", Map.of("id", versionId), RuleEngineRepository::versionDetail);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ParamRow> params(String versionId) {
        return jdbc.query("SELECT rule_code,param_key,value_text,value_type,unit,param_status,note FROM rule_param WHERE rule_set_version_id=:id ORDER BY rule_code ASC,param_key ASC",
                Map.of("id", versionId), (rs, i) -> new ParamRow(rs.getString("rule_code"), rs.getString("param_key"), rs.getString("value_text"), rs.getString("value_type"),
                        rs.getString("unit"), rs.getString("param_status"), rs.getString("note")));
    }

    public long countActivations(String ruleSetId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM rule_set_activation WHERE rule_set_id=:id", Map.of("id", ruleSetId), Long.class);
        return total == null ? 0 : total;
    }

    public List<ActivationRow> listActivations(String ruleSetId, int offset, int size) {
        return jdbc.query("SELECT activation_id,rule_set_id,kind,from_version_id,to_version_id,actor_id,note,resulting_version,created_at FROM rule_set_activation"
                + " WHERE rule_set_id=:id ORDER BY resulting_version ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", Map.of("id", ruleSetId, "offset", offset, "size", size),
                (rs, i) -> new ActivationRow(rs.getString("activation_id"), rs.getString("rule_set_id"), rs.getString("kind"), rs.getString("from_version_id"),
                        rs.getString("to_version_id"), rs.getString("actor_id"), rs.getString("note"), rs.getLong("resulting_version"), time(rs, "created_at")));
    }

    /** 头行条件更新：version 不符即并发冲突，调用方必须按 VERSION_CONFLICT 处理，不能继续写激活记录。 */
    public int updateHead(String ruleSetId, long expectedVersion, String activeVersionId, String shadowVersionId, String previousActiveVersionId, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", ruleSetId); p.put("expected", expectedVersion); p.put("active", activeVersionId); p.put("shadow", shadowVersionId); p.put("previous", previousActiveVersionId); p.put("at", at);
        return jdbc.update("UPDATE rule_set SET active_version_id=:active,shadow_version_id=:shadow,previous_active_version_id=:previous,version=version+1,updated_at=:at"
                + " WHERE rule_set_id=:id AND version=:expected", p);
    }

    public void insertActivation(String activationId, String ruleSetId, String kind, String fromVersionId, String toVersionId, String actorId, String note, long resultingVersion, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", activationId); p.put("set", ruleSetId); p.put("kind", kind); p.put("from", fromVersionId); p.put("to", toVersionId);
        p.put("actor", actorId); p.put("note", note); p.put("resulting", resultingVersion); p.put("at", at);
        jdbc.update("INSERT INTO rule_set_activation (activation_id,rule_set_id,kind,from_version_id,to_version_id,actor_id,note,resulting_version,created_at)"
                + " VALUES (:id,:set,:kind,:from,:to,:actor,:note,:resulting,:at)", p);
    }

    public long countRuns(RunQuery query) {
        Where where = runWhere(query);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM rule_run r" + where.sql, where.params, Long.class);
        return total == null ? 0 : total;
    }

    public List<RunDetailRow> listRuns(RunQuery query, int offset, int size) {
        Where where = runWhere(query);
        where.params.put("offset", offset); where.params.put("size", size);
        return jdbc.query(runSelect() + where.sql + " ORDER BY r.started_at DESC,r.run_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.params, RuleEngineRepository::runDetail);
    }

    public RunDetailRow findRunDetail(String runId) {
        List<RunDetailRow> rows = jdbc.query(runSelect() + " WHERE r.run_id=:id", Map.of("id", runId), RuleEngineRepository::runDetail);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Where runWhere(RunQuery query) {
        Where where = new Where();
        where.sql.append(" WHERE 1=1");
        if (query.mode() != null) { where.sql.append(" AND r.mode=:mode"); where.params.put("mode", query.mode()); }
        if (query.triggerKind() != null) { where.sql.append(" AND r.trigger_kind=:trigger"); where.params.put("trigger", query.triggerKind()); }
        if (query.from() != null) {
            where.sql.append(" AND r.started_at>=:from AND r.started_at<:to");
            where.params.put("from", query.from()); where.params.put("to", query.to());
        }
        return where;
    }

    private static String versionSelect() {
        return "SELECT v.rule_set_version_id,v.rule_set_id,s.rule_set_code,v.version_no,v.status_code,v.param_status,v.valid_from,v.valid_to,v.description,v.source_mode,"
                + "v.created_at,v.published_at,CASE WHEN s.active_version_id=v.rule_set_version_id THEN TRUE ELSE FALSE END AS is_active,"
                + "CASE WHEN s.shadow_version_id=v.rule_set_version_id THEN TRUE ELSE FALSE END AS is_shadow FROM rule_set_version v JOIN rule_set s ON s.rule_set_id=v.rule_set_id";
    }

    private static VersionDetailRow versionDetail(ResultSet rs, int i) throws SQLException {
        return new VersionDetailRow(rs.getString("rule_set_version_id"), rs.getString("rule_set_id"), rs.getString("rule_set_code"), rs.getInt("version_no"), rs.getString("status_code"),
                rs.getString("param_status"), time(rs, "valid_from"), time(rs, "valid_to"), rs.getString("description"), rs.getString("source_mode"), time(rs, "created_at"),
                time(rs, "published_at"), rs.getBoolean("is_active"), rs.getBoolean("is_shadow"));
    }

    private static String runSelect() {
        return "SELECT r.run_id,r.rule_set_id,s.rule_set_code,r.rule_set_version_id,r.mode,r.trigger_kind,r.replay_dataset_code,r.triggered_by,r.as_of,r.started_at,r.finished_at,r.status,"
                + "r.subject_count,r.evaluated_count,r.alarm_created_count,r.alarm_merged_count,r.error_summary,r.source_mode,r.created_at FROM rule_run r JOIN rule_set s ON s.rule_set_id=r.rule_set_id";
    }

    private static RunDetailRow runDetail(ResultSet rs, int i) throws SQLException {
        return new RunDetailRow(rs.getString("run_id"), rs.getString("rule_set_id"), rs.getString("rule_set_code"), rs.getString("rule_set_version_id"), rs.getString("mode"),
                rs.getString("trigger_kind"), rs.getString("replay_dataset_code"), rs.getString("triggered_by"), time(rs, "as_of"), time(rs, "started_at"), time(rs, "finished_at"),
                rs.getString("status"), rs.getInt("subject_count"), rs.getInt("evaluated_count"), rs.getInt("alarm_created_count"), rs.getInt("alarm_merged_count"),
                rs.getString("error_summary"), rs.getString("source_mode"), time(rs, "created_at"));
    }

    // ---- 运行记录 ----

    public RunRow findRun(String runId) {
        List<RunRow> rows = jdbc.query("SELECT run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,replay_dataset_code,as_of,status,source_mode FROM rule_run WHERE run_id=:id",
                Map.of("id", runId), (rs, i) -> new RunRow(rs.getString("run_id"), rs.getString("rule_set_id"), rs.getString("rule_set_version_id"),
                        RunMode.valueOf(rs.getString("mode")), rs.getString("trigger_kind"), rs.getString("replay_dataset_code"), time(rs, "as_of"),
                        rs.getString("status"), rs.getString("source_mode")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertRun(String runId, String ruleSetId, String versionId, RunMode mode, String triggerKind, String replayDataset,
            String triggeredBy, OffsetDateTime asOf, OffsetDateTime startedAt, String sourceMode) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", runId); p.put("set", ruleSetId); p.put("version", versionId); p.put("mode", mode.name()); p.put("trigger", triggerKind);
        p.put("dataset", replayDataset); p.put("by", triggeredBy); p.put("as_of", asOf); p.put("started", startedAt); p.put("source_mode", sourceMode);
        jdbc.update("INSERT INTO rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,replay_dataset_code,triggered_by,as_of,started_at,status,"
                + "subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at)"
                + " VALUES (:id,:set,:version,:mode,:trigger,:dataset,:by,:as_of,:started,'RUNNING',0,0,0,0,:source_mode,:started)", p);
    }

    /** 进程崩溃会留下 RUNNING 的运行记录；超过 Worker 给定的期限（当前为租约四倍）仍未收尾的按 FAILED 回收；回收不分触发方式，回放/手动运行必须在此期限内完成，否则永远无法再收尾。 */
    public int failStaleRuns(OffsetDateTime startedBefore, OffsetDateTime now) {
        Map<String, Object> p = new HashMap<>();
        p.put("before", startedBefore); p.put("now", now);
        return jdbc.update("UPDATE rule_run SET status='FAILED',finished_at=:now,error_summary='STALE_RUN_RECLAIMED' WHERE status='RUNNING' AND started_at<:before", p);
    }

    /** 只有 RUNNING 的运行可以收尾；受影响行数不为 1 说明已被收尾或不存在。 */
    public int finishRun(String runId, String status, int subjects, int evaluated, int alarmsCreated, int alarmsMerged, String errorSummary, OffsetDateTime finishedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", runId); p.put("status", status); p.put("subjects", subjects); p.put("evaluated", evaluated);
        p.put("created", alarmsCreated); p.put("merged", alarmsMerged); p.put("error", errorSummary); p.put("finished", finishedAt);
        return jdbc.update("UPDATE rule_run SET status=:status,subject_count=:subjects,evaluated_count=:evaluated,alarm_created_count=:created,"
                + "alarm_merged_count=:merged,error_summary=:error,finished_at=:finished WHERE run_id=:id AND status='RUNNING'", p);
    }

    // ---- 主体输入 ----

    public TargetRow findTarget(String targetId) {
        List<TargetRow> rows = jdbc.query("SELECT target_id,target_no,uav_sn,source_mode,owner_org_id,district_id FROM target WHERE target_id=:id", Map.of("id", targetId),
                (rs, i) -> new TargetRow(rs.getString("target_id"), rs.getString("target_no"), rs.getString("uav_sn"), rs.getString("source_mode"),
                        rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String objectType(String targetId) {
        return jdbc.queryForObject("SELECT object_type_code FROM target WHERE target_id=:id",
                Map.of("id", targetId), String.class);
    }

    public StateRow latestState(String targetId) {
        List<StateRow> rows = jdbc.query("SELECT " + locationColumns("s.location", "") + "," + locationColumns("s.pilot_location", "pilot_")
                + ",s.altitude_amsl_m,s.height_agl_m,s.speed_mps,s.heading_deg,"
                + "s.classification_confidence,s.fusion_confidence,s.observed_at,s.received_at,s.updated_at,s.pilot_observed_at FROM target_latest_state s WHERE s.target_id=:id",
                Map.of("id", targetId), (rs, i) -> {
                    BigDecimal[] point = location(rs, "");
                    BigDecimal[] pilot = location(rs, "pilot_");
                    return new StateRow(point == null ? null : point[0], point == null ? null : point[1], rs.getBigDecimal("altitude_amsl_m"),
                            rs.getBigDecimal("height_agl_m"), rs.getBigDecimal("speed_mps"), rs.getBigDecimal("heading_deg"),
                            rs.getBigDecimal("classification_confidence"), rs.getBigDecimal("fusion_confidence"),
                            time(rs, "observed_at"), time(rs, "received_at"), time(rs, "updated_at"),
                            pilot == null ? null : pilot[0], pilot == null ? null : pilot[1], time(rs, "pilot_observed_at"));
                });
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String latestTrackId(String targetId) {
        List<String> rows = jdbc.queryForList("SELECT track_id FROM track WHERE target_id=:id ORDER BY started_at DESC,created_at DESC,track_id DESC FETCH FIRST 1 ROWS ONLY",
                Map.of("id", targetId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最近 limit 个轨迹点的观测时刻（新到旧）；observed_at 为空的点按 received_at 计。 */
    public List<OffsetDateTime> recentPointTimes(String trackId, int limit) {
        return jdbc.query("SELECT COALESCE(observed_at,received_at) AS at FROM track_point WHERE track_id=:id ORDER BY point_seq DESC FETCH FIRST :limit ROWS ONLY",
                Map.of("id", trackId, "limit", limit), (rs, i) -> time(rs, "at"));
    }

    /**
     * C01 候选：与目标同 (owner_org_id, district_id) 的计划，uav_sn 相等 或 [start_at − 窗口, end_at + 窗口) 覆盖 as_of。
     * 计划状态词表尚未冻结（种子为 PENDING、测试为 APPROVED），这里不按状态过滤，由 C01 维度和复核承担。
     */
    public List<PlanFact> candidatePlans(String ownerOrgId, String districtId, String uavSn, OffsetDateTime asOf, int windowMinutes) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", ownerOrgId); p.put("district", districtId);
        p.put("latest_start", asOf.plusMinutes(windowMinutes)); p.put("earliest_end", asOf.minusMinutes(windowMinutes));
        // 目标无 sn 时不拼 sn 谓词：PostgreSQL 无法为孤立的 NULL 参数推断类型，且"无线索"本来就不该匹配任何 sn。
        String bySn = "";
        if (uavSn != null && !uavSn.isBlank()) { bySn = "p.uav_sn=:sn OR "; p.put("sn", uavSn.trim()); }
        return jdbc.query("SELECT p.plan_id,p.route_version_id,p.uav_sn,p.start_at,p.end_at,rv.corridor_width_m,rv.min_altitude_m,rv.max_altitude_m,rv.altitude_datum,"
                + "p.owner_org_id,p.district_id FROM flight_plan p JOIN route_version rv ON rv.route_version_id=p.route_version_id"
                + " WHERE p.owner_org_id=:org AND p.district_id=:district"
                + " AND (" + bySn + "(p.start_at IS NOT NULL AND p.end_at IS NOT NULL AND p.start_at<=:latest_start AND :earliest_end<p.end_at))"
                + " ORDER BY p.start_at ASC,p.plan_id ASC", p,
                (rs, i) -> new PlanFact(rs.getString("plan_id"), rs.getString("route_version_id"), rs.getString("uav_sn"), time(rs, "start_at"), time(rs, "end_at"),
                        rs.getBigDecimal("corridor_width_m"), rs.getBigDecimal("min_altitude_m"), rs.getBigDecimal("max_altitude_m"), rs.getString("altitude_datum"),
                        rs.getString("owner_org_id"), rs.getString("district_id")));
    }

    /** 计划主体：计划本身作为唯一候选进入 C01。 */
    public PlanFact planSubject(String planId) {
        List<PlanFact> rows = jdbc.query("SELECT p.plan_id,p.route_version_id,p.uav_sn,p.start_at,p.end_at,rv.corridor_width_m,rv.min_altitude_m,rv.max_altitude_m,rv.altitude_datum,"
                + "p.owner_org_id,p.district_id FROM flight_plan p JOIN route_version rv ON rv.route_version_id=p.route_version_id WHERE p.plan_id=:id", Map.of("id", planId),
                (rs, i) -> new PlanFact(rs.getString("plan_id"), rs.getString("route_version_id"), rs.getString("uav_sn"), time(rs, "start_at"), time(rs, "end_at"),
                        rs.getBigDecimal("corridor_width_m"), rs.getBigDecimal("min_altitude_m"), rs.getBigDecimal("max_altitude_m"), rs.getString("altitude_datum"),
                        rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String planSourceMode(String planId) {
        List<String> rows = jdbc.queryForList("SELECT source_mode FROM flight_plan WHERE plan_id=:id", Map.of("id", planId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 计划主体的观测来源：同元组、同 sn 且有最新状态的目标，取观测最新者。 */
    public TargetRow latestTargetBySn(String uavSn, String ownerOrgId, String districtId) {
        Map<String, Object> p = new HashMap<>();
        p.put("sn", uavSn); p.put("org", ownerOrgId); p.put("district", districtId);
        List<TargetRow> rows = jdbc.query("SELECT t.target_id,t.target_no,t.uav_sn,t.source_mode,t.owner_org_id,t.district_id FROM target t"
                + " JOIN target_latest_state s ON s.target_id=t.target_id WHERE t.uav_sn=:sn AND t.owner_org_id=:org AND t.district_id=:district"
                + " ORDER BY s.observed_at DESC,t.target_id ASC FETCH FIRST 1 ROWS ONLY", p,
                (rs, i) -> new TargetRow(rs.getString("target_id"), rs.getString("target_no"), rs.getString("uav_sn"), rs.getString("source_mode"),
                        rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Worker 待评估主体：最新状态在该目标最近一次同模式、同版本研判的 observed_at 之后更新，且仍新鲜；目录停用的目标不评估。
     */
    public List<Subject> pendingSubjects(RunMode mode, String versionId, OffsetDateTime freshSince, int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("mode", mode.name()); p.put("version", versionId); p.put("fresh_since", freshSince); p.put("limit", limit);
        return jdbc.query("SELECT t.target_id,t.owner_org_id,t.district_id,t.source_mode FROM target_latest_state s JOIN target t ON t.target_id=s.target_id"
                + " JOIN app_org o ON o.org_id=t.owner_org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=t.district_id AND d.enabled=TRUE"
                + " WHERE s.observed_at>=:fresh_since AND NOT EXISTS (SELECT 1 FROM rule_evaluation e WHERE e.target_id=t.target_id AND e.mode=:mode"
                + " AND e.rule_set_version_id=:version AND e.observed_at IS NOT NULL AND e.observed_at>=s.observed_at)"
                + " ORDER BY s.updated_at ASC,t.target_id ASC FETCH FIRST :limit ROWS ONLY", p,
                (rs, i) -> new Subject(SubjectKind.TARGET, rs.getString("target_id"), rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("source_mode")));
    }

    // ---- 研判与投影 ----

    public void insertEvaluation(EvaluationInsert e) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", e.evaluationId()); p.put("run", e.runId()); p.put("version", e.ruleSetVersionId()); p.put("mode", e.mode().name());
        p.put("kind", e.subjectKind().name()); p.put("target", e.targetId()); p.put("track", e.trackId()); p.put("plan", e.planId()); p.put("route", e.routeVersionId());
        p.put("observed", e.observedAt()); p.put("as_of", e.asOf()); p.put("evaluated", e.evaluatedAt()); p.put("freshness", e.freshness());
        p.put("plan_match", e.planMatchCode()); p.put("legal", e.legalStatus()); p.put("score", e.score()); p.put("grade", e.grade());
        p.put("violations", e.violationReasonsJson()); p.put("hits", e.hitDetailsJson()); p.put("unknowns", e.unknownReasonsJson());
        p.put("evidence", e.evidenceJson()); p.put("snapshot", e.inputSnapshotJson()); p.put("supersedes", e.supersedesEvaluationId());
        p.put("alarm_outcome", e.alarmOutcomeJson()); p.put("org", e.ownerOrgId()); p.put("district", e.districtId()); p.put("source_mode", e.sourceMode());
        jdbc.update("INSERT INTO rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,track_id,plan_id,route_version_id,observed_at,as_of,"
                + "evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,"
                + "supersedes_evaluation_id,alarm_outcome,owner_org_id,district_id,source_mode,created_at)"
                + " VALUES (:id,:run,:version,:mode,:kind,:target,:track,:plan,:route,:observed,:as_of,:evaluated,:freshness,:plan_match,:legal,:score,:grade,"
                + "CAST(:violations AS JSON),CAST(:hits AS JSON),CAST(:unknowns AS JSON),CAST(:evidence AS JSON),CAST(:snapshot AS JSON),:supersedes,"
                + "CAST(:alarm_outcome AS JSON),:org,:district,:source_mode,:evaluated)", p);
    }

    /**
     * 一次性回填：研判行只增，唯一允许的 UPDATE 是把 assessment_id / alarm_id / alarm_outcome 从 NULL 变为非 NULL。
     * 每列各自用“仅当仍为 NULL 时写入”的条件更新，而不是 COALESCE：PostgreSQL 不允许 COALESCE(jsonb 列, CAST(? AS JSON)) 混型
     * （json 与 jsonb 之间没有隐式转换，只有赋值转换），H2 上也不需要。逐列条件更新与 R__stage7 触发器“每个关联列只能从 NULL 回填一次”
     * 一一对应；已回填的列保持原值，不会被第二次调用覆盖。返回研判行是否存在（1/0），调用方据此判断回填目标是否丢失。
     */
    public int backfillEvaluation(String evaluationId, String assessmentId, String alarmId, String alarmOutcomeJson) {
        Map<String, Object> id = Map.of("id", evaluationId);
        if (assessmentId != null) {
            jdbc.update("UPDATE rule_evaluation SET assessment_id=:value WHERE evaluation_id=:id AND assessment_id IS NULL", params(evaluationId, assessmentId));
        }
        if (alarmId != null) {
            jdbc.update("UPDATE rule_evaluation SET alarm_id=:value WHERE evaluation_id=:id AND alarm_id IS NULL", params(evaluationId, alarmId));
        }
        if (alarmOutcomeJson != null) {
            jdbc.update("UPDATE rule_evaluation SET alarm_outcome=CAST(:value AS JSON) WHERE evaluation_id=:id AND alarm_outcome IS NULL", params(evaluationId, alarmOutcomeJson));
        }
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM rule_evaluation WHERE evaluation_id=:id", id, Long.class);
        return exists == null ? 0 : exists.intValue();
    }

    private static Map<String, Object> params(String evaluationId, String value) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", evaluationId); p.put("value", value);
        return p;
    }

    public EvaluationLink findEvaluationLink(String evaluationId) {
        List<EvaluationLink> rows = jdbc.query("SELECT evaluation_id,target_id,plan_id,assessment_id,mode FROM rule_evaluation WHERE evaluation_id=:id", Map.of("id", evaluationId),
                (rs, i) -> new EvaluationLink(rs.getString("evaluation_id"), rs.getString("target_id"), rs.getString("plan_id"), rs.getString("assessment_id"), rs.getString("mode")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertAssessment(AssessmentInsert a) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", a.assessmentId()); p.put("plan", a.planId()); p.put("target", a.targetId()); p.put("track", a.trackId()); p.put("route", a.routeVersionId());
        p.put("rule_version", a.ruleVersionId()); p.put("assessed", a.assessedAt()); p.put("conclusion", a.conclusionCode()); p.put("checks", a.checksJson());
        p.put("unknowns", a.unknownReasonsJson()); p.put("evidence", a.evidenceJson()); p.put("source_mode", a.sourceMode()); p.put("evaluation", a.evaluationId());
        p.put("set_version", a.ruleSetVersionId()); p.put("supersedes", a.supersedesAssessmentId());
        jdbc.update("INSERT INTO assessment_result (assessment_id,plan_id,target_id,track_id,route_version_id,rule_version_id,assessed_at,conclusion_code,checks,unknown_reasons,"
                + "evidence_references,source_mode,created_at,evaluation_id,rule_set_version_id,supersedes_assessment_id)"
                + " VALUES (:id,:plan,:target,:track,:route,:rule_version,:assessed,:conclusion,CAST(:checks AS JSON),CAST(:unknowns AS JSON),CAST(:evidence AS JSON),"
                + ":source_mode,:assessed,:evaluation,:set_version,:supersedes)", p);
    }

    // ---- 租约 ----

    /** 单行租约条件更新：未持有、已过期或本实例续约时才成功。 */
    public boolean acquireLease(String leaseName, String holder, OffsetDateTime now, OffsetDateTime until) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", leaseName); p.put("holder", holder); p.put("now", now); p.put("until", until);
        return jdbc.update("UPDATE rule_engine_lease SET holder=:holder,lease_until=:until,updated_at=:now WHERE lease_name=:name"
                + " AND (holder IS NULL OR lease_until IS NULL OR lease_until<:now OR holder=:holder)", p) == 1;
    }

    // ---- 映射 ----

    private static String ruleSetSelect() {
        return "SELECT rule_set_id,rule_set_code,name,active_version_id,shadow_version_id,previous_active_version_id,version,created_at,updated_at FROM rule_set";
    }

    private static RuleSetRow ruleSet(ResultSet rs, int i) throws SQLException {
        return new RuleSetRow(rs.getString("rule_set_id"), rs.getString("rule_set_code"), rs.getString("name"), rs.getString("active_version_id"),
                rs.getString("shadow_version_id"), rs.getString("previous_active_version_id"), rs.getLong("version"), time(rs, "created_at"), time(rs, "updated_at"));
    }

    /** alias 前缀区分同一行里的多个几何列（目标位置与飞手位置）。 */
    private String locationColumns(String column, String alias) {
        if (postgis) {
            return "CASE WHEN " + column + " IS NOT NULL THEN ST_X(" + column + ") END AS " + alias + "longitude,"
                    + "CASE WHEN " + column + " IS NOT NULL THEN ST_Y(" + column + ") END AS " + alias + "latitude,"
                    + "CAST(NULL AS VARCHAR) AS " + alias + "location_text";
        }
        return "CAST(NULL AS NUMERIC) AS " + alias + "longitude,CAST(NULL AS NUMERIC) AS " + alias + "latitude,"
                + "CAST(" + column + " AS VARCHAR) AS " + alias + "location_text";
    }

    /** H2 没有 ST_X/ST_Y，退化为解析 WKT；坐标越界视为缺失而不是裁剪。 */
    private static BigDecimal[] location(ResultSet rs, String alias) throws SQLException {
        BigDecimal longitude = rs.getBigDecimal(alias + "longitude"), latitude = rs.getBigDecimal(alias + "latitude");
        if (longitude == null || latitude == null) {
            String text = rs.getString(alias + "location_text");
            if (text == null) return null;
            int point = text.toUpperCase().indexOf("POINT");
            int open = text.indexOf('(', point), close = text.indexOf(')', open);
            if (point < 0 || open < 0 || close < 0) return null;
            String[] parts = text.substring(open + 1, close).trim().split("\\s+");
            if (parts.length != 2) return null;
            try { longitude = new BigDecimal(parts[0]); latitude = new BigDecimal(parts[1]); }
            catch (NumberFormatException ex) { return null; }
        }
        if (longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0 || latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0) return null;
        return new BigDecimal[] { longitude, latitude };
    }

    static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime t) return t;
        if (value instanceof ZonedDateTime t) return t.toOffsetDateTime();
        if (value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime t) return t.atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(value.toString());
    }

    static boolean databaseIsPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot determine database type", ex);
        }
    }

    private static final class Where { final StringBuilder sql = new StringBuilder(); final Map<String, Object> params = new HashMap<>(); }

    public record RunQuery(String mode, String triggerKind, OffsetDateTime from, OffsetDateTime to) { }
    public record VersionDetailRow(String ruleSetVersionId, String ruleSetId, String ruleSetCode, int versionNo, String statusCode, String paramStatus,
            OffsetDateTime validFrom, OffsetDateTime validTo, String description, String sourceMode, OffsetDateTime createdAt, OffsetDateTime publishedAt,
            boolean active, boolean shadow) { }
    public record ParamRow(String ruleCode, String key, String value, String type, String unit, String status, String note) { }
    public record ActivationRow(String activationId, String ruleSetId, String kind, String fromVersionId, String toVersionId, String actorId, String note,
            long resultingVersion, OffsetDateTime createdAt) { }
    public record RunDetailRow(String runId, String ruleSetId, String ruleSetCode, String ruleSetVersionId, String mode, String triggerKind, String replayDatasetCode,
            String triggeredBy, OffsetDateTime asOf, OffsetDateTime startedAt, OffsetDateTime finishedAt, String status, int subjectCount, int evaluatedCount,
            int alarmCreatedCount, int alarmMergedCount, String errorSummary, String sourceMode, OffsetDateTime createdAt) { }
    public record RuleSetRow(String ruleSetId, String ruleSetCode, String name, String activeVersionId, String shadowVersionId,
            String previousActiveVersionId, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record VersionRow(String ruleSetVersionId, String ruleSetId, int versionNo, String statusCode, String paramStatus, String sourceMode) { }
    public record MemberRow(String ruleCode, String ruleVersionId, int priority, boolean enabled) { }
    public record RunRow(String runId, String ruleSetId, String ruleSetVersionId, RunMode mode, String triggerKind, String replayDatasetCode,
            OffsetDateTime asOf, String status, String sourceMode) { }
    public record TargetRow(String targetId, String targetNo, String uavSn, String sourceMode, String ownerOrgId, String districtId) { }
    public record StateRow(BigDecimal longitude, BigDecimal latitude, BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps,
            BigDecimal headingDeg, BigDecimal classificationConfidence, BigDecimal fusionConfidence, OffsetDateTime observedAt,
            OffsetDateTime receivedAt, OffsetDateTime updatedAt,
            /* 阶段 8.5：融合层写入的飞手位置与它的观测时刻，C02-6 的输入；无则为空。
             * pilotObservedAt 暂时只到本行为止：冻结接口 TargetState 的第 15 个字段由领导添加，加完再接进 C02-6 的 facts（决策 8.5-28）。 */
            BigDecimal pilotLongitude, BigDecimal pilotLatitude, OffsetDateTime pilotObservedAt) { }
    public record EvaluationLink(String evaluationId, String targetId, String planId, String assessmentId, String mode) { }
    public record EvaluationInsert(String evaluationId, String runId, String ruleSetVersionId, RunMode mode, SubjectKind subjectKind, String targetId,
            String trackId, String planId, String routeVersionId, OffsetDateTime observedAt, OffsetDateTime asOf, OffsetDateTime evaluatedAt,
            String freshness, String planMatchCode, String legalStatus, BigDecimal score, String grade, String violationReasonsJson,
            String hitDetailsJson, String unknownReasonsJson, String evidenceJson, String inputSnapshotJson, String supersedesEvaluationId,
            String alarmOutcomeJson, String ownerOrgId, String districtId, String sourceMode) { }
    public record AssessmentInsert(String assessmentId, String planId, String targetId, String trackId, String routeVersionId, String ruleVersionId,
            OffsetDateTime assessedAt, String conclusionCode, String checksJson, String unknownReasonsJson, String evidenceJson, String sourceMode,
            String evaluationId, String ruleSetVersionId, String supersedesAssessmentId) { }
}
