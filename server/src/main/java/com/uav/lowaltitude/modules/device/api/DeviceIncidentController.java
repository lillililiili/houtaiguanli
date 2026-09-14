package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.DeviceIncidentService;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class DeviceIncidentController {

    private final DeviceIncidentService incidents;

    public DeviceIncidentController(DeviceIncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping("/device-incidents/{incidentId}")
    public ApiResponse<DeviceIncidentService.IncidentDetail> get(@PathVariable String incidentId) {
        return ApiResponse.ok(incidents.get(incidentId));
    }

    @PostMapping("/device-incidents/{incidentId}/reboot")
    public ResponseEntity<ApiResponse<DeviceService.Command>> reboot(
            @PathVariable String incidentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DeviceCommandController.RebootRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(incidents.reboot(incidentId, idempotencyKey, request.reason())));
    }

    @PostMapping("/device-incidents/{incidentId}/recovery-checks")
    public ApiResponse<DeviceIncidentService.RecoveryResult> recoveryCheck(
            @PathVariable String incidentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) RecoveryCheckRequest ignored) {
        return ApiResponse.ok(incidents.recoveryCheck(incidentId, idempotencyKey));
    }

    public record RecoveryCheckRequest(String note) { }
}
