package com.uav.lowaltitude.modules.device.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.EoManualTrackService;
import com.uav.lowaltitude.modules.device.application.EoManualTrackService.EoTrackingTask;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class EoTrackingController {

    private final EoManualTrackService service;

    public EoTrackingController(EoManualTrackService service) {
        this.service = service;
    }

    @PostMapping("/targets/{targetId}/eo-tracking-tasks")
    public ResponseEntity<ApiResponse<EoTrackingTask>> begin(
            @PathVariable String targetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) BeginRequest request) {
        BeginRequest body = request == null ? new BeginRequest(null, null) : request;
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(service.begin(targetId, body.deviceId(), body.reason(), idempotencyKey)));
    }

    @GetMapping("/targets/{targetId}/eo-tracking-tasks")
    public ApiResponse<EoTrackingTask> current(@PathVariable String targetId) {
        return ApiResponse.ok(service.current(targetId));
    }

    @PostMapping("/eo-tracking-tasks/{taskId}/end")
    public ResponseEntity<ApiResponse<EoTrackingTask>> end(
            @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(service.end(taskId, idempotencyKey)));
    }

    public record BeginRequest(String deviceId, String reason) { }
}
