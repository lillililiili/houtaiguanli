package com.uav.lowaltitude.modules.integrationconfig.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/** 天气风险依据说明里的时段写北京时间，不写毫秒数（CDX-P06）。 */
class WeatherForecastRiskWindowTest {
    private static long at(String text) { return OffsetDateTime.parse(text).toInstant().toEpochMilli(); }

    @Test
    void sameDayWindowWritesTheDateOnce() {
        assertThat(WeatherForecastRiskService.window(at("2026-10-07T08:30:00Z"), at("2026-10-07T09:00:00Z")))
                .isEqualTo("10月7日 16:30–17:00");
    }

    @Test
    void windowAcrossMidnightWritesBothDates() {
        assertThat(WeatherForecastRiskService.window(at("2026-10-07T15:30:00Z"), at("2026-10-07T16:30:00Z")))
                .isEqualTo("10月7日 23:30–10月8日 00:30");
    }
}
