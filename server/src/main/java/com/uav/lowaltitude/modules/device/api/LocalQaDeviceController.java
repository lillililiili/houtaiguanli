package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.Valid;
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
    /** 反制设备归属单位与区县；旧调用方仍可传 plan_id，只借用计划的单位与区县。 */
    public record Input(String ownerOrgId, String districtId, String planId) { }
    @PostMapping public ApiResponse<DeviceDetail> prepare(@Valid @RequestBody Input input,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.prepare(input.ownerOrgId(),input.districtId(),input.planId(),key));
    }
}
