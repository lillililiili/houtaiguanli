package com.uav.lowaltitude.modules.prediction.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * Origin-aware guards shared by the existing event workflows. An observed event linked to an
 * episode retains its own verification and illegality evidence; the link only shares notification
 * ownership, action exclusion and the prohibition on automatic countermeasures.
 */
@Service
public class PredictionEventGate {
    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private long freshnessMillis=com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Config.defaults().freshnessMillis();

    @org.springframework.beans.factory.annotation.Autowired
    public void configuration(PredictionConfiguration configuration) {
        freshnessMillis=configuration.parameters().forecast().freshnessMillis();
    }

    public PredictionEventGate(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Facts(String episodeId, String eventId, String role, String status, long version,
                        String predictionId, Long observedAt, Long validUntil, boolean evidenceValid,
                        boolean enteredEvidenceValid, boolean manualOnly, boolean humanConfirmed,
                        String eventState) { }

    public Facts facts(String eventId) {
        var rows=allFacts(eventId);
        return rows.isEmpty()?null:rows.get(0);
    }

    private List<Facts> allFacts(String eventId) {
        if (eventId == null || eventId.isBlank()) return List.of();
        return jdbc.query("""
                SELECT p.*,l.role,e.state_code,
                  EXISTS(SELECT 1 FROM uav_event_verification v WHERE v.event_id=e.event_id
                    AND v.conclusion='CONFIRMED' AND v.actor_id IS NOT NULL) AS human_confirmed
                FROM prediction_event_link l JOIN prediction_episode p ON p.episode_id=l.episode_id
                JOIN uav_event e ON e.event_id=l.event_id WHERE l.event_id=?
                ORDER BY CASE WHEN l.role='PREDICTED' THEN 0 ELSE 1 END,p.last_observed_at DESC,p.episode_id
                """, (r, n) -> new Facts(r.getString("episode_id"), eventId, r.getString("role"),
                r.getString("status"), r.getLong("version"), r.getString("latest_prediction_id"),
                r.getObject("last_observed_at", Long.class), r.getObject("valid_until", Long.class),
                r.getBoolean("current_evidence_valid"), r.getBoolean("entered_evidence_valid"),
                r.getBoolean("manual_counter_only"), r.getBoolean("human_confirmed"), r.getString("state_code")),
                eventId);
    }

    public boolean isPrediction(String eventId) {
        Facts f = facts(eventId);
        return f != null && "PREDICTED".equals(f.role());
    }

    public boolean manualCounterOnly(String eventId) {
        return allFacts(eventId).stream().anyMatch(Facts::manualOnly);
    }

    public boolean blocksAutomatic(String category, String eventId) {
        return allFacts(eventId).stream().anyMatch(f->"PREDICTED".equals(f.role())
                ||("counter".equals(category)&&f.manualOnly()));
    }

    /** Call before taking event, authorization, device or command locks. */
    public void lockEpisode(String eventId) {
        jdbc.queryForList("""
                SELECT episode_id FROM prediction_episode
                WHERE episode_id IN (SELECT episode_id FROM prediction_event_link WHERE event_id=?)
                ORDER BY episode_id FOR UPDATE
                """,eventId);
    }

    /** Null means the established, non-prediction verification policy applies. */
    public String confirmationBlock(String eventId) {
        Facts f = facts(eventId);
        if (f == null || !"PREDICTED".equals(f.role())) return null;
        if (!"ACTIVE".equals(f.status())) return "本次预测风险已变化，请查看当前预警状态后再核实";
        return current(f, clock.nowMillis()) ? "" : "缺少当前可靠实测和有效预测，暂不能核实预测风险";
    }

    public void requireSelectedPrediction(String eventId,String predictionId) {
        Facts f=facts(eventId);
        if(f==null||!"PREDICTED".equals(f.role()))return;
        if(predictionId==null||predictionId.isBlank()||jdbc.queryForObject(
                "SELECT COUNT(*) FROM prediction_episode_snapshot l JOIN prediction_snapshot s ON s.prediction_id=l.prediction_id"
                +" WHERE l.episode_id=? AND l.prediction_id=? AND s.status='READY' AND s.generated_at<=?",
                Long.class,f.episodeId(),predictionId,clock.nowMillis())!=1)
            throw new com.uav.lowaltitude.platform.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "PREDICTION_BASIS_CHANGED","请查看本次预警的预测依据后再核实");
    }

    public void freezeVerification(String historyId,String eventId,String predictionId) {
        Facts f=facts(eventId);
        if(f==null||!"PREDICTED".equals(f.role()))return;
        requireSelectedPrediction(eventId,predictionId);
        jdbc.update("INSERT INTO prediction_verification_basis(history_id,prediction_id,checked_prediction_id,episode_id)"
                +" VALUES (?,?,?,?)",historyId,predictionId,f.predictionId(),f.episodeId());
    }

