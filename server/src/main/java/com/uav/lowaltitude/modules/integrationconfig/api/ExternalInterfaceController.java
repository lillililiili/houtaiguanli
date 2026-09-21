package com.uav.lowaltitude.modules.integrationconfig.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.integrationconfig.application.ExternalInterfaceService;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.*;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class ExternalInterfaceController {
    private final ExternalInterfaceService service;
    public ExternalInterfaceController(ExternalInterfaceService service) { this.service=service; }
    @GetMapping("/external-interface-configs/{kind}")
    public ApiResponse<Configuration> get(@PathVariable String kind) { return ApiResponse.ok(service.get(kind)); }
    @PutMapping("/external-interface-configs/{kind}")
    public ApiResponse<Configuration> save(@PathVariable String kind,@Valid @RequestBody Input input) {
        return ApiResponse.ok(service.save(kind,input));
    }
    @GetMapping("/flight-plans/{planId}/weather-forecast")
    public ApiResponse<ForecastAvailability> forecast(@PathVariable String planId) { return ApiResponse.ok(service.forecast(planId)); }
}
