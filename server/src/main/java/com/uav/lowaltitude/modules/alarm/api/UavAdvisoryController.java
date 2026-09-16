package com.uav.lowaltitude.modules.alarm.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.alarm.application.UavAdvisoryService;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Overview;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/uav-events/{eventId}/advisory")
public class UavAdvisoryController {
    private final UavAdvisoryService service;
    public UavAdvisoryController(UavAdvisoryService service) {this.service=service;}
    @GetMapping public ApiResponse<Overview> overview(@PathVariable String eventId) {return ApiResponse.ok(service.overview(eventId));}
    @PostMapping("/actions") public ApiResponse<Overview> act(@PathVariable String eventId,
            @RequestBody(required=false) String body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.act(eventId,body,key));
    }
}
