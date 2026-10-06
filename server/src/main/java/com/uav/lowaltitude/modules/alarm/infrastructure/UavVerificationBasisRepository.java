package com.uav.lowaltitude.modules.alarm.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.alarm.domain.UavVerificationBasis.Facts;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;

/**
 * 读取人工核实依据的系统事实。调用方先完成事件读权限与范围校验；这里只取事实，不判断能否核实。
 * 目标按告警目标及其融合后的当前目标一起看，且只认与事件同一组织区域的目标与研判。
 */
@Repository
public class UavVerificationBasisRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final DisposalRepository rules;

    public UavVerificationBasisRepository(JdbcTemplate jdbc, DisposalRepository rules) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.rules = rules;
    }

    public Facts facts(String eventId, long now) {
        List<EventRow> events = jdbc.query("""
                SELECT e.created_at,e.owner_org_id,e.district_id,a.target_id,c.current_target_id
                FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id
                LEFT JOIN target_current_alias c ON c.historical_target_id=a.target_id
                WHERE e.event_id=:event
                """, Map.of("event", eventId), (rs, n) -> new EventRow(time(rs, "created_at"),
                rs.getString("owner_org_id"), rs.getString("district_id"), rs.getString("target_id"),
                rs.getString("current_target_id")));
        Integer freshSeconds = rules.freshSeconds();
        if (events.isEmpty()) return new Facts(false, null, null, null, freshSeconds, 0, now);
        EventRow event = events.get(0);
        Set<String> candidates = new LinkedHashSet<>();
        if (event.alarmTarget() != null) candidates.add(event.alarmTarget());
        if (event.currentTarget() != null) candidates.add(event.currentTarget());
        List<String> targets = candidates.isEmpty() ? List.of() : jdbc.queryForList("""
                SELECT target_id FROM target WHERE target_id IN (:ids) AND owner_org_id=:org AND district_id=:district
                """, Map.of("ids", candidates, "org", event.org(), "district", event.district()), String.class);
        Long observed = null, evaluated = null;
        if (!targets.isEmpty()) {
            observed = jdbc.query("SELECT MAX(observed_at) AS latest FROM target_latest_state WHERE target_id IN (:ids)",
                    Map.of("ids", targets), (rs, n) -> time(rs, "latest")).get(0);
            evaluated = jdbc.query("""
                    SELECT MAX(evaluated_at) AS latest FROM rule_evaluation
                    WHERE target_id IN (:ids) AND owner_org_id=:org AND district_id=:district AND mode='ACTIVE'
                    """, Map.of("ids", targets, "org", event.org(), "district", event.district()),
                    (rs, n) -> time(rs, "latest")).get(0);
        }
        long window = freshSeconds == null || freshSeconds <= 0 ? 0 : freshSeconds * 1000L;
        Long since = event.createdAt() == null ? null : event.createdAt() - window;
        return new Facts(!targets.isEmpty(), event.createdAt(), observed, evaluated, freshSeconds,
                media(eventId, targets, since), now);
    }

    /** 已入库、内容可用的光电截图/录像/现场照片：关联本事件，或在本次告警期间关联到该目标。 */
    private long media(String eventId, List<String> targets, Long since) {
        Map<String, Object> params = new HashMap<>();
        params.put("event", eventId);
        List<String> scopes = new ArrayList<>(List.of("(l.subject_kind='EVENT' AND l.subject_id=:event)"));
        if (!targets.isEmpty() && since != null) {
            scopes.add("(l.subject_kind='TARGET' AND l.subject_id IN (:targets) AND COALESCE(f.captured_at,f.stored_at)>=:since)");
            params.put("targets", targets);
            params.put("since", new Timestamp(since));
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_file f WHERE f.status='AVAILABLE'"
                + " AND f.kind_code IN ('EO_STILL','EO_VIDEO','SCENE_PHOTO') AND EXISTS (SELECT 1 FROM evidence_link l"
                + " WHERE l.evidence_id=f.evidence_id AND (" + String.join(" OR ", scopes) + "))", params, Long.class);
        return count == null ? 0 : count;
    }

    private static Long time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.getTime();
    }

    private record EventRow(Long createdAt, String org, String district, String alarmTarget, String currentTarget) { }
}
