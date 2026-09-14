package com.uav.lowaltitude.modules.airport.api;

import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.airport.api.AirportDtos.AirportDetailDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.AirportDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.NotificationTargetDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.PageDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.ProcedureRouteDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.ProtectedTargetDto;
import com.uav.lowaltitude.modules.airport.api.AirportDtos.RunwayDto;
import com.uav.lowaltitude.modules.airport.application.AirportService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 机场基础数据接口：只增，没有修改与删除入口。 */
@RestController
@RequestMapping("/api/v1/airports")
public class AirportController {
    private final AirportService service;

    public AirportController(AirportService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageDto<AirportDto>> list(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(service.list(parameters));
    }

    @GetMapping("/{airportId}")
    public ApiResponse<AirportDetailDto> detail(@PathVariable String airportId) { return ApiResponse.ok(service.detail(airportId)); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AirportDto> create(@RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(service.create(body, key));
    }

    @PostMapping("/{airportId}/runways")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RunwayDto> addRunway(@PathVariable String airportId, @RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(service.addRunway(airportId, body, key));
    }

    @PostMapping("/{airportId}/procedure-routes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProcedureRouteDto> addProcedureRoute(@PathVariable String airportId, @RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(service.addProcedureRoute(airportId, body, key));
    }

    @PostMapping("/{airportId}/protected-targets")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProtectedTargetDto> addProtectedTarget(@PathVariable String airportId, @RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(service.addProtectedTarget(airportId, body, key));
    }

    @PostMapping("/{airportId}/notification-targets")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NotificationTargetDto> addNotificationTarget(@PathVariable String airportId, @RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return ApiResponse.ok(service.addNotificationTarget(airportId, body, key));
    }
}
