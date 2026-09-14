package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;

/**
 * 血缘（target_lineage 只增）、当前别名（target_current_alias）与轨迹状态（target_track_status，E1 迁移 052）。
 * 读取都以目标的精确范围元组为谓词，复用阶段 2 的范围写法，不另造一套。
 */
@Repository
public class LineageRepository {
    private static final String LINEAGE_SELECT = "SELECT g.lineage_id,g.op,g.occurred_at,g.survivor_target_id,g.origin_target_id,g.member_target_ids,g.source_target_ids,g.basis,"
            + "g.algo_version,g.config_version,g.operator_kind,g.operator_id,g.note,g.snapshots,g.created_at FROM target_lineage g";
    private final NamedParameterJdbcTemplate jdbc;

    public LineageRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public TrackStatusRow findTrackStatus(String targetId) {
        List<TrackStatusRow> rows = jdbc.query("SELECT target_id,status,since,confirm_hits,miss_frames,last_observed_at,updated_at,version FROM target_track_status WHERE target_id=:t",
                Map.of("t", targetId), (rs, i) -> new TrackStatusRow(rs.getString("target_id"), rs.getString("status"), FusionConfigRepository.time(rs, "since"), rs.getInt("confirm_hits"),
                        rs.getInt("miss_frames"), FusionConfigRepository.time(rs, "last_observed_at"), FusionConfigRepository.time(rs, "updated_at"), rs.getLong("version")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 人工合并/分裂改写成员状态：只写 status/since/updated_at，不碰 E1 的命中计数。 */
    public void upsertTrackStatus(String targetId, String status, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("s", status); p.put("at", at);
        int updated = jdbc.update("UPDATE target_track_status SET status=:s, since=:at, updated_at=:at, version=version+1 WHERE target_id=:t", p);
        if (updated == 0) {
    jdbc.update("INSERT INTO target_track_status (target_id,status,since,confirm_hits,miss_frames,last_observed_at,updated_at,version) VALUES (:t,:s,:at,0,0,NULL,:at,0)", p);
        }
    }

    public AliasRow findAlias(String historicalTargetId) {
        List<AliasRow> rows = jdbc.query("SELECT historical_target_id,current_target_id,lineage_id,updated_at FROM target_current_alias WHERE historical_target_id=:t", Map.of("t", historicalTargetId),
                (rs, i) -> new AliasRow(rs.getString("historical_target_id"), rs.getString("current_target_id"), rs.getString("lineage_id"), FusionConfigRepository.time(rs, "updated_at")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsertAlias(String historicalTargetId, String currentTargetId, String lineageId, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("h", historicalTargetId); p.put("c", currentTargetId); p.put("l", lineageId); p.put("at", at);
        int updated = jdbc.update("UPDATE target_current_alias SET current_target_id=:c, lineage_id=:l, updated_at=:at WHERE historical_target_id=:h", p);
        if (updated == 0) jdbc.update("INSERT INTO target_current_alias (historical_target_id,current_target_id,lineage_id,updated_at) VALUES (:h,:c,:l,:at)", p);
    }

    /** 已被并入别的目标的历史目标：把指向旧 survivor 的别名一并转到新 survivor（链式合并保持可解析）。 */
    public int redirectAliases(String oldCurrent, String newCurrent, String lineageId, OffsetDateTime at) {
        Map<String, Object> p = new HashMap<>();
        p.put("o", oldCurrent); p.put("n", newCurrent); p.put("l", lineageId); p.put("at", at);
        return jdbc.update("UPDATE target_current_alias SET current_target_id=:n, lineage_id=:l, updated_at=:at WHERE current_target_id=:o", p);
    }

    public void insertLineage(LineageInsert g) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", g.lineageId()); p.put("op", g.op()); p.put("occurred", g.occurredAt()); p.put("survivor", g.survivorTargetId()); p.put("origin", g.originTargetId());
        p.put("members", g.memberTargetIdsJson()); p.put("sources", g.sourceTargetIdsJson()); p.put("basis", g.basisJson()); p.put("algo", g.algoVersion()); p.put("cfg", g.configVersion());
        p.put("okind", g.operatorKind()); p.put("oid", g.operatorId()); p.put("note", g.note()); p.put("snap", g.snapshotsJson()); p.put("created", g.createdAt());
        jdbc.update("INSERT INTO target_lineage (lineage_id,op,occurred_at,survivor_target_id,origin_target_id,member_target_ids,source_target_ids,basis,algo_version,config_version,operator_kind,operator_id,note,snapshots,created_at)"
                + " VALUES (:id,:op,:occurred,:survivor,:origin,CAST(:members AS JSON),CAST(:sources AS JSON),CAST(:basis AS JSON),:algo,:cfg,:okind,:oid,:note,CAST(:snap AS JSON),:created)", p);
    }

    /**
     * 与目标相关的血缘：作为 survivor、origin 或成员/来源出现。成员列表是 JSON 数组，H2 与 PostgreSQL 的 JSON 运算符不同，
     * 这里统一用文本包含判断；H2 把 CAST(? AS JSON) 存成带转义的 JSON 字符串，所以不能按 "id" 带引号匹配，只能匹配裸 ID。
     * 目标 ID 是 UUID/不可猜标识，裸串包含不会误命中其它目标。
     */
    public long countLineage(String targetId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + lineageFrom(), params(targetId), Long.class);
        return count == null ? 0 : count;
    }

    public List<LineageRow> listLineage(String targetId, int offset, int size) {
        Map<String, Object> p = params(targetId);
        p.put("offset", offset); p.put("size", size);
        return jdbc.query(LINEAGE_SELECT + " " + lineageFrom().replace("FROM target_lineage g", "") + " ORDER BY g.occurred_at ASC, g.lineage_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", p, LineageRepository::lineage);
    }

    public LineageRow lastLineage(String targetId) {
        Map<String, Object> p = params(targetId);
        List<LineageRow> rows = jdbc.query(LINEAGE_SELECT + " " + lineageFrom().replace("FROM target_lineage g", "") + " ORDER BY g.occurred_at DESC, g.lineage_id DESC FETCH FIRST 1 ROW ONLY", p, LineageRepository::lineage);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> params(String targetId) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId);
        p.put("contained", "%" + targetId + "%");
        return p;
    }

    private static String lineageFrom() {
        return "FROM target_lineage g WHERE g.survivor_target_id=:t OR g.origin_target_id=:t"
                + " OR CAST(g.member_target_ids AS VARCHAR) LIKE :contained OR CAST(g.source_target_ids AS VARCHAR) LIKE :contained";
    }

    /** 目标是否在当前用户范围内（精确元组 + 归属完整），复用阶段 2 目标读取谓词。 */
    public boolean targetVisible(String targetId, AccessDecision access, TargetReadRepository targets) {
        return targets.findTarget(targetId, access) != null;
    }

    private static LineageRow lineage(ResultSet rs, int ignored) throws SQLException {
        return new LineageRow(rs.getString("lineage_id"), rs.getString("op"), FusionConfigRepository.time(rs, "occurred_at"), rs.getString("survivor_target_id"), rs.getString("origin_target_id"),
                FusionConfigRepository.jsonText(rs.getObject("member_target_ids")), FusionConfigRepository.jsonText(rs.getObject("source_target_ids")), FusionConfigRepository.jsonText(rs.getObject("basis")),
                rs.getString("algo_version"), rs.getString("config_version"), rs.getString("operator_kind"), rs.getString("operator_id"), rs.getString("note"),
                FusionConfigRepository.jsonText(rs.getObject("snapshots")), FusionConfigRepository.time(rs, "created_at"));
    }

    public record TrackStatusRow(String targetId, String status, OffsetDateTime since, int confirmHits, int missFrames, OffsetDateTime lastObservedAt, OffsetDateTime updatedAt, long version) { }
    public record AliasRow(String historicalTargetId, String currentTargetId, String lineageId, OffsetDateTime updatedAt) { }
    public record LineageRow(String lineageId, String op, OffsetDateTime occurredAt, String survivorTargetId, String originTargetId, String memberTargetIdsJson, String sourceTargetIdsJson,
            String basisJson, String algoVersion, String configVersion, String operatorKind, String operatorId, String note, String snapshotsJson, OffsetDateTime createdAt) { }
    public record LineageInsert(String lineageId, String op, OffsetDateTime occurredAt, String survivorTargetId, String originTargetId, String memberTargetIdsJson, String sourceTargetIdsJson,
            String basisJson, String algoVersion, String configVersion, String operatorKind, String operatorId, String note, String snapshotsJson, OffsetDateTime createdAt) { }
}
