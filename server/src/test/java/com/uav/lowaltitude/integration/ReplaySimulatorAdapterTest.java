package com.uav.lowaltitude.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplaySimulatorAdapterTest {

    private HttpServer simulator;

    @BeforeEach
    void startSimulator() throws IOException {
        simulator = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        simulator.createContext("/api/status", exchange -> {
            byte[] body = ("{\"phase\":\"RUNNING\",\"mqtt_connected\":true,"
                    + "\"last_published_at\":1,\"manifest\":{\"devices\":{\"eo\":{\"external_id\":\"no\"}}}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        simulator.start();
    }

    @AfterEach
    void stopSimulator() { if (simulator != null) simulator.stop(0); }

    @Test
    void replayDevicesReadRunningSimulatorBeforeCommissioning() {
        DeviceAdapterRegistry registry = new DeviceAdapterRegistry(List.of(new ReplaySimulatorAdapter()));
        String configuration = "{\"connection\":{\"host\":\"127.0.0.1\",\"port\":"
                + simulator.getAddress().getPort() + "}}";

        assertThat(registry.supports(SourceMode.replay, "LINGYUN_MQTT_V8_6")).isTrue();
        assertThat(registry.require(SourceMode.replay, "EO_EDGE_MQTT_20250826")
                .connect(new DeviceAdapterPort.CommissionWork("task", "no", "device", "no", "EO_EDGE_MQTT_20250826", configuration))
                .resultCode()).isEqualTo("SIMULATOR_CONNECTED");
        assertThat(registry.require(SourceMode.replay, "EO_EDGE_MQTT_20250826")
                .commission(new DeviceAdapterPort.CommissionWork("task", "no", "device", "no", "EO_EDGE_MQTT_20250826", configuration))
                .resultCode()).isEqualTo("SIMULATOR_COMMISSION_PASSED");
    }

    @Test
    void replayDevicesDoNotPassWhenSimulatorIsStopped() throws IOException {
        simulator.removeContext("/api/status");
        simulator.createContext("/api/status", exchange -> {
            byte[] body = "{\"phase\":\"STOPPED\",\"mqtt_connected\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        String configuration = "{\"connection\":{\"host\":\"127.0.0.1\",\"port\":"
                + simulator.getAddress().getPort() + "}}";

        var result = new ReplaySimulatorAdapter().commission(
                new DeviceAdapterPort.CommissionWork("task", "no", "device", "no", "EO_EDGE_MQTT_20250826", configuration));
        assertThat(result.success()).isFalse();
        assertThat(result.resultCode()).isEqualTo("SIMULATOR_NOT_RUNNING");
    }
}
