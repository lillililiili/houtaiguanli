package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.*;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceService;
import com.uav.lowaltitude.platform.api.ApiResponse;

import com.uav.lowaltitude.platform.security.UnitOperationsContext;
import static com.uav.lowaltitude.platform.security.UnitOperationsContext.Family.*;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceController.*;

@RestController
@RequestMapping("/api/v1/unit-admin/operations")
public class UnitDeviceMaintenanceController {
    private final DeviceMaintenanceController delegate;
    private final UnitOperationsContext context;
    public UnitDeviceMaintenanceController(DeviceMaintenanceController delegate, UnitOperationsContext context) { this.delegate=delegate; this.context=context; }

    @GetMapping("/device-maintenance-tasks/{taskId}/workflow")
    public ApiResponse<Workflow> workflow(@PathVariable String taskId){
        return context.call(MAINTENANCE, "maintenance.read", () -> delegate.workflow(taskId));
    }

    @PostMapping("/device-maintenance-tasks/{taskId}/workflow/actions")
    public ApiResponse<Workflow> action(@PathVariable String taskId,@RequestBody WorkflowAction body,
            @RequestHeader(value="Idempotency-Key",required=false) String key){
        return context.call(MAINTENANCE, "maintenance.op", () -> delegate.action(taskId,body,key));
    }

    @GetMapping("/device-maintenance-messages")
    public ApiResponse<MessagePage> messages(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="8") int size,
            @RequestParam(value="unread_only",defaultValue="false") boolean unreadOnly,
            @RequestParam(value="pending_only",defaultValue="false") boolean pendingOnly){
        return context.call(MAINTENANCE, "maintenance.read", () -> delegate.messages(page,size,unreadOnly,pendingOnly));
    }

    @PostMapping("/device-maintenance-messages/{taskId}/read")
    public ApiResponse<ReadReceipt> read(@PathVariable String taskId){
        return context.call(MAINTENANCE, "maintenance.read", () -> delegate.read(taskId));
    }

    @GetMapping("/devices/{deviceId}/maintenance-report")
    public ApiResponse<DeviceReport> deviceReport(@PathVariable String deviceId) {
        return context.call(MAINTENANCE, "maintenance.read", () -> delegate.deviceReport(deviceId));
    }

    @GetMapping("/device-maintenance-tasks")
    public ApiResponse<Page> list(@RequestParam(defaultValue="PENDING") String status,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(value="device_id",required=false) String deviceId) {
        return context.call(MAINTENANCE, "maintenance.read", () -> delegate.list(status,page,size,deviceId));
    }

    @PostMapping("/device-maintenance-tasks/{taskId}/handling")
    public ApiResponse<Task> handle(@PathVariable String taskId, @RequestBody HandleRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return context.call(MAINTENANCE, "maintenance.op", () -> delegate.handle(taskId,body,key));
    }
}
