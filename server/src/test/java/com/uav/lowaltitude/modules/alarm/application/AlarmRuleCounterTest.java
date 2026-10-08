package com.uav.lowaltitude.modules.alarm.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.application.DisposalExecutionGateway;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@ExtendWith(MockitoExtension.class)
class AlarmRuleCounterTest {
    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");
    private static final EventRow EVENT = new EventRow("event-1", "alarm-1", "target-1", "CONFIRMED", "org", "district",
            NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC), 3L, "mock");

    @Mock UavEventRepository events;
    @Mock UavAdvisoryRepository advisory;
    @Mock UavAdvisoryService phases;
    @Mock DisposalRepository repository;
    @Mock DisposalPolicyRepository policies;
    @Mock DisposalExecutionGateway gateway;
    @Mock EmergencyStopRepository emergencyStops;
    @Mock DeviceRepository devices;
    @Mock AuditService audit;
    @Mock com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility eligibility;

    private AlarmRuleCounter counter;

    @BeforeEach
    void setup() {
        counter = new AlarmRuleCounter(events, advisory, phases, repository, policies, gateway, emergencyStops, devices,
                audit, new AppClock(Clock.fixed(NOW, ZoneOffset.UTC)), new ObjectMapper(), eligibility);
    }

    @Test
    void waitsUntilTheAlarmReachesCounterPhase() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.WATCHING);

        counter.launchIfPassed("event-1", "run-1");

        verify(repository, never()).insert(any());
    }

    @Test
    void doesNotChooseAmongSeveralDevices() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        when(devices.operableCounterDevices(eq("ifr"), eq("mock"), eq("org"), eq("district"), isNull(), eq("ifr"), eq(false)))
                .thenReturn(List.of("dev-1", "dev-2"));

        counter.launchIfPassed("event-1", "run-1");

        verify(repository, never()).insert(any());
        verify(gateway, never()).dispatchAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void launchesOneDirectCounterWithoutAnApprover() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        when(devices.operableCounterDevices(eq("ifr"), eq("mock"), eq("org"), eq("district"), isNull(), eq("ifr"), eq(false)))
                .thenReturn(List.of("dev-1"));
        when(repository.nextSequence(anyString())).thenReturn(7);
        when(repository.authorizeDirect(anyString(), eq(0L), any(), any(), anyString())).thenReturn(1);
        when(gateway.dispatchAs(any(), eq("dev-1"), anyString(), anyString(), any(), eq(DisposalRules.COUNTERMEASURE), eq(Map.of()), anyString()))
                .thenReturn(new DisposalExecutionGateway.Accepted("command-1"));
        when(repository.transition(anyString(), eq(1L), eq(DisposalRules.EXECUTING), any(), eq("command-1"), isNull(), isNull())).thenReturn(1);

        counter.launchIfPassed("event-1", "run-1");

        verify(repository).insert(org.mockito.ArgumentMatchers.argThat(row ->
                AutomationPrincipal.USER_ID.equals(row.requestedBy())
                        && DisposalRules.REQUESTED.equals(row.status())
                        && "dev-1".equals(row.deviceId())
                        && DisposalRules.LINGYUN_B.equals(row.channel())));
        verify(repository).authorizeDirect(anyString(), eq(0L), any(OffsetDateTime.class), any(OffsetDateTime.class),
                eq("反制规则已满足，系统按直接授权发起，未指定审批人。"));
        verify(gateway, never()).dispatch4chAs(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mockEventUsesTheSingleSimulatedController() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        when(devices.operableCounterDevices(eq("ifr"), eq("mock"), eq("org"), eq("district"), isNull(), eq("ifr"), eq(false)))
                .thenReturn(List.of());
        when(devices.operableCounterDevices(eq("countermeasure"), eq("live"), eq("org"), eq("district"),
                eq(com.uav.lowaltitude.integration.device.DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0), isNull(), eq(true)))
                .thenReturn(List.of("cm4"));
        when(repository.nextSequence(anyString())).thenReturn(8);
        when(repository.authorizeDirect(anyString(), eq(0L), any(), any(), anyString())).thenReturn(1);
        when(gateway.dispatch4chAs(any(), eq("cm4"), anyString(), anyString(), eq(DisposalRules.COUNTERMEASURE), anyString()))
                .thenReturn(new DisposalExecutionGateway.Accepted("command-4"));
        when(repository.transition(anyString(), eq(1L), eq(DisposalRules.EXECUTING), any(), eq("command-4"), isNull(), isNull())).thenReturn(1);

        counter.launchIfPassed("event-1", "run-1");

        verify(repository).insert(org.mockito.ArgumentMatchers.argThat(row ->
                "cm4".equals(row.deviceId()) && DisposalRules.COUNTERMEASURE_4CH.equals(row.channel())));
        verify(gateway, never()).dispatchAs(any(), any(), any(), any(), any(), any(), any(), any());
        verify(audit).record(eq(AutomationPrincipal.USER_ID), eq(AutomationPrincipal.ACCOUNT), eq(AutomationPrincipal.ROLE),
                eq("disposal"), eq("disposal_direct_authorized"), eq("disposal_authorization"), anyString(),
                org.mockito.ArgumentMatchers.contains("approved_by="), eq("SUCCESS"), eq(""), eq(""));
    }

    private void ready() {
        when(eligibility.allowsRun("counter", "event-1", "run-1")).thenReturn(true);
        when(repository.actor(AutomationPrincipal.USER_ID)).thenReturn(new AuthUser(AutomationPrincipal.USER_ID,
                AutomationPrincipal.ACCOUNT, "自动规则", AutomationPrincipal.ROLE, 0, false, "ALL"));
        when(events.lock(eq("event-1"), any(AccessDecision.class))).thenReturn(EVENT);
        org.mockito.Mockito.lenient().when(advisory.counterBlockReason("event-1")).thenReturn("");
    }

    @Test void revokedOrUnrelatedPassCannotCreateAnAuthorization() {
        counter.launchIfPassed("event-1", "run-1");
        org.mockito.Mockito.verifyNoInteractions(repository, gateway, events, audit);
    }

    @Test void rulesChangedWhileWaitingForDeviceLockCannotCreateAnAuthorization() {
        ready();
        when(eligibility.allowsRun("counter", "event-1", "run-1")).thenReturn(true, false);
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        when(devices.operableCounterDevices(eq("ifr"), eq("mock"), eq("org"), eq("district"), isNull(), eq("ifr"), eq(false)))
                .thenReturn(List.of("dev-1"));
        counter.launchIfPassed("event-1", "run-1");
        verify(emergencyStops).lockDevice("dev-1");
        verify(repository, never()).insert(any());
        org.mockito.Mockito.verifyNoInteractions(gateway, audit);
    }

    @Test void busyDeviceSkipsTheRoundWithoutCreatingAQueuedAuthorization() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        simulatedController();
        when(repository.deviceRunningOther("cm4", null)).thenReturn(true);

        counter.launchIfPassed("event-1", "run-1");

        verify(repository, never()).insert(any());
        verify(repository, never()).insertEvent(anyString(), anyString(), anyString(), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(gateway, audit);
    }

    @Test void commandInFlightOnTheDeviceAlsoWaits() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        simulatedController();
        when(devices.hasActiveWork("cm4")).thenReturn(true);

        counter.launchIfPassed("event-1", "run-1");

        verify(repository, never()).insert(any());
        org.mockito.Mockito.verifyNoInteractions(gateway, audit);
    }

    @Test void queuedAutomaticCounterIsDispatchedOnceTheDeviceIsFree() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        var queued = authorization("auth-q", DisposalRules.APPROVED, AutomationPrincipal.USER_ID, null, null);
        when(repository.counterAttempts("event-1")).thenReturn(List.of(queued));
        when(repository.lockForSystem("auth-q")).thenReturn(queued);
        when(gateway.dispatch4chAs(any(), eq("cm4"), anyString(), eq("auth-q"), eq(DisposalRules.COUNTERMEASURE), anyString()))
                .thenReturn(new DisposalExecutionGateway.Accepted("command-q"));
        when(repository.transition(eq("auth-q"), eq(1L), eq(DisposalRules.EXECUTING), any(), eq("command-q"), isNull(), isNull()))
                .thenReturn(1);

        counter.launchIfPassed("event-1", "run-1");

        verify(repository, never()).insert(any());
        verify(emergencyStops).lockDevice("cm4");
        verify(repository).insertEvent(anyString(), eq("auth-q"), eq("EXECUTE"), eq(AutomationPrincipal.USER_ID),
                eq("反制设备空出来了，系统接着下发这条排队的自动反制。"), anyString(), any());
    }

    @Test void queuedAutomaticCounterWaitsQuietlyWhileTheDeviceIsStillOn() {
        ready();
        when(phases.phaseForAutomation("event-1")).thenReturn(NotifyFlow.Phase.AWAIT_COUNTER);
        when(policies.active()).thenReturn(policy());
        var queued = authorization("auth-q", DisposalRules.APPROVED, AutomationPrincipal.USER_ID, null, null);
        when(repository.counterAttempts("event-1")).thenReturn(List.of(queued));
        when(repository.lockForSystem("auth-q")).thenReturn(queued);
        when(repository.deviceRunningOther("cm4", "auth-q")).thenReturn(true);

        counter.launchIfPassed("event-1", "run-1");

        verify(repository, never()).insertEvent(anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(repository, never()).transition(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(gateway, audit);
    }

    @Test void anyOtherCounterHistoryStopsAutomaticLaunches() {
        ready();
        when(repository.counterAttempts("event-1")).thenReturn(List.of(
                authorization("auth-m", DisposalRules.APPROVED, "operator-1", null, null)));

        counter.launchIfPassed("event-1", "run-1");

        verify(repository, never()).insert(any());
        org.mockito.Mockito.verifyNoInteractions(gateway, audit, phases, policies);
    }

    private void simulatedController() {
        when(devices.operableCounterDevices(eq("ifr"), eq("mock"), eq("org"), eq("district"), isNull(), eq("ifr"), eq(false)))
                .thenReturn(List.of());
        when(devices.operableCounterDevices(eq("countermeasure"), eq("live"), eq("org"), eq("district"),
                eq(com.uav.lowaltitude.integration.device.DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0), isNull(), eq(true)))
                .thenReturn(List.of("cm4"));
    }

    static DisposalRepository.AuthorizationRow authorization(String id, String status, String requester, String commandId,
            String resultCode) {
        OffsetDateTime from = NOW.minusSeconds(30).atOffset(ZoneOffset.UTC);
        return new DisposalRepository.AuthorizationRow(id, "AUTH-" + id, DisposalRules.COUNTERMEASURE, "UAV_EVENT", "event-1",
                "target-1", "cm4", DisposalRules.COUNTERMEASURE_4CH, "反制规则已满足，系统自动发起。", requester, from, null, null,
                null, from, from.plusMinutes(10), status, commandId, resultCode, null, "demo-v1", "org", "district", "mock",
                1L, null, null, AutomationPrincipal.USER_ID.equals(requester) ? "DIRECT" : "REVIEW");
    }

    private static DisposalPolicy policy() {
        return new DisposalPolicy("demo-v1", "DEMO", Map.of(
                "max_active_per_subject", 1,
                "time_limit_min", Map.of("COUNTERMEASURE", 10),
                "command_map", Map.of("COUNTERMEASURE", Map.of("operation_type", 1, "operation_cmd", 60003))));
    }
}
