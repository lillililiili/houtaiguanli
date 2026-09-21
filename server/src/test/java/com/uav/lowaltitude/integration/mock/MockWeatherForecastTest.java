package com.uav.lowaltitude.integration.mock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class MockWeatherForecastTest {
    @Test void simulationRequiresExplicitDevelopmentProfile() {
        for(String[] profiles : new String[][]{{},{"prod"},{"production"},{"local","prod"},{"test","production"}}) {
            var env=new MockEnvironment(); env.setActiveProfiles(profiles);
            var mock=new MockWeatherForecast(env);
            assertThat(mock.available()).isFalse();
            assertThatThrownBy(()->mock.forecast("东营市",1000)).isInstanceOf(IllegalStateException.class);
        }
        for(String profile : new String[]{"local","test"}) {
            var env=new MockEnvironment();env.setActiveProfiles(profile);
            assertThat(new MockWeatherForecast(env).available()).isTrue();
        }
    }
}
