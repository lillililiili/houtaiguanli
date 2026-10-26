package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/** 核实规则通过后，把仍待核实的告警事件记为属实。不创建反制授权，也不发送处罚通知。 */
@Service
public class AlarmRuleVerification {
    private UavRiskCaseIdentity cases;
    @org.springframework.beans.factory.annotation.Autowired public void cases(UavRiskCaseIdentity value){cases=value;}
    private com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService responseOrder;
    @org.springframework.beans.factory.annotation.Autowired public void responseOrder(com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService value){responseOrder=value;}
    private com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPredictionEventGate(com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions) { this.predictions=predictions; }
    public static final String NOTE = "核实规则已满足，系统核实属实。";
    private final JdbcTemplate jdbc;
    private final UavEventRepository events;
    private final AuditService audit;
    private final AppClock clock;
    private final AutomationRuntimeEligibility eligibility;
    public AlarmRuleVerification(JdbcTemplate jdbc, UavEventRepository events, AuditService audit, AppClock clock,
            AutomationRuntimeEligibility eligibility) {
        this.jdbc = jdbc; this.events = events; this.audit = audit; this.clock = clock; this.eligibility = eligibility;
    }
    @Transactional
    public void confirmIfPassed(String eventId, String runId) {
        if (eventId == null || eventId.isBlank() || runId == null || runId.isBlank()) return;
        if(predictions!=null)predictions.lockEpisode(eventId);
        if (predictions != null && predictions.blocksAutomatic("verify",eventId)) return;
        boolean riskVerified=false;
        if(cases!=null&&cases.hasCases(eventId)){
            var selected=cases.cases(eventId);if(selected.size()!=1)return;
            var c=selected.get(0);if(c.manualCounterOnly()||!java.util.Set.of("ENTERED","OBSERVED").contains(c.stage())||!"PENDING_VERIFICATION".equals(cases.details(c.riskCaseId()).get("verification_status")))return;
            if(!cases.currentMeasuredArea(c)||!eligibility.allowsRun("verify",eventId,runId))return;
            var versions=jdbc.queryForList("SELECT version FROM uav_event WHERE event_id=? FOR UPDATE",Long.class,eventId);if(versions.isEmpty())return;
            jdbc.update("INSERT INTO uav_risk_case_verification(verification_id,risk_case_id,case_version,event_id,event_version,conclusion,basis_kind,actor_id,note,created_at,trigger_mode,automation_run_id) VALUES(?,?,?,?,?,'CONFIRMED','OBSERVED',NULL,?,?,'AUTO',?)",
                    UUID.randomUUID().toString(),c.riskCaseId(),c.version()+1,eventId,versions.get(0),NOTE,clock.nowMillis(),runId);
            jdbc.update("UPDATE uav_risk_case SET version=version+1 WHERE risk_case_id=?",c.riskCaseId());
            if(responseOrder!=null){var current=events.find(eventId,new com.uav.lowaltitude.modules.identity.domain.AccessDecision("system:response-order",com.uav.lowaltitude.modules.identity.domain.ScopeMode.ALL));if(current!=null)responseOrder.ensureLocked(current);}
            audit.record(null,"AUTO_VERIFY","SYSTEM","alarm","uav_risk_case_verified","uav_risk_case",c.riskCaseId(),"conclusion=CONFIRMED; automation_run_id="+runId+"; disposition_only=true","SUCCESS","","");
            // Only the unique independent measured event retains its original event-level automatic verdict.
            if(cases.caseCount(eventId)>1)return;
            riskVerified=true;
        }
        var rows = jdbc.query("SELECT state_code, version FROM uav_event WHERE event_id=?",
                (rs, n) -> new State(rs.getString("state_code"), rs.getLong("version")), eventId);
        if (rows.isEmpty() || !"PENDING_VERIFICATION".equals(rows.get(0).state)) return;
        if (!riskVerified&&!eligibility.allowsRun("verify", eventId, runId)) return;
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
