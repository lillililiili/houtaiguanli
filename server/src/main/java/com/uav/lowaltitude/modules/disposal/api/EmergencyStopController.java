package com.uav.lowaltitude.modules.disposal.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.disposal.application.EmergencyStopService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.modules.disposal.api.EmergencyStopDtos.Overview;

@RestController
@RequestMapping("/api/v1/uav-events/{eventId}/emergency-stop")
public class EmergencyStopController {
    private final EmergencyStopService service;
    public EmergencyStopController(EmergencyStopService service) { this.service = service; }
    @GetMapping
    public ApiResponse<Overview> get(@PathVariable String eventId) { return ApiResponse.ok(service.overview(eventId)); }
    @PostMapping
    public ApiResponse<Overview> stop(@PathVariable String eventId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) String body) { return ApiResponse.ok(service.stop(eventId, key, body)); }
    @PostMapping("/{stopId}/notes")
    public ApiResponse<Overview> note(@PathVariable String eventId, @PathVariable String stopId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody String body) {
        return ApiResponse.ok(service.followUp(eventId, stopId, null, "NOTE", key, body));
    }
    @PostMapping("/{stopId}/devices/{deviceId}/retry")
    public ApiResponse<Overview> retry(@PathVariable String eventId, @PathVariable String stopId,
            @PathVariable String deviceId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) String body) {
        return ApiResponse.ok(service.followUp(eventId, stopId, deviceId, "RETRY", key, body));
    }
    @PostMapping("/{stopId}/devices/{deviceId}/manual-confirm")
    public ApiResponse<Overview> confirm(@PathVariable String eventId, @PathVariable String stopId,
            @PathVariable String deviceId, @RequestHeader("Idempotency-Key") String key, @RequestBody String body) {
        return ApiResponse.ok(service.followUp(eventId, stopId, deviceId, "MANUAL_CONFIRM", key, body));
    }
}
