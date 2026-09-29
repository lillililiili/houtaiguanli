package com.uav.lowaltitude.modules.integrationconfig.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalWeatherRiskService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/** Explicit simulated warning input, independent from plan weather forecasts. */
@RestController @Profile(SimulationPolicy.PROFILE)
@ConditionalOnProperty(name="app.weather-risk.qa.enabled",havingValue="true")
@RequestMapping("/api/v1/local-interface-simulator/weather-risks")
public class LocalWeatherRiskController {
    private final LocalWeatherRiskService service;
    public LocalWeatherRiskController(LocalWeatherRiskService service) { this.service=service; }
    @PostMapping public ApiResponse<Map<String,Object>> receive(@Valid @RequestBody Input input) {
        return ApiResponse.ok(service.receive(input));
    }
    public record Input(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String messageId,
        @NotBlank @Size(max=36) String planId,
        @NotNull @Pattern(regexp="WEATHER_STRONG_WIND|WEATHER_THUNDERSTORM|WEATHER_LOW_VISIBILITY") String reasonCode,
        @NotNull @Pattern(regexp="LOW|MEDIUM|HIGH|CRITICAL") String severity,
        @NotBlank @Size(max=900) String reasonText,
        @NotNull @Positive Long publishedAt,@NotNull @Positive Long validFrom,@NotNull @Positive Long validTo,
        @NotNull @Size(min=4,max=101) List<@NotNull @Size(min=2,max=2) List<@NotNull Double>> polygon,
        @DecimalMin("0") @DecimalMax("150") BigDecimal windSpeedMps,
        @DecimalMin("0") @DecimalMax("360") BigDecimal windFromDegrees,
        @DecimalMin("0") @DecimalMax("1000000") BigDecimal visibilityM) { }
}
