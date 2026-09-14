package com.uav.lowaltitude.modules.flight.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.flight.api.FlightDtos.FlightPlanDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.PageDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.RouteDto;
import com.uav.lowaltitude.modules.flight.api.FlightDtos.RouteVersionDto;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class FlightReadController {

    private final FlightReadService service;

    public FlightReadController(FlightReadService service) {
        this.service = service;
    }

    @GetMapping("/flight-plans")
    public ApiResponse<PageDto<FlightPlanDto>> flightPlans(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.flightPlans(parameters));
    }

    @GetMapping("/flight-plans/{planId}")
    public ApiResponse<FlightPlanDto> flightPlan(@PathVariable String planId) {
        return ApiResponse.ok(service.flightPlan(planId));
    }

    @GetMapping("/routes")
    public ApiResponse<PageDto<RouteDto>> routes(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.routes(parameters));
    }

    @GetMapping("/routes/{routeId}")
    public ApiResponse<RouteDto> route(@PathVariable String routeId) {
        return ApiResponse.ok(service.route(routeId));
    }

    @GetMapping("/routes/{routeId}/versions")
    public ApiResponse<PageDto<RouteVersionDto>> routeVersions(
            @PathVariable String routeId, @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.routeVersions(routeId, parameters));
    }

    @GetMapping("/route-versions/{routeVersionId}")
    public ApiResponse<RouteVersionDto> routeVersion(@PathVariable String routeVersionId) {
        return ApiResponse.ok(service.routeVersion(routeVersionId));
    }
}
