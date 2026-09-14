package com.uav.lowaltitude.modules.alarm.infrastructure;

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

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

@Repository
public class UavEventRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public UavEventRepository(JdbcTemplate jdbcTemplate) { this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate); }

    public EventRow find(String eventId, AccessDecision access) { return one(eventId, access, false); }
    public EventRow lock(String eventId, AccessDecision access) { return one(eventId, access, true); }

    public long countVerifications(String eventId, AccessDecision access) {
        Where where = where(access); where.parameters.put("event_id", eventId); where.sql.append(" AND e.event_id=:event_id");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification h JOIN uav_event e ON e.event_id=h.event_id JOIN alarm a ON a.alarm_id=e.alarm_id" + where.sql, where.parameters, Long.class);
        return count == null ? 0 : count;
    }

    public List<VerificationRow> verifications(String eventId, AccessDecision access, int offset, int size) {
        Where where = where(access); where.parameters.put("event_id", eventId); where.parameters.put("offset", offset); where.parameters.put("size", size); where.sql.append(" AND e.event_id=:event_id");
        return jdbc.query("SELECT h.history_id,h.previous_state,h.resulting_state,h.conclusion,h.note,h.version,h.actor_id,h.created_at,au.name AS actor_name FROM uav_event_verification h JOIN uav_event e ON e.event_id=h.event_id JOIN alarm a ON a.alarm_id=e.alarm_id LEFT JOIN app_user au ON au.user_id=h.actor_id" + where.sql + " ORDER BY h.version ASC,h.history_id ASC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY", where.parameters, UavEventRepository::verification);
    }

    public int update(String eventId, long expectedVersion, String nextState, OffsetDateTime at) {
        return jdbc.getJdbcTemplate().update("UPDATE uav_event SET state_code=?,updated_at=?,version=version+1 WHERE event_id=? AND version=?", nextState, at, eventId, expectedVersion);
    }

    public void appendHistory(String id, String eventId, String previousState, String resultingState, String conclusion,
            String note, long version, String actorId, OffsetDateTime at) {
        jdbc.getJdbcTemplate().update("INSERT INTO uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)", id, eventId, version, previousState, resultingState, conclusion, note, actorId, at);
    }

    /** 接收链和 local/test seeder 都经此处建事件；唯一 alarm_id 约束保证同一来源告警不重复成事件。 */
    public boolean createForAlarm(String eventId, String alarmId, String state, String orgId,
            String districtId, OffsetDateTime at) {
        try {
            return jdbc.getJdbcTemplate().update("INSERT INTO uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,0)", eventId, alarmId, state, orgId, districtId, at, at) == 1;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    private EventRow one(String eventId, AccessDecision access, boolean lock) {
        Where where = where(access); where.parameters.put("event_id", eventId); where.sql.append(" AND e.event_id=:event_id");
        List<EventRow> rows = jdbc.query("SELECT e.event_id,e.alarm_id,a.target_id,a.source_mode,e.state_code,e.owner_org_id,e.district_id,e.created_at,e.updated_at,e.version FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id" + where.sql + (lock ? " FOR UPDATE" : ""), where.parameters, UavEventRepository::event);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Where where(AccessDecision access) {
        StringBuilder sql = new StringBuilder(" WHERE a.owner_org_id=e.owner_org_id AND a.district_id=e.district_id");
        Map<String, Object> parameters = new HashMap<>();
        if (access.scopeMode() == ScopeMode.ASSIGNED) {
            // 锁定前也按同一授权元组过滤，避免可猜 event_id 泄露或跨域锁竞争。
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_data_scope ds JOIN app_org o ON o.org_id=ds.org_id AND o.enabled=TRUE JOIN app_district d ON d.district_id=ds.district_id AND d.enabled=TRUE WHERE ds.user_id=:scope_user_id AND ds.org_id=e.owner_org_id AND ds.district_id=e.district_id)");
            parameters.put("scope_user_id", access.userId());
        }
        return new Where(sql, parameters);
    }

    private static EventRow event(ResultSet rs, int ignored) throws SQLException {
        return new EventRow(rs.getString("event_id"), rs.getString("alarm_id"), rs.getString("target_id"),
                rs.getString("state_code"), rs.getString("owner_org_id"), rs.getString("district_id"),
                time(rs, "created_at"), time(rs, "updated_at"), rs.getLong("version"), rs.getString("source_mode"));
    }
    private static VerificationRow verification(ResultSet rs, int ignored) throws SQLException {
        return new VerificationRow(rs.getString("history_id"), rs.getString("previous_state"), rs.getString("resulting_state"), rs.getString("conclusion"), rs.getString("note"), rs.getLong("version"), rs.getString("actor_id"), time(rs, "created_at"), rs.getString("actor_name"));
    }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); if (value instanceof OffsetDateTime time) return time; if (value instanceof ZonedDateTime time) return time.toOffsetDateTime(); if (value instanceof Timestamp time) return time.toInstant().atOffset(ZoneOffset.UTC); if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC); return OffsetDateTime.parse(value.toString());
    }
    private record Where(StringBuilder sql, Map<String, Object> parameters) { }
    public record EventRow(String eventId, String alarmId, String targetId, String state, String ownerOrgId,
            String districtId, OffsetDateTime createdAt, OffsetDateTime updatedAt, long version, String sourceMode) { }
    public record VerificationRow(String historyId, String previousState, String resultingState, String conclusion,
            String note, long version, String actorId, OffsetDateTime createdAt, String actorName) { }
}
