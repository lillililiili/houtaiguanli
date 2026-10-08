package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.constraints.*;

/** Explicit simulation facts, not additional fields in a vendor protocol. */
public final class LocalQaDeviceStatusDtos {
    private LocalQaDeviceStatusDtos() { }
    public record Input(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String messageId,
            @NotBlank @Size(max=36) String deviceId,
            @NotBlank @Size(max=36) String sourceId,
            @NotNull @Positive Long observedAt,
            @NotNull @Pattern(regexp="ONLINE|OFFLINE|ABNORMAL|UNKNOWN") String connectivity,
            @NotNull @Pattern(regexp="GOOD|BAD|DEGRADED|UNKNOWN") String healthCode,
            @NotNull Boolean hasAlarm) { }
    public record Receipt(String messageId,String deviceId,long observedAt,long receivedAt,
            String sourceType,boolean simulated) { }
}
