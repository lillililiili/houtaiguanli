package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import com.uav.lowaltitude.modules.device.api.LocalQaDeviceController;
import com.uav.lowaltitude.modules.device.application.LocalQaDeviceService;

class LocalQaDeviceGateTest {
    @Test void disabledUnlessExplicitlyEnabled() { absent(false,"local","qa"); }
    @Test void localWithoutQaCannotActivate() { absent(true,"local"); }
    @Test void qaWithoutLocalCannotActivate() { absent(true,"qa"); }
    @Test void productionAlwaysWins() { absent(true,"local","qa","production"); }
    @Test void prodAlwaysWinsEvenWithTest() { absent(true,"test","prod"); }
    private void absent(boolean enabled,String... profiles) {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("qa-gate",Map.of("app.qa.device-setup.enabled",enabled)));
            context.register(LocalQaDeviceController.class,LocalQaDeviceService.class,
                com.uav.lowaltitude.modules.device.api.LocalQaDeviceStatusController.class,
                com.uav.lowaltitude.modules.device.application.LocalQaDeviceStatusService.class);context.refresh();
            assertThat(context.getBeansOfType(LocalQaDeviceController.class)).isEmpty();
            assertThat(context.getBeansOfType(LocalQaDeviceService.class)).isEmpty();
            assertThat(context.getBeansOfType(com.uav.lowaltitude.modules.device.api.LocalQaDeviceStatusController.class)).isEmpty();
            assertThat(context.getBeansOfType(com.uav.lowaltitude.modules.device.application.LocalQaDeviceStatusService.class)).isEmpty();
        }
    }
}
