package com.uav.lowaltitude.modules.flightavoidance.api;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.*;
import com.uav.lowaltitude.modules.flightavoidance.application.FlightAvoidanceService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/flight-avoidance")
public class FlightAvoidanceController {
    private final FlightAvoidanceService service;
    public FlightAvoidanceController(FlightAvoidanceService service) { this.service = service; }

    @GetMapping("/policy") public ApiResponse<Policy> policy() { return ApiResponse.ok(service.policy()); }

    @com.uav.lowaltitude.platform.security.BackendOnly
    @PutMapping("/policy") public ApiResponse<Policy> save(@Valid @RequestBody PolicyInput input, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.savePolicy(input, key));
    }

    @GetMapping("/policy/history") public ApiResponse<ChangePage> history(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.history(page, size));
    }

    /** 反制弹窗：所选反制设备附近的航班情况。 */
    @GetMapping("/check") public ApiResponse<Check> check(@RequestParam("device_id") String deviceId,
            @RequestParam(value = "countermeasure_mode", required = false) String mode) {
        return ApiResponse.ok(service.preview(deviceId, mode));
    }

    /** 融合感知「民航航班」图层。 */
    @GetMapping("/traffic") public ApiResponse<Traffic> traffic() { return ApiResponse.ok(service.traffic()); }

    /** 告警详情：这起告警的反制前、反制中航班检查记录。 */
    @GetMapping("/checks") public ApiResponse<List<CheckRecord>> checks(@RequestParam("event_id") String eventId) {
        return ApiResponse.ok(service.checks(eventId));
    }
}
