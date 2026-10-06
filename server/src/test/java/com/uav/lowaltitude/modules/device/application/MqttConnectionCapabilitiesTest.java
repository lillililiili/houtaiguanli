package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/** 管理端“数据来源”选项随部署而定：模拟回放只在显式测试环境开放，正式环境只能建真实来源连接。 */
class MqttConnectionCapabilitiesTest {
    final DeviceAccessPolicy permissions = mock(DeviceAccessPolicy.class);

    @Test void replayIsOfferedOnlyWhereSimulationIsExplicitlyAllowed() {
        for (String[] profiles : new String[][]{{"local", "qa"}, {"test"}}) {
            var capabilities = service(profiles).capabilities();
            assertThat(capabilities.sourceModes()).containsExactly("live", "replay");
            assertThat(capabilities.simulationAllowed()).isTrue();
        }
        for (String[] profiles : new String[][]{{}, {"production"}, {"local"}, {"local", "qa", "production"}}) {
            var capabilities = service(profiles).capabilities();
            assertThat(capabilities.sourceModes()).containsExactly("live");
            assertThat(capabilities.simulationAllowed()).isFalse();
        }
    }

    @Test void capabilitiesNeedInterfaceReadPermission() {
        when(permissions.requireInterfacesRead()).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无接口配置权限"));
        assertThatThrownBy(() -> service("local", "qa").capabilities()).isInstanceOf(ApiException.class);
    }

    private MqttConfigurationService service(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new MqttConfigurationService(null, null, permissions, null, null, null, null, null, null, new SimulationPolicy(environment));
    }
}
