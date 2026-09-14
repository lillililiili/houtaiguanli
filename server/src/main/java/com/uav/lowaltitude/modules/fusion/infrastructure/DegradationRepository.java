package com.uav.lowaltitude.modules.fusion.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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

    public DegradationRow findDegradation(String targetId) {
        List<DegradationRow> rows = jdbc.query("SELECT target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at FROM target_degradation WHERE target_id=:t",
                Map.of("t", targetId), (rs, i) -> new DegradationRow(rs.getString("target_id"), rs.getString("level"), FusionConfigRepository.jsonText(rs.getObject("available_source_ids")),
                        rs.getBigDecimal("confidence_deficit"), rs.getBoolean("determined"), FusionConfigRepository.time(rs, "since"), FusionConfigRepository.time(rs, "updated_at")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsertDegradation(String targetId, String level, String availableJson, BigDecimal deficit, boolean determined, OffsetDateTime since, OffsetDateTime updatedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("level", level); p.put("available", availableJson); p.put("deficit", deficit); p.put("determined", determined); p.put("since", since); p.put("updated", updatedAt);
        int updated = jdbc.update("UPDATE target_degradation SET level=:level, available_source_ids=CAST(:available AS JSON), confidence_deficit=:deficit, determined=:determined, since=:since, updated_at=:updated WHERE target_id=:t", p);
        if (updated == 0) {
            jdbc.update("INSERT INTO target_degradation (target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at) VALUES (:t,:level,CAST(:available AS JSON),:deficit,:determined,:since,:updated)", p);
        }
    }

    public SelectionRow findSelection(String targetId) {
        List<SelectionRow> rows = jdbc.query("SELECT target_id,position_source_id,class_source_id,identity_source_id,motion_source_id,class_code,class_confidence,identity_clue,selected_at,config_version,manual_class_override,updated_at,version"
                + " FROM target_attribute_selection WHERE target_id=:t", Map.of("t", targetId), DegradationRepository::selection);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsertSelection(String targetId, String positionSourceId, String classSourceId, String identitySourceId, String motionSourceId,
            String classCode, BigDecimal classConfidence, String identityClue, OffsetDateTime selectedAt, String configVersion, boolean manualOverride, OffsetDateTime updatedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", targetId); p.put("pos", positionSourceId); p.put("cls", classSourceId); p.put("idn", identitySourceId); p.put("mot", motionSourceId);
        p.put("code", classCode); p.put("conf", classConfidence); p.put("clue", identityClue); p.put("selected", selectedAt); p.put("cfg", configVersion); p.put("override", manualOverride); p.put("updated", updatedAt);
        int updated = jdbc.update("UPDATE target_attribute_selection SET position_source_id=:pos, class_source_id=:cls, identity_source_id=:idn, motion_source_id=:mot, class_code=:code, class_confidence=:conf,"
                + " identity_clue=:clue, selected_at=:selected, config_version=:cfg, manual_class_override=:override, updated_at=:updated WHERE target_id=:t", p);
        if (updated == 0) {
            jdbc.update("INSERT INTO target_attribute_selection (target_id,position_source_id,class_source_id,identity_source_id,motion_source_id,class_code,class_confidence,identity_clue,selected_at,config_version,manual_class_override,updated_at,version)"
                    + " VALUES (:t,:pos,:cls,:idn,:mot,:code,:conf,:clue,:selected,:cfg,:override,:updated,0)", p);
        }
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

    private static SelectionRow selection(ResultSet rs, int ignored) throws SQLException {
        return new SelectionRow(rs.getString("target_id"), rs.getString("position_source_id"), rs.getString("class_source_id"), rs.getString("identity_source_id"), rs.getString("motion_source_id"),
                rs.getString("class_code"), rs.getBigDecimal("class_confidence"), rs.getString("identity_clue"), FusionConfigRepository.time(rs, "selected_at"), rs.getString("config_version"),
                rs.getBoolean("manual_class_override"), FusionConfigRepository.time(rs, "updated_at"), rs.getLong("version"));
    }

    public record DegradationRow(String targetId, String level, String availableSourceIdsJson, BigDecimal deficit, boolean determined, OffsetDateTime since, OffsetDateTime updatedAt) { }
    public record SelectionRow(String targetId, String positionSourceId, String classSourceId, String identitySourceId, String motionSourceId, String classCode,
            BigDecimal classConfidence, String identityClue, OffsetDateTime selectedAt, String configVersion, boolean manualClassOverride, OffsetDateTime updatedAt, long version) { }
}
