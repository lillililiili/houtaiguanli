package com.uav.lowaltitude.modules.device.api;

import java.math.BigDecimal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.LocalQaRadarService;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceDetail;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

@RestController
@Profile(SimulationPolicy.PROFILE)
@ConditionalOnProperty(prefix="app.qa.device-setup",name="enabled",havingValue="true")
@RequestMapping("/api/v1/local-interface-simulator/radar-device")
public class LocalQaRadarController {
    private final LocalQaRadarService service;
    public LocalQaRadarController(LocalQaRadarService service) { this.service=service; }
    public record Input(@NotBlank String ownerOrgId,@NotBlank String districtId,
                        @NotNull BigDecimal longitude,@NotNull BigDecimal latitude, String coordinateSystem) { }
    @PostMapping public ApiResponse<DeviceDetail> prepare(@Valid @RequestBody Input body,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.prepare(body.ownerOrgId(),body.districtId(),body.longitude(),body.latitude(),body.coordinateSystem(),key));
    }
}
