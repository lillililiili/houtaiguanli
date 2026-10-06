package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.TrackState;

/**
 * 目标身份：target 头行（统一编号、归属元组）、target_track_status、target_lineage（只增）、target_current_alias。
 * Worker 每帧只更新 target.last_seen_at/updated_at，不递增 target.version（决策 8-6：version 留给人工修订/合并/分裂，
 * 否则页面持有的 expected_version 会被后台每帧顶掉）。
 */
@Repository
public class IdentityRepository {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.ofHours(8));
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean postgresql;

    public IdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.postgresql = Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())));
    }

    public record TargetRow(String targetId, String targetNo, String objectTypeCode, Instant firstSeenAt, Instant lastSeenAt, String sourceMode,
            String ownerOrgId, String districtId, boolean unified) {
        public FusionDomainKey domain() { return new FusionDomainKey(sourceMode, ownerOrgId, districtId); }
    }
    public record StatusRow(String targetId, TrackState state, String primarySourceId, long version) { }
    public record ActiveTarget(TargetRow target, StatusRow status) { }

    /**
     * 业务编号：目标-yyyyMMdd-序号（北京时间当天内递增，冲突则顺延）。
     *
     * PostgreSQL 用事务级 advisory lock 串行化同一天的编号分配，避免多个应用实例同时按 COUNT(*)
     * 算出相同编号；H2 测试环境由 synchronized 保证单 JVM 内的同等语义。
     */
    public synchronized String nextTargetNo(Instant at) {
        String prefix = "目标-" + DAY.format(at) + "-";
        if (postgresql) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:lock_key))",
                    Map.of("lock_key", "target-business-no:" + prefix), rs -> { });
        }
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM target WHERE target_no LIKE :prefix", Map.of("prefix", prefix + "%"), Long.class);
        long seq = (existing == null ? 0 : existing) + 1;
        while (true) {
            String candidate = prefix + String.format("%03d", seq);
            Long clash = jdbc.queryForObject("SELECT COUNT(*) FROM target WHERE target_no=:no", Map.of("no", candidate), Long.class);
            if (clash == null || clash == 0) return candidate;
            seq++;
        }
    }

    public void insertTarget(String targetId, String targetNo, String objectTypeCode, String uavSn, Instant firstSeenAt, FusionDomainKey domain, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", targetId); p.put("no", targetNo); p.put("type", objectTypeCode); p.put("sn", uavSn); p.put("first", Timestamp.from(firstSeenAt)); p.put("last", Timestamp.from(firstSeenAt));
        p.put("mode", domain.sourceMode()); p.put("org", domain.ownerOrgId()); p.put("district", domain.districtId()); p.put("now", Timestamp.from(now));
        jdbc.update("INSERT INTO target (target_id,target_no,object_type_code,subtype,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version,unified)"
                + " VALUES (:id,:no,:type,NULL,:sn,:first,:last,:mode,:org,:district,:now,:now,0,TRUE)", p);
    }

    /** 只推进 last_seen_at（迟到帧不回退），不动 version。 */
    public void touchTarget(String targetId, Instant seenAt, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", targetId); p.put("seen", Timestamp.from(seenAt)); p.put("now", Timestamp.from(now));
        jdbc.update("UPDATE target SET first_seen_at=CASE WHEN first_seen_at IS NULL OR first_seen_at>:seen THEN :seen ELSE first_seen_at END,"
                + " last_seen_at=CASE WHEN last_seen_at IS NULL OR last_seen_at<:seen THEN :seen ELSE last_seen_at END, updated_at=:now WHERE target_id=:id", p);
    }

    /** 一帧内被命中的多个目标的 last_seen_at 一次批量推进（ZT-06），口径与 {@link #touchTarget} 相同。 */
    @SuppressWarnings("unchecked")
    public void touchTargets(Map<String, Instant> seenByTarget, Instant now) {
        if (seenByTarget == null || seenByTarget.isEmpty()) return;
        Map<String, Object>[] batch = new Map[seenByTarget.size()];
        int i = 0;
        for (Map.Entry<String, Instant> entry : seenByTarget.entrySet()) {
            Map<String, Object> p = new HashMap<>();
            p.put("id", entry.getKey()); p.put("seen", Timestamp.from(entry.getValue())); p.put("now", Timestamp.from(now));
            batch[i++] = p;
        }
        jdbc.batchUpdate("UPDATE target SET first_seen_at=CASE WHEN first_seen_at IS NULL OR first_seen_at>:seen THEN :seen ELSE first_seen_at END,"
                + " last_seen_at=CASE WHEN last_seen_at IS NULL OR last_seen_at<:seen THEN :seen ELSE last_seen_at END, updated_at=:now WHERE target_id=:id", batch);
    }

    /** 一批目标头行当前的类别（ZT-04）：融合类别与它不同才算改判。值可能为 null（识别中/未定类）。 */
    public Map<String, String> objectTypes(Collection<String> targetIds) {
        Map<String, String> out = new HashMap<>();
        if (targetIds == null || targetIds.isEmpty()) return out;
        jdbc.query("SELECT target_id,object_type_code FROM target WHERE target_id IN (:ids)", Map.of("ids", List.copyOf(targetIds)),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString("target_id"), rs.getString("object_type_code")));
        return out;
    }

    /** 系统改判的一次类别变化：改前（可能为空，即"识别中/未定类"）与改后。 */
    public record ClassRevision(String previousClassCode, String newClassCode) { }

    /**
     * 融合层的类别结论变了，目标头行跟着改（ZT-04）。不递增 version（决策 8-6：系统写入不与人工修订争乐观锁），
     * 人工修订过类别的目标不动（manual_class_override 优先）。真的改了才返回改前改后，没变返回 null——没变就不留变化记录。
     */
    public ClassRevision reviseSystemClass(String targetId, String classCode, Instant now) {
        if (classCode == null) return null;
        List<String> current = jdbc.query("SELECT object_type_code FROM target WHERE target_id=:id", Map.of("id", targetId), (rs, i) -> rs.getString("object_type_code"));
        if (current.isEmpty() || classCode.equals(current.get(0))) return null;
        Map<String, Object> p = new HashMap<>();
        p.put("id", targetId); p.put("type", classCode); p.put("now", Timestamp.from(now));
        int updated = jdbc.update("UPDATE target SET object_type_code=:type, updated_at=:now WHERE target_id=:id"
                + " AND (object_type_code IS NULL OR object_type_code<>:type)"
                + " AND NOT EXISTS (SELECT 1 FROM target_attribute_selection a WHERE a.target_id=:id AND a.manual_class_override=TRUE)", p);
        return updated == 1 ? new ClassRevision(current.get(0), classCode) : null;
    }

    public void setObjectTypeIfMissing(String targetId, String classCode) {
        if (classCode == null) return;
        jdbc.update("UPDATE target SET object_type_code=:type WHERE target_id=:id AND object_type_code IS NULL", Map.of("id", targetId, "type", classCode));
    }

    /** Only explicitly normalized replay observations may supply simulator serial/subtype metadata. */
    public void applySimulatorIdentity(String targetId,String classCode,String serial,String subtype,Instant observedAt) {
        Map<String,Object> p=new HashMap<>();
        p.put("id",targetId);p.put("type",classCode);p.put("sn","UAV".equals(classCode)?serial:null);
        p.put("subtype","UNKNOWN".equals(classCode)?subtype:null);p.put("observed",Timestamp.from(observedAt));
        jdbc.update("UPDATE target SET object_type_code=:type,uav_sn=:sn,subtype=:subtype WHERE target_id=:id AND source_mode='replay' AND last_seen_at<=:observed"
                + " AND NOT EXISTS(SELECT 1 FROM target_attribute_selection a WHERE a.target_id=:id AND a.manual_class_override=TRUE)",p);
    }

    public TargetRow findTarget(String targetId) {
        List<TargetRow> rows = jdbc.query("SELECT target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,unified FROM target WHERE target_id=:id",
                Map.of("id", targetId), IdentityRepository::target);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StatusRow findStatus(String targetId) {
        List<StatusRow> rows = jdbc.query("SELECT target_id,status,since,confirm_hits,miss_frames,last_observed_at,primary_source_id,version FROM target_track_status WHERE target_id=:id",
                Map.of("id", targetId), IdentityRepository::status);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertStatus(String targetId, TrackState state, Instant now) {
        Map<String, Object> p = statusParams(targetId, state, null, now);
        jdbc.update("INSERT INTO target_track_status (target_id,status,since,confirm_hits,miss_frames,last_observed_at,primary_source_id,updated_at,version)"
                + " VALUES (:id,:status,:since,:hits,:misses,:last,:primary,:now,0)", p);
    }

    public void updateStatus(String targetId, TrackState state, String primarySourceId, Instant now) {
        Map<String, Object> p = statusParams(targetId, state, primarySourceId, now);
        jdbc.update("UPDATE target_track_status SET status=:status,since=:since,confirm_hits=:hits,miss_frames=:misses,last_observed_at=:last,primary_source_id=:primary,"
                + "updated_at=:now,version=version+1 WHERE target_id=:id", p);
    }

    /** 一条待写的轨迹状态（字段与 {@link #updateStatus} 相同）。 */
    public record StatusUpdate(String targetId, TrackState state, String primarySourceId) { }

    /** 一帧的目标状态推进一次批量写（ZT-06），语义与逐条 {@link #updateStatus} 相同。 */
    @SuppressWarnings("unchecked")
    public void updateStatuses(List<StatusUpdate> updates, Instant now) {
        if (updates == null || updates.isEmpty()) return;
        Map<String, Object>[] batch = new Map[updates.size()];
        for (int i = 0; i < updates.size(); i++) {
            StatusUpdate update = updates.get(i);
            batch[i] = statusParams(update.targetId(), update.state(), update.primarySourceId(), now);
        }
        jdbc.batchUpdate("UPDATE target_track_status SET status=:status,since=:since,confirm_hits=:hits,miss_frames=:misses,last_observed_at=:last,primary_source_id=:primary,"
                + "updated_at=:now,version=version+1 WHERE target_id=:id", batch);
    }

    /** 同一分区内仍活跃（TENTATIVE/STABLE/SHORT_LOST）的统一目标。 */
    public List<ActiveTarget> activeTargets(FusionDomainKey domain) {
        Map<String, Object> p = new HashMap<>();
        p.put("mode", domain.sourceMode()); p.put("org", domain.ownerOrgId()); p.put("district", domain.districtId());
        String scope = " AND t.source_mode=:mode AND " + (domain.ownerOrgId() == null ? "t.owner_org_id IS NULL" : "t.owner_org_id=:org")
                + " AND " + (domain.districtId() == null ? "t.district_id IS NULL" : "t.district_id=:district");
        return jdbc.query("SELECT t.target_id,t.target_no,t.object_type_code,t.first_seen_at,t.last_seen_at,t.source_mode,t.owner_org_id,t.district_id,t.unified,"
                + "s.status,s.since,s.confirm_hits,s.miss_frames,s.last_observed_at,s.primary_source_id,s.version FROM target t JOIN target_track_status s ON s.target_id=t.target_id"
                + " WHERE t.unified=TRUE AND s.status IN ('TENTATIVE','STABLE','SHORT_LOST')" + scope + " ORDER BY t.first_seen_at ASC, t.target_id ASC", p,
                (rs, i) -> new ActiveTarget(target(rs, i), status(rs, i)));
    }

    public String insertLineage(String op, Instant occurredAt, String survivorId, String originId, String memberIdsJson, String sourceIdsJson, String basisJson,
            String algoVersion, String configVersion, String snapshotsJson) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id); p.put("op", op); p.put("at", Timestamp.from(occurredAt)); p.put("survivor", survivorId); p.put("origin", originId); p.put("members", memberIdsJson);
        p.put("sources", sourceIdsJson); p.put("basis", basisJson); p.put("algo", algoVersion); p.put("config", configVersion); p.put("snapshots", snapshotsJson);
        jdbc.update("INSERT INTO target_lineage (lineage_id,op,occurred_at,survivor_target_id,origin_target_id,member_target_ids,source_target_ids,basis,algo_version,config_version,"
                + "operator_kind,operator_id,note,snapshots,created_at) VALUES (:id,:op,:at,:survivor,:origin,CAST(:members AS JSON),CAST(:sources AS JSON),CAST(:basis AS JSON),"
                + ":algo,:config,'SYSTEM',NULL,NULL,CAST(:snapshots AS JSON),:at)", p);
        return id;
    }

    public void insertAlias(String historicalId, String currentId, String lineageId, Instant now) {
        Map<String, Object> p = Map.of("historical", historicalId, "current", currentId, "lineage", lineageId, "now", Timestamp.from(now));
        jdbc.update("INSERT INTO target_current_alias (historical_target_id,current_target_id,lineage_id,updated_at) VALUES (:historical,:current,:lineage,:now)", p);
        // 更早并入被并目标的别名也要指向新的幸存者，别名链保持单跳。
        jdbc.update("UPDATE target_current_alias SET current_target_id=:current,lineage_id=:lineage,updated_at=:now WHERE current_target_id=:historical", p);
    }

    /** 沿别名解析到当前目标（别名保持单跳，这里仍循环以防旧数据）。 */
    public String resolveAlias(String targetId) {
        String current = targetId;
        for (int hop = 0; hop < 16; hop++) {
            List<String> next = jdbc.queryForList("SELECT current_target_id FROM target_current_alias WHERE historical_target_id=:id", Map.of("id", current), String.class);
            if (next.isEmpty()) return current;
            current = next.get(0);
        }
        return current;
    }

    /** 指向同一当前目标的历史目标（合并成员），用于组装该目标的全部 link 估计。 */
    public List<String> aliasMembers(String currentId) {
        return jdbc.queryForList("SELECT historical_target_id FROM target_current_alias WHERE current_target_id=:id ORDER BY historical_target_id", Map.of("id", currentId), String.class);
    }

    private static Map<String, Object> statusParams(String targetId, TrackState state, String primarySourceId, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", targetId); p.put("status", state.status().name()); p.put("since", Timestamp.from(state.since())); p.put("hits", state.confirmHits());
        p.put("misses", state.missFrames()); p.put("last", state.lastObservedAt() == null ? null : Timestamp.from(state.lastObservedAt())); p.put("primary", primarySourceId);
        p.put("now", Timestamp.from(now));
        return p;
    }

    private static TargetRow target(ResultSet rs, int i) throws SQLException {
        return new TargetRow(rs.getString("target_id"), rs.getString("target_no"), rs.getString("object_type_code"), instant(rs, "first_seen_at"), instant(rs, "last_seen_at"),
                rs.getString("source_mode"), rs.getString("owner_org_id"), rs.getString("district_id"), rs.getBoolean("unified"));
    }

    private static StatusRow status(ResultSet rs, int i) throws SQLException {
        return new StatusRow(rs.getString("target_id"), new TrackState(TrackStatus.valueOf(rs.getString("status")), instant(rs, "since"), rs.getInt("confirm_hits"),
                rs.getInt("miss_frames"), instant(rs, "last_observed_at")), rs.getString("primary_source_id"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
