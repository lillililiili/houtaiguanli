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
    /** 上级（管服平台）计划接口是否可用；available=false 时计划页须提示上级计划数据暂时取不到。时间均为 epoch 毫秒，未知时为空。 */
    public record PlanUpstreamStatus(String status, boolean available, String message, Long configuredAt, Long lastReceivedAt) { }
    /** 合并了同一区域好几份预报时，published_at 是其中最新的发布时间，每个时段的 published_at 是它自己那份的（CDX-P06）。 */
    public record Forecast(String areaName, String providerName, long publishedAt, String sourceMode,
            java.util.List<ForecastPeriod> periods) { }
    /** published_at：这一时段出自哪次发布；只有一份预报（天气模拟服务）时为空、不出现。 */
    public record ForecastPeriod(long from, long to, String summary, double temperatureC,
            double windSpeedMs, double gustMs, int windDirectionDeg,
            int precipitationProbabilityPct, int humidityPct, Long publishedAt) {
        public ForecastPeriod(long from, long to, String summary, double temperatureC, double windSpeedMs, double gustMs,
                int windDirectionDeg, int precipitationProbabilityPct, int humidityPct) {
            this(from, to, summary, temperatureC, windSpeedMs, gustMs, windDirectionDeg, precipitationProbabilityPct, humidityPct, null);
        }
    }
}
