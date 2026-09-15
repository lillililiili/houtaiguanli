package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.uav.lowaltitude.modules.device.application.CommissionDeviceInformationService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
public class CommissionDeviceInformationController {
    private final CommissionDeviceInformationService service;
    public CommissionDeviceInformationController(CommissionDeviceInformationService service) { this.service = service; }
    @GetMapping("/api/v1/commission-tasks/device-information/{deviceId}")
    public ApiResponse<CommissionDeviceInformationService.Information> get(@PathVariable String deviceId) {
        return ApiResponse.ok(service.get(deviceId));
    }
}
