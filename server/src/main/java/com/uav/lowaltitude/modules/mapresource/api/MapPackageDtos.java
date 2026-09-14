package com.uav.lowaltitude.modules.mapresource.api;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class MapPackageDtos {
    private MapPackageDtos() { }

    public record PackageDto(
            String packageId,
            String packageName,
            String cityCode,
            String cityName,
            String dataVersion,
            String coordinateSystem,
            String archiveName,
            String archiveSha256,
            String manifestSha256,
            long sizeBytes,
            int fileCount,
            List<Double> bounds,
            int minZoom,
            int maxZoom,
            int displayMaxZoom,
            String status,
            String validationMessage,
            String uploadedByName,
            long uploadedAt,
            String activatedByName,
            Long activatedAt,
            int version) { }

    public record RuntimeDto(
            String activePackageId,
            String previousPackageId,
            long revision,
            int version,
            String runtimeConfigUrl,
            boolean runtimeConfigReady,
            String businessCityCode,
            boolean businessOverlaysVisible) { }

    public record CatalogDto(List<PackageDto> items, RuntimeDto runtime) { }

    public record ActivationRequest(
            @NotNull @Min(0) Integer expectedVersion,
            @NotBlank @Size(max = 500) String reason) { }

    public record DeleteRequest(
            @NotNull @Min(0) Integer expectedVersion,
            @NotBlank @Size(max = 500) String reason) { }
}
