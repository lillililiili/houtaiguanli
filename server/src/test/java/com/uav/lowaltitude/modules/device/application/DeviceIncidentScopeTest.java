package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
import org.junit.jupiter.api.Test;

class DeviceIncidentScopeTest {
    @Test void modulePermissionNeverExposesOrMutatesAnIncidentOutsideDeviceScope() {
        var repository=mock(DeviceRepository.class);var access=mock(DeviceAccessPolicy.class);
        var recovery=mock(DeviceRecoveryService.class);
        var service=new DeviceIncidentService(repository,mock(DeviceService.class),access,new AppClock(),mock(AuditService.class),new ObjectMapper(),recovery);
        String incident=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();
        when(repository.findIncident(incident)).thenReturn(Map.of("incident_id",incident,"device_id",device,"stage","PENDING"));
        when(repository.find(device)).thenReturn(null);
        assertThatThrownBy(()->service.get(incident)).isInstanceOf(ApiException.class).hasMessageContaining("授权范围");
        assertThatThrownBy(()->service.recoveryCheck(incident,UUID.randomUUID().toString())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.reboot(incident,UUID.randomUUID().toString(),"范围回归")).isInstanceOf(ApiException.class);
        verifyNoInteractions(recovery);
        verify(repository,never()).lockForRecovery(device);
    }
}