    /** An empty string allows a prediction, null delegates an ordinary event to its original rules. */
    public String counterBlock(String eventId, boolean continuing) {
        Facts f = facts(eventId);
        if (f == null) return null;
        // The actual event retains its own measured evidence/verification policy even when an old
        // linked prediction cycle closes. Only predictive authorizations depend on its lifecycle.
        if (!"PREDICTED".equals(f.role())) return null;
        if ("UNKNOWN".equals(f.status())) return "预测依据不可靠，已暂停处置；恢复可靠观测后须人工重新发起";
        if ("CLEARED".equals(f.status())) return "本次预测风险已解除，不能继续处置";
        if (!"CONFIRMED".equals(f.eventState()) || !f.humanConfirmed()) return "预测风险须先由有权限人员人工核实";
        if ("ENTERED".equals(f.status()) && !continuing) return "目标已实际进入，请在关联实测告警核实并发起新的处置";
        if (!current(f, clock.nowMillis())) return "缺少当前有效实测及预测风险依据，暂不能反制";
        return "";
    }

    public String notificationBlock(String eventId) {
        Facts f = facts(eventId);
        if (f == null) return null;
        if ("PREDICTED".equals(f.role())) {
            if ("UNKNOWN".equals(f.status())) return "预测依据待确认，暂停通知";
            if ("CLEARED".equals(f.status())) return "预测风险已解除，取消后续通知";
            if (!f.humanConfirmed() || !"CONFIRMED".equals(f.eventState())) return "预测风险尚未人工核实，暂停通知";
            if (!current(f, clock.nowMillis())) return "缺少当前可靠实测和有效预测，暂停通知";
        }
        return eventId.equals(notificationOwner(eventId)) ? "" : "同次风险已由关联告警通知，本告警不重复发送";
    }

    /**
     * The first claimed or delivered SMS owns this episode's notification cycle. The episode lock
     * serializes first claims across the predicted and observed event; records are never copied.
     */
    public String notificationOwner(String eventId) {
        var rows = jdbc.queryForList("""
                SELECT DISTINCT t.event_id,t.triggered_at FROM uav_auto_sms_task t
                JOIN prediction_event_link l ON l.event_id=t.event_id
                JOIN prediction_episode p ON p.episode_id=l.episode_id
                WHERE l.episode_id IN (SELECT episode_id FROM prediction_event_link WHERE event_id=?)
                  AND t.attempt_count>0 AND t.triggered_at>=p.started_at
                  AND p.active_key IS NOT NULL
                ORDER BY t.triggered_at,t.event_id
                """,eventId);
        return rows.isEmpty() ? eventId : rows.get(0).get("event_id").toString();
    }

