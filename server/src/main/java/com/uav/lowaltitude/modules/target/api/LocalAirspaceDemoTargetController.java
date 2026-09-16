package com.uav.lowaltitude.modules.target.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.EoManualTrackService.EoTrackingTask;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.modules.target.application.LocalAirspaceDemoTargetService;
import com.uav.lowaltitude.modules.target.application.LocalAirspaceDemoTargetService.PreparedTarget;

@RestController
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/local/airspace-demo/targets/{targetId}")
public class LocalAirspaceDemoTargetController {
    private final LocalAirspaceDemoTargetService service;

    public LocalAirspaceDemoTargetController(LocalAirspaceDemoTargetService service) { this.service = service; }

    @PutMapping
    public ApiResponse<PreparedTarget> prepare(@PathVariable("targetId") String targetId, @RequestBody FrameRequest request) {
        return ApiResponse.ok(service.prepare(targetId, request.frame()));
    }

    @PostMapping("/eo-tracking-tasks")
    public ResponseEntity<ApiResponse<EoTrackingTask>> begin(@PathVariable("targetId") String targetId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody FrameRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(service.begin(targetId, request.frame(), key)));
    }

    public record FrameRequest(Integer frame) { }
}
