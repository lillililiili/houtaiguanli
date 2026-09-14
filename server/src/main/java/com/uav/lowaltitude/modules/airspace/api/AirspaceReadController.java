package com.uav.lowaltitude.modules.airspace.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.AirspaceSummaryDto;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos.PageDto;
import com.uav.lowaltitude.modules.airspace.application.AirspaceReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class AirspaceReadController {

    private final AirspaceReadService service;

    public AirspaceReadController(AirspaceReadService service) {
        this.service = service;
    }

    @GetMapping("/airspaces")
    public ApiResponse<PageDto<AirspaceSummaryDto>> airspaces(
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.airspaces(parameters));
    }

    @GetMapping("/airspaces/{airspaceId}")
    public ApiResponse<AirspaceDtos.AirspaceDetailDto> airspace(@PathVariable String airspaceId) {
        return ApiResponse.ok(service.airspace(airspaceId));
    }

    @GetMapping("/airspaces/{airspaceId}/versions")
    public ApiResponse<PageDto<AirspaceDtos.AirspaceVersionDto>> versions(
            @PathVariable String airspaceId, @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.versions(airspaceId, parameters));
    }

    @GetMapping("/airspace-versions/{airspaceVersionId}")
    public ApiResponse<AirspaceDtos.AirspaceVersionDto> version(@PathVariable String airspaceVersionId) {
        return ApiResponse.ok(service.version(airspaceVersionId));
    }

    @GetMapping("/flight-plans/{planId}/airspace-conflicts")
    public ApiResponse<List<AirspaceDtos.AirspaceConflictDto>> conflicts(@PathVariable String planId) {
        return ApiResponse.ok(service.conflicts(planId));
    }
}