    public boolean hasOtherActiveCounter(String eventId, String excludedAuthorization) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM disposal_authorization a
                JOIN prediction_event_link l ON l.event_id=a.subject_id
                WHERE l.episode_id IN (SELECT episode_id FROM prediction_event_link WHERE event_id=?)
                  AND a.subject_kind='UAV_EVENT'
                  AND a.action_type IN ('COUNTERMEASURE','JAMMING')
                  AND a.status IN ('REQUESTED','APPROVED','EXECUTING')
                  AND a.authorization_id<>?
                """, Long.class, eventId, excludedAuthorization==null?"":excludedAuthorization) > 0;
    }

    public List<String> linkedEvents(String eventId) {
        return jdbc.queryForList("""
                SELECT DISTINCT event_id FROM prediction_event_link
                WHERE episode_id IN (SELECT episode_id FROM prediction_event_link WHERE event_id=?) ORDER BY event_id
                """,String.class,eventId);
    }

    public Long notificationRevision(String eventId) {
        Facts f=facts(eventId);
        return f!=null&&"PREDICTED".equals(f.role())?f.version():null;
    }

    /** Pause is a durable barrier: reliable recovery cannot revive a previously claimed send. */
    public void markPaused(String eventId,long revision) {
        Facts f=facts(eventId);
        if(f!=null&&"PREDICTED".equals(f.role()))jdbc.update("""
                UPDATE prediction_episode SET last_paused_version=?
                WHERE episode_id=? AND last_paused_version<?
                """,revision,f.episodeId(),revision);
    }

    public boolean notificationClaimInterrupted(String eventId,Long revision) {
        if(revision==null)return false;
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prediction_event_link l JOIN prediction_episode p ON p.episode_id=l.episode_id
                WHERE l.event_id=? AND l.role='PREDICTED' AND p.last_paused_version>?
                """,Long.class,eventId,revision)>0;
    }

    /**
     * A prediction is never the alarm_id of a measured legality evaluation. For its pilot only,
     * use the current independent measured plan match, with exact source/scope and a fresh timestamp.
     */
    public String pilotPlan(String eventId,String evaluationId) {
        Facts f=facts(eventId);
        if(f==null||!"PREDICTED".equals(f.role())||evaluationId==null||!current(f,clock.nowMillis()))return null;
        long now=clock.nowMillis();
        var rows=jdbc.query("""
                SELECT r.plan_id,r.target_id,r.owner_org_id,r.district_id,r.source_mode,r.freshness_code,r.observed_at
                FROM prediction_episode ep
                JOIN rule_evaluation r ON r.target_id=ep.target_id AND r.owner_org_id=ep.owner_org_id
                  AND r.district_id=ep.district_id AND r.source_mode=ep.source_mode
                JOIN flight_plan p ON p.plan_id=r.plan_id AND p.owner_org_id=r.owner_org_id
                  AND p.district_id=r.district_id AND p.source_mode=r.source_mode
                WHERE ep.episode_id=? AND r.evaluation_id=? AND r.mode='ACTIVE'
                  AND r.freshness_code IN ('FRESH','REPLAY') AND r.plan_match_code IN ('FULL','PARTIAL')
                  AND r.observed_at>=? AND r.observed_at<=?
                  AND NOT EXISTS(SELECT 1 FROM rule_evaluation newer WHERE newer.target_id=r.target_id
                    AND newer.owner_org_id=r.owner_org_id AND newer.district_id=r.district_id
                    AND newer.source_mode=r.source_mode AND newer.mode='ACTIVE'
                    AND (newer.evaluated_at>r.evaluated_at
                      OR(newer.evaluated_at=r.evaluated_at AND newer.evaluation_id>r.evaluation_id)))
                """,(r,n)->new PilotMatch(r.getString("plan_id"),r.getString("target_id"),r.getString("owner_org_id"),
                        r.getString("district_id"),r.getString("source_mode"),r.getString("freshness_code"),
                        r.getTimestamp("observed_at").getTime()),
                f.episodeId(),evaluationId,new java.sql.Timestamp(now-freshnessMillis),new java.sql.Timestamp(now));
        if(rows.isEmpty())return null;
        var r=rows.get(0);
        if(!"FRESH".equals(r.freshness())&&!realtimeReplayMeasurement(jdbc,now,
                r.target(),r.org(),r.district(),r.source(),r.observedAt(),freshnessMillis))return null;
        return r.plan();
    }
    private record PilotMatch(String plan,String target,String org,String district,String source,String freshness,long observedAt) { }

    /** K03 input has independently checked protocol identity and real-time MEAS provenance. */
    static boolean realtimeReplayMeasurement(JdbcTemplate jdbc,long now,String target,String org,String district,
            String source,Long observedAt,long freshnessMillis) {
        if(!"replay".equals(source)||observedAt==null||observedAt>now||observedAt<now-freshnessMillis)return false;
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prediction_episode ep JOIN prediction_snapshot s ON s.prediction_id=ep.latest_prediction_id
                WHERE ep.target_id=? AND ep.owner_org_id=? AND ep.district_id=? AND ep.source_mode='replay'
                  AND ep.active_key IS NOT NULL AND ep.last_observed_at=? AND ep.valid_until>?
                  AND ((ep.status='ACTIVE' AND ep.current_evidence_valid=TRUE)
                    OR (ep.status='ENTERED' AND ep.entered_evidence_valid=TRUE))
                  AND s.target_id=ep.target_id AND s.owner_org_id=ep.owner_org_id AND s.district_id=ep.district_id
                  AND s.source_mode='replay' AND s.simulated=TRUE AND s.based_on_observed_at=ep.last_observed_at
                """,Long.class,target,org,district,observedAt,now)>0;
    }

    /** Prediction disappearance is not departure; only the worker's new measured clear evidence is. */
    public PilotDepartureWatch.Assessment assessment(String eventId, long since, long now) {
        Facts f = facts(eventId);
        if (f == null || !"PREDICTED".equals(f.role())) return null;
        if (f.observedAt() == null || f.observedAt() <= since || f.observedAt() > now)
            return new PilotDepartureWatch.Assessment(PilotDepartureWatch.Presence.UNKNOWN, null, "PREDICTION_NO_NEW_MEASUREMENT");
        var presence = "CLEARED".equals(f.status()) ? PilotDepartureWatch.Presence.LEFT
                : current(f, now) ? PilotDepartureWatch.Presence.STILL_PRESENT : PilotDepartureWatch.Presence.UNKNOWN;
        return new PilotDepartureWatch.Assessment(presence, null, "PREDICTION_EPISODE_" + f.status());
    }

    static boolean current(Facts f, long now) {
        if (f.observedAt() == null || f.observedAt() > now || f.validUntil() == null || now >= f.validUntil()) return false;
        return ("ACTIVE".equals(f.status()) && f.evidenceValid())
                || ("ENTERED".equals(f.status()) && f.enteredEvidenceValid());
    }
}
