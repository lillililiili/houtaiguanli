package com.uav.lowaltitude.modules.evidence.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

@Repository
public class EvidenceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public boolean catalogEnabled(String orgId, String districtId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_org o JOIN app_district d ON d.district_id=:district AND d.enabled=TRUE
                WHERE o.org_id=:org AND o.enabled=TRUE
                """, Map.of("org", orgId, "district", districtId), Long.class);
        return count != null && count > 0;
    }

    public SubjectRef findSubject(String kind, String id) {
        String sql = switch (kind) {
            case "EVENT" -> "SELECT event_id AS id, event_id AS no, owner_org_id, district_id FROM uav_event WHERE event_id=:id";
            case "TARGET" -> "SELECT target_id AS id, target_no AS no, owner_org_id, district_id FROM target WHERE target_id=:id";
            case "PLAN" -> "SELECT plan_id AS id, plan_no AS no, owner_org_id, district_id FROM flight_plan WHERE plan_id=:id";
            case "DEVICE" -> """
                    SELECT d.device_id AS id, d.device_no AS no, s.owner_org_id, s.district_id
                    FROM ops_device d JOIN device_business_scope s ON s.ops_device_id=d.device_id
                    WHERE d.device_id=:id""";
            case "COMMAND" -> """
                    SELECT c.command_id AS id, c.command_no AS no, s.owner_org_id, s.district_id
                    FROM device_command c JOIN ops_device d ON d.device_id=c.device_id
                    JOIN device_business_scope s ON s.ops_device_id=d.device_id
                    WHERE c.command_id=:id""";
            case "COMMISSION" -> """
                    SELECT t.commission_id AS id, t.commission_no AS no, s.owner_org_id, s.district_id
                    FROM commission_task t JOIN ops_device d ON d.device_id=t.device_id
                    JOIN device_business_scope s ON s.ops_device_id=d.device_id
                    WHERE t.commission_id=:id""";
            case "CASE" -> """
                    SELECT case_id AS id, case_no AS no, owner_org_id, district_id
                    FROM punishment_case WHERE case_id=:id""";
            case "AUTHORIZATION" -> """
                    SELECT authorization_id AS id, authorization_no AS no, owner_org_id, district_id
                    FROM disposal_authorization WHERE authorization_id=:id""";
            default -> null;
        };
        if (sql == null) return null;
        List<SubjectRef> rows = jdbc.query(sql, Map.of("id", id), (rs, i) -> new SubjectRef(
                rs.getString("id"), rs.getString("no"), rs.getString("owner_org_id"), rs.getString("district_id")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean subjectVisible(String kind, String id, AccessDecision access) {
        SubjectRef subject = findSubject(kind, id);
        if (subject == null || subject.ownerOrgId() == null || subject.districtId() == null) return false;
        if (!catalogEnabled(subject.ownerOrgId(), subject.districtId())) return false;
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM app_user_data_scope ds
                    JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE
                    JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE
                    WHERE ds.user_id=:user AND ds.org_id=:org AND ds.district_id=:district
                    """, Map.of("user", access.userId(), "org", subject.ownerOrgId(), "district", subject.districtId()), Long.class);
            return count != null && count > 0;
        }
        return true;
    }

    public void insertFile(FileInsert row) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", row.evidenceId()); p.put("no", row.evidenceNo()); p.put("kind", row.kindCode());
        p.put("name", row.originalName()); p.put("type", row.contentType()); p.put("backend", row.storageBackend());
        p.put("key", row.objectKey()); p.put("size", row.sizeBytes()); p.put("sha", row.sha256());
        p.put("captured", ts(row.capturedAt())); p.put("stored", ts(row.storedAt()));
        p.put("retain", ts(row.retainUntil())); p.put("status", row.status());
        p.put("mode", row.sourceMode()); p.put("org", row.ownerOrgId()); p.put("district", row.districtId());
        p.put("created", ts(row.createdAt())); p.put("updated", ts(row.updatedAt())); p.put("version", row.version());
        jdbc.update("""
                INSERT INTO evidence_file (evidence_id,evidence_no,kind_code,original_name,content_type,storage_backend,
                    object_key,size_bytes,sha256,captured_at,stored_at,retain_until,status,source_mode,owner_org_id,district_id,
                    created_at,updated_at,version)
                VALUES (:id,:no,:kind,:name,:type,:backend,:key,:size,:sha,:captured,:stored,:retain,:status,:mode,:org,:district,
                    :created,:updated,:version)
                """, p);
    }

    public void updateAvailable(String evidenceId, long size, String sha256, Instant storedAt, Instant updatedAt, long version) {
        jdbc.update("""
                UPDATE evidence_file SET size_bytes=:size, sha256=:sha, stored_at=:stored, status='AVAILABLE',
                    updated_at=:updated, version=:version WHERE evidence_id=:id
                """, Map.of("id", evidenceId, "size", size, "sha", sha256, "stored", ts(storedAt),
                "updated", ts(updatedAt), "version", version));
    }

    public int updateStatus(String evidenceId, String status, Instant updatedAt, long expectedVersion) {
        return jdbc.update("""
                UPDATE evidence_file SET status=:status, updated_at=:updated, version=version+1
                WHERE evidence_id=:id AND version=:version
                """, Map.of("id", evidenceId, "status", status, "updated", ts(updatedAt), "version", expectedVersion));
    }

    public int markDestroyed(String evidenceId, String destroyedBy, String reason, String approval,
            Instant destroyedAt, long expectedVersion) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", evidenceId);
        p.put("by", destroyedBy);
        p.put("reason", reason);
        p.put("approval", approval);
        p.put("at", ts(destroyedAt));
        p.put("version", expectedVersion);
        return jdbc.update("""
                UPDATE evidence_file SET status='DESTROYED', destroyed_at=:at, destroyed_by=:by,
                    destroy_reason=:reason, destroy_approval=:approval, updated_at=:at, version=version+1
                WHERE evidence_id=:id AND version=:version AND status<>'DESTROYED'
                """, p);
    }

    public long count(FileQuery query, AccessDecision access, boolean ingest) {
        Where where = where(query, access, ingest);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_file f" + where.sql, where.params, Long.class);
        return total == null ? 0 : total;
    }

    public List<FileRow> list(FileQuery query, AccessDecision access, boolean ingest, int offset, int size) {
        Where where = where(query, access, ingest);
        where.params.put("offset", offset);
        where.params.put("size", size);
        return jdbc.query("SELECT " + FILE_COLUMNS + " FROM evidence_file f" + where.sql
                + " ORDER BY f.captured_at DESC NULLS LAST, f.stored_at DESC NULLS LAST, f.evidence_id DESC"
                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.params, EvidenceRepository::file);
    }

    public FileRow find(String evidenceId) {
        List<FileRow> rows = jdbc.query("SELECT " + FILE_COLUMNS + " FROM evidence_file f WHERE f.evidence_id=:id",
                Map.of("id", evidenceId), EvidenceRepository::file);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<LinkRow> links(String evidenceId) {
        return jdbc.query("""
                SELECT link_id,evidence_id,subject_kind,subject_id,event_id,device_id,target_id,plan_id,command_id,commission_id,created_at
                FROM evidence_link WHERE evidence_id=:id ORDER BY created_at ASC, link_id ASC
                """, Map.of("id", evidenceId), EvidenceRepository::link);
    }

    public List<HoldRow> holds(String evidenceId) {
        return jdbc.query("""
                SELECT hold_id,evidence_id,event_id,held_by,reason,released_at,released_by,created_at
                FROM evidence_hold WHERE evidence_id=:id ORDER BY created_at DESC, hold_id DESC
                """, Map.of("id", evidenceId), EvidenceRepository::hold);
    }

    public boolean hasActiveHold(String evidenceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_hold WHERE evidence_id=:id AND released_at IS NULL",
                Map.of("id", evidenceId), Long.class);
        return count != null && count > 0;
    }

    public void insertLink(String linkId, String evidenceId, String kind, String subjectId, Instant createdAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", linkId); p.put("evidence", evidenceId); p.put("kind", kind); p.put("subject", subjectId);
        p.put("created", ts(createdAt));
        p.put("event", null); p.put("device", null); p.put("target", null);
        p.put("plan", null); p.put("command", null); p.put("commission", null);
        p.put("caseId", null); p.put("auth", null);
        switch (kind) {
            case "EVENT" -> p.put("event", subjectId);
            case "DEVICE" -> p.put("device", subjectId);
            case "TARGET" -> p.put("target", subjectId);
            case "PLAN" -> p.put("plan", subjectId);
            case "COMMAND" -> p.put("command", subjectId);
            case "COMMISSION" -> p.put("commission", subjectId);
            case "CASE" -> p.put("caseId", subjectId);
            case "AUTHORIZATION" -> p.put("auth", subjectId);
            default -> throw new IllegalArgumentException(kind);
        }
        jdbc.update("""
                INSERT INTO evidence_link (link_id,evidence_id,subject_kind,subject_id,event_id,device_id,target_id,plan_id,command_id,commission_id,case_id,authorization_id,created_at)
                VALUES (:id,:evidence,:kind,:subject,:event,:device,:target,:plan,:command,:commission,:caseId,:auth,:created)
                """, p);
    }

    public boolean linkExists(String evidenceId, String kind, String subjectId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_link WHERE evidence_id=:e AND subject_kind=:k AND subject_id=:s",
                Map.of("e", evidenceId, "k", kind, "s", subjectId), Long.class);
        return count != null && count > 0;
    }

    public void insertHold(String holdId, String evidenceId, String heldBy, String reason, Instant createdAt) {
        jdbc.update("""
                INSERT INTO evidence_hold (hold_id,evidence_id,held_by,reason,created_at)
                VALUES (:id,:evidence,:by,:reason,:created)
                """, Map.of("id", holdId, "evidence", evidenceId, "by", heldBy, "reason", reason, "created", ts(createdAt)));
    }

    public HoldRow findHold(String holdId) {
        List<HoldRow> rows = jdbc.query("""
                SELECT hold_id,evidence_id,event_id,held_by,reason,released_at,released_by,created_at
                FROM evidence_hold WHERE hold_id=:id
                """, Map.of("id", holdId), EvidenceRepository::hold);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int releaseHold(String holdId, String releasedBy, Instant releasedAt) {
        return jdbc.update("""
                UPDATE evidence_hold SET released_at=:at, released_by=:by
                WHERE hold_id=:id AND released_at IS NULL
                """, Map.of("id", holdId, "by", releasedBy, "at", ts(releasedAt)));
    }

    public void insertAccess(String accessId, String evidenceId, String userId, String action, String result,
            String reasonCode, Instant createdAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", accessId); p.put("evidence", evidenceId); p.put("user", userId);
        p.put("action", action); p.put("result", result); p.put("reason", reasonCode); p.put("created", ts(createdAt));
        jdbc.update("""
                INSERT INTO evidence_access_log (access_id,evidence_id,user_id,action,result,reason_code,created_at)
                VALUES (:id,:evidence,:user,:action,:result,:reason,:created)
                """, p);
    }

    public List<AccessRow> accessLogs(String evidenceId, int offset, int size) {
        return jdbc.query("""
                SELECT access_id,evidence_id,user_id,action,result,reason_code,created_at
                FROM evidence_access_log WHERE evidence_id=:id
                ORDER BY created_at DESC, access_id DESC
                OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
                """, Map.of("id", evidenceId, "offset", offset, "size", size), EvidenceRepository::access);
    }

    public long accessCount(String evidenceId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_access_log WHERE evidence_id=:id",
                Map.of("id", evidenceId), Long.class);
        return total == null ? 0 : total;
    }

    public int linkCount(String evidenceId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_link WHERE evidence_id=:id",
                Map.of("id", evidenceId), Long.class);
        return total == null ? 0 : total.intValue();
    }

    private Where where(FileQuery query, AccessDecision access, boolean ingest) {
        StringBuilder sql = new StringBuilder(" WHERE f.owner_org_id IS NOT NULL AND f.district_id IS NOT NULL");
        Map<String, Object> params = new HashMap<>();
        sql.append(" AND EXISTS (SELECT 1 FROM app_org o WHERE o.org_id=f.owner_org_id AND o.enabled=TRUE)");
        sql.append(" AND EXISTS (SELECT 1 FROM app_district d WHERE d.district_id=f.district_id AND d.enabled=TRUE)");
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            sql.append("""
                     AND EXISTS (SELECT 1 FROM app_user_data_scope ds
                     JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE
                     JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE
                     WHERE ds.user_id=:scope_user_id AND ds.org_id=f.owner_org_id AND ds.district_id=f.district_id)
                    """);
            params.put("scope_user_id", access.userId());
        }
        if (!ingest) {
            sql.append(" AND EXISTS (SELECT 1 FROM evidence_link l WHERE l.evidence_id=f.evidence_id)");
        }
        if (query.kindCode() != null) { sql.append(" AND f.kind_code=:kind"); params.put("kind", query.kindCode()); }
        if (query.status() != null) { sql.append(" AND f.status=:status"); params.put("status", query.status()); }
        // 保管状态与读取时 EvidenceRetention.custody 同一口径：冻结优先，其余按到期日与当前时刻比较。
        if (query.custody() != null) {
            String held = "EXISTS (SELECT 1 FROM evidence_hold eh WHERE eh.evidence_id=f.evidence_id AND eh.released_at IS NULL)";
            java.time.Instant now = java.time.Instant.now();
            params.put("custody_now", java.sql.Timestamp.from(now));
            params.put("custody_nearing", java.sql.Timestamp.from(now.plus(java.time.Duration.ofDays(com.uav.lowaltitude.modules.evidence.domain.EvidenceRetention.NEARING_DAYS))));
            switch (query.custody()) {
                case "HELD" -> sql.append(" AND ").append(held);
                case "DUE" -> sql.append(" AND NOT ").append(held).append(" AND f.retain_until IS NOT NULL AND f.retain_until<=:custody_now");
                case "NEARING" -> sql.append(" AND NOT ").append(held).append(" AND f.retain_until IS NOT NULL AND f.retain_until>:custody_now AND f.retain_until<=:custody_nearing");
                default -> sql.append(" AND NOT ").append(held).append(" AND (f.retain_until IS NULL OR f.retain_until>:custody_nearing)");
            }
        }
        if (query.q() != null) {
            sql.append(" AND (LOWER(f.evidence_no) LIKE :q OR LOWER(f.original_name) LIKE :q OR LOWER(f.evidence_id) LIKE :q)");
            params.put("q", "%" + query.q().toLowerCase() + "%");
        }
        if (query.subjectKind() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM evidence_link l WHERE l.evidence_id=f.evidence_id AND l.subject_kind=:subject_kind AND l.subject_id=:subject_id)");
            params.put("subject_kind", query.subjectKind());
            params.put("subject_id", query.subjectId());
        }
        return new Where(sql, params);
    }

    private static final String FILE_COLUMNS = "f.evidence_id,f.evidence_no,f.kind_code,f.original_name,f.content_type,"
            + "f.storage_backend,f.object_key,f.size_bytes,f.sha256,f.captured_at,f.stored_at,f.status,f.retain_until,"
            + "f.source_mode,f.owner_org_id,f.district_id,f.created_at,f.updated_at,f.version,"
            + "f.destroyed_at,f.destroyed_by,f.destroy_reason,f.destroy_approval";

    private static FileRow file(ResultSet rs, int ignored) throws SQLException {
        return new FileRow(rs.getString("evidence_id"), rs.getString("evidence_no"), rs.getString("kind_code"),
                rs.getString("original_name"), rs.getString("content_type"), rs.getString("storage_backend"),
                rs.getString("object_key"), longOrNull(rs, "size_bytes"), rs.getString("sha256"),
                time(rs, "captured_at"), time(rs, "stored_at"), rs.getString("status"), time(rs, "retain_until"),
                rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"),
                time(rs, "created_at"), time(rs, "updated_at"), rs.getLong("version"),
                time(rs, "destroyed_at"), rs.getString("destroyed_by"), rs.getString("destroy_reason"),
                rs.getString("destroy_approval"));
    }

    private static LinkRow link(ResultSet rs, int ignored) throws SQLException {
        return new LinkRow(rs.getString("link_id"), rs.getString("evidence_id"), rs.getString("subject_kind"),
                rs.getString("subject_id"), rs.getString("event_id"), rs.getString("device_id"), rs.getString("target_id"),
                rs.getString("plan_id"), rs.getString("command_id"), rs.getString("commission_id"), time(rs, "created_at"));
    }

    private static HoldRow hold(ResultSet rs, int ignored) throws SQLException {
        return new HoldRow(rs.getString("hold_id"), rs.getString("evidence_id"), rs.getString("event_id"),
                rs.getString("held_by"), rs.getString("reason"), time(rs, "released_at"),
                rs.getString("released_by"), time(rs, "created_at"));
    }

    private static AccessRow access(ResultSet rs, int ignored) throws SQLException {
        return new AccessRow(rs.getString("access_id"), rs.getString("evidence_id"), rs.getString("user_id"),
                rs.getString("action"), rs.getString("result"), rs.getString("reason_code"), time(rs, "created_at"));
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record Where(StringBuilder sql, Map<String, Object> params) { }

    public record FileQuery(String kindCode, String status, String subjectKind, String subjectId, String q, String custody) {
        public FileQuery(String kindCode, String status, String subjectKind, String subjectId, String q) { this(kindCode, status, subjectKind, subjectId, q, null); }
    }

    public record FileInsert(String evidenceId, String evidenceNo, String kindCode, String originalName,
            String contentType, String storageBackend, String objectKey, Long sizeBytes, String sha256,
            Instant capturedAt, Instant storedAt, Instant retainUntil, String status, String sourceMode,
            String ownerOrgId, String districtId, Instant createdAt, Instant updatedAt, long version) { }

    public record FileRow(String evidenceId, String evidenceNo, String kindCode, String originalName,
            String contentType, String storageBackend, String objectKey, Long sizeBytes, String sha256,
            Instant capturedAt, Instant storedAt, String status, Instant retainUntil, String sourceMode,
            String ownerOrgId, String districtId, Instant createdAt, Instant updatedAt, long version,
            Instant destroyedAt, String destroyedBy, String destroyReason, String destroyApproval) { }

    public record LinkRow(String linkId, String evidenceId, String subjectKind, String subjectId, String eventId,
            String deviceId, String targetId, String planId, String commandId, String commissionId, Instant createdAt) { }

    public record HoldRow(String holdId, String evidenceId, String eventId, String heldBy, String reason,
            Instant releasedAt, String releasedBy, Instant createdAt) { }

    public record AccessRow(String accessId, String evidenceId, String userId, String action, String result,
            String reasonCode, Instant createdAt) { }

    public record SubjectRef(String id, String no, String ownerOrgId, String districtId) { }
}
