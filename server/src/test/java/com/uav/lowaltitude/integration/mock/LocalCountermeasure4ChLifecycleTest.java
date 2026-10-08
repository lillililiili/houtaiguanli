package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class LocalCountermeasure4ChLifecycleTest {
    @Test
    void contextRefreshLeavesTheQaPortForTheRealtimeReceiver() throws Exception {
        // Hold a disposable port so an accidental auto-start cannot go unnoticed:
        // the old lifecycle would fall back to another port and report running.
        try (var receiver = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local", "qa");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("qa-test",
                    Map.of("app.qa.device-setup.enabled", true, "app.dev-seed.enabled", false)));
            context.registerBean(LocalCountermeasure4ChSimulator.class,
                    () -> new LocalCountermeasure4ChSimulator(receiver.getLocalPort()));
            context.refresh();
            var simulator = context.getBean(LocalCountermeasure4ChSimulator.class);
            assertThat(simulator.isRunning()).isFalse();
            assertThat(simulator.port()).isZero();
        }
    }

    @Test
    void isolatedTestsCanStillStartAndStopTheirOwnCounterpart() {
        var simulator = new LocalCountermeasure4ChSimulator(0);
        try {
            simulator.start();
            assertThat(simulator.isRunning()).isTrue();
            assertThat(simulator.port()).isPositive();
        } finally {
            simulator.stop();
        }
        assertThat(simulator.isRunning()).isFalse();
    }
}
