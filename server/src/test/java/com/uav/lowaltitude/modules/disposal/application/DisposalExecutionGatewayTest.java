package com.uav.lowaltitude.modules.disposal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.application.Countermeasure4ChControlService;
import com.uav.lowaltitude.modules.device.application.LingyunControlService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 执行受阻分四种（决策 13-14 / 13-22），四种的补救方不同：换设备 / 等厂家 / 运维补登记 / 现场处理。
 *
 * 这些判断必须**在调 A 之前**做完：A 的 enqueue 带 @Transactional，它在调用方事务里抛异常会把整个事务
 * 标成 rollback-only，随后写的"受阻事件"根本提交不了（实测表现为 UnexpectedRollbackException → 500）。
 * 所以本测试同时盯住两件事：分类对不对，以及**被拦下时有没有真的没去调 A**。
 */
class DisposalExecutionGatewayTest {

    private static DisposalPolicy policy(int operationCmd) {
        return new DisposalPolicy("demo-v1", "DEMO", Map.of(
                "command_map", Map.of("COUNTERMEASURE", Map.of("operation_type", 1, "operation_cmd", operationCmd))));
    }

    private LingyunControlService control;
    private Countermeasure4ChControlService countermeasure;
    private MqttRepository mqtt;
    private DeviceRepository devices;

    private DisposalExecutionGateway gateway(boolean bound, boolean enabled, boolean online) {
        return gateway(bound, enabled, online, "LINGYUN_MQTT_V8_6");
    }

    private DisposalExecutionGateway gateway(boolean bound, boolean enabled, boolean online, String protocol) {
        control = mock(LingyunControlService.class);
        countermeasure = mock(Countermeasure4ChControlService.class);
        mqtt = mock(MqttRepository.class);
        devices = mock(DeviceRepository.class);
        when(mqtt.binding(anyString(), anyBoolean())).thenReturn(bound ? mock(Binding.class) : null);
        Map<String, Object> device = new HashMap<>();
        device.put("enabled", enabled);
        device.put("connectivity", online ? "ONLINE" : "OFFLINE");
        device.put("protocol_code", protocol);
        when(devices.find(anyString())).thenReturn(device);
        when(control.enqueue(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyString()))
                .thenReturn("cmd-1");
        when(countermeasure.enqueue(anyString(), anyString(), anyString(), anyString(), nullable(String.class),
                any(), anyString())).thenReturn("cmd-4ch");
        return new DisposalExecutionGateway(control, countermeasure, mqtt, devices);
    }

    private DisposalExecutionGateway.Result dispatch(DisposalExecutionGateway gateway, int cmd) {
        return gateway.dispatch("dev-1", "key-1", "auth-1", policy(cmd), "COUNTERMEASURE", Map.of(), "理由");
    }

    /** 60003 干扰迫降已映射 ifr；50000 是协议标明未有真实设备的码，family() 仍为 null。 */
    private static final int OPENED = 60003, NOT_OPENED = 50000;

    @Test
    void acceptedCarriesTheCommandId() {
        DisposalExecutionGateway gateway = gateway(true, true, true);
        assertThat(dispatch(gateway, OPENED)).isEqualTo(new DisposalExecutionGateway.Accepted("cmd-1"));
    }

    @Test
    void unopenedCommandCodeIsProtocolNotOpenedAndNeverReachesA() {
        DisposalExecutionGateway gateway = gateway(true, true, true);
        DisposalExecutionGateway.Rejected rejected =
                (DisposalExecutionGateway.Rejected) dispatch(gateway, NOT_OPENED);
        assertThat(rejected.eventKind()).isEqualTo("PROTOCOL_NOT_OPENED");
        assertThat(rejected.errorCode()).isEqualTo("DEVICE_CONTROL_UNAVAILABLE");
        verify(control, never()).enqueue(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyString());
    }

    @Test
    void openedJammingCommandReachesAWhenBoundAndOnline() {
        DisposalExecutionGateway gateway = gateway(true, true, true);
        assertThat(dispatch(gateway, OPENED)).isEqualTo(new DisposalExecutionGateway.Accepted("cmd-1"));
        verify(control).enqueue(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyString());
    }

    @Test
    void unboundDeviceIsNotBoundAndNeverReachesA() {
        DisposalExecutionGateway gateway = gateway(false, true, true);
        DisposalExecutionGateway.Rejected rejected = (DisposalExecutionGateway.Rejected) dispatch(gateway, OPENED);
        assertThat(rejected.eventKind()).isEqualTo("DEVICE_NOT_BOUND");
        assertThat(rejected.errorCode()).isEqualTo("DEVICE_NOT_BOUND");
        verify(control, never()).enqueue(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyString());
    }

    @Test
    void disabledOrOfflineDeviceIsOfflineAndNeverReachesA() {
        for (DisposalExecutionGateway gateway : new DisposalExecutionGateway[]{
                gateway(true, false, true), gateway(true, true, false)}) {
            DisposalExecutionGateway.Rejected rejected = (DisposalExecutionGateway.Rejected) dispatch(gateway, OPENED);
            assertThat(rejected.eventKind()).isEqualTo("DEVICE_OFFLINE");
            assertThat(rejected.errorCode()).isEqualTo("DEVICE_OFFLINE");
        }
        verify(control, never()).enqueue(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyString());
    }

    @Test
    void fourChannelOnNonFourChannelDeviceIsCapabilityAndNeverReachesA() {
        DisposalExecutionGateway gateway = gateway(false, true, true, "RADAR_TCP_V3_0_0");
        DisposalExecutionGateway.Rejected rejected =
                (DisposalExecutionGateway.Rejected) gateway.dispatch4ch("dev-1", "key-1", "auth-1",
                        DisposalRules.COUNTERMEASURE, "理由");
        assertThat(rejected.eventKind()).isEqualTo("DEVICE_CONTROL_UNAVAILABLE");
        verify(countermeasure, never()).enqueue(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), any(), anyString());
    }

    @Test
    void fourChannelJammingReachesAWhenOnline() {
        DisposalExecutionGateway gateway = gateway(false, true, true,
                DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0);
        assertThat(gateway.dispatch4ch("dev-1", "key-1", "auth-1", DisposalRules.JAMMING, "理由"))
                .isEqualTo(new DisposalExecutionGateway.Accepted("cmd-4ch"));
        verify(countermeasure).enqueue(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), any(), anyString());
    }

    @Test
    void fourChannelRejectsDecoyAndDispersalWithoutCallingA() {
        DisposalExecutionGateway gateway = gateway(false, true, true,
                DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0);
        assertThatThrownBy(() -> gateway.dispatch4ch("dev-1", "key-1", "auth-1", DisposalRules.DECOY, "理由"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "VALIDATION_ERROR");
        assertThatThrownBy(() -> gateway.dispatch4ch("dev-1", "key-1", "auth-1", DisposalRules.DISPERSAL, "理由"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "VALIDATION_ERROR");
        verify(countermeasure, never()).enqueue(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), any(), anyString());
    }
}
