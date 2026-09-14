package com.uav.lowaltitude.modules.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.super-admin-recovery", name = "enabled", havingValue = "true")
@Order(40)
public class SuperAdminRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminRecoveryRunner.class);

    private final Environment environment;
    private final SuperAdminRecoveryService service;
    private final ConfigurableApplicationContext context;

    public SuperAdminRecoveryRunner(Environment environment, SuperAdminRecoveryService service,
            ConfigurableApplicationContext context) {
        this.environment = environment;
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", ""))) {
            throw new IllegalStateException("admin recovery requires spring.main.web-application-type=none");
        }
        service.recover();
        log.info("super administrator recovery completed; all sessions were revoked and password change is required");
        SpringApplication.exit(context, () -> 0);
    }
}
