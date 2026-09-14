package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.Countermeasure4ChControlService;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.LingyunControlService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class DeviceCommandController {

    private final DeviceService service;
    private final LingyunControlService control;
    private final Countermeasure4ChControlService countermeasure;

    public DeviceCommandController(DeviceService service, LingyunControlService control,
                                   Countermeasure4ChControlService countermeasure) {
        this.service = service;
        this.control = control;
        this.countermeasure = countermeasure;
    }

    @PostMapping("/devices/{deviceId}/commands/reboot")
    public ResponseEntity<ApiResponse<DeviceService.Command>> reboot(
            @PathVariable String deviceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RebootRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(service.createReboot(deviceId, idempotencyKey, request.reason())));
    }

    @PostMapping("/devices/{deviceId}/commands/lingyun-control")
    public ResponseEntity<ApiResponse<DeviceService.Command>> lingyunControl(
            @PathVariable String deviceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ControlRequest request) {
        if (request == null) request = new ControlRequest(null, null, null, null, null);
        String id = control.enqueue(deviceId, idempotencyKey, request.authorizationId(), request.operationType(),
                request.operationCmd(), request.operationParams(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(service.command(id)));
    }

    @PostMapping("/devices/{deviceId}/commands/countermeasure-4ch")
    public ResponseEntity<ApiResponse<DeviceService.Command>> countermeasure4ch(
            @PathVariable String deviceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody FourChRequest request) {
        if (request == null) request = new FourChRequest(null, null, null, null, null);
        String id = countermeasure.enqueue(deviceId, idempotencyKey, request.authorizationId(), request.action(),
                request.channel(), request.mask(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(service.command(id)));
    }

    @PostMapping("/devices/{deviceId}/commands/emergency-stop")
    public ResponseEntity<ApiResponse<DeviceService.Command>> emergencyStop(@PathVariable String deviceId) {
        throw new ApiException(HttpStatus.CONFLICT, "CONTROL_NOT_ENABLED", "急停：设备协议未提供");
    }

    @GetMapping("/device-commands/{commandId}")
    public ApiResponse<DeviceService.Command> command(@PathVariable String commandId) {
        return ApiResponse.ok(service.command(commandId));
    }

    public record RebootRequest(@NotBlank String reason) { }
    public record ControlRequest(String authorizationId, Integer operationType, Integer operationCmd,
                                 java.util.Map<String, Object> operationParams, String reason) { }
    public record FourChRequest(String authorizationId, String action, String channel, Integer mask, String reason) { }
}
