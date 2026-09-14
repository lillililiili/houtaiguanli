package com.uav.lowaltitude.modules.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

class ReportPeriodResolverTest {

    private final ReportPeriodResolver resolver = new ReportPeriodResolver(
            new AppClock(Clock.fixed(Instant.parse("2026-03-04T04:00:00Z"), ZoneOffset.UTC)));

    @Test
    void resolvesDailyAndNaturalWeekAcrossYear() {
        var daily = resolver.resolve("DAILY", "2026-02-28");
        assertThat(daily.from().toString()).isEqualTo("2026-02-28");
        assertThat(daily.to().toString()).isEqualTo("2026-02-28");

        var week = resolver.resolve("WEEKLY", "2026-01-01");
        assertThat(week.from().toString()).isEqualTo("2025-12-29");
        assertThat(week.to().toString()).isEqualTo("2026-01-04");
    }

    @Test
    void clampsCurrentWeekAndMonthToShanghaiToday() {
        var week = resolver.resolve("WEEKLY", "2026-03-04");
        assertThat(week.from().toString()).isEqualTo("2026-03-02");
        assertThat(week.to().toString()).isEqualTo("2026-03-04");
        var month = resolver.resolve("MONTHLY", "2026-03-01");
        assertThat(month.from().toString()).isEqualTo("2026-03-01");
        assertThat(month.to().toString()).isEqualTo("2026-03-04");
    }

    @Test
    void resolvesLeapMonthAndRejectsFutureOrInvalidType() {
        var leap = resolver.resolve("MONTHLY", "2024-02-10");
        assertThat(leap.from().toString()).isEqualTo("2024-02-01");
        assertThat(leap.to().toString()).isEqualTo("2024-02-29");
        assertThatThrownBy(() -> resolver.resolve("DAILY", "2026-03-05")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> resolver.resolve("YEARLY", "2026-03-01")).isInstanceOf(ApiException.class);
    }
}
