package com.uav.lowaltitude.modules.integrationconfig.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Explicit request contracts for the local normalized observation simulator. */
public final class LocalObservationSimulatorDtos {
    private LocalObservationSimulatorDtos() { }

    public record ObservationDeviceInput(
            @NotBlank @Size(max = 64) String messageId,
            @NotBlank @Size(max = 128) String name,
            @NotNull BigDecimal longitude,
            @NotNull BigDecimal latitude,
            @NotBlank @Size(max = 36) String ownerOrgId,
            @NotBlank @Size(max = 36) String districtId) { }

    public record WeatherDeviceInput(
            @NotBlank @Size(max = 64) String messageId,
            @NotBlank @Size(max = 128) String deviceNo,
            @NotBlank @Size(max = 128) String name,
            @NotNull BigDecimal longitude,
            @NotNull BigDecimal latitude,
            @NotBlank @Size(max = 36) String ownerOrgId,
            @NotBlank @Size(max = 36) String districtId) { }

    public record TargetObservationsInput(
            @NotBlank @Size(max = 64) String messageId,
            @NotBlank @Size(max = 36) String sourceId,
            @NotNull @Positive Long observedAt,
            @Size(max = 36) String ownerOrgId,
            @Size(max = 36) String districtId,
            @NotEmpty @Size(max = 100) List<@Valid TargetItem> items) { }

    public record TargetItem(
            @NotBlank @Size(max = 128) String externalTargetId,
            String externalTrackId,
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal altitudeAmslM,
            BigDecimal heightAglM,
            BigDecimal speedMps,
            BigDecimal headingDeg,
            String classCode,
            BigDecimal classConfidence,
            String uavSn,
            String subtype,
            BigDecimal pilotLongitude,
            BigDecimal pilotLatitude,
            @Positive Integer objectCount) { }

    public record WeatherObservationInput(
            @NotBlank @Size(max = 64) String messageId,
            @NotBlank @Size(max = 36) String deviceId,
            @NotNull @Positive Long observedAt,
            @NotNull BigDecimal longitude,
            @NotNull BigDecimal latitude,
            BigDecimal temperatureC,
            BigDecimal windSpeedMs,
            BigDecimal gustMs,
            BigDecimal windFromDegrees,
            BigDecimal humidityPercent,
            BigDecimal pressureHpa,
            BigDecimal precipitationMm,
            BigDecimal visibilityM) { }

    public record RegisteredDevice(String sourceId, String deviceId, String messageId) { }

    public record AcceptedInput(String messageId, String sourceId, String deviceId, int acceptedCount) { }
}
