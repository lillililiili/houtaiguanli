package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import com.uav.lowaltitude.modules.directory.api.LocalQaNotificationController;
import com.uav.lowaltitude.modules.directory.application.LocalQaNotificationService;

class LocalQaNotificationGateTest {
    @Test void disabledUnlessExplicitlyEnabled() { absent(false,"local","qa"); }
    @Test void localWithoutQaCannotActivate() { absent(true,"local"); }
    @Test void qaWithoutLocalCannotActivate() { absent(true,"qa"); }
    @Test void productionAlwaysWins() { absent(true,"local","qa","production"); }
    @Test void prodAlwaysWinsEvenWithTest() { absent(true,"test","prod"); }
    private void absent(boolean enabled,String... profiles) {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("qa-gate",Map.of("app.qa.notification-setup.enabled",enabled)));
            context.register(LocalQaNotificationController.class,LocalQaNotificationService.class);context.refresh();
            assertThat(context.getBeansOfType(LocalQaNotificationController.class)).isEmpty();
            assertThat(context.getBeansOfType(LocalQaNotificationService.class)).isEmpty();
        }
    }
}
