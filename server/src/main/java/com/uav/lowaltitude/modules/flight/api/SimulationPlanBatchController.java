package com.uav.lowaltitude.modules.flight.api;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.flight.application.SimulationPlanBatchService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

@RestController
@Profile(SimulationPolicy.PROFILE)
@RequestMapping("/api/v1/local-interface-simulator/plan-batches")
public class SimulationPlanBatchController {
    private final SimulationPlanBatchService service;
    public SimulationPlanBatchController(SimulationPlanBatchService service) { this.service=service; }
    @PostMapping("/activate")
    public ApiResponse<Activation> activate(@Valid @RequestBody Input input) { return ApiResponse.ok(service.activate(input)); }

    public record Input(@NotBlank @Size(max=64) String batchId, @Positive long startedAt,
        @NotBlank @Size(max=36) String ownerOrgId, @NotBlank @Size(max=36) String districtId,
        @NotNull @Size(max=2000) List<@NotBlank @Size(max=36) String> planIds,
        @NotNull @Size(max=10000) List<@NotBlank @Size(max=36) String> historicalPlanIds) { }
    public record Activation(String batchId,int planCount,int historicalPlanCount) { }
}
