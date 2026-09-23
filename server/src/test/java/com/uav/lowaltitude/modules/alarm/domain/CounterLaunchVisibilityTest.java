package com.uav.lowaltitude.modules.alarm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow.Phase;

class CounterLaunchVisibilityTest {
    @Test void buttonAppearsOnlyAtAwaitCounter() {
        assertThat(CounterLaunchVisibility.visible(Phase.AWAIT_COUNTER)).isTrue();
        assertThat(CounterLaunchVisibility.visible(Phase.AUTO_SMS)).isFalse();
        assertThat(CounterLaunchVisibility.visible(Phase.WATCHING)).isFalse();
        assertThat(CounterLaunchVisibility.visible(Phase.AUTO_CALL)).isFalse();
        assertThat(CounterLaunchVisibility.visible(null)).isFalse();
    }
}
