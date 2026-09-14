package com.uav.lowaltitude.modules.punishment.infrastructure;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

/**
 * 处罚案件持久化。事件流与复核只增：本类不提供任何 UPDATE/DELETE 它们的方法，PG 侧另有触发器兜底。
 * 只读别的模块的表（交接、事件、证据），不写它们。
 */
@Repository
public class PunishmentRepository {
    private static final String CASE_COLUMNS = "c.case_id,c.case_no,c.event_id,c.handoff_id,c.status,c.party_type,"
            + "c.party_name,c.officer_id,c.officer_name,c.primary_violation_code,c.filed_by,c.filed_by_name,c.filed_at,"
            + "c.decided_at,c.closed_at,c.close_note,c.withdraw_reason,c.owner_org_id,c.district_id,c.source_mode,c.version";

    private final NamedParameterJdbcTemplate jdbc;
    /** 只用于"建当日计数行"：与阶段 13 同一理由，见 nextSequence。 */
    private final TransactionTemplate newTransaction;

    public PunishmentRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactions) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /* ---- 编号（决策 14-6，写法同 13-5）---- */

    /**
     * 发号：先确保当日计数行存在，再对该行加锁自增。
     *
     * 两处沿用阶段 13 的结论：不用 ON CONFLICT（H2 的 PostgreSQL 兼容模式不解析）；
     * 建行放在**独立事务**里，因为并发下撞唯一键会把 PostgreSQL 的整个事务打成 aborted（25P02），
     * 等于把并发立案写成偶发 500。计数行不是业务数据，独立提交没有副作用；号段空缺无所谓——要的是不重复。
     */
    public int nextSequence(String dayKey) {
        newTransaction.executeWithoutResult(status -> {
            try {
                jdbc.update("INSERT INTO punishment_no_counter (day_key,next_no) SELECT :day,1"
                        + " WHERE NOT EXISTS (SELECT 1 FROM punishment_no_counter WHERE day_key=:day)",
                        Map.of("day", dayKey));
            } catch (DataIntegrityViolationException raced) {
                status.setRollbackOnly();
            }
        });
        Integer next = jdbc.queryForObject("SELECT next_no FROM punishment_no_counter WHERE day_key=:day FOR UPDATE",
                Map.of("day", dayKey), Integer.class);
        if (next == null) throw new IllegalStateException("punishment number counter missing for " + dayKey);
        jdbc.update("UPDATE punishment_no_counter SET next_no=next_no+1 WHERE day_key=:day", Map.of("day", dayKey));
        return next;
    }

    /* ---- 案件 ---- */

    public void insertCase(CaseInsert row) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.caseId()); p.put("no", row.caseNo()); p.put("event", row.eventId());
        p.put("handoff", row.handoffId()); p.put("status", row.status()); p.put("party", row.partyType());
        p.put("name", row.partyName()); p.put("by", row.filedBy()); p.put("byName", row.filedByName());
        p.put("at", row.filedAt()); p.put("org", row.ownerOrgId()); p.put("district", row.districtId());
        p.put("mode", row.sourceMode());
        jdbc.update("INSERT INTO punishment_case (case_id,case_no,event_id,handoff_id,status,party_type,party_name,"
                + "filed_by,filed_by_name,filed_at,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " VALUES (:id,:no,:event,:handoff,:status,:party,:name,:by,:byName,:at,:org,:district,:mode,0,:at,:at)", p);
    }

    public CaseRow lock(String caseId, AccessDecision access) { return one(caseId, access, true); }
    public CaseRow find(String caseId, AccessDecision access) { return one(caseId, access, false); }

    private CaseRow one(String caseId, AccessDecision access, boolean lock) {
        Where where = scope(access);
        where.params.put("id", caseId);
        List<CaseRow> rows = jdbc.query("SELECT " + CASE_COLUMNS + " FROM punishment_case c" + where.sql
                + " AND c.case_id=:id" + (lock ? " FOR UPDATE" : ""), where.params, PunishmentRepository::caseRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<CaseRow> list(AccessDecision access, String status, String eventId, String handoffId, int offset, int limit) {
        Where where = filter(access, status, eventId, handoffId);
        where.params.put("limit", limit); where.params.put("offset", offset);
        return jdbc.query("SELECT " + CASE_COLUMNS + " FROM punishment_case c" + where.sql
                + " ORDER BY c.filed_at DESC, c.case_id DESC LIMIT :limit OFFSET :offset",
                where.params, PunishmentRepository::caseRow);
    }

    public long count(AccessDecision access, String status, String eventId, String handoffId) {
        Where where = filter(access, status, eventId, handoffId);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM punishment_case c" + where.sql, where.params, Long.class);
        return total == null ? 0 : total;
    }

    /** 状态迁移：只在版本未变时落笔，返回 0 表示已被别人改过，调用方据此报 409。 */
    public int transition(String caseId, long expectedVersion, String status, OffsetDateTime at,
                          Map<String, Object> extraColumns) {
        StringBuilder sql = new StringBuilder("UPDATE punishment_case SET status=:status,version=version+1,updated_at=:at");
        Map<String, Object> p = new HashMap<>(extraColumns);
        p.put("id", caseId); p.put("v", expectedVersion); p.put("status", status); p.put("at", at);
        for (String column : extraColumns.keySet()) sql.append(',').append(column).append("=:").append(column);
        sql.append(" WHERE case_id=:id AND version=:v");
        return jdbc.update(sql.toString(), p);
    }

    public void insertCaseEvent(String eventId, String caseId, String kind, String actorId, String actorName,
                                String note, String snapshotJson, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", eventId); p.put("case", caseId); p.put("kind", kind); p.put("actor", actorId);
        p.put("actorName", actorName); p.put("note", note); p.put("snapshot", snapshotJson); p.put("at", at);
        jdbc.update("INSERT INTO punishment_case_event (event_id,case_id,event_kind,actor_id,actor_name,note,snapshot,occurred_at)"
                + " VALUES (:id,:case,:kind,:actor,:actorName,:note,CAST(:snapshot AS JSON),:at)", p);
    }

    public List<CaseEventRow> caseEvents(String caseId) {
        return jdbc.query("SELECT event_id,case_id,event_kind,actor_id,actor_name,note,snapshot,occurred_at"
                + " FROM punishment_case_event WHERE case_id=:id ORDER BY occurred_at ASC, event_id ASC",
                Map.of("id", caseId), (rs, i) -> new CaseEventRow(rs.getString("event_id"), rs.getString("case_id"),
                        rs.getString("event_kind"), rs.getString("actor_id"), rs.getString("actor_name"),
                        rs.getString("note"), rs.getString("snapshot"),
                        rs.getObject("occurred_at", OffsetDateTime.class)));
    }

    /* ---- 线索 ---- */

    public void insertLead(String leadId, String caseId, String kind, String description, String createdBy,
                           OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", leadId); p.put("case", caseId); p.put("kind", kind); p.put("desc", description);
        p.put("by", createdBy); p.put("at", at);
        jdbc.update("INSERT INTO punishment_case_lead (lead_id,case_id,kind,description,resolved,created_by,created_at)"
                + " VALUES (:id,:case,:kind,:desc,FALSE,:by,:at)", p);
    }

    public int resolveLead(String leadId, String caseId, String note, String resolvedBy, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", leadId); p.put("case", caseId); p.put("note", note); p.put("by", resolvedBy); p.put("at", at);
        return jdbc.update("UPDATE punishment_case_lead SET resolved=TRUE,resolved_note=:note,resolved_by=:by,"
                + "resolved_at=:at WHERE lead_id=:id AND case_id=:case AND resolved=FALSE", p);
    }

    public List<LeadRow> leads(String caseId, Boolean resolved) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", caseId);
        String sql = "SELECT lead_id,case_id,kind,description,resolved,resolved_note,created_at,resolved_at"
                + " FROM punishment_case_lead WHERE case_id=:id";
        if (resolved != null) { sql += " AND resolved=:resolved"; p.put("resolved", resolved); }
        return jdbc.query(sql + " ORDER BY created_at ASC, lead_id ASC", p, (rs, i) -> new LeadRow(
                rs.getString("lead_id"), rs.getString("case_id"), rs.getString("kind"), rs.getString("description"),
                rs.getBoolean("resolved"), rs.getString("resolved_note"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("resolved_at", OffsetDateTime.class)));
    }

    /* ---- 裁量 ---- */

    public int nextDiscretionVersion(String caseId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM penalty_discretion WHERE case_id=:id",
                Map.of("id", caseId), Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    /** 旧草稿作废：一案同时只能有一份待确认的裁量，否则"以哪一版为准"说不清。 */
    public int supersedeDrafts(String caseId) {
        return jdbc.update("UPDATE penalty_discretion SET status='SUPERSEDED' WHERE case_id=:id AND status='DRAFT'",
                Map.of("id", caseId));
    }

    public int supersedeConfirmed(String caseId) {
        return jdbc.update("UPDATE penalty_discretion SET status='SUPERSEDED' WHERE case_id=:id AND status='CONFIRMED'",
                Map.of("id", caseId));
    }

    public void insertDiscretion(DiscretionInsert row) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.discretionId()); p.put("case", row.caseId()); p.put("v", row.versionNo());
        p.put("violation", row.violationCode()); p.put("rule", row.ruleCode()); p.put("type", row.penaltyType());
        p.put("amount", row.fineAmount()); p.put("factors", row.factorsJson()); p.put("basis", row.basisText());
        p.put("by", row.draftedBy()); p.put("at", row.draftedAt());
        jdbc.update("INSERT INTO penalty_discretion (discretion_id,case_id,version_no,status,violation_code,rule_code,"
                + "penalty_type,fine_amount,factors,basis_text,drafted_by,drafted_at)"
                + " VALUES (:id,:case,:v,'DRAFT',:violation,:rule,:type,:amount,CAST(:factors AS JSON),:basis,:by,:at)", p);
    }

    public int confirmDiscretion(String discretionId, String caseId, String decidedBy, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", discretionId); p.put("case", caseId); p.put("by", decidedBy); p.put("at", at);
        return jdbc.update("UPDATE penalty_discretion SET status='CONFIRMED',decided_by=:by,decided_at=:at"
                + " WHERE discretion_id=:id AND case_id=:case AND status='DRAFT'", p);
    }

    public DiscretionRow discretion(String discretionId, String caseId) {
        List<DiscretionRow> rows = jdbc.query(discretionSelect() + " WHERE d.discretion_id=:id AND d.case_id=:case",
                Map.of("id", discretionId, "case", caseId), PunishmentRepository::discretionRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前有效裁量：优先已确认的，其次草稿——页面要显示"现在以哪一版为准"。 */
    public DiscretionRow currentDiscretion(String caseId) {
        List<DiscretionRow> rows = jdbc.query(discretionSelect() + " WHERE d.case_id=:case AND d.status IN ('CONFIRMED','DRAFT')"
                + " ORDER BY CASE d.status WHEN 'CONFIRMED' THEN 0 ELSE 1 END, d.version_no DESC",
                Map.of("case", caseId), PunishmentRepository::discretionRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String discretionSelect() {
        return "SELECT d.discretion_id,d.case_id,d.version_no,d.status,d.violation_code,d.rule_code,d.penalty_type,"
                + "d.fine_amount,d.factors,d.basis_text,d.drafted_by,d.drafted_at,d.decided_by,d.decided_at"
                + " FROM penalty_discretion d";
    }

    /* ---- 罚则 ---- */

    public List<RuleRow> rules() {
        return jdbc.query("SELECT rule_code,violation_code,title,legal_basis,fine_min,fine_max,fine_reference,"
                + "penalty_types,schema_status,enabled FROM penalty_rule WHERE enabled=TRUE ORDER BY rule_code ASC",
                Map.of(), PunishmentRepository::ruleRow);
    }

    public RuleRow rule(String ruleCode) {
        List<RuleRow> rows = jdbc.query("SELECT rule_code,violation_code,title,legal_basis,fine_min,fine_max,"
                + "fine_reference,penalty_types,schema_status,enabled FROM penalty_rule WHERE rule_code=:code AND enabled=TRUE",
                Map.of("code", ruleCode), PunishmentRepository::ruleRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ---- 决定书 ---- */

    public int nextDocumentSequence(String caseId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM penalty_decision_document WHERE case_id=:id",
                Map.of("id", caseId), Integer.class);
        return (count == null ? 0 : count) + 1;
    }

    public void insertDocument(DocumentInsert row) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.documentId()); p.put("no", row.documentNo()); p.put("case", row.caseId());
        p.put("discretion", row.discretionId()); p.put("template", row.templateVersion());
        p.put("fields", row.fieldsJson()); p.put("sha", row.renderedSha256()); p.put("by", row.issuedBy());
        p.put("byName", row.issuedByName()); p.put("at", row.issuedAt());
        jdbc.update("INSERT INTO penalty_decision_document (document_id,document_no,case_id,discretion_id,template_version,"
                + "status,fields,rendered_sha256,issued_by,issued_by_name,issued_at,version,updated_at)"
                + " VALUES (:id,:no,:case,:discretion,:template,'ISSUED',CAST(:fields AS JSON),:sha,:by,:byName,:at,0,:at)", p);
    }

    public int revokeDocument(String documentId, long expectedVersion, String reason, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", documentId); p.put("v", expectedVersion); p.put("reason", reason); p.put("at", at);
        return jdbc.update("UPDATE penalty_decision_document SET status='REVOKED',revoked_at=:at,revoke_reason=:reason,"
                + "version=version+1,updated_at=:at WHERE document_id=:id AND version=:v AND status='ISSUED'", p);
    }

    public DocumentRow document(String documentId) {
        List<DocumentRow> rows = jdbc.query(documentSelect() + " WHERE f.document_id=:id",
                Map.of("id", documentId), PunishmentRepository::documentRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DocumentRow> documents(String caseId) {
        return jdbc.query(documentSelect() + " WHERE f.case_id=:case ORDER BY f.issued_at ASC, f.document_id ASC",
                Map.of("case", caseId), PunishmentRepository::documentRow);
    }

    public int issuedDocumentCount(String caseId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM penalty_decision_document"
                + " WHERE case_id=:id AND status='ISSUED'", Map.of("id", caseId), Integer.class);
        return count == null ? 0 : count;
    }

    private static String documentSelect() {
        return "SELECT f.document_id,f.document_no,f.case_id,f.discretion_id,f.template_version,f.status,f.fields,"
                + "f.rendered_sha256,f.issued_by,f.issued_by_name,f.issued_at,f.revoked_at,f.revoke_reason,f.version"
                + " FROM penalty_decision_document f";
    }

    /* ---- 复核 ---- */

    public void insertReview(String reviewId, String caseId, String reviewerId, String reviewerName,
                             String conclusion, String note, String missingLeadsJson, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", reviewId); p.put("case", caseId); p.put("reviewer", reviewerId); p.put("name", reviewerName);
        p.put("conclusion", conclusion); p.put("note", note); p.put("leads", missingLeadsJson); p.put("at", at);
        jdbc.update("INSERT INTO punishment_review (review_id,case_id,reviewer_id,reviewer_name,conclusion,note,"
                + "missing_leads,created_at) VALUES (:id,:case,:reviewer,:name,:conclusion,:note,CAST(:leads AS JSON),:at)", p);
    }

    /* ---- 只读别人的表 ---- */

    /** 交接必须是处罚类型且调用者可见（决策 14-5）；范围与交接表同一口径。 */
    public HandoffRefRow punishmentHandoff(String handoffId, AccessDecision access) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", handoffId);
        StringBuilder sql = new StringBuilder("SELECT h.handoff_id,h.event_id,h.handoff_type,h.owner_org_id,"
                + "h.district_id,h.source_mode FROM handoff h WHERE h.handoff_id=:id AND h.handoff_type='UAV_PUNISHMENT'"
                + " AND h.event_id IS NOT NULL");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs WHERE gs.user_id=:scope_user"
                    + " AND gs.org_id=h.owner_org_id AND gs.district_id=h.district_id)");
            params.put("scope_user", access.userId());
        }
        List<HandoffRefRow> rows = jdbc.query(sql.toString(), params, (rs, i) -> new HandoffRefRow(
                rs.getString("handoff_id"), rs.getString("event_id"), rs.getString("handoff_type"),
                rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("source_mode")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 承办人必须是可见且启用的用户（契约 §2.2）。 */
    public String enabledUserName(String userId) {
        List<String> names = jdbc.queryForList("SELECT name FROM app_user WHERE user_id=:id AND status='ACTIVE'",
                Map.of("id", userId), String.class);
        return names.isEmpty() ? null : names.get(0);
    }

    /* ---- 范围与映射 ---- */

    private static Where filter(AccessDecision access, String status, String eventId, String handoffId) {
        Where where = scope(access);
        add(where, "c.status", "f_status", status);
        add(where, "c.event_id", "f_event", eventId);
        add(where, "c.handoff_id", "f_handoff", handoffId);
        return where;
    }

    private static Where scope(AccessDecision access) {
        Where where = new Where();
        where.sql.append(" WHERE EXISTS (SELECT 1 FROM app_org co JOIN app_district cd ON cd.district_id=c.district_id"
                + " WHERE co.org_id=c.owner_org_id AND co.enabled=TRUE AND cd.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            where.sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs JOIN app_org o ON o.org_id=gs.org_id AND o.enabled=TRUE"
                    + " JOIN app_district d2 ON d2.district_id=gs.district_id AND d2.enabled=TRUE WHERE gs.user_id=:scope_user"
                    + " AND gs.org_id=c.owner_org_id AND gs.district_id=c.district_id)");
            where.params.put("scope_user", access.userId());
        }
        return where;
    }

    private static void add(Where where, String column, String name, String value) {
        if (value != null) { where.sql.append(" AND ").append(column).append("=:").append(name); where.params.put(name, value); }
    }

    private static CaseRow caseRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new CaseRow(rs.getString("case_id"), rs.getString("case_no"), rs.getString("event_id"),
                rs.getString("handoff_id"), rs.getString("status"), rs.getString("party_type"), rs.getString("party_name"),
                rs.getString("officer_id"), rs.getString("officer_name"), rs.getString("primary_violation_code"),
                rs.getString("filed_by"), rs.getString("filed_by_name"), rs.getObject("filed_at", OffsetDateTime.class),
                rs.getObject("decided_at", OffsetDateTime.class), rs.getObject("closed_at", OffsetDateTime.class),
                rs.getString("close_note"), rs.getString("withdraw_reason"), rs.getString("owner_org_id"),
                rs.getString("district_id"), rs.getString("source_mode"), rs.getLong("version"));
    }

    private static DiscretionRow discretionRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new DiscretionRow(rs.getString("discretion_id"), rs.getString("case_id"), rs.getInt("version_no"),
                rs.getString("status"), rs.getString("violation_code"), rs.getString("rule_code"),
                rs.getString("penalty_type"), rs.getLong("fine_amount"), rs.getString("factors"),
                rs.getString("basis_text"), rs.getString("drafted_by"), rs.getObject("drafted_at", OffsetDateTime.class),
                rs.getString("decided_by"), rs.getObject("decided_at", OffsetDateTime.class));
    }

    private static DocumentRow documentRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new DocumentRow(rs.getString("document_id"), rs.getString("document_no"), rs.getString("case_id"),
                rs.getString("discretion_id"), rs.getString("template_version"), rs.getString("status"),
                rs.getString("fields"), rs.getString("rendered_sha256"), rs.getString("issued_by"),
                rs.getString("issued_by_name"), rs.getObject("issued_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoke_reason"), rs.getLong("version"));
    }

    private static RuleRow ruleRow(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new RuleRow(rs.getString("rule_code"), rs.getString("violation_code"), rs.getString("title"),
                rs.getString("legal_basis"), rs.getLong("fine_min"), rs.getLong("fine_max"),
                rs.getObject("fine_reference") == null ? null : rs.getLong("fine_reference"),
                rs.getString("penalty_types"), rs.getString("schema_status"), rs.getBoolean("enabled"));
    }

    private static final class Where {
        private final StringBuilder sql = new StringBuilder();
        private final Map<String, Object> params = new HashMap<>();
    }

    public record CaseInsert(String caseId, String caseNo, String eventId, String handoffId, String status,
            String partyType, String partyName, String filedBy, String filedByName, OffsetDateTime filedAt,
            String ownerOrgId, String districtId, String sourceMode) { }

    public record CaseRow(String caseId, String caseNo, String eventId, String handoffId, String status,
            String partyType, String partyName, String officerId, String officerName, String primaryViolationCode,
            String filedBy, String filedByName, OffsetDateTime filedAt, OffsetDateTime decidedAt,
            OffsetDateTime closedAt, String closeNote, String withdrawReason, String ownerOrgId, String districtId,
            String sourceMode, long version) { }

    public record CaseEventRow(String eventId, String caseId, String eventKind, String actorId, String actorName,
            String note, String snapshot, OffsetDateTime occurredAt) { }

    public record LeadRow(String leadId, String caseId, String kind, String description, boolean resolved,
            String resolvedNote, OffsetDateTime createdAt, OffsetDateTime resolvedAt) { }

    public record DiscretionInsert(String discretionId, String caseId, int versionNo, String violationCode,
            String ruleCode, String penaltyType, long fineAmount, String factorsJson, String basisText,
            String draftedBy, OffsetDateTime draftedAt) { }

    public record DiscretionRow(String discretionId, String caseId, int versionNo, String status, String violationCode,
            String ruleCode, String penaltyType, long fineAmount, String factorsJson, String basisText,
            String draftedBy, OffsetDateTime draftedAt, String decidedBy, OffsetDateTime decidedAt) { }

    public record DocumentInsert(String documentId, String documentNo, String caseId, String discretionId,
            String templateVersion, String fieldsJson, String renderedSha256, String issuedBy, String issuedByName,
            OffsetDateTime issuedAt) { }

    public record DocumentRow(String documentId, String documentNo, String caseId, String discretionId,
            String templateVersion, String status, String fieldsJson, String renderedSha256, String issuedBy,
            String issuedByName, OffsetDateTime issuedAt, OffsetDateTime revokedAt, String revokeReason, long version) { }

    public record RuleRow(String ruleCode, String violationCode, String title, String legalBasis, long fineMin,
            long fineMax, Long fineReference, String penaltyTypes, String schemaStatus, boolean enabled) { }

    public record HandoffRefRow(String handoffId, String eventId, String handoffType, String ownerOrgId,
            String districtId, String sourceMode) { }
}
