package com.uav.lowaltitude.modules.disposal.application;

import java.util.Objects;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.RunRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.alarm.application.AutomaticCounterNotificationGate;

/** Durable provenance for newly issued automatic counters; historical automatic authorizations stay inert. */
@Component
public class AutomaticCounterAuthorization {
    private com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPredictionEventGate(com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions) { this.predictions=predictions; }
    public static final String CONTRACT = "RULE_COUNTER_V2";
    private final DisposalRepository authorizations;
    private final AutomationRuntimeRepository runs;
    private final AutomationRuntimeEligibility eligibility;
    private final ObjectMapper json;
    private final SimulationPolicy simulation;
    private final boolean qaDevices;
    private final AppClock clock;
    private final long maxAge;
    private final AutomaticCounterNotificationGate notifications;

    public AutomaticCounterAuthorization(DisposalRepository authorizations, AutomationRuntimeRepository runs,
            AutomationRuntimeEligibility eligibility, ObjectMapper json, SimulationPolicy simulation,
            @Value("${app.qa.device-setup.enabled:false}") boolean qaDevices, AppClock clock,
            @Value("${app.automation-rules.fact-max-age-ms:30000}") long maxAge,
            AutomaticCounterNotificationGate notifications) {
        this.authorizations = authorizations;
        this.runs = runs;
        this.eligibility = eligibility;
        this.json = json;
        this.simulation = simulation;
        this.qaDevices = qaDevices;
        this.clock = clock;
        this.maxAge = maxAge;
        this.notifications = notifications;
    }

    public List<String> devices(String sourceMode, String org, String district) {
        return authorizations.automaticCounterDevices(sourceMode, org, district, clock.nowMillis(), maxAge,
                qaDevices && simulation.allowed());
    }

    public RunRow passingRun(String eventId, String runId) {
        if (predictions != null && predictions.blocksAutomatic("counter",eventId)) return null;
        if (!eligibility.allowsRun("counter", eventId, runId)) return null;
        RunRow run = runs.run(runId);
        return run != null && "counter".equals(run.category()) && eventId.equals(run.eventId())
                && "PASS".equals(run.status()) ? run : null;
    }

    /** A later observation may have a different run ID; it must retain the originally authorized rule version. */
    public boolean mayStart(AuthorizationRow row) {
        if (row != null && predictions != null && predictions.blocksAutomatic("counter",row.subjectId())) return false;
        RunRow original = origin(row);
        if (original == null || !Set.of("APPROVED", "EXECUTING").contains(row.status())
                || authorizations.automaticStopCovered(row.authorizationId())) return false;
        var current = runs.state("counter", row.subjectId());
        if (current == null || current.version() != original.version()
                || !sameContext(original, runs.run(current.runId()))) return false;
        var available = devices(row.sourceMode(), row.ownerOrgId(), row.districtId());
        return available.size() == 1 && available.get(0).equals(row.deviceId())
                && eligibility.allowsRun("counter", row.subjectId(), current.runId())
                && notifications.allows(row.subjectId());
    }

    public boolean belongsToVersion(AuthorizationRow row, RunRow current) {
        RunRow original = origin(row);
        return original != null && sameContext(original, current);
    }

    private static boolean sameContext(RunRow original, RunRow current) {
        return current != null && "counter".equals(current.category()) && "PASS".equals(current.status())
                && original.version() == current.version()
                && Objects.equals(original.eventId(), current.eventId())
                && Objects.equals(original.targetId(), current.targetId())
                && Objects.equals(original.mode(), current.mode());
    }

    RunRow origin(AuthorizationRow row) {
        if (row != null && predictions != null && predictions.blocksAutomatic("counter",row.subjectId())) return null;
        if (row == null || !AutomationPrincipal.USER_ID.equals(row.requestedBy())
                || !"DIRECT".equals(row.authorizationMode()) || !"UAV_EVENT".equals(row.subjectKind())
                || !"COUNTERMEASURE".equals(row.actionType()) || !"COUNTERMEASURE_4CH".equals(row.channel())
                || row.targetId() == null || row.requestedAt() == null || row.approvedBy() != null) return null;
        var grants = authorizations.events(row.authorizationId()).stream()
                .filter(event -> "DIRECT_AUTHORIZE".equals(event.eventKind())).toList();
        if (grants.size() != 1 || !AutomationPrincipal.USER_ID.equals(grants.get(0).actorId())) return null;
        try {
            var snapshot = json.readTree(grants.get(0).snapshot());
            if (!CONTRACT.equals(snapshot.path("automation_contract").asText())
                    || !snapshot.path("group_version").isIntegralNumber()) return null;
            RunRow original = runs.run(snapshot.path("automation_run_id").asText());
            return original != null && "counter".equals(original.category()) && "PASS".equals(original.status())
                    && Objects.equals(original.eventId(), row.subjectId())
                    && Objects.equals(original.targetId(), row.targetId())
                    && Objects.equals(original.mode(), row.sourceMode())
                    && original.version() == snapshot.path("group_version").longValue()
                    && original.at() <= row.requestedAt().toInstant().toEpochMilli() ? original : null;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            return null;
        }
    }
}
