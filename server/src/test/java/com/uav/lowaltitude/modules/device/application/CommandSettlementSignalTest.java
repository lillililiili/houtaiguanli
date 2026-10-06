package com.uav.lowaltitude.modules.device.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.DeviceAdapterRegistry;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.infrastructure.Countermeasure4ChControlRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.LingyunControlRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.disposal.application.DisposalCommandGuard;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * BUG-08：反制指令无论成功、失败、超时还是下发前被取消，都要通知处置结案；
 * 否则处置授权会一直停在“执行中”，页面收不到变化信号，只有打开详情时才被动同步。
 */
class CommandSettlementSignalTest {
    private final long now = 1_000_000L;
    private final AppClock clock = new AppClock(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DisposalCommandGuard guard = mock(DisposalCommandGuard.class);

    private final Countermeasure4ChControlRepository fourChannelControls = mock(Countermeasure4ChControlRepository.class);
    private final Countermeasure4ChControlService fourChannel = new Countermeasure4ChControlService(
            mock(DeviceAccessPolicy.class), devices, fourChannelControls, mock(DeviceAdapterRegistry.class), clock,
            mock(AuditService.class), new ObjectMapper(), events, new MockEnvironment(), guard);

    private final LingyunControlRepository lingyunControls = mock(LingyunControlRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MqttSessionSupervisor> sessions = mock(ObjectProvider.class);
    private final MqttSessionSupervisor supervisor = mock(MqttSessionSupervisor.class);
    private final LingyunControlService lingyun = new LingyunControlService(
            mock(DeviceAccessPolicy.class), devices, mock(MqttRepository.class), lingyunControls, sessions, clock,
            mock(AuditService.class), new ObjectMapper(), events, new MockEnvironment(), guard,
            mock(PlatformTransactionManager.class));

    @Test
    void fourChannelStartCancelledForOfflineDeviceSettlesDisposal() {
        when(fourChannelControls.control("c")).thenReturn(fourChannelCommand());
        when(guard.mayStart("auth")).thenReturn(true);
        when(devices.find("device")).thenReturn(device("OFFLINE"));
        when(fourChannelControls.updateCommand(eq("c"), eq("QUEUED"), eq("CANCELLED"), anyLong(),
                eq("DEVICE_NOT_OPERABLE"), anyString())).thenReturn(1);

        fourChannel.dispatch("c");

        verify(events).publishEvent(new DeviceCommandFinished("c"));
    }

    @Test
    void fourChannelStartCancelledAfterAuthorizationStoppedSettlesDisposal() {
        when(fourChannelControls.control("c")).thenReturn(fourChannelCommand());
        when(guard.mayStart("auth")).thenReturn(false);
        when(fourChannelControls.updateCommand(eq("c"), eq("QUEUED"), eq("CANCELLED"), anyLong(),
                eq("AUTHORIZATION_STOPPED"), anyString())).thenReturn(1);

        fourChannel.dispatch("c");

        verify(events).publishEvent(new DeviceCommandFinished("c"));
    }

    @Test
    void lostRaceDoesNotSignalAgain() {
        when(fourChannelControls.control("c")).thenReturn(fourChannelCommand());
        when(guard.mayStart("auth")).thenReturn(true);
        when(devices.find("device")).thenReturn(device("OFFLINE"));
        when(fourChannelControls.updateCommand(eq("c"), eq("QUEUED"), eq("CANCELLED"), anyLong(),
                eq("DEVICE_NOT_OPERABLE"), anyString())).thenReturn(0);

        fourChannel.dispatch("c");

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void lingyunStartCancelledForOfflineDeviceSettlesDisposal() {
        when(lingyunControls.control("c")).thenReturn(lingyunCommand("QUEUED", now + 10_000));
        when(guard.mayStart("auth")).thenReturn(true);
        when(devices.find("device")).thenReturn(device("OFFLINE"));
        when(lingyunControls.updateCommand(eq("c"), eq("QUEUED"), eq("CANCELLED"), anyLong(),
                eq("DEVICE_NOT_OPERABLE"), anyString())).thenReturn(1);

        lingyun.dispatch("c");

        verify(events).publishEvent(new DeviceCommandFinished("c"));
    }

    @Test
    void lingyunExpiredSendWindowSettlesDisposal() {
        when(lingyunControls.control("c")).thenReturn(lingyunCommand("QUEUED", now));
        when(guard.mayStart("auth")).thenReturn(true);
        when(lingyunControls.updateCommand(eq("c"), eq("QUEUED"), eq("TIMED_OUT"), anyLong(),
                eq("COMMAND_EXPIRED"), anyString())).thenReturn(1);

        lingyun.dispatch("c");

        verify(events).publishEvent(new DeviceCommandFinished("c"));
    }

    @Test
    void lingyunUnknownPublishResultSettlesDisposal() {
        when(lingyunControls.control("c")).thenReturn(
                lingyunCommand("QUEUED", now + 10_000), lingyunCommand("QUEUED", now + 10_000),
                lingyunCommand("SENT", now + 10_000), lingyunCommand("SENT", now + 10_000));
        when(guard.mayStart("auth")).thenReturn(true);
        when(devices.find("device")).thenReturn(device("ONLINE"));
        when(sessions.getIfAvailable()).thenReturn(supervisor);
        when(lingyunControls.updateCommand("c", "QUEUED", "SENT", now, null, null)).thenReturn(1);
        doThrow(new IllegalStateException("connection lost after send")).when(supervisor).publish(anyString(), anyString(), any());
        when(lingyunControls.updateCommand(eq("c"), eq("SENT"), eq("TIMED_OUT"), anyLong(),
                eq("PUBLISH_RESULT_UNKNOWN"), anyString())).thenReturn(1);

        lingyun.dispatch("c");

        verify(events).publishEvent(new DeviceCommandFinished("c"));
    }

    private Map<String, Object> fourChannelCommand() {
        Map<String, Object> command = new HashMap<>();
        command.put("command_id", "c");
        command.put("status", "QUEUED");
        command.put("action", Countermeasure4ChControlService.ACTION_MASK);
        command.put("mask", 15);
        command.put("control_authorization_id", "auth");
        command.put("device_id", "device");
        return command;
    }

    private Map<String, Object> lingyunCommand(String status, long deadline) {
        Map<String, Object> command = new HashMap<>();
        command.put("command_id", "c");
        command.put("status", status);
        command.put("operation_type", 1);
        command.put("operation_cmd", 1);
        command.put("control_authorization_id", "auth");
        command.put("deadline_at", deadline);
        command.put("device_id", "device");
        command.put("provider_code", "lingyun");
        command.put("device_type_abbr", "radar");
        command.put("external_device_id", "ext-1");
        command.put("command_no", "LY-1");
        command.put("broker_id", "broker");
        return command;
    }

    private static Map<String, Object> device(String connectivity) {
        return Map.of("device_id", "device", "enabled", true, "connectivity", connectivity, "health_code", "GOOD");
    }
}
