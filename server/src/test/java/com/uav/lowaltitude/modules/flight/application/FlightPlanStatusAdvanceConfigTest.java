package com.uav.lowaltitude.modules.flight.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.uav.lowaltitude.platform.time.AppClock;

class FlightPlanStatusAdvanceConfigTest {
    private static final Instant END = Instant.parse("2026-09-28T20:49:45Z");

    private ApplicationContextRunner context(JdbcTemplate jdbc, Instant now, String profiles) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profiles)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(AppClock.class, () -> new AppClock(Clock.fixed(now, ZoneOffset.UTC)))
                .withUserConfiguration(FlightPlanStatusAdvanceJob.class);
    }

    private JdbcTemplate database() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:plan_expiry_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE flight_plan (plan_id VARCHAR(36) PRIMARY KEY, status_code VARCHAR(32),"
                + " start_at TIMESTAMP WITH TIME ZONE, end_at TIMESTAMP WITH TIME ZONE,"
                + " updated_at TIMESTAMP WITH TIME ZONE, version BIGINT DEFAULT 0)");
        return jdbc;
    }

    private void plan(JdbcTemplate jdbc, String id, String status, Instant start, Instant end) {
        jdbc.update("INSERT INTO flight_plan(plan_id,status_code,start_at,end_at,updated_at) VALUES(?,?,?,?,?)",
                id, status, Timestamp.from(start), Timestamp.from(end), Timestamp.from(start));
    }

    private String status(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT status_code FROM flight_plan WHERE plan_id=?", String.class, id);
    }

    @Test void qaCompletesAnExecutingPlanAtItsExactEnd() {
        JdbcTemplate jdbc = database();
        plan(jdbc, "one-hour", "EXECUTING", END.minusSeconds(3600), END);
        context(jdbc, END.minusMillis(1), "local,qa").run(ctx -> {
            assertThat(ctx).hasSingleBean(FlightPlanStatusAdvanceJob.class);
            ctx.getBean(FlightPlanStatusAdvanceJob.class).poll();
            assertThat(status(jdbc, "one-hour")).isEqualTo("EXECUTING");
        });
        context(jdbc, END, "local,qa").run(ctx -> {
            ctx.getBean(FlightPlanStatusAdvanceJob.class).poll();
            assertThat(status(jdbc, "one-hour")).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT version FROM flight_plan", Long.class)).isEqualTo(1L);
        });
    }

    @Test void qaCatchesUpAcrossRestartWithoutChangingCancelledOrCompletedPlans() {
        JdbcTemplate jdbc = database();
        for (String state : new String[] {"PENDING", "EXECUTING", "CANCELLED", "COMPLETED"}) {
            plan(jdbc, state, state, END.minusSeconds(3600), END);
        }
        context(jdbc, END.plusSeconds(86400), "local,qa").run(ctx -> {
            assertThat(ctx).hasSingleBean(FlightPlanStatusAdvanceJob.class);
            assertThat(ctx.getBean(FlightPlanStatusAdvanceJob.class).advanceOnce()).containsExactly(0, 2);
            assertThat(status(jdbc, "PENDING")).isEqualTo("COMPLETED");
            assertThat(status(jdbc, "EXECUTING")).isEqualTo("COMPLETED");
            assertThat(status(jdbc, "CANCELLED")).isEqualTo("CANCELLED");
        });
        context(jdbc, END.plusSeconds(86460), "local,qa").run(ctx -> {
            assertThat(ctx.getBean(FlightPlanStatusAdvanceJob.class).advanceOnce()).containsExactly(0, 0);
            assertThat(jdbc.queryForObject("SELECT SUM(version) FROM flight_plan", Long.class)).isEqualTo(2L);
        });
    }

    @Test void qaStartsOnlyPlansWhoseTimeWindowHasBegun() {
        JdbcTemplate jdbc = database();
        plan(jdbc, "starts-now", "PENDING", END, END.plusSeconds(3600));
        plan(jdbc, "future", "PENDING", END.plusSeconds(1), END.plusSeconds(3601));
        context(jdbc, END, "local,qa").run(ctx -> {
            assertThat(ctx).hasSingleBean(FlightPlanStatusAdvanceJob.class);
            assertThat(ctx.getBean(FlightPlanStatusAdvanceJob.class).advanceOnce()).containsExactly(1, 0);
            assertThat(status(jdbc, "starts-now")).isEqualTo("EXECUTING");
            assertThat(status(jdbc, "future")).isEqualTo("PENDING");
        });
    }

    @Test void explicitDisableStillFreezesQaFixtures() {
        context(database(), END, "local,qa")
                .withPropertyValues("app.flight.status-advance.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FlightPlanStatusAdvanceJob.class));
    }

    @Test void qaDoesNotEnableAdvancementInProduction() {
        context(database(), END, "local,qa,production")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FlightPlanStatusAdvanceJob.class));
    }
}
