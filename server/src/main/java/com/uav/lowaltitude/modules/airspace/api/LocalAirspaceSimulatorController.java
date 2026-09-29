package com.uav.lowaltitude.modules.airspace.api;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.airspace.application.UpstreamAirspaceService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@Profile(com.uav.lowaltitude.platform.config.SimulationPolicy.PROFILE)
@RequestMapping("/api/v1/local-interface-simulator/airspaces")
public class LocalAirspaceSimulatorController {
    private final UpstreamAirspaceService service;
    public LocalAirspaceSimulatorController(UpstreamAirspaceService service){this.service=service;}
    @GetMapping("/context") public ApiResponse<Map<String,Object>> context(){return ApiResponse.ok(service.mockContext());}
    @PostMapping public ApiResponse<Map<String,Object>> receive(@RequestBody(required=false) String request){return ApiResponse.ok(service.receiveMock(request));}
}
