package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.*;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class DeviceMaintenanceController {
    private final DeviceMaintenanceService service;
    private final com.uav.lowaltitude.modules.device.application.DeviceMaintenanceWorkflowService workflows;
    public DeviceMaintenanceController(DeviceMaintenanceService service,com.uav.lowaltitude.modules.device.application.DeviceMaintenanceWorkflowService workflows) { this.service = service; this.workflows=workflows; }

    @GetMapping("/device-maintenance-tasks/{taskId}/workflow")
    public ApiResponse<Workflow> workflow(@PathVariable String taskId){return ApiResponse.ok(workflows.get(taskId));}

    @PostMapping("/device-maintenance-tasks/{taskId}/workflow/actions")
    public ApiResponse<Workflow> action(@PathVariable String taskId,@RequestBody WorkflowAction body,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return ApiResponse.ok(workflows.action(taskId,body,key));}

    @GetMapping("/device-maintenance-messages")
    public ApiResponse<MessagePage> messages(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="8") int size,
            @RequestParam(value="unread_only",defaultValue="false") boolean unreadOnly){return ApiResponse.ok(workflows.messages(page,size,unreadOnly));}

    @PostMapping("/device-maintenance-messages/{taskId}/read")
    public ApiResponse<ReadReceipt> read(@PathVariable String taskId){return ApiResponse.ok(workflows.markRead(taskId));}

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

    @PostMapping("/device-maintenance-tasks/{taskId}/notifications/resend")
    public ApiResponse<Task> resend(@PathVariable String taskId,@RequestBody ResendRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key){
        return ApiResponse.ok(service.resend(taskId,body,key));
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
