package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** target_degradation 与 target_attribute_selection：每目标一行，逐帧覆盖；人工修订只改 manual_class_override 与类别。 */
@Repository
public class DegradationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DegradationRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    private static final String SELECT_DEGRADATION = "SELECT target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at FROM target_degradation";

    public DegradationRow findDegradation(String targetId) {
        List<DegradationRow> rows = jdbc.query(SELECT_DEGRADATION + " WHERE target_id=:t", Map.of("t", targetId), DegradationRepository::degradation);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 多个目标的降级行一次取回（ZT-06）；没有行的目标不在结果里。 */
    public Map<String, DegradationRow> findDegradations(Collection<String> targetIds) {
        Map<String, DegradationRow> out = new HashMap<>();
        if (targetIds == null || targetIds.isEmpty()) return out;
        for (DegradationRow row : jdbc.query(SELECT_DEGRADATION + " WHERE target_id IN (:t)", Map.of("t", List.copyOf(targetIds)), DegradationRepository::degradation)) {
            out.put(row.targetId(), row);
        }
        return out;
    }

    private static final String UPDATE_DEGRADATION = "UPDATE target_degradation SET level=:level, available_source_ids=CAST(:available AS JSON), confidence_deficit=:deficit,"
            + " determined=:determined, since=:since, updated_at=:updated WHERE target_id=:t";
    private static final String INSERT_DEGRADATION = "INSERT INTO target_degradation (target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at)"
            + " VALUES (:t,:level,CAST(:available AS JSON),:deficit,:determined,:since,:updated)";

    public void upsertDegradation(String targetId, String level, String availableJson, BigDecimal deficit, boolean determined, OffsetDateTime since, OffsetDateTime updatedAt) {
        upsertDegradations(List.of(new DegradationWrite(targetId, level, availableJson, deficit, determined, since, updatedAt)));
    }

    /** 一条待写的降级行（字段与 {@link #upsertDegradation} 相同）。 */
    public record DegradationWrite(String targetId, String level, String availableJson, BigDecimal deficit, boolean determined, OffsetDateTime since, OffsetDateTime updatedAt) { }

    /** 一帧内多个目标的降级行一次批量写（ZT-06）：先批量 UPDATE，没有命中行的再批量 INSERT。 */
    public void upsertDegradations(List<DegradationWrite> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Map<String, Object>> batch = new ArrayList<>();
        for (DegradationWrite row : rows) {
            Map<String, Object> p = new HashMap<>();
            p.put("t", row.targetId()); p.put("level", row.level()); p.put("available", row.availableJson()); p.put("deficit", row.deficit());
            p.put("determined", row.determined()); p.put("since", row.since()); p.put("updated", row.updatedAt());
            batch.add(p);
        }
        upsert(UPDATE_DEGRADATION, INSERT_DEGRADATION, batch);
    }

    private static final String SELECT_SELECTION = "SELECT target_id,position_source_id,class_source_id,identity_source_id,motion_source_id,class_code,class_confidence,identity_clue,"
            + "selected_at,config_version,manual_class_override,updated_at,version FROM target_attribute_selection";

    public SelectionRow findSelection(String targetId) {
        List<SelectionRow> rows = jdbc.query(SELECT_SELECTION + " WHERE target_id=:t", Map.of("t", targetId), DegradationRepository::selection);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 多个目标的属性优选一次取回（ZT-06）；没有行的目标不在结果里。 */
    public Map<String, SelectionRow> findSelections(Collection<String> targetIds) {
        Map<String, SelectionRow> out = new HashMap<>();
        if (targetIds == null || targetIds.isEmpty()) return out;
        for (SelectionRow row : jdbc.query(SELECT_SELECTION + " WHERE target_id IN (:t)", Map.of("t", List.copyOf(targetIds)), DegradationRepository::selection)) {
            out.put(row.targetId(), row);
        }
        return out;
    }

    private static final String UPDATE_SELECTION = "UPDATE target_attribute_selection SET position_source_id=:pos, class_source_id=:cls, identity_source_id=:idn, motion_source_id=:mot,"
            + " class_code=:code, class_confidence=:conf, identity_clue=:clue, selected_at=:selected, config_version=:cfg, manual_class_override=:override, updated_at=:updated,"
            + " version=version+CASE WHEN COALESCE(class_code,'UNKNOWN')<>COALESCE(:code,'UNKNOWN') THEN 1 ELSE 0 END WHERE target_id=:t";
    private static final String INSERT_SELECTION = "INSERT INTO target_attribute_selection (target_id,position_source_id,class_source_id,identity_source_id,motion_source_id,class_code,"
            + "class_confidence,identity_clue,selected_at,config_version,manual_class_override,updated_at,version)"
            + " VALUES (:t,:pos,:cls,:idn,:mot,:code,:conf,:clue,:selected,:cfg,:override,:updated,0)";

    public void upsertSelection(String targetId, String positionSourceId, String classSourceId, String identitySourceId, String motionSourceId,
            String classCode, BigDecimal classConfidence, String identityClue, OffsetDateTime selectedAt, String configVersion, boolean manualOverride, OffsetDateTime updatedAt) {
        upsertSelections(List.of(new SelectionWrite(targetId, positionSourceId, classSourceId, identitySourceId, motionSourceId, classCode, classConfidence, identityClue,
                selectedAt, configVersion, manualOverride, updatedAt)));
    }

    /** 一条待写的属性优选（字段与 {@link #upsertSelection} 相同）。 */
    public record SelectionWrite(String targetId, String positionSourceId, String classSourceId, String identitySourceId, String motionSourceId,
            String classCode, BigDecimal classConfidence, String identityClue, OffsetDateTime selectedAt, String configVersion, boolean manualOverride, OffsetDateTime updatedAt) { }

    /** 一帧内多个目标的属性优选一次批量写（ZT-06）：先批量 UPDATE（类别变了才递增 version），没有命中行的再批量 INSERT。 */
    public void upsertSelections(List<SelectionWrite> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Map<String, Object>> batch = new ArrayList<>();
        for (SelectionWrite row : rows) {
            Map<String, Object> p = new HashMap<>();
            p.put("t", row.targetId()); p.put("pos", row.positionSourceId()); p.put("cls", row.classSourceId()); p.put("idn", row.identitySourceId()); p.put("mot", row.motionSourceId());
            p.put("code", row.classCode()); p.put("conf", row.classConfidence()); p.put("clue", row.identityClue()); p.put("selected", row.selectedAt()); p.put("cfg", row.configVersion());
            p.put("override", row.manualOverride()); p.put("updated", row.updatedAt());
            batch.add(p);
        }
        upsert(UPDATE_SELECTION, INSERT_SELECTION, batch);
    }

    /** 批量 UPDATE；驱动明确报 0 行的再批量 INSERT（报不出行数的负值按已更新处理）。 */
    @SuppressWarnings("unchecked")
    private void upsert(String updateSql, String insertSql, List<Map<String, Object>> batch) {
        int[] counts = jdbc.batchUpdate(updateSql, batch.toArray(new Map[0]));
        List<Map<String, Object>> missing = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) if (counts[i] == 0) missing.add(batch.get(i));
        if (!missing.isEmpty()) jdbc.batchUpdate(insertSql, missing.toArray(new Map[0]));
    }

    /** 人工修订类别：只改类别相关列并置 manual_class_override，不碰位置/身份主源。 */
    public void markManualClass(String targetId, String classCode, BigDecimal confidence, OffsetDateTime at, String configVersion) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("code", classCode); p.put("conf", confidence); p.put("at", at); p.put("cfg", configVersion);
        int updated = jdbc.update("UPDATE target_attribute_selection SET class_source_id=NULL, class_code=:code, class_confidence=:conf, manual_class_override=TRUE, updated_at=:at, version=version+1 WHERE target_id=:t", p);
        if (updated == 0) {
            jdbc.update("INSERT INTO target_attribute_selection (target_id,class_code,class_confidence,selected_at,config_version,manual_class_override,updated_at,version) VALUES (:t,:code,:conf,:at,:cfg,TRUE,:at,0)", p);
        }
    }

    private static DegradationRow degradation(ResultSet rs, int ignored) throws SQLException {
        return new DegradationRow(rs.getString("target_id"), rs.getString("level"), FusionConfigRepository.jsonText(rs.getObject("available_source_ids")),
                rs.getBigDecimal("confidence_deficit"), rs.getBoolean("determined"), FusionConfigRepository.time(rs, "since"), FusionConfigRepository.time(rs, "updated_at"));
    }

    private static SelectionRow selection(ResultSet rs, int ignored) throws SQLException {
        return new SelectionRow(rs.getString("target_id"), rs.getString("position_source_id"), rs.getString("class_source_id"), rs.getString("identity_source_id"), rs.getString("motion_source_id"),
                rs.getString("class_code"), rs.getBigDecimal("class_confidence"), rs.getString("identity_clue"), FusionConfigRepository.time(rs, "selected_at"), rs.getString("config_version"),
                rs.getBoolean("manual_class_override"), FusionConfigRepository.time(rs, "updated_at"), rs.getLong("version"));
    }

    public record DegradationRow(String targetId, String level, String availableSourceIdsJson, BigDecimal deficit, boolean determined, OffsetDateTime since, OffsetDateTime updatedAt) { }
    public record SelectionRow(String targetId, String positionSourceId, String classSourceId, String identitySourceId, String motionSourceId, String classCode,
            BigDecimal classConfidence, String identityClue, OffsetDateTime selectedAt, String configVersion, boolean manualClassOverride, OffsetDateTime updatedAt, long version) { }
}
