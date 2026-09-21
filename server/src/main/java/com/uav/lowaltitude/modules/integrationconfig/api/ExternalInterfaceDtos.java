package com.uav.lowaltitude.modules.integrationconfig.api;

import jakarta.validation.constraints.*;

public final class ExternalInterfaceDtos {
    private ExternalInterfaceDtos() { }
    public record Input(@NotNull @Min(0) Long version, @NotBlank @Size(max=128) String name,
            @Size(max=64) String sourceCode, @Size(max=16) String direction,
            @Size(max=512) String endpoint, @Size(max=128) String credentialRef,
            @Size(max=512) String allowedCidrs, @Size(max=128) String areaName,
            @Min(1) @Max(10080) Integer intervalMinutes, @Min(1) @Max(10080) Integer validityMinutes, @Size(max=16) String sourceMode) { }
    public record Configuration(String kind, String name, String sourceCode, String direction,
            String endpoint, String credentialRef, String allowedCidrs, String areaName,
            Integer intervalMinutes, Integer validityMinutes, long version, Long updatedAt,
            String status, boolean enabled, String message, String sourceMode) { }
    public record ForecastAvailability(String planId, String status, String message, Forecast forecast) { }
    public record Forecast(String areaName, String providerName, long publishedAt, String sourceMode,
            java.util.List<ForecastPeriod> periods) { }
    public record ForecastPeriod(long from, long to, String summary, double temperatureC,
            double windSpeedMs, double gustMs, int windDirectionDeg,
            int precipitationProbabilityPct, int humidityPct) { }
}
