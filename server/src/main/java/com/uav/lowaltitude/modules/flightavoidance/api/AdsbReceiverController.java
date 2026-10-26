package com.uav.lowaltitude.modules.flightavoidance.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import com.uav.lowaltitude.modules.flightavoidance.api.FlightAvoidanceDtos.*;
import com.uav.lowaltitude.modules.flightavoidance.application.AdsbReceiverService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/adsb-receivers")
public class AdsbReceiverController {
    private final AdsbReceiverService service;
    public AdsbReceiverController(AdsbReceiverService service) { this.service = service; }
    @GetMapping("/{id}") public ApiResponse<ReceiverProfile> get(@PathVariable String id) { return ApiResponse.ok(service.get(id)); }
    @com.uav.lowaltitude.platform.security.BackendOnly
    @PostMapping public ApiResponse<ReceiverProfile> create(@Valid @RequestBody ReceiverInput input) { return ApiResponse.ok(service.create(input)); }
    @com.uav.lowaltitude.platform.security.BackendOnly
    @PutMapping("/{id}") public ApiResponse<ReceiverProfile> update(@PathVariable String id, @Valid @RequestBody ReceiverInput input) {
        return ApiResponse.ok(service.update(id, input));
    }
}
