package com.uav.lowaltitude.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReplaySimulatorAdapterTest {

    @Test
    void replayDevicesUseLogicalAdapterForAnyRegisteredProtocol() {
        DeviceAdapterRegistry registry = new DeviceAdapterRegistry(List.of(new ReplaySimulatorAdapter()));

        assertThat(registry.supports(SourceMode.replay, "LINGYUN_MQTT_V8_6")).isTrue();
        assertThat(registry.require(SourceMode.replay, "EO_EDGE_MQTT_20250826")
                .connect(new DeviceAdapterPort.CommissionWork("task", "no", "device", "no", "EO_EDGE_MQTT_20250826", "{}"))
                .resultCode()).isEqualTo("SIMULATOR_CONNECTED");
    }
}
