package com.uav.lowaltitude.modules.alarm.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.alarm.application.NoCounterService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/uav-events/{eventId}/no-counter-decision")
public class NoCounterController {
    private final NoCounterService service;
    public NoCounterController(NoCounterService service) { this.service=service; }
    @GetMapping public ApiResponse<NoCounterDtos.Status> get(@PathVariable String eventId) {return ApiResponse.ok(service.get(eventId));}
    @PostMapping public ApiResponse<NoCounterDtos.Status> decide(@PathVariable String eventId,
            @RequestBody(required=false) String body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.decide(eventId,body,key));
    }
}
