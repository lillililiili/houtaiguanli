package com.uav.lowaltitude.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.uav.lowaltitude.platform.time.AppClock;

class RealtimeHubTest {
    @Test void listenerLossIsReportedWithoutClosingTheBrowserStream() throws Exception {
        var hub = new RealtimeHub(new AppClock(), 2, 60_000);
        hub.setListening(true);
        var mvc = MockMvcBuilders.standaloneSetup(new RealtimeController(hub)).build();
        var result = mvc.perform(get("/api/v1/realtime/events")).andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        hub.setListening(false);
        hub.setListening(false);
        hub.heartbeat();
        hub.setListening(true);
        hub.publish(Set.of("*"));
        String events = result.getResponse().getContentAsString();
        assertThat(events).contains("event:ready", "\"listening\":false", ":ping", "event:change", "\"topics\":[\"*\"]");
        assertThat(events.split("event:status", -1)).hasSize(3);
        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test void changedTopicsContainNoBusinessPayload() throws Exception {
        var hub = new RealtimeHub(new AppClock(), 2, 60_000);
        var mvc = MockMvcBuilders.standaloneSetup(new RealtimeController(hub)).build();
        var result = mvc.perform(get("/api/v1/realtime/events")).andReturn();
        hub.publish(Set.of("device", "plan_check"));
        assertThat(result.getResponse().getContentAsString()).contains("\"topics\":[\"device\",\"plan_check\"]")
                .doesNotContain("device_id", "plan_id", "password", "token");
    }
}
