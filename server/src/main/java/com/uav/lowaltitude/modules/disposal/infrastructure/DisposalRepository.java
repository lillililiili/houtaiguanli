package com.uav.lowaltitude.modules.disposal.infrastructure;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.security.AuthUser;

/** 处置授权持久化。事件表只增：本类不提供任何 UPDATE/DELETE 事件的方法，PG 侧另有触发器兜底。 */
@Repository
public class DisposalRepository {
    /** 姓名随行取出：页面要显示人名而不是内部 ID，逐条再查一次用户就是列表页的 N+1（决策 13-26）。 */
    private static final String JOINS = " LEFT JOIN app_user ru ON ru.user_id=a.requested_by"
            + " LEFT JOIN app_user au ON au.user_id=a.approved_by";
    private static final String NAME_COLUMNS = ",ru.name AS requested_by_name,au.name AS approved_by_name";
    private static final String COLUMNS = "a.authorization_id,a.authorization_no,a.action_type,a.subject_kind,a.subject_id,"
            + "a.target_id,a.device_id,a.channel,a.reason,a.requested_by,a.requested_at,a.approved_by,a.approved_at,"
            + "a.decision_note,a.valid_from,a.valid_until,a.status,a.execution_command_id,a.result_code,a.result_detail,"
            + "a.policy_version,a.owner_org_id,a.district_id,a.source_mode,a.version";

    private final NamedParameterJdbcTemplate jdbc;
    /** 只用于"建当日计数行"这一步：见 nextSequence 的说明，它必须跑在调用方事务之外。 */
    private final TransactionTemplate newTransaction;

