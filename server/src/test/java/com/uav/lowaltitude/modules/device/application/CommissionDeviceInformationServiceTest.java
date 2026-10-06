package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.device.infrastructure.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

class CommissionDeviceInformationServiceTest {
    private final DeviceAccessPolicy access = mock(DeviceAccessPolicy.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceInformationRepository data = mock(DeviceInformationRepository.class);
    private final ProtocolDataRepository protocol = mock(ProtocolDataRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimulationPolicy simulation = mock(SimulationPolicy.class);
    private final CommissionDeviceInformationService service = new CommissionDeviceInformationService(access, devices, data,
            protocol, new DeviceInformationAssembler(mapper), mapper, new AppClock(Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC)), simulation);
    private void device(String code) {
        when(access.requireCommissionRead()).thenReturn(new AuthUser("user", "test", "测试", "role", 1, false, "ASSIGNED"));
        when(devices.find("device")).thenReturn(Map.of("device_id", "device", "protocol_code", code, "source_mode", "live"));
        when(data.inScope("device", "user", "ASSIGNED")).thenReturn(true);
    }
    @Test void rejectsOutOfScopeBeforeReadingProtocolData() {
        device("RADAR_TCP_V3_0_0"); when(data.inScope("device", "user", "ASSIGNED")).thenReturn(false);
        assertThatThrownBy(() -> service.get("device")).isInstanceOf(ApiException.class);
        verifyNoInteractions(protocol);
    }
    @Test void reportsSimulationPolicyWithoutInferringPermissionFromDeviceSource() {
        device("COUNTERMEASURE_TCP_4CH_V2_0");
        assertThat(service.get("device").simulationAllowed()).isFalse();
        when(simulation.allowed()).thenReturn(true);
        assertThat(service.get("device").simulationAllowed()).isTrue();
        when(simulation.allowed()).thenReturn(false);
        assertThat(service.get("device").simulationAllowed()).isFalse();
    }
    @Test void usesAcceptedMqttMessageIdentityAndShowsMissingWorkParameters() {
        device("LINGYUN_MQTT_V8_6");
        when(data.mqtt("device", false)).thenReturn(Map.of("device_type_abbr", "radar", "device_id", "sensing-id", "last_pt_time", 99_000L, "last_msg_cnt", 0L));
        when(data.latestSense("lingyun:radar:sensing-id", 99_000L, 0L)).thenReturn(Map.of("payload_json", "{\"deviceId\":\"sensor\",\"ptTime\":99000,\"msgCnt\":0,\"objects\":[]}", "received_at", 100_000L));
        var info = service.get("device");
        // 现场 MQTT 设备也可调测：平台核对会话、订阅和已接收上报，不向设备下发指令
        assertThat(info.taskSupported()).isTrue();
        assertThat(info.notes()).anyMatch(note -> note.contains("工参与感知报文") && note.contains("不向设备下发指令"));
        assertThat(info.sections().stream().filter(s -> s.code().equals("sensing")).findFirst().orElseThrow().fields()).allMatch(f -> f.status().equals("RECEIVED"));
        assertThat(info.sections().stream().filter(s -> s.code().equals("work_parameters")).findFirst().orElseThrow().fields()).allMatch(f -> f.status().equals("NOT_REPORTED"));
        verify(devices, never()).findProfile(anyString());
    }
    @Test void replaySimulatorMqttDeviceIsCommissionedThroughThePlatformLinkAndMarkedSimulated() {
        when(access.requireCommissionRead()).thenReturn(new AuthUser("user", "test", "测试", "role", 1, false, "ASSIGNED"));
        when(devices.find("device")).thenReturn(Map.of("device_id", "device", "protocol_code", "LINGYUN_MQTT_V8_6",
                "source_mode", "replay", "simulated", true));
        when(data.inScope("device", "user", "ASSIGNED")).thenReturn(true);
        when(data.mqtt("device", false)).thenReturn(Map.of("device_type_abbr", "weather", "device_id", "weather-id"));
        var info = service.get("device");
        assertThat(info.taskSupported()).isTrue();
        assertThat(info.notes()).anyMatch(note -> note.contains("模拟器上报") && note.contains("结果标为模拟"));
        assertThat(info.notes()).noneMatch(note -> note.contains("感知报文"));
    }
    @Test void preservesEntireEoHeartbeatAndSeparatesCameraReceptionTime() {
        device("EO_EDGE_MQTT_20250826");
        when(data.mqtt("device", true)).thenReturn(Map.of("heartbeat_json", "{\"event\":\"Heartbeat\",\"timestamp\":99000,\"metadata\":{\"workState\":2,\"taskId\":\"tracking-1\"}}", "last_heartbeat_at", 100_000L,
                "camera_status_json", "{\"zoomIndex\":0}", "camera_received_at", 1L));
        var info = service.get("device");
        assertThat(info.sections().stream().filter(s -> s.code().equals("eo_heartbeat")).findFirst().orElseThrow().fields().get(6).value().asText()).isEqualTo("tracking-1");
        assertThat(info.sections().stream().filter(s -> s.code().equals("eo_camera")).findFirst().orElseThrow().fields().get(6).status()).isEqualTo("STALE");
    }
}
