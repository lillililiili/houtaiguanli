package com.uav.lowaltitude.modules.situation.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.situation.application.SituationRiskReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/situation/risk-items")
public class SituationRiskController {
    private final SituationRiskReadService service;
    public SituationRiskController(SituationRiskReadService service) { this.service=service; }
    @GetMapping public ApiResponse<SituationRiskDtos.Page> list(@RequestParam MultiValueMap<String,String> parameters) {
        return ApiResponse.ok(service.list(parameters));
    }
    @GetMapping("/{key}") public ApiResponse<SituationRiskDtos.Item> detail(@PathVariable String key) {
        return ApiResponse.ok(service.detail(key));
    }
}
