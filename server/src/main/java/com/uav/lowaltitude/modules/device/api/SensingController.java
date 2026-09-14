package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.SensingTargetService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/sensing/targets")
public class SensingController {

    private final SensingTargetService service;

    public SensingController(SensingTargetService service) { this.service = service; }

    @GetMapping
    public ApiResponse<SensingTargetService.TargetPage> list(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(name = "radar_classification", required = false) Integer classification,
            @RequestParam(name = "updated_after", required = false) Long updatedAfter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(deviceId, active, classification, updatedAfter, page, size));
    }

    @GetMapping("/{id}/track")
    public ApiResponse<SensingTargetService.TargetTrack> track(@PathVariable String id,
            @RequestParam(required = false) Long from, @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.ok(service.track(id, from, to, limit));
    }
}
