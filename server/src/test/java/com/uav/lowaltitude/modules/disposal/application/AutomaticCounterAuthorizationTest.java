package com.uav.lowaltitude.modules.disposal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.RunRow;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.State;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.EventRow;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.alarm.application.AutomaticCounterNotificationGate;

@ExtendWith(MockitoExtension.class)
class AutomaticCounterAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    @Mock DisposalRepository authorizations;
    @Mock AutomationRuntimeRepository runs;
    @Mock AutomationRuntimeEligibility eligibility;
    @Mock SimulationPolicy simulation;
    @Mock AutomaticCounterNotificationGate notifications;
    private AutomaticCounterAuthorization guard;
    private final String event = UUID.randomUUID().toString(), target = UUID.randomUUID().toString();
    private final String device = UUID.randomUUID().toString(), runId = UUID.randomUUID().toString();

    @BeforeEach void setup() {
        guard = new AutomaticCounterAuthorization(authorizations, runs, eligibility, new ObjectMapper(), simulation,
                true, new AppClock(Clock.fixed(NOW, ZoneOffset.UTC)), 30000, notifications);
    }

    @Test void freshPassCanAdvanceObservationWithoutChangingTheOriginalGroupVersion() {
        var row = row("APPROVED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        origin(row, contract(17), run(17, target, "replay"));
        when(runs.state("counter", event)).thenReturn(new State("counter", event, 17, "new-observation", "PASS", null, "{}", "sig"));
        when(runs.run("new-observation")).thenReturn(run(17, target, "replay"));
        when(authorizations.automaticCounterDevices(eq("replay"), eq("org"), eq("district"), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(List.of(device));
        when(eligibility.allowsRun("counter", event, "new-observation")).thenReturn(true);
        when(notifications.allows(event)).thenReturn(true, false);
        assertThat(guard.mayStart(row)).isTrue();
        verify(eligibility).allowsRun("counter", event, "new-observation");
        assertThat(guard.mayStart(row)).as("Queued dispatch must recheck notification evidence despite the same passing rule").isFalse();
        verify(notifications, times(2)).allows(event);
    }

    @Test void linkedObservedEventCannotResumeRuleCounterV2() {
        var predictions=mock(com.uav.lowaltitude.modules.prediction.application.PredictionEventGate.class);
        guard.setPredictionEventGate(predictions);
        when(predictions.blocksAutomatic("counter",event)).thenReturn(true);
        assertThat(guard.passingRun(event,runId)).isNull();
        assertThat(guard.mayStart(row("APPROVED","live","COUNTERMEASURE","COUNTERMEASURE_4CH"))).isFalse();
        verifyNoInteractions(authorizations,runs,eligibility,notifications);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"automation_contract\":\"RULE_COUNTER_V1\"}", "not-json",
            "{\"automation_contract\":\"RULE_COUNTER_V2\",\"group_version\":\"17\"}"})
    void historicalMissingOrMalformedBindingCannotBecomeExecutable(String snapshot) {
        var row = row("APPROVED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        when(authorizations.events(row.authorizationId())).thenReturn(List.of(grant(row, snapshot)));
        assertThat(guard.mayStart(row)).isFalse();
        verifyNoInteractions(eligibility, runs);
    }

    @Test void duplicateGrantCannotRetrofitHistoricalAuthorization() {
        var row = row("APPROVED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        when(authorizations.events(row.authorizationId())).thenReturn(List.of(grant(row, "{}"), grant(row, contract(17))));
        assertThat(guard.origin(row)).isNull();
    }

    @Test void bindingRejectsAnotherTargetSourceVersionAndRunCreatedAfterAuthorization() {
        var row = row("APPROVED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        origin(row, contract(17), run(17, "other-target", "replay"));
        assertThat(guard.origin(row)).isNull();
        when(runs.run(runId)).thenReturn(run(17, target, "live"));
        assertThat(guard.origin(row)).isNull();
        when(runs.run(runId)).thenReturn(run(18, target, "replay"));
        assertThat(guard.origin(row)).isNull();
        when(runs.run(runId)).thenReturn(new RunRow(runId, "counter", event, target, 17, "PASS", "",
                NOW.plusSeconds(1).toEpochMilli(), NOW.toEpochMilli(), "replay", "[]"));
        assertThat(guard.origin(row)).isNull();
    }

    @Test void changedVersionNonPassAndAmbiguousDeviceCannotDispatch() {
        var row = row("APPROVED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        origin(row, contract(17), run(17, target, "replay"));
        when(runs.state("counter", event)).thenReturn(new State("counter", event, 18, "current", "PASS", null, "{}", "sig"));
        assertThat(guard.mayStart(row)).isFalse();
        when(runs.state("counter", event)).thenReturn(new State("counter", event, 17, "current", "NOT_MATCHED", null, "{}", "sig"));
        when(runs.run("current")).thenReturn(run(17, target, "replay"));
        when(authorizations.automaticCounterDevices(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(List.of(device));
        assertThat(guard.mayStart(row)).isFalse();
        when(authorizations.automaticCounterDevices(anyString(), anyString(), anyString(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(List.of(device, "another"));
        assertThat(guard.mayStart(row)).isFalse();
    }

    @Test void currentRunMustKeepTheOriginalTargetSourceAndCategoryEvenAtTheSameVersion() {
        var row = row("APPROVED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        origin(row, contract(17), run(17, target, "replay"));
        when(runs.state("counter", event)).thenReturn(new State("counter", event, 17, "current", "PASS", null, "{}", "sig"));
        when(runs.run("current")).thenReturn(run(17, "replaced-target", "replay"));
        assertThat(guard.mayStart(row)).isFalse();
        when(runs.run("current")).thenReturn(run(17, target, "live"));
        assertThat(guard.mayStart(row)).isFalse();
        when(runs.run("current")).thenReturn(new RunRow("current", "verify", event, target, 17, "PASS", "", NOW.toEpochMilli(), NOW.toEpochMilli(), "replay", "[]"));
        assertThat(guard.mayStart(row)).isFalse();
        verifyNoInteractions(eligibility);
    }

    @ParameterizedTest @ValueSource(strings = {"STOPPED", "COMPLETED", "FAILED", "EXPIRED"})
    void terminalAuthorizationCannotRestartDespitePersistentPass(String status) {
        var row = row(status, "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        origin(row, contract(17), run(17, target, "replay"));
        assertThat(guard.mayStart(row)).isFalse();
        verifyNoInteractions(eligibility);
    }

    @Test void retiredChannelsAndJammingAreNeverRestored() {
        assertThat(guard.mayStart(row("APPROVED", "replay", "JAMMING", "LINGYUN_B"))).isFalse();
        assertThat(guard.mayStart(row("APPROVED", "replay", "COUNTERMEASURE", "LINGYUN_B"))).isFalse();
        verifyNoInteractions(authorizations, runs, eligibility);
    }

    @Test void emergencyStopCoverageBlocksEvenAnAuthorizationStillShowingApproved() {
        var row = row("APPROVED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        origin(row, contract(17), run(17, target, "replay"));
        when(authorizations.automaticStopCovered(row.authorizationId())).thenReturn(true);
        assertThat(guard.mayStart(row)).isFalse();
        verifyNoInteractions(eligibility);
    }

    @Test void retryRequiresOriginalVersionAndSameEventTargetAndSource() {
        var row = row("FAILED", "replay", "COUNTERMEASURE", "COUNTERMEASURE_4CH");
        origin(row, contract(17), run(17, target, "replay"));
        assertThat(guard.belongsToVersion(row, run(17, target, "replay"))).isTrue();
        assertThat(guard.belongsToVersion(row, run(18, target, "replay"))).isFalse();
        assertThat(guard.belongsToVersion(row, run(17, "another-target", "replay"))).isFalse();
        assertThat(guard.belongsToVersion(row, run(17, target, "live"))).isFalse();
    }

    @Test void simulationRequiresBothTheEnvironmentAndQaDeviceGate() {
        when(simulation.allowed()).thenReturn(false);
        guard.devices("replay", "org", "district");
        verify(authorizations).automaticCounterDevices("replay", "org", "district", NOW.toEpochMilli(), 30000, false);
        when(simulation.allowed()).thenReturn(true);
        guard.devices("replay", "org", "district");
        verify(authorizations).automaticCounterDevices("replay", "org", "district", NOW.toEpochMilli(), 30000, true);
    }

    private void origin(AuthorizationRow row, String snapshot, RunRow original) {
        when(authorizations.events(row.authorizationId())).thenReturn(List.of(grant(row, snapshot)));
        when(runs.run(runId)).thenReturn(original);
    }
    private String contract(long version) {
        return "{\"automation_contract\":\"RULE_COUNTER_V2\",\"group_version\":" + version + ",\"automation_run_id\":\"" + runId + "\"}";
    }
    private EventRow grant(AuthorizationRow row, String snapshot) {
        return new EventRow(UUID.randomUUID().toString(), row.authorizationId(), "DIRECT_AUTHORIZE", AutomationPrincipal.USER_ID,
                "", snapshot, NOW.atOffset(ZoneOffset.UTC));
    }
    private RunRow run(long version, String runTarget, String mode) {
        return new RunRow(runId, "counter", event, runTarget, version, "PASS", "", NOW.minusSeconds(1).toEpochMilli(), NOW.toEpochMilli(), mode, "[]");
    }
    private AuthorizationRow row(String status, String mode, String action, String channel) {
        var at = NOW.atOffset(ZoneOffset.UTC);
        return new AuthorizationRow("auth", "AUTH", action, "UAV_EVENT", event, target, device, channel, "", AutomationPrincipal.USER_ID,
                at, null, null, null, at, at.plusMinutes(10), status, null, null, null, "policy", "org", "district", mode, 1, null, null, "DIRECT");
    }
}
