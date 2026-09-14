package com.uav.lowaltitude.modules.mapresource.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.uav.lowaltitude.modules.mapresource.api.MapPackageDtos.ActivationRequest;
import com.uav.lowaltitude.modules.mapresource.api.MapPackageDtos.CatalogDto;
import com.uav.lowaltitude.modules.mapresource.api.MapPackageDtos.DeleteRequest;
import com.uav.lowaltitude.modules.mapresource.api.MapPackageDtos.PackageDto;
import com.uav.lowaltitude.modules.mapresource.application.MapPackageService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/map-packages")
public class MapPackageController {
    private final MapPackageService maps;

    public MapPackageController(MapPackageService maps) {
        this.maps = maps;
    }

    @GetMapping
    public ApiResponse<CatalogDto> catalog() {
        return ApiResponse.ok(maps.catalog());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PackageDto> upload(@RequestPart("file") MultipartFile file,
            @RequestParam("city_code") String cityCode,
            @RequestParam("city_name") String cityName,
            @RequestParam("reason") String reason,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(maps.upload(file, cityCode, cityName, reason, idempotencyKey,
                request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @PostMapping("/{packageId}/activate")
    public ApiResponse<CatalogDto> activate(@PathVariable String packageId,
            @Valid @RequestBody ActivationRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(maps.activate(packageId, body.expectedVersion(), body.reason(), idempotencyKey,
                request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @PostMapping("/rollback")
    public ApiResponse<CatalogDto> rollback(@Valid @RequestBody ActivationRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(maps.rollback(body.expectedVersion(), body.reason(), idempotencyKey,
                request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @DeleteMapping("/{packageId}")
    public ApiResponse<CatalogDto> delete(@PathVariable String packageId,
            @Valid @RequestBody DeleteRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(maps.delete(packageId, body.expectedVersion(), body.reason(), idempotencyKey,
                request.getRemoteAddr(), request.getHeader("User-Agent")));
    }
}
