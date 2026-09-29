package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.LocalQaDeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceDetail;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
@ConditionalOnProperty(prefix="app.qa.device-setup",name="enabled",havingValue="true")
@RequestMapping("/api/v1/local-interface-simulator/countermeasure-device")
public class LocalQaDeviceController {
    private final LocalQaDeviceService service;
    public LocalQaDeviceController(LocalQaDeviceService service) { this.service=service; }
    public record Input(@NotBlank String planId) { }
    @PostMapping public ApiResponse<DeviceDetail> prepare(@Valid @RequestBody Input input,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.prepare(input.planId(),key));
    }
}
