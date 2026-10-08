package com.uav.lowaltitude.modules.integrationconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.FlightPlanDto;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.Period;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.WeatherInput;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalInterfaceRepository.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LocalForecastReadServiceTest {
    private final LocalInterfaceRepository repository = mock(LocalInterfaceRepository.class);
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final LocalForecastReadService service = new LocalForecastReadService(repository, json);
    private final String planId = UUID.randomUUID().toString();
    private final String area = "测试区域-" + UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({"100000, mock", "1900000000000, replay"})
    void newerNonOverlappingForecastDoesNotHideOlderMatchingForecast(long start, String mode) throws Exception {
        var valid = period(start - 20, start + 50);
        when(repository.weatherMessages()).thenReturn(List.of(
                row(null, area, start + 10, period(start + 100, start + 200)),
                row(null, area, start, valid)));
        var result = service.read(plan(start, start + 100, mode));
        assertThat(result.forecast().publishedAt()).isEqualTo(start);
        assertThat(result.forecast().periods().get(0).from()).isEqualTo(valid.from());
        assertThat(result.forecast().periods().get(0).to()).isEqualTo(valid.to());
        assertThat(result.forecast().sourceMode()).isEqualTo(mode);
    }

    @ParameterizedTest
    @CsvSource({"1, 99, false", "1, 100, false", "200, 300, false", "201, 300, false",
            "99, 101, true", "199, 201, true", "100, 200, true", "1, 300, true"})
    void planBoundForecastAlsoRequiresStrictTimeOverlap(long from, long to, boolean expected) throws Exception {
        when(repository.weatherMessages()).thenReturn(List.of(row(planId, area, 1, period(from, to))));
        assertThat(service.read(plan(100L, 200L, "mock")) != null).isEqualTo(expected);
    }

    @Test
    void incompleteOrInvalidPlanWindowCannotMatch() throws Exception {
        when(repository.weatherMessages()).thenReturn(List.of(row(planId, area, 1, period(1, 1000))));
        for (Long[] window : List.of(new Long[]{null, 200L}, new Long[]{100L, null},
                new Long[]{200L, 100L}, new Long[]{100L, 100L})) {
            assertThat(service.read(plan(window[0], window[1], "mock"))).isNull();
        }
    }

    @Test
    void wrongAreaCannotDisplaceMatchingForecastAndLatestEligibleWins() throws Exception {
        when(repository.weatherMessages()).thenReturn(List.of(
                row(null, "其他区域", 30, period(100, 200)),
                row(null, area, 20, period(100, 200)),
                row(null, area, 10, period(100, 200))));
        assertThat(service.read(plan(100L, 200L, "replay")).forecast().publishedAt()).isEqualTo(20);
    }

    @Test
    void mixedPeriodsKeepOriginalFactsWhenOnePeriodOverlaps() throws Exception {
        when(repository.weatherMessages()).thenReturn(List.of(row(null, area, 1,
                period(1, 100), period(150, 250), period(300, 400))));
        var result = service.read(plan(100L, 200L, "mock"));
        assertThat(result.forecast().publishedAt()).isEqualTo(1);
        assertThat(result.forecast().periods()).hasSize(3);
        assertThat(result.forecast().periods().get(1).to()).isEqualTo(250);
    }

    /** CDX-P06：一个计划受同一区域 6 份预报影响，天气页签只显示了 1 份。 */
    @Test
    void everyOverlappingAreaForecastIsListedByStartAndNewestPublicationWinsPerSlot() throws Exception {
        when(repository.weatherMessages()).thenReturn(List.of(
                row(null, "其他区域", 40, period(100, 200, "雷雨")),
                row(null, area, 30, period(150, 200, "小雨")),
                row(null, area, 25, period(200, 260, "晴")),
                row(null, area, 20, period(100, 150, "阴")),
                row(null, area, 10, period(150, 200, "多云"))));
        var forecast = service.read(plan(100L, 200L, "mock")).forecast();
        assertThat(forecast.publishedAt()).isEqualTo(30);
        assertThat(forecast.periods()).extracting(p -> p.from() + "-" + p.to() + " " + p.summary() + " @" + p.publishedAt())
                .containsExactly("100-150 阴 @20", "150-200 小雨 @30");
    }

    private FlightPlanDto plan(Long start, Long end, String mode) {
        return new FlightPlanDto(planId, "PLAN-" + planId, "PENDING", null, mode, "SN",
                start, end, null, null, null, List.of(), 0, 0, 0, null, area, null);
    }

    private Row row(String plan, String areaName, long published, Period... periods) throws Exception {
        String id = UUID.randomUUID().toString();
        var input = new WeatherInput(id, plan, areaName, published, List.of(periods));
        return new Row(id, id, "WEATHER_FORECAST", "IN", plan, "operator", "READY",
                json.writeValueAsString(input), "{}", published, 0);
    }

    private Period period(long from, long to) {
        return period(from, to, "多云");
    }

    private Period period(long from, long to, String summary) {
        return new Period(from, to, summary, 22D, 4D, 6D, 180, 20, 75);
    }
}
