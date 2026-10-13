package com.uav.lowaltitude.testsupport;

import com.uav.lowaltitude.modules.device.application.DeviceCatalogTypes;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pre-retirement protocol rows for isolated compatibility tests, never a registration API bypass in the app. */
public final class HistoricalMqttDeviceFixture {
    private HistoricalMqttDeviceFixture() { }

    public static String insert(MqttRepository repository, Registration registration, long now) {
        assertThat(registration.sourceMode()).isEqualTo("replay");
        assertThatThrownBy(() -> DeviceCatalogTypes.requireRegistrable(registration.deviceTypeAbbr()))
                .isInstanceOf(ApiException.class).hasMessageContaining("已停用");
        return repository.register(registration, now);
    }
}
