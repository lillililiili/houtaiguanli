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

class CommissionDeviceInformationServiceTest {
    private final DeviceAccessPolicy access = mock(DeviceAccessPolicy.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceInformationRepository data = mock(DeviceInformationRepository.class);
    private final ProtocolDataRepository protocol = mock(ProtocolDataRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CommissionDeviceInformationService service = new CommissionDeviceInformationService(access, devices, data,
            protocol, new DeviceInformationAssembler(mapper), mapper, new AppClock(Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC)));
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
    @Test void usesAcceptedMqttMessageIdentityAndShowsMissingWorkParameters() {
        device("LINGYUN_MQTT_V8_6");
        when(data.mqtt("device", false)).thenReturn(Map.of("device_type_abbr", "radar", "device_id", "sensing-id", "last_pt_time", 99_000L, "last_msg_cnt", 0L));
        when(data.latestSense("lingyun:radar:sensing-id", 99_000L, 0L)).thenReturn(Map.of("payload_json", "{\"deviceId\":\"sensor\",\"ptTime\":99000,\"msgCnt\":0,\"objects\":[]}", "received_at", 100_000L));
        var info = service.get("device");
        assertThat(info.taskSupported()).isFalse();
        assertThat(info.sections().stream().filter(s -> s.code().equals("sensing")).findFirst().orElseThrow().fields()).allMatch(f -> f.status().equals("RECEIVED"));
        assertThat(info.sections().stream().filter(s -> s.code().equals("work_parameters")).findFirst().orElseThrow().fields()).allMatch(f -> f.status().equals("NOT_REPORTED"));
        verify(devices, never()).findProfile(anyString());
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
