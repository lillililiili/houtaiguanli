package com.uav.lowaltitude.modules.flightavoidance.api;

import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.*;
import com.uav.lowaltitude.modules.flightavoidance.application.AdsbReceiverService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 设备模拟器的航班接收机：登记和送航班位置。只在本地测试环境（local+qa）开放。 */
@RestController
@Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
@ConditionalOnProperty(prefix = "app.qa.device-setup", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/local-interface-simulator/adsb-receiver")
public class LocalQaAdsbController {
    private final AdsbReceiverService service;
    public LocalQaAdsbController(AdsbReceiverService service) { this.service = service; }
    @PostMapping public ApiResponse<ReceiverProfile> prepare(@Valid @RequestBody SimulatedReceiverInput input) {
        return ApiResponse.ok(service.prepareSimulated(input));
    }
    @PostMapping("/{id}/aircraft") public ApiResponse<Map<String, Integer>> report(@PathVariable String id,
            @Valid @RequestBody SimulatedReport report) {
        return ApiResponse.ok(Map.of("accepted", service.receiveSimulated(id, report)));
    }
    @PostMapping("/{id}/release") public ApiResponse<Map<String, Boolean>> release(@PathVariable String id) {
        return ApiResponse.ok(Map.of("released", service.releaseSimulated(id)));
    }
}
