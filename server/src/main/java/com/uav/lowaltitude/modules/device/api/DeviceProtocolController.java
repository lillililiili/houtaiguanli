package com.uav.lowaltitude.modules.device.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.DeviceProtocolService;
import com.uav.lowaltitude.modules.device.application.DeviceProtocolService.ProtocolDescriptor;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/device-protocols")
public class DeviceProtocolController {

    private final DeviceProtocolService service;

    public DeviceProtocolController(DeviceProtocolService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<ProtocolDescriptor>> catalog() { return ApiResponse.ok(service.catalog()); }
}
