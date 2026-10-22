package com.uav.lowaltitude.modules.prediction.api;

import com.uav.lowaltitude.modules.prediction.application.PredictionReadService;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.PageDto;
import com.uav.lowaltitude.platform.api.ApiResponse;
import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PredictionController {
    private final PredictionReadService service;
    public PredictionController(PredictionReadService service){this.service=service;}
    @GetMapping("/targets/{id}/prediction")
    public ApiResponse<Map<String,Object>> target(@PathVariable String id){return ApiResponse.ok(service.target(id));}
    @GetMapping("/predictions/current")
    public ApiResponse<PageDto<Map<String,Object>>> current(@RequestParam MultiValueMap<String,String> parameters){return ApiResponse.ok(service.current(parameters));}
}
