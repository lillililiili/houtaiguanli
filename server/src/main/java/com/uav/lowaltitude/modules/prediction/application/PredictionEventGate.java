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
    private com.uav.lowaltitude.modules.handoff.application.MeasuredPunishmentBasis measuredPunishment;
    @org.springframework.beans.factory.annotation.Autowired
    public void measuredPunishment(com.uav.lowaltitude.modules.handoff.application.MeasuredPunishmentBasis value){measuredPunishment=value;}
    private com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity riskCases;
    @org.springframework.beans.factory.annotation.Autowired
    public void riskCases(com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity riskCases){this.riskCases=riskCases;}
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
        List<Facts> facts=jdbc.query("""
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
        if(riskCases!=null&&riskCases.selectedCase()!=null){
            var selected=riskCases.cases(eventId);
            if(selected.size()!=1)return List.of();
            String episode=selected.get(0).episodeId();
            return facts.stream().filter(f->java.util.Objects.equals(episode,f.episodeId())).toList();
        }
        return facts;
    }

    public boolean isPrediction(String eventId) {
        Facts f = facts(eventId);
        return f != null && "PREDICTED".equals(f.role());
    }

    /** Observation supports human verification; it neither verifies nor authorizes countermeasures. */
    public Boolean observationRequired(String eventId) {
        // Case verification does not rewrite the raw event verdict. Do not let that old pending
        // verdict keep automatic observation alive after the risk was rejected or became invalid.
        // Read every region here: a valid independent risk for the same event still needs observing.
        if (riskCases != null) {
            var cases = riskCases.allCases(eventId);
            if (!cases.isEmpty() && cases.stream().allMatch(c ->
                    java.util.Set.of("ENDED", "CLEARED", "UNKNOWN").contains(c.stage()))) return false;
        }
        Facts f = facts(eventId);
        if (f == null || !"PREDICTED".equals(f.role())) return null;
        if(measuredHandoffCurrent(eventId))return true;
        return current(f, clock.nowMillis());
    }

    public boolean manualCounterOnly(String eventId) {
        return allFacts(eventId).stream().anyMatch(Facts::manualOnly);
    }

    public boolean blocksAutomatic(String category, String eventId) {
        if("dispose".equals(category)&&measuredPunishment!=null&&measuredPunishment.derived(eventId))
            return !measuredPunishment.independentMeasuredEvent(eventId);
        // Verification follows the current regional risk, not its original prediction label.
        // Only the current measured event can take over; an old prediction event or another
        // region must not borrow the entry evidence. Countermeasure origin restrictions remain.
        if ("verify".equals(category) && riskCases != null) {
            var selected = riskCases.cases(eventId);
            if (riskCases.selectedCase() != null && selected.size() != 1) return true;
            if (selected.stream().anyMatch(c -> c.episodeId() != null || c.manualCounterOnly())) {
                if (selected.size() != 1) return true;
                var c = selected.get(0);
                return !"ENTERED".equals(c.stage()) || !eventId.equals(c.actionEventId())
                        || c.airspaceId() == null || !riskCases.currentMeasuredArea(c);
            }
        }
        return allFacts(eventId).stream().anyMatch(f->"PREDICTED".equals(f.role())
                ||(("counter".equals(category)||"verify".equals(category))&&f.manualOnly()));
    }

    public String automaticBlockReason(String category) {
        return switch(category) {
            case "verify" -> "当前风险阶段或本禁飞区实测入区依据不满足自动核实条件";
            case "counter" -> "预测衍生风险须由有权限人员人工发起反制";
            default -> "缺少本次风险独立、充分的实测违法依据，不能自动移送处罚";
        };
    }

    /** Call before taking event, authorization, device or command locks. */
    public void lockEpisode(String eventId) {
        if(riskCases!=null){riskCases.lockCases(eventId);return;}
        jdbc.queryForList("""
                SELECT episode_id FROM prediction_episode
                WHERE episode_id IN (SELECT episode_id FROM prediction_event_link WHERE event_id=?)
                ORDER BY episode_id FOR UPDATE
                """,eventId);
    }

    /** Null means the established, non-prediction verification policy applies. */
    public String confirmationBlock(String eventId) {
        return confirmationBlock(facts(eventId),clock.nowMillis());
    }

    /** Shared by the current-evidence dialog and the locked submit guard. */
    static String confirmationBlock(Facts f,long now) {
        if (f == null || !"PREDICTED".equals(f.role())) return null;
        if (!"ACTIVE".equals(f.status())) return "本次预测风险已变化，请查看当前预警状态后再核实";
        return current(f, now) ? "" : "缺少当前可靠实测和有效预测，暂不能核实预测风险";
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
        if(riskCases!=null&&riskCases.hasCases(eventId)) {
            for(var c:riskCases.cases(eventId)){
                if(!c.confirmed())return "当前区域风险尚未核实";
                if(java.util.Set.of("UNKNOWN","CLEARED","ENDED").contains(c.stage()))return "当前风险已结束或依据不足，不能启动或继续反制";
                if("ENTERED".equals(c.stage())&&!riskCases.currentMeasuredArea(c))return "缺少本禁飞区当前有效的实测入区依据";
            }
        }
        Facts f = facts(eventId);
        if (f == null) return null;
        // The actual event retains its own measured evidence/verification policy even when an old
        // linked prediction cycle closes. Only predictive authorizations depend on its lifecycle.
        if (!"PREDICTED".equals(f.role())) return null;
        // The case keeps its predictive origin, but the current basis is now measured entry.
        // currentMeasuredArea checks the latest position, region/version and ordinary measured
        // evidence window. It does not extend, reopen or change an existing authorization.
        if(measuredHandoffCurrent(eventId))return "";
        if ("UNKNOWN".equals(f.status())) return "预测依据不可靠，已暂停处置；恢复可靠观测后须人工重新发起";
        if ("CLEARED".equals(f.status())) return "本次预测风险已解除，不能继续处置";
        if ((riskCases==null||!riskCases.confirmedForDisposal(eventId))&&(!"CONFIRMED".equals(f.eventState()) || !f.humanConfirmed())) return "预测风险须先由有权限人员人工核实";
        if ("ENTERED".equals(f.status()) && !continuing && (riskCases==null||!riskCases.confirmedForDisposal(eventId))) return "目标已实际进入，请从统一风险记录办理处置";
        if (!current(f, clock.nowMillis())) return "缺少当前有效实测及预测风险依据，暂不能反制";
        return "";
    }

    public String notificationBlock(String eventId) {
        if(riskCases!=null&&riskCases.hasCases(eventId)) {
            for(var c:riskCases.cases(eventId)){
                if(!c.confirmed())return "当前区域风险尚未核实，暂停通知";
                if(java.util.Set.of("UNKNOWN","CLEARED","ENDED").contains(c.stage()))return "当前风险已结束或依据不足，暂停通知";
                if("ENTERED".equals(c.stage())&&!riskCases.currentMeasuredArea(c))return "缺少本禁飞区当前有效的实测入区依据，暂停通知";
            }
        }
        Facts f = facts(eventId);
        if (f == null) return null;
        if ("PREDICTED".equals(f.role())&&!measuredHandoffCurrent(eventId)) {
            if ("UNKNOWN".equals(f.status())) return "预测依据待确认，暂停通知";
            if ("CLEARED".equals(f.status())) return "预测风险已解除，取消后续通知";
            if ((riskCases==null||!riskCases.confirmedForDisposal(eventId))&&(!f.humanConfirmed() || !"CONFIRMED".equals(f.eventState()))) return "预测风险尚未人工核实，暂停通知";
            if (!current(f, clock.nowMillis())) return "缺少当前可靠实测和有效预测，暂停通知";
        }
        return eventId.equals(notificationOwner(eventId)) ? "" : "同次风险已由关联告警通知，本告警不重复发送";
    }

    /**
     * The first claimed or delivered SMS owns this episode's notification cycle. The episode lock
     * serializes first claims across the predicted and observed event; records are never copied.
     */
    public String notificationOwner(String eventId) {
        if(riskCases!=null&&riskCases.hasCases(eventId))return riskCases.notificationOwner(eventId);
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
        if(riskCases!=null&&riskCases.hasCases(eventId)){
            for(var c:riskCases.cases(eventId))for(String id:riskCases.authorizationIds(c.riskCaseId()))
                if(!id.equals(excludedAuthorization)&&jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE authorization_id=? AND action_type IN ('COUNTERMEASURE','JAMMING') AND status IN ('REQUESTED','APPROVED','EXECUTING')",Long.class,id)>0)return true;
            return false;
        }
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
        if(f==null||!"PREDICTED".equals(f.role())||evaluationId==null)return null;
        boolean measured=measuredHandoffCurrent(eventId);
        if(!measured&&!current(f,clock.nowMillis()))return null;
        Long measuredWindow=measured?riskCases.measuredFreshnessMillis():null;
        if(measured&&measuredWindow==null)return null;
        long window=measured?measuredWindow:freshnessMillis;
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
                f.episodeId(),evaluationId,new java.sql.Timestamp(now-window),new java.sql.Timestamp(now));
        if(rows.isEmpty())return null;
        var r=rows.get(0);
        if(!measured&&!"FRESH".equals(r.freshness())&&!realtimeReplayMeasurement(jdbc,now,
                r.target(),r.org(),r.district(),r.source(),r.observedAt(),window))return null;
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
        if(measuredHandoffCurrent(eventId)) {
            boolean newer=riskCases.cases(eventId).stream().allMatch(c->{Long at=riskCases.measuredObservedAt(c);return at!=null&&at>since&&at<=now;});
            return new PilotDepartureWatch.Assessment(newer?PilotDepartureWatch.Presence.STILL_PRESENT:PilotDepartureWatch.Presence.UNKNOWN,null,"CURRENT_MEASURED_CASE_ENTRY");
        }
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

    /** Same case and region only; one unrelated measured risk cannot keep another risk alive. */
    public boolean measuredHandoffCurrent(String eventId) {
        if(riskCases==null)return false;
        var cases=riskCases.cases(eventId);
        return !cases.isEmpty()&&cases.stream().allMatch(c->c.episodeId()!=null&&"ENTERED".equals(c.stage())&&riskCases.currentMeasuredArea(c));
    }
}
