package com.uav.lowaltitude.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

@Component
public class DeviceAdapterRegistry {

    private final Map<Key, DeviceAdapterPort> adapters;

    public DeviceAdapterRegistry(List<DeviceAdapterPort> values) {
        Map<Key, DeviceAdapterPort> indexed = new LinkedHashMap<>();
        for (DeviceAdapterPort adapter : values) {
            Key key = new Key(adapter.mode(), adapter.protocolCode());
            if (indexed.putIfAbsent(key, adapter) != null)
                throw new IllegalStateException("Duplicate device adapter route " + key);
        }
        adapters = Map.copyOf(indexed);
    }

    public Optional<DeviceAdapterPort> find(SourceMode mode, String protocolCode) {
        DeviceAdapterPort exact = adapters.get(new Key(mode, normalize(protocolCode)));
        if (exact != null) return Optional.of(exact);
        if (mode == SourceMode.mock) return Optional.ofNullable(adapters.get(new Key(mode, DeviceProtocolCodes.MOCK)));
        return Optional.empty();
    }

    public DeviceAdapterPort require(SourceMode mode, String protocolCode) {
        return find(mode, protocolCode).orElseThrow(() ->
                new IllegalStateException("No DeviceAdapterPort for source_mode=" + mode + ", protocol_code=" + protocolCode));
    }

    public boolean supports(SourceMode mode, String protocolCode) { return find(mode, protocolCode).isPresent(); }

    private static String normalize(String value) { return value == null || value.isBlank() ? "" : value.trim(); }
    private record Key(SourceMode mode, String protocolCode) { }
}
