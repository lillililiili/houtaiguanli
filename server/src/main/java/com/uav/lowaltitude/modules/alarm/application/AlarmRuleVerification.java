package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/** 核实规则通过后，把仍待核实的告警事件记为属实。不创建反制授权，也不发送处罚通知。 */
@Service
public class AlarmRuleVerification {
    public static final String NOTE = "核实规则已满足，系统核实属实。";
    private final JdbcTemplate jdbc;
    private final UavEventRepository events;
    private final AuditService audit;
    private final AppClock clock;
    public AlarmRuleVerification(JdbcTemplate jdbc, UavEventRepository events, AuditService audit, AppClock clock) {
        this.jdbc = jdbc; this.events = events; this.audit = audit; this.clock = clock;
    }
    @Transactional
    public void confirmIfPassed(String eventId, String runId) {
        if (eventId == null || eventId.isBlank() || runId == null || runId.isBlank()) return;
        var rows = jdbc.query("SELECT state_code, version FROM uav_event WHERE event_id=?",
                (rs, n) -> new State(rs.getString("state_code"), rs.getLong("version")), eventId);
        if (rows.isEmpty() || !"PENDING_VERIFICATION".equals(rows.get(0).state)) return;
        long version = rows.get(0).version;
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (events.update(eventId, version, "CONFIRMED", at) != 1) return;
        jdbc.update("INSERT INTO uav_event_verification (history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at,automation_run_id) VALUES (?,?,?,?,?,?,?,NULL,?,?)",
                UUID.randomUUID().toString(), eventId, version + 1, "PENDING_VERIFICATION", "CONFIRMED", "CONFIRMED", NOTE, at, runId);
        audit.record(null, "AUTO_VERIFY", "SYSTEM", "alarm", "uav_event_verified", "uav_event", eventId,
                "conclusion=CONFIRMED; automation_run_id=" + runId, "SUCCESS", "", "");
    }
    private record State(String state, long version) { }
}
