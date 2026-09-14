package com.uav.lowaltitude.integration;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.platform.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceModeGuardTest {

    @Test
    void rejectsBlankMode() {
        AppProperties props = new AppProperties();
        props.setSourceMode(" ");
        assertThatThrownBy(() -> new SourceModeGuard(props, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.source-mode");
    }

    @Test
    void rejectsLiveModeWithoutAdapter() {
        AppProperties props = new AppProperties();
        props.setSourceMode("live");
        assertThatThrownBy(() -> new SourceModeGuard(props, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live");
    }

    @Test
    void acceptsMockWhenAdapterPresent() {
        AppProperties props = new AppProperties();
        props.setSourceMode("mock");
        assertThatCode(() -> new SourceModeGuard(props, List.of(new MockAdapter())))
                .doesNotThrowAnyException();
    }
}
