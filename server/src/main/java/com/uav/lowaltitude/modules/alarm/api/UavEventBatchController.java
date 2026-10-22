package com.uav.lowaltitude.modules.alarm.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.uav.lowaltitude.modules.alarm.application.UavEventBatchService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/uav-events/batch")
public class UavEventBatchController {
    private final UavEventBatchService service;
    public UavEventBatchController(UavEventBatchService service) { this.service = service; }

    @PostMapping("/precheck")
    public ApiResponse<UavEventBatchDtos.PrecheckResult> precheck(@RequestBody(required = false) String body) {
        return ApiResponse.ok(service.precheck(body));
    }
    @PostMapping("/verify")
    public ApiResponse<UavEventBatchDtos.BatchResult> verify(@RequestBody(required = false) String body) {
        return ApiResponse.ok(service.verify(body));
    }
    @PostMapping("/counter")
    public ApiResponse<UavEventBatchDtos.BatchResult> counter(@RequestBody(required = false) String body) {
        return ApiResponse.ok(service.counter(body));
    }
}
