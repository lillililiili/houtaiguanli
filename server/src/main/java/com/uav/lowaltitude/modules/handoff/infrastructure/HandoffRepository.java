package com.uav.lowaltitude.modules.handoff.infrastructure;

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
public class HandoffRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public HandoffRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /* ---- 接收方目录 ---- */

    public List<RecipientRow> enabledRecipients(String handoffType) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT recipient_id,display_name,handoff_type FROM handoff_recipient WHERE enabled=TRUE";
        if (handoffType != null) { sql += " AND handoff_type=:type"; params.put("type", handoffType); }
        return jdbc.query(sql + " ORDER BY recipient_id ASC", params, HandoffRepository::recipient);
    }

    public boolean anyEnabledRecipient(String handoffType) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM handoff_recipient WHERE enabled=TRUE AND handoff_type=:type",
                Map.of("type", handoffType), Long.class);
        return count != null && count > 0;
    }

    /**
     * 该类型标了默认的那个接收方（决策 18-14）；没有就返回 null。
     *
     * 取 MIN(recipient_id) 兜底不是随便挑：迁移只会标一个默认，但库是共享的、有人手工多标一个也不会报错，
     * 那时"随处理顺序变"比"稳定地取同一个"更难查——同一条风险两次通知可能落到不同接收方。
     */
    public RecipientRow findDefaultRecipient(String handoffType) {
        List<RecipientRow> rows = jdbc.query("SELECT recipient_id,display_name,handoff_type FROM handoff_recipient"
                + " WHERE handoff_type=:type AND enabled=TRUE AND is_default=TRUE"
                + " ORDER BY recipient_id FETCH FIRST 1 ROWS ONLY",
                Map.of("type", handoffType), HandoffRepository::recipient);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 该类型**恰好只有一个**启用接收方时返回它，否则返回 null（决策 18-16）。
     *
     * 取两行再判断，而不是 COUNT 后再查一次：两条语句之间目录可能被改，
     * "数出 1 个"和"取到的那一个"就未必是同一件事。
     */
    public RecipientRow findSoleEnabledRecipient(String handoffType) {
        List<RecipientRow> rows = jdbc.query("SELECT recipient_id,display_name,handoff_type FROM handoff_recipient"
                + " WHERE handoff_type=:type AND enabled=TRUE ORDER BY recipient_id FETCH FIRST 2 ROWS ONLY",
                Map.of("type", handoffType), HandoffRepository::recipient);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    public RecipientRow findEnabledRecipient(String recipientId, String handoffType) {
        List<RecipientRow> rows = jdbc.query("SELECT recipient_id,display_name,handoff_type FROM handoff_recipient"
                + " WHERE enabled=TRUE AND handoff_type=:type AND recipient_id=:id", Map.of("type", handoffType, "id", recipientId),
                HandoffRepository::recipient);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ---- 写入 ---- */

    public boolean logicalExists(String sourceKind, String sourceId, String handoffType, String recipientId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE source_kind=:kind AND source_id=:source"
                + " AND handoff_type=:type AND recipient_id=:recipient",
                Map.of("kind", sourceKind, "source", sourceId, "type", handoffType, "recipient", recipientId), Long.class);
        return count != null && count > 0;
    }

    public void insertHandoff(HandoffInsert row) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", row.handoffId()); params.put("kind", row.sourceKind()); params.put("source", row.sourceId());
        params.put("risk", row.riskId()); params.put("event", row.eventId()); params.put("type", row.handoffType());
        params.put("recipient", row.recipientId()); params.put("version", row.sourceVersion()); params.put("org", row.ownerOrgId());
        params.put("district", row.districtId()); params.put("mode", row.sourceMode()); params.put("submitter", row.submittedBy());
        params.put("created", row.createdAt());
        jdbc.update("INSERT INTO handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,source_version,"
                + "owner_org_id,district_id,source_mode,submitted_by,created_at) VALUES (:id,:kind,:source,:risk,:event,:type,:recipient,"
                + ":version,:org,:district,:mode,:submitter,:created)", params);
    }

    /**
     * 投递之后回填处理结果（决策 18-14）。交接行在投递之前就落库了，所以这里是一次 UPDATE 而不是插入时带上——
     * 反过来把插入推迟到投递之后，会让"渠道没接通"时连交接记录都没有，材料就白提交了。
     */
    public void updateReceiptResult(String handoffId, String receiptResult) {
        jdbc.update("UPDATE handoff SET receipt_result=:result WHERE handoff_id=:id",
                Map.of("id", handoffId, "result", receiptResult));
    }

    public void insertSnapshot(String handoffId, int schemaVersion, String json, OffsetDateTime at) {
        // CAST(:snapshot AS JSON) 在 PostgreSQL 与 H2 都可写入；H2 会把字符串包成 JSON 文本，读取侧统一解包。
        jdbc.update("INSERT INTO handoff_material_snapshot (handoff_id,schema_version,snapshot,created_at)"
                + " VALUES (:id,:schema,CAST(:snapshot AS JSON),:created)",
                Map.of("id", handoffId, "schema", schemaVersion, "snapshot", json, "created", at));
    }

    public void insertDelivery(DeliveryInsert row) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", row.deliveryId()); params.put("handoff", row.handoffId()); params.put("attempt", row.attemptNo());
        params.put("delivery", row.deliveryStatus()); params.put("receipt", row.receiptStatus()); params.put("blocked", row.blockedReason());
        params.put("created", row.createdAt()); params.put("submitted", row.submittedAt()); params.put("delivered", row.deliveredAt());
        params.put("acknowledged", row.acknowledgedAt());
        jdbc.update("INSERT INTO handoff_delivery (delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,blocked_reason,"
                + "created_at,submitted_at,delivered_at,acknowledged_at) VALUES (:id,:handoff,:attempt,:delivery,:receipt,:blocked,"
                + ":created,:submitted,:delivered,:acknowledged)", params);
    }

    /* ---- 读取 ---- */

    public long count(HandoffQuery query, AccessDecision access) {
        Where where = where(query, access);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from() + where.sql, where.params, Long.class);
        return total == null ? 0 : total;
    }

    public List<HandoffRow> list(HandoffQuery query, AccessDecision access, int offset, int size) {
        Where where = where(query, access);
        where.params.put("offset", offset); where.params.put("size", size);
        return jdbc.query(select() + from() + where.sql
                + " ORDER BY h.created_at DESC,h.handoff_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                where.params, HandoffRepository::handoff);
    }

    public HandoffRow find(String handoffId, AccessDecision access) {
        Where where = where(HandoffQuery.empty(), access);
        where.sql.append(" AND h.handoff_id=:handoff_id"); where.params.put("handoff_id", handoffId);
        List<HandoffRow> rows = jdbc.query(select() + from() + where.sql, where.params, HandoffRepository::handoff);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SnapshotRow snapshot(String handoffId) {
        List<SnapshotRow> rows = jdbc.query("SELECT schema_version,CAST(snapshot AS VARCHAR) AS snapshot_text"
                + " FROM handoff_material_snapshot WHERE handoff_id=:id", Map.of("id", handoffId),
                (rs, ignored) -> new SnapshotRow(rs.getInt("schema_version"), rs.getString("snapshot_text")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countDeliveries(String handoffId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM handoff_delivery WHERE handoff_id=:id", Map.of("id", handoffId), Long.class);
        return total == null ? 0 : total;
    }

    public List<DeliveryRow> deliveries(String handoffId, int offset, int size) {
        return jdbc.query(deliverySelect() + " WHERE d.handoff_id=:id ORDER BY d.attempt_no ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                Map.of("id", handoffId, "offset", offset, "size", size), HandoffRepository::delivery);
    }

    public DeliveryRow latestDelivery(String handoffId) {
        List<DeliveryRow> rows = jdbc.query(deliverySelect() + " WHERE d.handoff_id=:id ORDER BY d.attempt_no DESC FETCH FIRST 1 ROW ONLY",
                Map.of("id", handoffId), HandoffRepository::delivery);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ---- SQL 片段 ---- */

    private Where where(HandoffQuery query, AccessDecision access) {
        Where where = scope(access);
        add(where, "h.source_kind", "kind", query.sourceKind);
        add(where, "h.source_id", "source", query.sourceId);
        add(where, "d.delivery_status", "delivery", query.deliveryStatus);
        add(where, "d.receipt_status", "receipt", query.receiptStatus);
        add(where, "h.source_mode", "mode", query.sourceMode);
        if (query.createdFrom != null) {
            where.sql.append(" AND h.created_at>=:created_from AND h.created_at<:created_to");
            where.params.put("created_from", query.createdFrom); where.params.put("created_to", query.createdTo);
        }
        return where;
    }

    private static Where scope(AccessDecision access) {
        Where where = new Where();
        // 交接归属复制自源风险；ALL 仍要求归属目录存在且启用，ASSIGNED 必须同一授权行同时命中组织和区域。
        where.sql.append(" WHERE EXISTS (SELECT 1 FROM app_org ho JOIN app_district hd ON hd.district_id=h.district_id"
                + " WHERE ho.org_id=h.owner_org_id AND ho.enabled=TRUE AND hd.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            where.sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope gs JOIN app_org o ON o.org_id=gs.org_id AND o.enabled=TRUE"
                    + " JOIN app_district d2 ON d2.district_id=gs.district_id AND d2.enabled=TRUE WHERE gs.user_id=:scope_user"
                    + " AND gs.org_id=h.owner_org_id AND gs.district_id=h.district_id)");
            where.params.put("scope_user", access.userId());
        }
        return where;
    }

    private static void add(Where where, String column, String name, String value) {
        if (value != null) { where.sql.append(" AND ").append(column).append("=:").append(name); where.params.put(name, value); }
    }
    private static String select() {
        return "SELECT h.handoff_id,h.source_kind,h.source_id,h.handoff_type,h.recipient_id,rc.display_name,h.source_version,h.owner_org_id,"
                + "h.district_id,h.source_mode,h.submitted_by,h.created_at,h.receipt_result,d.delivery_status,d.receipt_status,d.blocked_reason,"
                + "org_ref.name AS owner_org_name,dist_ref.name AS district_name,su.name AS submitted_by_name,"
                // 来源业务编号：风险取来源风险编号，无人机事件取其告警的来源告警编号。
                + "COALESCE(fr.source_risk_id,al.source_alarm_id) AS source_no";
    }
    private static String from() {
        // delivery_status 指最新一次尝试；列表、详情与 count 共用同一联接，避免口径漂移。
        return " FROM handoff h JOIN handoff_recipient rc ON rc.recipient_id=h.recipient_id"
                + " JOIN handoff_delivery d ON d.handoff_id=h.handoff_id"
                + " AND d.attempt_no=(SELECT MAX(x.attempt_no) FROM handoff_delivery x WHERE x.handoff_id=h.handoff_id)"
                + " LEFT JOIN app_org org_ref ON org_ref.org_id=h.owner_org_id LEFT JOIN app_district dist_ref ON dist_ref.district_id=h.district_id"
                + " LEFT JOIN app_user su ON su.user_id=h.submitted_by LEFT JOIN flight_risk fr ON fr.risk_id=h.risk_id"
                + " LEFT JOIN uav_event ue ON ue.event_id=h.event_id LEFT JOIN alarm al ON al.alarm_id=ue.alarm_id";
    }
    private static String deliverySelect() {
        return "SELECT d.delivery_id,d.handoff_id,d.attempt_no,d.delivery_status,d.receipt_status,d.blocked_reason,d.created_at,"
                + "d.submitted_at,d.delivered_at,d.acknowledged_at FROM handoff_delivery d";
    }
    private static RecipientRow recipient(ResultSet rs, int ignored) throws SQLException {
        return new RecipientRow(rs.getString("recipient_id"), rs.getString("display_name"), rs.getString("handoff_type"));
    }
    private static HandoffRow handoff(ResultSet rs, int ignored) throws SQLException {
        return new HandoffRow(rs.getString("handoff_id"), rs.getString("source_kind"), rs.getString("source_id"), rs.getString("handoff_type"),
                rs.getString("recipient_id"), rs.getString("display_name"), rs.getLong("source_version"), rs.getString("owner_org_id"),
                rs.getString("district_id"), rs.getString("source_mode"), rs.getString("submitted_by"), time(rs, "created_at"),
                rs.getString("delivery_status"), rs.getString("receipt_status"), rs.getString("receipt_result"), rs.getString("blocked_reason"),
                rs.getString("owner_org_name"), rs.getString("district_name"), rs.getString("submitted_by_name"), rs.getString("source_no"));
    }
    private static DeliveryRow delivery(ResultSet rs, int ignored) throws SQLException {
        return new DeliveryRow(rs.getString("delivery_id"), rs.getString("handoff_id"), rs.getInt("attempt_no"), rs.getString("delivery_status"),
                rs.getString("receipt_status"), rs.getString("blocked_reason"), time(rs, "created_at"), time(rs, "submitted_at"),
                time(rs, "delivered_at"), time(rs, "acknowledged_at"));
    }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); if (value == null) return null;
        if (value instanceof OffsetDateTime t) return t; if (value instanceof ZonedDateTime t) return t.toOffsetDateTime();
        if (value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof LocalDateTime t) return t.atOffset(ZoneOffset.UTC); return OffsetDateTime.parse(value.toString());
    }

    private static final class Where { final StringBuilder sql = new StringBuilder(); final Map<String, Object> params = new HashMap<>(); }
    public record HandoffQuery(String sourceKind, String sourceId, String deliveryStatus, OffsetDateTime createdFrom, OffsetDateTime createdTo,
            String sourceMode, String receiptStatus) {
        public HandoffQuery(String sourceKind, String sourceId, String deliveryStatus, OffsetDateTime createdFrom, OffsetDateTime createdTo, String sourceMode) {
            this(sourceKind, sourceId, deliveryStatus, createdFrom, createdTo, sourceMode, null);
        }
        public static HandoffQuery empty() { return new HandoffQuery(null, null, null, null, null, null); }
    }
    public record RecipientRow(String recipientId, String displayName, String handoffType) { }
    public record HandoffRow(String handoffId, String sourceKind, String sourceId, String handoffType, String recipientId, String recipientName,
            long sourceVersion, String ownerOrgId, String districtId, String sourceMode, String submittedBy, OffsetDateTime createdAt,
            String deliveryStatus, String receiptStatus, String receiptResult, String blockedReason,
            String ownerOrgName, String districtName, String submittedByName, String sourceNo) { }
    public record DeliveryRow(String deliveryId, String handoffId, int attemptNo, String deliveryStatus, String receiptStatus, String blockedReason,
            OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime deliveredAt, OffsetDateTime acknowledgedAt) { }
    public record SnapshotRow(int schemaVersion, String json) { }
    /* ---- 处罚交接材料包 v2 的取数（决策 14-2）。只读别人模块的表，不改它们。 ---- */

    /** 事件 + 其告警的事实：处罚认定"何时发生了什么"的依据。 */
    public EventMaterialRow eventMaterial(String eventId) {
        List<EventMaterialRow> rows = jdbc.query("SELECT e.event_id,e.alarm_id,e.state_code,e.owner_org_id,e.district_id,"
                + "e.version,a.source_alarm_id,a.alarm_type,a.severity,a.occurred_at,a.received_at,a.source_mode,a.target_id"
                + " FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=:id",
                Map.of("id", eventId), (rs, i) -> new EventMaterialRow(rs.getString("event_id"), rs.getString("alarm_id"),
                        rs.getString("source_alarm_id"), rs.getString("alarm_type"), rs.getString("severity"),
                        rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("received_at", OffsetDateTime.class),
                        rs.getString("state_code"), rs.getString("target_id"), rs.getString("owner_org_id"),
                        rs.getString("district_id"), rs.getString("source_mode"), rs.getLong("version")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 核实历史全量：谁在什么时候认定它属实。 */
    public List<EventVerificationRow> eventVerifications(String eventId) {
        return jdbc.query("SELECT v.conclusion,v.note,v.resulting_state,v.version,v.created_at,v.actor_id,u.name AS actor_name"
                + " FROM uav_event_verification v LEFT JOIN app_user u ON u.user_id=v.actor_id"
                + " WHERE v.event_id=:id ORDER BY v.created_at ASC, v.history_id ASC",
                Map.of("id", eventId), (rs, i) -> new EventVerificationRow(rs.getString("conclusion"), rs.getString("note"),
                        rs.getString("resulting_state"), rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("actor_id"),
                        rs.getString("actor_name")));
    }

    /**
     * 该事件的全部**终态**授权。只取终态：还在申请或执行中的授权说明不了"已经处置过"，
     * 把它们冻进材料包会让读卷宗的人以为当时已经处置完成。
     */
    public List<DisposalMaterialRow> eventDisposals(String eventId) {
        return jdbc.query("SELECT d.authorization_id,d.authorization_no,d.action_type,d.channel,d.device_id,d.status,"
                + "ru.name AS requested_by_name,au.name AS approved_by_name,d.valid_from,d.valid_until,"
                + "d.result_code,d.result_detail,"
                // 完成时刻取事件流里 COMPLETE/MANUAL_RESULT 的发生时刻（决策 14-26）。
                // 原先拿 updated_at 冒充：那是"这行最后被改动的时间"，作废、回执、任何一次更新都会推它，
                // 落到卷宗上就成了一个说不出依据的"完成时间"。取不到就省略键，不猜。
                + "(SELECT MIN(e.occurred_at) FROM disposal_authorization_event e"
                + "   WHERE e.authorization_id=d.authorization_id"
                + "   AND e.event_kind IN ('COMPLETE','MANUAL_RESULT')) AS completed_at"
                + " FROM disposal_authorization d"
                + " LEFT JOIN app_user ru ON ru.user_id=d.requested_by"
                + " LEFT JOIN app_user au ON au.user_id=d.approved_by"
                + " WHERE d.subject_kind='UAV_EVENT' AND d.subject_id=:id"
                + " AND d.status IN ('COMPLETED','FAILED','STOPPED','EXPIRED','CANCELLED','REJECTED')"
                + " ORDER BY d.requested_at ASC, d.authorization_id ASC",
                Map.of("id", eventId), (rs, i) -> new DisposalMaterialRow(rs.getString("authorization_id"),
                        rs.getString("authorization_no"), rs.getString("action_type"), rs.getString("channel"),
                        rs.getString("device_id"), rs.getString("status"), rs.getString("requested_by_name"),
                        rs.getString("approved_by_name"), rs.getObject("valid_from", OffsetDateTime.class),
                        rs.getObject("valid_until", OffsetDateTime.class), rs.getString("result_code"),
                        rs.getString("result_detail"), rs.getObject("completed_at", OffsetDateTime.class)));
    }

    /** 事件主体上关联的证据（只读协作者 A 的表，不写）。 */
    public List<EvidenceMaterialRow> eventEvidence(String eventId) {
        return jdbc.query("SELECT f.evidence_id,f.evidence_no,f.kind_code,f.sha256,f.captured_at,f.status"
                + " FROM evidence_link l JOIN evidence_file f ON f.evidence_id=l.evidence_id"
                + " WHERE l.subject_kind='EVENT' AND l.subject_id=:id"
                + " ORDER BY f.captured_at ASC, f.evidence_id ASC",
                Map.of("id", eventId), (rs, i) -> new EvidenceMaterialRow(rs.getString("evidence_id"),
                        rs.getString("evidence_no"), rs.getString("kind_code"), rs.getString("sha256"),
                        rs.getObject("captured_at", OffsetDateTime.class), rs.getString("status")));
    }

    public record EventMaterialRow(String eventId, String alarmId, String sourceAlarmId, String alarmType, String severity,
            OffsetDateTime occurredAt, OffsetDateTime receivedAt, String state, String targetId, String ownerOrgId,
            String districtId, String sourceMode, long version) { }
    public record EventVerificationRow(String conclusion, String note, String resultingState, long version,
            OffsetDateTime createdAt, String actorId, String actorName) { }
    public record DisposalMaterialRow(String authorizationId, String authorizationNo, String actionType, String channel,
            String deviceId, String status, String requestedByName, String approvedByName, OffsetDateTime validFrom,
            OffsetDateTime validUntil, String resultCode, String resultDetail, OffsetDateTime completedAt) { }
    public record EvidenceMaterialRow(String evidenceId, String evidenceNo, String kindCode, String sha256,
            OffsetDateTime capturedAt, String status) { }

    public record HandoffInsert(String handoffId, String sourceKind, String sourceId, String riskId, String eventId, String handoffType,
            String recipientId, long sourceVersion, String ownerOrgId, String districtId, String sourceMode, String submittedBy,
            OffsetDateTime createdAt) { }
    public record DeliveryInsert(String deliveryId, String handoffId, int attemptNo, String deliveryStatus, String receiptStatus,
            String blockedReason, OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime deliveredAt, OffsetDateTime acknowledgedAt) { }
}
