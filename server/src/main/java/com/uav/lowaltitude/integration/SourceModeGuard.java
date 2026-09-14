package com.uav.lowaltitude.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.platform.config.AppProperties;

@Component
public class SourceModeGuard {

    public SourceModeGuard(AppProperties properties, List<AdapterPort> adapters) {
        String raw = properties.getSourceMode();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("app.source-mode is required");
        }
        SourceMode expected;
        try {
            expected = SourceMode.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown app.source-mode=" + raw.trim(), ex);
        }
        boolean present = adapters.stream().anyMatch(adapter -> adapter.mode() == expected);
        if (!present) {
            throw new IllegalStateException("No AdapterPort for app.source-mode=" + expected);
        }
    }
}
