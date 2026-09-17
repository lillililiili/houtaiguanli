package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.*;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class DeviceMaintenanceController {
    private final DeviceMaintenanceService service;
    public DeviceMaintenanceController(DeviceMaintenanceService service) { this.service = service; }

    @PostMapping("/flight-plans/{planId}/device-maintenance-tasks")
    public ApiResponse<Task> create(@PathVariable String planId, @RequestBody CreateRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return ApiResponse.ok(service.create(planId, body, key));
    }

    @GetMapping("/flight-plans/{planId}/device-maintenance-tasks")
    public ApiResponse<Page> forPlan(@PathVariable String planId,@RequestParam(value="device_id",required=false) String deviceId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.forPlan(planId,deviceId,page,size));
    }

    @GetMapping("/device-maintenance-tasks")
    public ApiResponse<Page> list(@RequestParam(defaultValue="PENDING") String status,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.list(status, page, size));
    }

    @PostMapping("/device-maintenance-tasks/{taskId}/handling")
    public ApiResponse<Task> handle(@PathVariable String taskId, @RequestBody HandleRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return ApiResponse.ok(service.handle(taskId, body, key));
    }
}
