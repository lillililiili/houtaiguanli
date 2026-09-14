package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 人工写操作触及的阶段 2 目标表：锁行、条件更新版本、迁移 link、新建分裂目标、写类别修订。
 * 只有人工动作会递增 target.version（决策 8-6）；引擎每帧写入不走这里。
 */
@Repository
public class TargetWriteRepository {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final NamedParameterJdbcTemplate jdbc;

    public TargetWriteRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public TargetHead lock(String targetId) {
        List<TargetHead> rows = jdbc.query("SELECT target_id,target_no,object_type_code,subtype,uav_sn,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,version"
                + " FROM target WHERE target_id=:t FOR UPDATE", Map.of("t", targetId), TargetWriteRepository::head);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 条件更新：版本不符即 0 行，调用方按 VERSION_CONFLICT 处理，不会把类别写到已被他人改动的目标上。 */
    public int updateClass(String targetId, long expectedVersion, String classCode, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("expected", expectedVersion); p.put("code", classCode); p.put("at", at);
        return jdbc.update("UPDATE target SET object_type_code=:code, updated_at=:at, version=version+1 WHERE target_id=:t AND version=:expected", p);
    }

    public int bumpVersion(String targetId, long expectedVersion, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("expected", expectedVersion); p.put("at", at);
        return jdbc.update("UPDATE target SET updated_at=:at, version=version+1 WHERE target_id=:t AND version=:expected", p);
    }

    public void insertClassificationRevision(String revisionId, String targetId, String previousClass, String newClass, String note, String actorId, long targetVersion, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", revisionId); p.put("t", targetId); p.put("prev", previousClass); p.put("new", newClass); p.put("note", note); p.put("actor", actorId); p.put("v", targetVersion); p.put("at", at);
        jdbc.update("INSERT INTO target_classification_revision (revision_id,target_id,previous_class_code,new_class_code,note,actor_id,target_version,created_at)"
                + " VALUES (:id,:t,:prev,:new,:note,:actor,:v,:at)", p);
    }

    public String latestRevisionId(String targetId, long targetVersion) {
        List<String> rows = jdbc.queryForList("SELECT revision_id FROM target_classification_revision WHERE target_id=:t AND target_version=:v",
                Map.of("t", targetId, "v", targetVersion), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateClassificationConfidence(String targetId, BigDecimal confidence, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("c", confidence); p.put("at", at);
        jdbc.update("UPDATE target_latest_state SET classification_confidence=:c, updated_at=:at WHERE target_id=:t", p);
    }

    public List<LinkRow> links(String targetId) {
        return jdbc.query("SELECT link_id,target_id,source_id,external_target_id FROM target_source_link WHERE target_id=:t ORDER BY link_id ASC",
                Map.of("t", targetId), (rs, i) -> new LinkRow(rs.getString("link_id"), rs.getString("target_id"), rs.getString("source_id"), rs.getString("external_target_id")));
    }

    /** 分裂：把选中的 link 迁到新目标。原 link 行不复制不删除，历史轨迹仍挂在同一 link 上。 */
    public int moveLink(String linkId, String newTargetId) {
        return jdbc.update("UPDATE target_source_link SET target_id=:t WHERE link_id=:l", Map.of("t", newTargetId, "l", linkId));
    }

    public void insertTarget(String targetId, String targetNo, TargetHead origin, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("no", targetNo); p.put("type", origin.objectTypeCode()); p.put("subtype", origin.subtype()); p.put("sn", origin.uavSn());
        p.put("first", origin.firstSeenAt()); p.put("last", origin.lastSeenAt()); p.put("mode", origin.sourceMode()); p.put("org", origin.ownerOrgId());
        p.put("district", origin.districtId()); p.put("at", at);
        jdbc.update("INSERT INTO target (target_id,target_no,object_type_code,subtype,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " VALUES (:t,:no,:type,:subtype,:sn,:first,:last,:mode,:org,:district,:at,:at,0)", p);
    }

    /** 目标编号：目标-yyyyMMdd-序号，同日顺延；唯一约束兜底。 */
    public String nextTargetNo(OffsetDateTime at) {
        String prefix = "目标-" + DAY.format(at.atZoneSameInstant(java.time.ZoneOffset.UTC)) + "-";
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM target WHERE target_no LIKE :p", Map.of("p", prefix + "%"), Long.class);
        long next = (count == null ? 0 : count) + 1;
        while (exists(prefix + String.format("%04d", next))) next++;
        return prefix + String.format("%04d", next);
    }

    private boolean exists(String targetNo) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM target WHERE target_no=:n", Map.of("n", targetNo), Long.class);
        return count != null && count > 0;
    }

    private static TargetHead head(ResultSet rs, int ignored) throws SQLException {
        return new TargetHead(rs.getString("target_id"), rs.getString("target_no"), rs.getString("object_type_code"), rs.getString("subtype"), rs.getString("uav_sn"),
                rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"), FusionConfigRepository.time(rs, "first_seen_at"),
                FusionConfigRepository.time(rs, "last_seen_at"), rs.getLong("version"));
    }

    public record TargetHead(String targetId, String targetNo, String objectTypeCode, String subtype, String uavSn, String sourceMode, String ownerOrgId, String districtId,
            OffsetDateTime firstSeenAt, OffsetDateTime lastSeenAt, long version) { }
    public record LinkRow(String linkId, String targetId, String sourceId, String externalTargetId) { }
}
