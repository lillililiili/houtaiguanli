package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.DeviceTrendService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/device-monitor/devices")
public class DeviceTrendController {
    private final DeviceTrendService service;
    public DeviceTrendController(DeviceTrendService service) { this.service=service; }
    @GetMapping("/{id}/trends")
    public ApiResponse<DeviceTrendService.Trends> get(@PathVariable String id,@RequestParam(defaultValue="1h") String range) {
        return ApiResponse.ok(service.get(id,range));
    }
}
