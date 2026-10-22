package com.uav.lowaltitude.modules.airspace.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.airspace.application.AirspaceWriteService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/airspaces")
public class LocalAirspaceController {
    private final AirspaceWriteService service;
    public LocalAirspaceController(AirspaceWriteService service) { this.service=service; }

    @PostMapping
    public ApiResponse<AirspaceWriteDtos.CreatedAirspaceDto> create(@RequestBody String body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.createLocal(body,key));
    }
    @PutMapping("/{id}")
    public ApiResponse<AirspaceWriteDtos.CreatedAirspaceDto> update(@PathVariable String id,@RequestBody String body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.updateLocal(id,body,key));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<AirspaceWriteDtos.DeletedAirspaceDto> delete(@PathVariable String id,@RequestBody String body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.deleteLocal(id,body,key));
    }
    @GetMapping("/management-context")
    public ApiResponse<AirspaceWriteDtos.ManagementContextDto> context() { return ApiResponse.ok(service.managementContext()); }
}
