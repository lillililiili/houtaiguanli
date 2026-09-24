package com.uav.lowaltitude.modules.disposal.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.platform.time.AppClock;

@ExtendWith(MockitoExtension.class)
class DisposalReceiptSyncTest {
    private static final Instant NOW = Instant.parse("2026-09-24T03:15:31Z");

    @Mock DisposalRepository repository;
    @Mock DeviceRepository devices;
    @Mock DisposalJammingChain jammingChain;
    @Mock HandoffSubmissionService handoffs;

    private DisposalReceiptSync sync;

    @BeforeEach
    void setup() {
        sync = new DisposalReceiptSync(repository, devices, new AppClock(Clock.fixed(NOW, ZoneOffset.UTC)),
                new ObjectMapper(), jammingChain, handoffs, false);
    }

    @Test
    void successfulCounterCommandCompletesTheAuthorizationAndChainsJamming() {
        when(repository.findExecutingByCommand("cmd-1")).thenReturn(row(DisposalRules.COUNTERMEASURE));
        when(devices.findCommand("cmd-1")).thenReturn(Map.of("status", "SUCCEEDED", "result_code", "COUNTERMEASURE_SET_OK", "result_detail", "ok"));
        when(repository.transitionFromStatus(eq("auth-1"), eq(DisposalRules.EXECUTING), eq(DisposalRules.COMPLETED),
                any(), eq("COUNTERMEASURE_SET_OK"), eq("ok"))).thenReturn(1);

        sync.syncByCommand("cmd-1");

        verify(jammingChain).scheduleAfterComplete("auth-1");
        verify(handoffs, never()).automaticAfterJamming(any());
    }

    @Test
    void failedCommandDoesNotChainJamming() {
        when(repository.findExecutingByCommand("cmd-1")).thenReturn(row(DisposalRules.COUNTERMEASURE));
        when(devices.findCommand("cmd-1")).thenReturn(Map.of("status", "FAILED", "result_code", "DEVICE_FAILED", "result_detail", "no"));
        when(repository.transitionFromStatus(eq("auth-1"), eq(DisposalRules.EXECUTING), eq(DisposalRules.FAILED),
                any(), eq("DEVICE_FAILED"), eq("no"))).thenReturn(1);

        sync.syncByCommand("cmd-1");

        verify(jammingChain, never()).scheduleAfterComplete(any());
    }

    private static AuthorizationRow row(String action) {
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
        return new AuthorizationRow("auth-1", "AUTH-1", action, "UAV_EVENT", "event-1", null, "device-1",
                DisposalRules.COUNTERMEASURE_4CH, "反制", "automation-rule-runner", at, null, null, null,
                at, at.plusMinutes(10), DisposalRules.EXECUTING, "cmd-1", null, null, "demo", "org", "district",
                "replay", 1L, null, null, "DIRECT");
    }
}
