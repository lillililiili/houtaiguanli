package com.uav.lowaltitude.modules.alarm.api;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/uav-risk-cases")
public class UavRiskCaseController {
    private final UavRiskCaseService service;
    public UavRiskCaseController(UavRiskCaseService service){this.service=service;}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable String id){return ApiResponse.ok(service.detail(id));}
    @PostMapping("/{id}/verifications") public ApiResponse<Map<String,Object>> verify(@PathVariable String id,
            @RequestBody String body,@RequestHeader(value="Idempotency-Key",required=false) String key){return ApiResponse.ok(service.verify(id,body,key));}
}
