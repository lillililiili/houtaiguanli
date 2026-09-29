package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalWeatherRiskService;

class LocalWeatherRiskGateTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withBean(LocalWeatherRiskService.class,()->mock(LocalWeatherRiskService.class))
        .withUserConfiguration(LocalWeatherRiskController.class);
    @Test void defaultsOffEvenInQa() {
        runner.withPropertyValues("spring.profiles.active=local,qa").run(c->assertThat(c).doesNotHaveBean(LocalWeatherRiskController.class));
    }
    @Test void explicitQaOrTestCanEnable() {
        for(String profiles:new String[]{"local,qa","test"})runner.withPropertyValues("spring.profiles.active="+profiles,"app.weather-risk.qa.enabled=true")
            .run(c->assertThat(c).hasSingleBean(LocalWeatherRiskController.class));
    }
    @Test void productionAndLocalOnlyCannotExposeInput() {
        for(String profiles:new String[]{"local","qa","local,qa,prod","test,production"})runner.withPropertyValues("spring.profiles.active="+profiles,"app.weather-risk.qa.enabled=true")
            .run(c->assertThat(c).doesNotHaveBean(LocalWeatherRiskController.class));
    }
}
