package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.EoDispatchService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/targets/{targetId}/eo-dispatch")
public class EoDispatchController {
    private final EoDispatchService service;
    public EoDispatchController(EoDispatchService service) {this.service=service;}
    @GetMapping
    public ApiResponse<EoDispatchService.View> view(@PathVariable String targetId) {return ApiResponse.ok(service.view(targetId));}
    @PostMapping
    public ApiResponse<EoDispatchService.View> request(@PathVariable String targetId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) Request body) {
        return ApiResponse.ok(service.request(targetId,body==null?null:body.expectedTaskId(),body==null?null:body.deviceId(),key));
    }
    @PostMapping("/cancel")
    public ApiResponse<EoDispatchService.View> cancel(@PathVariable String targetId,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.cancel(targetId,key));
    }
    public record Request(String expectedTaskId,String deviceId) { }
}
