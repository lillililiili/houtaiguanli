package com.uav.lowaltitude.modules.disposal.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.RunRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.platform.time.AppClock;

@ExtendWith(MockitoExtension.class)
class DisposalReceiptSyncTest {
    private static final Instant NOW = Instant.parse("2026-09-24T03:15:31Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);

    @Mock DisposalRepository repository;
    @Mock DeviceRepository devices;
    @Mock DisposalJammingChain jammingChain;
    @Mock HandoffSubmissionService handoffs;
    @Mock DisposalPolicyRepository policies;
    @Mock EmergencyStopRepository stops;

    private DisposalReceiptSync sync;

    @BeforeEach
    void setup() {
        sync = new DisposalReceiptSync(repository, devices, new AppClock(Clock.fixed(NOW, ZoneOffset.UTC)),
                new ObjectMapper(), jammingChain, handoffs, policies, stops, false);
    }

    /** 新-20：四通道设备回“已打开”不算完成，仍是反制中，记下到时关闭的时刻，并接上信号干扰。 */
    @Test
    void relayOnKeepsCounterExecutingRecordsTheRunAndChainsJamming() {
        AuthorizationRow row = row("auth-1", DisposalRules.COUNTERMEASURE, "cmd-1");
        when(repository.findExecutingByCommand("cmd-1")).thenReturn(row);
        when(devices.findCommand("cmd-1")).thenReturn(Map.of("status", "SUCCEEDED", "result_code", "COUNTERMEASURE_SET_OK",
                "completed_at", NOW.toEpochMilli()));
        when(repository.relayMask("cmd-1")).thenReturn(15);
        when(repository.lockForSystem("auth-1")).thenReturn(row);
        when(policies.active()).thenReturn(new DisposalPolicy("demo-v1", "DEMO", Map.of("device_run_seconds", 60)));
        when(repository.startRun("auth-1", "device-1", "cmd-1", AT, AT.plusSeconds(60), AT)).thenReturn(true);

        sync.syncByCommand("cmd-1");

        verify(repository, never()).transitionFromStatus(anyString(), anyString(), anyString(), any(), any(), any());
        verify(repository).insertEvent(anyString(), eq("auth-1"), eq("RECEIPT"), isNull(), contains("满 60 秒系统自动全部关闭"),
                anyString(), eq(AT));
        verify(jammingChain).scheduleAfterDeviceOn("auth-1");
        verify(jammingChain, never()).scheduleAfterComplete(any());
        verify(handoffs, never()).automaticAfterJamming(any());
    }

    /** 转干扰沿用来源反制的关闭时刻，两条一起关。 */
    @Test
    void chainedJammingOnSharesTheSourceCounterOffTime() {
        AuthorizationRow row = row("auth-2", DisposalRules.JAMMING, "cmd-2");
        OffsetDateTime sourceDue = AT.plusSeconds(55);
        when(repository.findExecutingByCommand("cmd-2")).thenReturn(row);
        when(devices.findCommand("cmd-2")).thenReturn(Map.of("status", "SUCCEEDED", "completed_at", NOW.toEpochMilli()));
        when(repository.relayMask("cmd-2")).thenReturn(13);
        when(repository.lockForSystem("auth-2")).thenReturn(row);
        when(repository.run("auth-2")).thenReturn(null);
        when(stops.parent("auth-2")).thenReturn("auth-1");
        when(repository.run("auth-1")).thenReturn(new RunRow("auth-1", "device-1", "cmd-1", AT.minusSeconds(5), sourceDue,
                null, 0, null, null));
        when(repository.startRun("auth-2", "device-1", "cmd-2", AT, sourceDue, AT)).thenReturn(true);

        sync.syncByCommand("cmd-2");

        verify(repository, never()).transitionFromStatus(anyString(), anyString(), anyString(), any(), any(), any());
        verify(repository).insertEvent(anyString(), eq("auth-2"), eq("RECEIPT"), isNull(), contains("和来源反制一起到时自动全部关闭"),
                anyString(), eq(AT));
        verify(jammingChain, never()).scheduleAfterDeviceOn(any());
        verify(handoffs, never()).automaticAfterJamming(any());
    }

    /** 已经记过运行（读时同步、定时兜底反复走到）：什么都不再做，也不加锁。 */
    @Test
    void relayOnAlreadyRecordedIsANoOp() {
        AuthorizationRow row = row("auth-1", DisposalRules.COUNTERMEASURE, "cmd-1");
        when(devices.findCommand("cmd-1")).thenReturn(Map.of("status", "SUCCEEDED"));
        when(repository.relayMask("cmd-1")).thenReturn(15);
        when(repository.run("auth-1")).thenReturn(new RunRow("auth-1", "device-1", "cmd-1", AT, AT.plusSeconds(60),
                null, 0, null, null));

        sync.syncOne(row);

        verify(repository, never()).lockForSystem(any());
        verify(repository, never()).insertEvent(any(), any(), any(), any(), any(), any(), any());
        verify(jammingChain, never()).scheduleAfterDeviceOn(any());
    }

    @Test
    void failedStartFailsTheAuthorizationAndDoesNotChainJamming() {
        when(repository.findExecutingByCommand("cmd-1")).thenReturn(row("auth-1", DisposalRules.COUNTERMEASURE, "cmd-1"));
        when(devices.findCommand("cmd-1")).thenReturn(Map.of("status", "FAILED", "result_code", "DEVICE_FAILED", "result_detail", "no"));
        when(repository.relayMask("cmd-1")).thenReturn(15);
        when(repository.transitionFromStatus(eq("auth-1"), eq(DisposalRules.EXECUTING), eq(DisposalRules.FAILED),
                any(), eq("DEVICE_FAILED"), eq("no"))).thenReturn(1);

        sync.syncByCommand("cmd-1");

        verify(jammingChain, never()).scheduleAfterComplete(any());
        verify(jammingChain, never()).scheduleAfterDeviceOn(any());
    }

    /** 全部关闭回“成功”：干扰记完成，来源反制一起完成，再自动移送处罚。 */
    @Test
    void allOffCompletesJammingAndItsSourceCounterThenHandsOff() {
        when(repository.findExecutingByCommand("off-1")).thenReturn(row("auth-2", DisposalRules.JAMMING, "off-1"));
        when(devices.findCommand("off-1")).thenReturn(Map.of("status", "SUCCEEDED", "result_code", "COUNTERMEASURE_SET_OK"));
        when(repository.relayMask("off-1")).thenReturn(0);
        when(repository.transitionFromStatus(eq("auth-2"), eq(DisposalRules.EXECUTING), eq(DisposalRules.COMPLETED),
                any(), eq("COUNTERMEASURE_SET_OK"), isNull())).thenReturn(1);
        when(stops.parent("auth-2")).thenReturn("auth-1");
        when(repository.transitionFromStatus(eq("auth-1"), eq(DisposalRules.EXECUTING), eq(DisposalRules.COMPLETED),
                any(), eq("COUNTERMEASURE_SET_OK"), isNull())).thenReturn(1);

        sync.syncByCommand("off-1");

        verify(repository).insertEvent(anyString(), eq("auth-2"), eq("RECEIPT"), isNull(), eq("设备回执：已全部关闭"), anyString(), any());
        verify(repository).insertEvent(anyString(), eq("auth-1"), eq("COMPLETE"), isNull(), isNull(), anyString(), any());
        verify(jammingChain, never()).scheduleAfterComplete(any());
        verify(handoffs).automaticAfterJamming("event-1");
    }

    /** 全部关闭没成功：授权仍是反制中，交给到时关闭的定时任务重试。 */
    @Test
    void failedAllOffLeavesTheAuthorizationExecuting() {
        when(repository.findExecutingByCommand("off-1")).thenReturn(row("auth-1", DisposalRules.COUNTERMEASURE, "off-1"));
        when(devices.findCommand("off-1")).thenReturn(Map.of("status", "TIMED_OUT"));
        when(repository.relayMask("off-1")).thenReturn(0);

        sync.syncByCommand("off-1");

        verify(repository, never()).transitionFromStatus(anyString(), anyString(), anyString(), any(), any(), any());
        verify(repository, never()).insertEvent(any(), any(), any(), any(), any(), any(), any());
    }

    private static AuthorizationRow row(String id, String action, String commandId) {
        return new AuthorizationRow(id, "AUTH-" + id, action, "UAV_EVENT", "event-1", null, "device-1",
                DisposalRules.COUNTERMEASURE_4CH, "反制", "automation-rule-runner", AT, null, null, null,
                AT, AT.plusMinutes(10), DisposalRules.EXECUTING, commandId, null, null, "demo", "org", "district",
                "replay", 1L, null, null, "DIRECT");
    }
}