    public DisposalRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactions) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /* ---- 编号 ---- */

    /**
     * 发号：先确保当日计数行存在，再对该行加锁自增（决策 13-5）。
     *
     * 两处刻意的写法：
     * 1. 不用 ON CONFLICT —— H2 的 PostgreSQL 兼容模式不解析它（与 FusionInboxRepository 同一结论），
     *    而单测库与生产库必须跑同一条语句。
     * 2. 建行这一步跑在**独立事务**里。当日第一条授权若并发到达，两个事务会同时看到"行不存在"，
     *    其中一个撞唯一键；在 PostgreSQL 里失败语句会把整个事务打成 aborted，后续语句全报 25P02——
     *    等于把并发发号写成偶发 500。放进独立事务后，冲突只废掉那个内层事务，外层照常继续。
     * 计数行不是业务数据，独立提交没有副作用；回滚导致的号段空缺也无所谓——编号要的是不重复，不是连续。
     */
    public int nextSequence(String dayKey) {
        ensureDay(dayKey);
        Integer next = jdbc.queryForObject("SELECT next_no FROM disposal_no_counter WHERE day_key=:day FOR UPDATE",
                Map.of("day", dayKey), Integer.class);
        if (next == null) {
            // 独立事务刚建过行还查不到，只可能是被并发删了；这属于不该发生的状态，不能猜一个号继续发。
            throw new IllegalStateException("disposal number counter missing for " + dayKey);
        }
        jdbc.update("UPDATE disposal_no_counter SET next_no=next_no+1 WHERE day_key=:day", Map.of("day", dayKey));
        return next;
    }

    private void ensureDay(String dayKey) {
        newTransaction.executeWithoutResult(status -> {
            try {
                jdbc.update("INSERT INTO disposal_no_counter (day_key,next_no) SELECT :day,1"
                        + " WHERE NOT EXISTS (SELECT 1 FROM disposal_no_counter WHERE day_key=:day)",
                        Map.of("day", dayKey));
            } catch (DataIntegrityViolationException raced) {
                // 并发的另一个事务刚建好同一行：目的已经达成，这不是错误。
                status.setRollbackOnly();
            }
        });
    }

    /* ---- 写 ---- */

    public void insert(AuthorizationInsert row) {
        Map<String, Object> p = insertParams(row);
        jdbc.update("INSERT INTO disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "target_id,device_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,"
                + "source_mode,version,created_at,updated_at) VALUES (:id,:no,:action,:kind,:subject,:target,:device,:channel,"
                + ":reason,:by,:at,:status,:policy,:org,:district,:mode,0,:at,:at)", p);
    }

    /**
     * 反制完成后自动接上的信号干扰：一次插入即 APPROVED，并带齐审批人、时限与来源授权。
     * 审批约束要求这四列同生同灭，不能先按 REQUESTED 插入再补。
     */
    public void insertChainedApproved(AuthorizationInsert row, String chainedFrom, String approvedBy,
                                      OffsetDateTime approvedAt, OffsetDateTime validFrom, OffsetDateTime validUntil,
                                      String decisionNote) {
        Map<String, Object> p = insertParams(row);
        p.put("chained", chainedFrom); p.put("approver", approvedBy); p.put("approvedAt", approvedAt);
        p.put("from", validFrom); p.put("until", validUntil); p.put("note", decisionNote);
        jdbc.update("INSERT INTO disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,decision_note,"
                + "valid_from,valid_until,status,policy_version,owner_org_id,district_id,source_mode,"
                + "chained_from_authorization_id,version,created_at,updated_at) VALUES (:id,:no,:action,:kind,:subject,:target,"
                + ":device,:channel,:reason,:by,:at,:approver,:approvedAt,:note,:from,:until,:status,:policy,:org,:district,"
                + ":mode,:chained,0,:at,:at)", p);
    }

    private static Map<String, Object> insertParams(AuthorizationInsert row) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.authorizationId()); p.put("no", row.authorizationNo()); p.put("action", row.actionType());
        p.put("kind", row.subjectKind()); p.put("subject", row.subjectId()); p.put("target", row.targetId());
        p.put("device", row.deviceId()); p.put("channel", row.channel()); p.put("reason", row.reason());
        p.put("by", row.requestedBy()); p.put("at", row.requestedAt()); p.put("policy", row.policyVersion());
        p.put("org", row.ownerOrgId()); p.put("district", row.districtId()); p.put("mode", row.sourceMode());
        p.put("status", row.status());
        return p;
    }

    /** 审批：只在版本未变时落笔，返回 0 表示已被别人改过，调用方据此报 409。 */
    public int approve(String id, long expectedVersion, String approvedBy, OffsetDateTime approvedAt,
                       OffsetDateTime validFrom, OffsetDateTime validUntil, String note) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("v", expectedVersion); p.put("by", approvedBy); p.put("at", approvedAt);
        p.put("from", validFrom); p.put("until", validUntil); p.put("note", note);
        return jdbc.update("UPDATE disposal_authorization SET status='APPROVED',approved_by=:by,approved_at=:at,"
                + "valid_from=:from,valid_until=:until,decision_note=:note,version=version+1,updated_at=:at"
                + " WHERE authorization_id=:id AND version=:v", p);
    }

    public int reject(String id, long expectedVersion, String approvedBy, OffsetDateTime at, String note) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("v", expectedVersion); p.put("by", approvedBy); p.put("at", at); p.put("note", note);
        return jdbc.update("UPDATE disposal_authorization SET status='REJECTED',approved_by=:by,approved_at=:at,"
                + "decision_note=:note,version=version+1,updated_at=:at WHERE authorization_id=:id AND version=:v", p);
    }

    /** 状态迁移的通用写法；result_code/result_detail/execution_command_id 为 null 时保持原值不动。 */
    public int transition(String id, long expectedVersion, String status, OffsetDateTime at,
                          String commandId, String resultCode, String resultDetail) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("v", expectedVersion); p.put("status", status); p.put("at", at);
        p.put("cmd", commandId); p.put("code", resultCode); p.put("detail", resultDetail);
        return jdbc.update("UPDATE disposal_authorization SET status=:status,version=version+1,updated_at=:at,"
                + "execution_command_id=COALESCE(:cmd,execution_command_id),"
                + "result_code=COALESCE(:code,result_code),result_detail=COALESCE(:detail,result_detail)"
                + " WHERE authorization_id=:id AND version=:v", p);
    }

    /** 回执与到期由系统推进，没有调用方持有的版本号，因此按当前状态而不是版本号做乐观并发。 */
    public int transitionFromStatus(String id, String fromStatus, String status, OffsetDateTime at,
                                    String resultCode, String resultDetail) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("from", fromStatus); p.put("status", status); p.put("at", at);
        p.put("code", resultCode); p.put("detail", resultDetail);
        return jdbc.update("UPDATE disposal_authorization SET status=:status,version=version+1,updated_at=:at,"
                + "result_code=COALESCE(:code,result_code),result_detail=COALESCE(:detail,result_detail)"
                + " WHERE authorization_id=:id AND status=:from", p);
    }

    public void insertEvent(String eventId, String authorizationId, String eventKind, String actorId,
                            String note, String snapshotJson, OffsetDateTime occurredAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", eventId); p.put("auth", authorizationId); p.put("kind", eventKind); p.put("actor", actorId);
        p.put("note", note); p.put("snapshot", snapshotJson); p.put("at", occurredAt);
        jdbc.update("INSERT INTO disposal_authorization_event (event_id,authorization_id,event_kind,actor_id,note,snapshot,occurred_at)"
                + " VALUES (:id,:auth,:kind,:actor,:note,CAST(:snapshot AS JSON),:at)", p);
    }

    /* ---- 读 ---- */

    /** 加锁读：同一授权的并发审批/执行必须串行，否则两人可以同时把 REQUESTED 批成 APPROVED。 */
    public AuthorizationRow lock(String id, AccessDecision access) {
        Where where = scope(access);
        where.params.put("id", id);
        List<AuthorizationRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM disposal_authorization a"
                + where.sql + " AND a.authorization_id=:id FOR UPDATE", where.params, DisposalRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AuthorizationRow find(String id, AccessDecision access) {
        Where where = scope(access);
        where.params.put("id", id);
        List<AuthorizationRow> rows = jdbc.query("SELECT " + COLUMNS + NAME_COLUMNS + " FROM disposal_authorization a"
                + JOINS + where.sql + " AND a.authorization_id=:id", where.params, DisposalRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<AuthorizationRow> list(AccessDecision access, Query query, int offset, int limit) {
        Where where = filter(access, query);
        where.params.put("limit", limit); where.params.put("offset", offset);
        return jdbc.query("SELECT " + COLUMNS + NAME_COLUMNS + " FROM disposal_authorization a" + JOINS + where.sql
                + " ORDER BY a.requested_at DESC, a.authorization_id DESC LIMIT :limit OFFSET :offset",
                where.params, DisposalRepository::row);
    }

    public long count(AccessDecision access, Query query) {
        Where where = filter(access, query);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization a" + where.sql, where.params, Long.class);
        return total == null ? 0 : total;
    }

    /** 同主体同动作的活动授权数，用于 max_active_per_subject。范围不参与：并发上限是业务事实，不随谁在看而变。 */
    public long activeCount(String subjectKind, String subjectId, String actionType) {
        Map<String, Object> p = new HashMap<>();
        p.put("kind", subjectKind); p.put("subject", subjectId); p.put("action", actionType);
        p.put("statuses", DisposalRules.ACTIVE_STATUSES);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE subject_kind=:kind"
                + " AND subject_id=:subject AND action_type=:action AND status IN (:statuses)", p, Long.class);
        return total == null ? 0 : total;
    }

    /** 某主体是否存在已完成授权（决策 13-6，供处罚交接前提判断）。 */
    public boolean completedExists(String subjectKind, String subjectId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE subject_kind=:kind"
                + " AND subject_id=:subject AND status='COMPLETED'",
                Map.of("kind", subjectKind, "subject", subjectId), Long.class);
        return total != null && total > 0;
    }

    /** 该反制是否已经接出过信号干扰。系统链式用，不跟调用者范围。 */
    public boolean chainedFrom(String parentAuthorizationId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE chained_from_authorization_id=:id",
                Map.of("id", parentAuthorizationId), Long.class);
        return total != null && total > 0;
    }

    /** 该主体是否已有任一信号干扰授权（含手选），有则不再自动接。 */
    public boolean actionExists(String subjectKind, String subjectId, String actionType) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE subject_kind=:kind"
                + " AND subject_id=:subject AND action_type=:action",
                Map.of("kind", subjectKind, "subject", subjectId, "action", actionType), Long.class);
        return total != null && total > 0;
    }

    /** 系统链式读取，不加范围谓词：来源授权刚完成，不能因为执行人不在范围里就把链停掉。 */
    public AuthorizationRow findUnlocked(String id) {
        List<AuthorizationRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM disposal_authorization a"
                + " WHERE a.authorization_id=:id", Map.of("id", id), DisposalRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String latestExecuteActor(String authorizationId) {
        List<String> actors = jdbc.query("SELECT actor_id FROM disposal_authorization_event WHERE authorization_id=:id"
                + " AND event_kind='EXECUTE' AND actor_id IS NOT NULL ORDER BY occurred_at DESC, event_id DESC LIMIT 1",
                Map.of("id", authorizationId), (rs, i) -> rs.getString(1));
        return actors.isEmpty() ? null : actors.get(0);
    }

    public AuthUser actor(String userId) {
        if (userId == null || userId.isBlank()) return null;
        List<AuthUser> rows = jdbc.query("SELECT user_id,account,name,role_code,permission_version,must_change_password,scope_mode"
                + " FROM app_user WHERE user_id=:id", Map.of("id", userId), (rs, i) -> new AuthUser(
                rs.getString("user_id"), rs.getString("account"), rs.getString("name"), rs.getString("role_code"),
                rs.getInt("permission_version"), rs.getBoolean("must_change_password"), rs.getString("scope_mode")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 目标的归属元组与最近观测时刻，供以目标为主体的授权使用（决策 13-24）。
     *
     * 先经 target_current_alias 解析：融合会把历史目标并入当前目标，拿着旧 ID 发起处置
     * 等于对一个已经不存在的航迹动手——解析到存活目标再判断。
     * 范围条件与授权列表同一口径：看不见的目标必须表现为"不存在"，否则拿 ID 就能试探别的辖区有什么目标。
     */
    public TargetScope targetScope(String targetId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", targetId);
        StringBuilder sql = new StringBuilder(
                "SELECT t.target_id,t.owner_org_id,t.district_id,t.source_mode,s.observed_at"
                + " FROM target t LEFT JOIN target_latest_state s ON s.target_id=t.target_id"
                + " WHERE t.target_id=COALESCE((SELECT a.current_target_id FROM target_current_alias a"
                + "   WHERE a.historical_target_id=:id), :id)"
                + " AND EXISTS (SELECT 1 FROM app_org o JOIN app_district d ON d.district_id=t.district_id"
                + "   WHERE o.org_id=t.owner_org_id AND o.enabled=TRUE AND d.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs WHERE gs.user_id=:scope_user"
                    + " AND gs.org_id=t.owner_org_id AND gs.district_id=t.district_id)");
            params.put("scope_user", access.userId());
        }
        List<TargetScope> rows = jdbc.query(sql.toString(), params, (rs, i) -> new TargetScope(
                rs.getString("target_id"), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getString("source_mode"), rs.getObject("observed_at", OffsetDateTime.class)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 生效规则集的 C03.fresh_seconds。取不到时返回 null——调用方据此拒绝，而不是自己编一个秒数：
     * 编出来的阈值会让"目标是不是还活着"这件事变成没人认账的判断。
     */
    public Integer freshSeconds() {
        List<String> rows = jdbc.query("SELECT p.value_text FROM rule_param p"
                + " JOIN rule_set s ON s.active_version_id=p.rule_set_version_id"
                + " WHERE p.rule_code='C03' AND p.param_key='fresh_seconds'", Map.of(),
                (rs, i) -> rs.getString(1));
        if (rows.isEmpty()) return null;
        try { return Integer.valueOf(rows.get(0).trim()); }
        catch (NumberFormatException notANumber) { return null; }
    }

    public List<EventRow> events(String authorizationId) {
        return jdbc.query("SELECT event_id,authorization_id,event_kind,actor_id,note,snapshot,occurred_at"
                + " FROM disposal_authorization_event WHERE authorization_id=:id ORDER BY occurred_at ASC, event_id ASC",
                Map.of("id", authorizationId), (rs, i) -> new EventRow(rs.getString("event_id"),
                        rs.getString("authorization_id"), rs.getString("event_kind"), rs.getString("actor_id"),
                        rs.getString("note"), com.uav.lowaltitude.modules.fusion.infrastructure.FusionConfigRepository.jsonText(rs.getObject("snapshot")),
                        rs.getObject("occurred_at", OffsetDateTime.class)));
    }

    /** 批量取事件种类，供列表页推导 device_stop_result 而不必逐条查事件流（N+1）。 */
    public Map<String, List<String>> eventKinds(List<String> authorizationIds) {
        Map<String, List<String>> kinds = new HashMap<>();
        if (authorizationIds.isEmpty()) return kinds;
        jdbc.query("SELECT authorization_id,event_kind FROM disposal_authorization_event WHERE authorization_id IN (:ids)",
                Map.of("ids", authorizationIds), rs -> {
                    kinds.computeIfAbsent(rs.getString("authorization_id"), k -> new ArrayList<>())
                            .add(rs.getString("event_kind"));
                });
        return kinds;
    }

    /** 到期扫描：只挑已批准且过了有效期的，执行中的不动（设备已经动了，标成过期等于抹掉一次真实处置）。 */
    public List<AuthorizationRow> expirable(OffsetDateTime now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM disposal_authorization a WHERE a.status='APPROVED'"
                + " AND a.valid_until IS NOT NULL AND a.valid_until<=:now ORDER BY a.valid_until ASC LIMIT :limit",
                Map.of("now", now, "limit", limit), DisposalRepository::row);
    }

    /** 执行中且经协议 B 下发的授权，供回执同步比对 A 的 device_command 状态。 */
    public List<AuthorizationRow> awaitingReceipt(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM disposal_authorization a WHERE a.status='EXECUTING'"
                + " AND a.execution_command_id IS NOT NULL ORDER BY a.updated_at ASC LIMIT :limit",
                Map.of("limit", limit), DisposalRepository::row);
    }

    /* ---- 范围与筛选 ---- */

    private static Where filter(AccessDecision access, Query query) {
        Where where = scope(access);
        add(where, "a.subject_kind", "f_kind", query.subjectKind());
        add(where, "a.subject_id", "f_subject", query.subjectId());
        add(where, "a.status", "f_status", query.status());
        add(where, "a.action_type", "f_action", query.actionType());
        return where;
    }

    /** 与交接同一口径：ALL 仍要求归属目录存在且启用，ASSIGNED 必须同一授权行同时命中组织与区域。 */
    private static Where scope(AccessDecision access) {
        Where where = new Where();
        where.sql.append(" WHERE EXISTS (SELECT 1 FROM app_org ao JOIN app_district ad ON ad.district_id=a.district_id"
                + " WHERE ao.org_id=a.owner_org_id AND ao.enabled=TRUE AND ad.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            where.sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs JOIN app_org o ON o.org_id=gs.org_id AND o.enabled=TRUE"
                    + " JOIN app_district d2 ON d2.district_id=gs.district_id AND d2.enabled=TRUE WHERE gs.user_id=:scope_user"
                    + " AND gs.org_id=a.owner_org_id AND gs.district_id=a.district_id)");
            where.params.put("scope_user", access.userId());
        }
        return where;
    }

    private static void add(Where where, String column, String name, String value) {
        if (value != null) { where.sql.append(" AND ").append(column).append("=:").append(name); where.params.put(name, value); }
    }

    private static AuthorizationRow row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new AuthorizationRow(rs.getString("authorization_id"), rs.getString("authorization_no"),
                rs.getString("action_type"), rs.getString("subject_kind"), rs.getString("subject_id"),
                rs.getString("target_id"), rs.getString("device_id"), rs.getString("channel"), rs.getString("reason"),
                rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class),
                rs.getString("approved_by"), rs.getObject("approved_at", OffsetDateTime.class),
                rs.getString("decision_note"), rs.getObject("valid_from", OffsetDateTime.class),
                rs.getObject("valid_until", OffsetDateTime.class), rs.getString("status"),
                rs.getString("execution_command_id"), rs.getString("result_code"), rs.getString("result_detail"),
                rs.getString("policy_version"), rs.getString("owner_org_id"), rs.getString("district_id"),
                rs.getString("source_mode"), rs.getLong("version"),
                name(rs, "requested_by_name"), name(rs, "approved_by_name"));
    }

    /** 姓名列在部分查询里不存在（如到期扫描），取不到就当没有，不让缺一列把整条读崩。 */
    private static String name(java.sql.ResultSet rs, String column) {
        try { return rs.getString(column); } catch (java.sql.SQLException absent) { return null; }
    }

    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final Map<String, Object> params = new HashMap<>();
    }

    public record TargetScope(String targetId, String ownerOrgId, String districtId, String sourceMode,
            OffsetDateTime observedAt) { }

    public record Query(String subjectKind, String subjectId, String status, String actionType) { }

    public record AuthorizationInsert(String authorizationId, String authorizationNo, String actionType,
            String subjectKind, String subjectId, String targetId, String deviceId, String channel, String reason,
            String requestedBy, OffsetDateTime requestedAt, String status, String policyVersion, String ownerOrgId,
            String districtId, String sourceMode) { }

    public record AuthorizationRow(String authorizationId, String authorizationNo, String actionType, String subjectKind,
            String subjectId, String targetId, String deviceId, String channel, String reason, String requestedBy,
            OffsetDateTime requestedAt, String approvedBy, OffsetDateTime approvedAt, String decisionNote,
            OffsetDateTime validFrom, OffsetDateTime validUntil, String status, String executionCommandId,
            String resultCode, String resultDetail, String policyVersion, String ownerOrgId, String districtId,
            String sourceMode, long version, String requestedByName, String approvedByName) { }

    public record EventRow(String eventId, String authorizationId, String eventKind, String actorId, String note,
            String snapshot, OffsetDateTime occurredAt) { }
}
