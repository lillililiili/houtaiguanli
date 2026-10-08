package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.LocalQaDeviceStatusService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

@RestController
@Profile(SimulationPolicy.PROFILE)
@ConditionalOnProperty(prefix="app.qa.device-setup",name="enabled",havingValue="true")
@RequestMapping("/api/v1/local-interface-simulator/device-status")
public class LocalQaDeviceStatusController {
    private final LocalQaDeviceStatusService service;
    public LocalQaDeviceStatusController(LocalQaDeviceStatusService service){this.service=service;}
    @PostMapping public ApiResponse<LocalQaDeviceStatusDtos.Receipt> accept(
            @Valid @RequestBody LocalQaDeviceStatusDtos.Input input){
        return ApiResponse.ok(service.accept(input));
    }
}
