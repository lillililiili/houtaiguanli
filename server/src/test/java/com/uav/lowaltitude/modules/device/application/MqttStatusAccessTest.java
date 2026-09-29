package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

class MqttStatusAccessTest {
    final MqttRepository data = mock(MqttRepository.class);
    final EoEdgeRepository edges = mock(EoEdgeRepository.class);
    final DeviceAccessPolicy permissions = mock(DeviceAccessPolicy.class);
    final AccessService access = mock(AccessService.class);
    final AppClock clock = mock(AppClock.class);
    final AuthUser reader = new AuthUser("reader", "reader", "监测读取", "role", 1, false, "ALL");
    final MqttConfigurationService service = new MqttConfigurationService(data, edges, permissions, access,
            null, null, clock, null, null, null);

    void fixture(boolean eo) {
        when(permissions.requireMonitoringRead()).thenReturn(reader);
        when(permissions.requireDevicesRead()).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无台账权限"));
        when(clock.nowMillis()).thenReturn(1000L);
        when(data.scopeExists("org", "district")).thenReturn(true);
        var status = new LinkedHashMap<String, Object>(Map.of("lease_until", 2000L, "broker_enabled", true,
                "connection_state", "CONNECTED", "subscribed", true, "last_heartbeat_at", 900L,
                "last_error", "private diagnostic", "broker_id", "private-broker", "heartbeat_json", "private-payload"));
        if (eo) {
            var binding = mock(com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding.class);
            when(binding.ownerOrgId()).thenReturn("org"); when(binding.districtId()).thenReturn("district");
            when(binding.sourceMode()).thenReturn("replay"); when(binding.reportingTopic()).thenReturn("private/topic");
            when(binding.brokerId()).thenReturn("private-broker");
            when(edges.binding("device", false)).thenReturn(binding); when(edges.status("device")).thenReturn(status);
        } else {
            var binding = mock(com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Binding.class);
            when(binding.ownerOrgId()).thenReturn("org"); when(binding.districtId()).thenReturn("district");
            when(binding.sourceMode()).thenReturn("replay"); when(binding.deviceTypeAbbr()).thenReturn("radar");
            when(binding.topic(false)).thenReturn("private/topic");
            when(data.binding("device", false)).thenReturn(binding); when(data.status("device")).thenReturn(status);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void monitoringOnlyReadsOperationalStatusWithoutConnectionOrRawDiagnostics(boolean eo) {
        fixture(eo);
        var result = service.status("device");
        assertThat(result).containsEntry("connection_state", "CONNECTED").containsEntry("last_heartbeat_at", 900L);
        assertThat(result).doesNotContainKeys("broker_id", "heartbeat_json", "reporting_topic", "static_topic");
        assertThat(result.get("last_error")).isNotEqualTo("private diagnostic");
        verify(permissions, never()).requireDevicesRead();
        verify(access).requireTuple("org", "district");
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void monitorPermissionCannotBeSkippedForEitherProtocol(boolean eo) {
        fixture(eo);
        when(permissions.requireMonitoringRead()).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无监测权限"));
        assertThatThrownBy(() -> service.status("device")).isInstanceOf(ApiException.class);
        verify(data, never()).status("device"); verify(edges, never()).status("device");
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void authorizedDeviceOperatorRetainsDiagnostics(boolean eo) {
        fixture(eo); when(permissions.canOperateDevices(reader)).thenReturn(true);
        assertThat(service.status("device")).containsEntry("broker_id", "private-broker");
    }
}
