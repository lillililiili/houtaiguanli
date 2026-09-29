package com.uav.lowaltitude.platform.config;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.uav.lowaltitude.integration.MockAdapter;
import com.uav.lowaltitude.integration.mock.MockWeatherForecast;
import com.uav.lowaltitude.platform.api.ApiException;

class SimulationPolicyTest {
    @Test void simulationNeedsExplicitQaAndProductionAlwaysWins() {
        for(String[] profiles : List.of(new String[]{}, new String[]{"local"}, new String[]{"qa"},
                new String[]{"local","qa","production"}, new String[]{"test","prod"})) {
            MockEnvironment env=environment(profiles); SimulationPolicy policy=new SimulationPolicy(env);
            assertThat(policy.allowed()).isFalse();
            assertThatThrownBy(()->policy.requireSourceMode("replay")).isInstanceOf(ApiException.class);
            assertThatThrownBy(policy::requireSimulation).isInstanceOf(ApiException.class);
            assertThatCode(()->policy.requireSourceMode("live")).doesNotThrowAnyException();
            assertThat(new MockWeatherForecast(env).available()).isFalse();
        }
        for(String[] profiles : List.of(new String[]{"local","qa"},new String[]{"test"}))
            assertThat(new SimulationPolicy(environment(profiles)).allowed()).isTrue();
    }
    @Test void formalConfigurationFailsClosedForEverySimulationSwitch() {
        for(String key:List.of("app.dev-seed.enabled","app.device.mock-adapter.enabled",
                "app.fusion.replay.run-on-start","app.rule-engine.replay.run-on-start","app.rule-engine.allow-demo-active")) {
            var env=environment("production").withProperty(key,"true");
            assertThatThrownBy(()->new SimulationPolicy(env).validateConfiguration()).isInstanceOf(IllegalStateException.class).hasMessageContaining(key);
        }
        assertThatThrownBy(()->new SimulationPolicy(environment("production").withProperty("app.source-mode","mock")).validateConfiguration()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new SimulationPolicy(environment("production").withProperty("app.handoff.channel","mock")).validateConfiguration()).isInstanceOf(IllegalStateException.class);
        assertThatCode(()->new SimulationPolicy(environment("production")).validateConfiguration()).doesNotThrowAnyException();
        assertThat(new AppProperties().getSourceMode()).isEqualTo("live");
    }
    @Test void forcedMockAdapterFlagCannotInstallAdapterInFormalContext() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment("production","local","qa").withProperty("app.device.mock-adapter.enabled","true"));
            context.register(MockAdapter.class); context.refresh();
            assertThat(context.getBeansOfType(MockAdapter.class)).isEmpty();
        }
    }
    static MockEnvironment environment(String... profiles) { var env=new MockEnvironment();env.setActiveProfiles(profiles);return env; }
}
