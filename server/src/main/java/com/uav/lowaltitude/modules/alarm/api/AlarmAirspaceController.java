package com.uav.lowaltitude.modules.alarm.api;

import com.uav.lowaltitude.modules.alarm.application.AlarmAirspaceReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alarms")
public class AlarmAirspaceController {
    private final AlarmAirspaceReadService service;
    public AlarmAirspaceController(AlarmAirspaceReadService service) { this.service = service; }
    @GetMapping("/{alarmId}/airspace-hits")
    public ApiResponse<AlarmAirspaceReadService.Result> read(@PathVariable String alarmId) {
        return ApiResponse.ok(service.read(alarmId));
    }
}
