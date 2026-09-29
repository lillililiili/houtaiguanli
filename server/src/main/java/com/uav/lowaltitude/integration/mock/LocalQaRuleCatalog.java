package com.uav.lowaltitude.integration.mock;

import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local QA configuration. No users, targets, plans, alarms or replay fixtures. */
@Component
@Profile("local & qa & !prod & !production")
@Order(65)
public class LocalQaRuleCatalog implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public LocalQaRuleCatalog(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        new LocalStage7RuleEngineSeeder(jdbc).prepareRuleCatalog(Instant.now(),false);
    }
}
