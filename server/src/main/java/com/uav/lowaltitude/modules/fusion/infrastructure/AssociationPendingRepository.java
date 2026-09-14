package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 待定关联：门限歧义、分裂候选、合并候选的连续帧计数；落定或过期只写 resolved_at/resolution。 */
@Repository
public class AssociationPendingRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AssociationPendingRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public record PendingRow(String pendingId, String domainKey, String observationId, String candidateIdsJson, String reason, String pendingKey,
            Instant firstSeenAt, Instant lastSeenAt, int framesSeen) { }

    public PendingRow findOpen(String domainKey, String pendingKey) {
        List<PendingRow> rows = jdbc.query("SELECT pending_id,fusion_domain_key,observation_id,CAST(candidate_target_ids AS VARCHAR) AS candidates,reason,pending_key,first_seen_at,last_seen_at,frames_seen"
                + " FROM association_pending WHERE fusion_domain_key=:domain AND pending_key=:key AND resolved_at IS NULL", Map.of("domain", domainKey, "key", pendingKey), AssociationPendingRepository::row);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<PendingRow> listOpen(String domainKey, String reason) {
        return jdbc.query("SELECT pending_id,fusion_domain_key,observation_id,CAST(candidate_target_ids AS VARCHAR) AS candidates,reason,pending_key,first_seen_at,last_seen_at,frames_seen"
                + " FROM association_pending WHERE fusion_domain_key=:domain AND reason=:reason AND resolved_at IS NULL ORDER BY first_seen_at, pending_id",
                Map.of("domain", domainKey, "reason", reason), AssociationPendingRepository::row);
    }

    public String insert(String domainKey, String observationId, String candidateIdsJson, String reason, String pendingKey, Instant at) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("domain", domainKey); p.put("observation", observationId); p.put("candidates", candidateIdsJson); p.put("reason", reason);
        p.put("key", pendingKey); p.put("at", Timestamp.from(at));
        jdbc.update("INSERT INTO association_pending (pending_id,fusion_domain_key,observation_id,candidate_target_ids,reason,pending_key,first_seen_at,last_seen_at,frames_seen,created_at)"
                + " VALUES (:id,:domain,:observation,CAST(:candidates AS JSON),:reason,:key,:at,:at,1,:at)", p);
        return id;
    }

    public void touch(String pendingId, Instant lastSeenAt, int framesSeen) {
        jdbc.update("UPDATE association_pending SET last_seen_at=:at, frames_seen=:frames WHERE pending_id=:id",
                Map.of("id", pendingId, "at", Timestamp.from(lastSeenAt), "frames", framesSeen));
    }

    public void resolve(String pendingId, String resolution, Instant at) {
        jdbc.update("UPDATE association_pending SET resolved_at=:at, resolution=:resolution WHERE pending_id=:id AND resolved_at IS NULL",
                Map.of("id", pendingId, "at", Timestamp.from(at), "resolution", resolution));
    }

    private static PendingRow row(ResultSet rs, int i) throws SQLException {
        return new PendingRow(rs.getString("pending_id"), rs.getString("fusion_domain_key"), rs.getString("observation_id"), rs.getString("candidates"), rs.getString("reason"),
                rs.getString("pending_key"), rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(), rs.getInt("frames_seen"));
    }
}
