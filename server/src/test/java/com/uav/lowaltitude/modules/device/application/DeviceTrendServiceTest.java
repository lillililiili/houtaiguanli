package com.uav.lowaltitude.modules.device.application;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.List;
import com.uav.lowaltitude.modules.device.infrastructure.*;
import com.uav.lowaltitude.platform.time.AppClock;

class DeviceTrendServiceTest {
    @Test void validatesRangeAndDeviceBeforeReadingStatistics() {
        var access=mock(DeviceAccessPolicy.class); var devices=mock(DeviceRepository.class);
        var repository=mock(DeviceTrendRepository.class); var clock=mock(AppClock.class);
        var service=new DeviceTrendService(access,devices,repository,clock);
        when(devices.find("missing")).thenReturn(null);
        assertThatThrownBy(()->service.get("missing","1h")).hasMessageContaining("设备不存在");
        when(devices.find("a")).thenReturn(Map.of("protocol_code","RADAR_TCP_V3_0_0","simulated",false));
        assertThatThrownBy(()->service.get("a","all")).hasMessageContaining("时间范围");
        verifyNoInteractions(repository);
        when(clock.nowMillis()).thenReturn(700_000_000L);
        when(repository.series(anyString(),anyLong(),anyLong(),anyLong(),anyBoolean(),anyBoolean(),anyBoolean())).thenReturn(List.of());
        var result=service.get("a","7d");
        assertThat(result.to()-result.from()).isEqualTo(604_800_000L);
        assertThat(result.bucketMs()).isEqualTo(3_600_000L);
        assertThat(result.sensingSupported()).isTrue();
        verify(access,times(3)).requireMonitoringRead();
    }
    @Test void deniedPermissionCannotQueryDevicesOrTrends() {
        var access=mock(DeviceAccessPolicy.class); var devices=mock(DeviceRepository.class); var repository=mock(DeviceTrendRepository.class);
        doThrow(new IllegalStateException("denied")).when(access).requireMonitoringRead();
        assertThatThrownBy(()->new DeviceTrendService(access,devices,repository,mock(AppClock.class)).get("a","1h")).hasMessage("denied");
        verifyNoInteractions(devices,repository);
    }
}
