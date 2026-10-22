package com.uav.lowaltitude.modules.device.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Broker;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.time.AppClock;

class EoCoordinateReceiptTest {
    @ParameterizedTest
    @ValueSource(strings = {"", ",\"coordinate_system\":\"GCJ02\"", ",\"coordinateSystem\":4326"})
    void invalidObservationDoesNotDiscardRealStartReceiptOrRefreshSensing(String declaration) {
        var edges = mock(EoEdgeRepository.class, RETURNS_DEEP_STUBS);
        var mqtt = mock(MqttRepository.class);
        var clock = mock(AppClock.class);
        String device = UUID.randomUUID().toString(), task = UUID.randomUUID().toString(), command = UUID.randomUUID().toString();
        Binding binding = new Binding(device, "fusion-device", "ops-source", "source", "edge", "broker",
                "external", "live", true, null, 1, "org", "district");
        Broker broker = mock(Broker.class);
        when(clock.nowMillis()).thenReturn(1000L);
        when(mqtt.fence("broker", "owner", 1000L)).thenReturn(true);
        when(mqtt.broker("broker", false)).thenReturn(broker);
        when(broker.enabled()).thenReturn(true);
        when(broker.sourceMode()).thenReturn("live");
        when(edges.bindingByExternal("edge", "external", true)).thenReturn(binding);
        when(edges.task(task)).thenReturn(Map.of("ops_device_id", device, "status", "OPEN", "created_at", 100L,
                "begin_command_id", command));
        when(edges.command(command)).thenReturn(Map.of("status", "SENT", "issued_at", 200L));
        when(edges.updateCommand(eq(command), eq("SENT"), eq("SUCCEEDED"), eq(1000L), eq("200"), anyString())).thenReturn(1);
        String raw = "{\"event\":\"BeginTracking\",\"edgeId\":\"edge\",\"timestamp\":500,\"metadata\":{"
                + "\"deviceId\":\"external\",\"taskId\":\"" + task + "\",\"codeStatus\":200,\"workState\":1,"
                + "\"aiStatus\":{\"longitude\":116.75,\"latitude\":39.25" + declaration + "}}}";
        new EoEdgeIngressService(edges, mqtt, clock, new ObjectMapper()).receive("broker", "owner",
                binding.reportingTopic(), raw.getBytes(StandardCharsets.UTF_8), 1, 1, false, false, 1000L);
        verify(edges).addControlReceipt(command, "200", 500L, 1000L, raw);
        verify(edges, never()).inbox(any(), any(), anyLong());
        verify(edges, never()).report(any(), anyLong());
        verify(edges, never()).trackingReport(anyString(), anyLong(), anyLong());
        verify(mqtt).diagnostic(eq("broker"), eq(device), eq(binding.reportingTopic()), anyString(), eq(1000L),
                eq("REJECTED"), eq(declaration.isEmpty() ? "COORDINATE_SYSTEM_REQUIRED" : "COORDINATE_SYSTEM_NOT_SUPPORTED"));
    }
}
