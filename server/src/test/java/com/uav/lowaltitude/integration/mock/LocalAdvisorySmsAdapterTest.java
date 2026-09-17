package com.uav.lowaltitude.integration.mock;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
class LocalAdvisorySmsAdapterTest {
    @Test void productionCannotBypassGuardWithDevelopmentProfile() {
        var environment=new MockEnvironment();environment.setActiveProfiles("production","local");
        assertThat(new LocalAdvisorySmsAdapter(environment).simulationAvailable("mock")).isFalse();
    }
    @Test void onlyLocalOrTestMockReplayCanSimulate() {
        for(String profile:new String[]{"production","default","local","test"}) {
            var env=new MockEnvironment();env.setActiveProfiles(profile);var adapter=new LocalAdvisorySmsAdapter(env);
            boolean development=profile.equals("local")||profile.equals("test");
            assertThat(adapter.simulationAvailable("mock")).isEqualTo(development);
            assertThat(adapter.simulationAvailable("replay")).isEqualTo(development);
            assertThat(adapter.simulationAvailable("live")).isFalse();
            assertThatThrownBy(()->adapter.simulate("live","演示","内容")).hasFieldOrPropertyWithValue("code","SMS_CHANNEL_UNAVAILABLE");
        }
    }
}
