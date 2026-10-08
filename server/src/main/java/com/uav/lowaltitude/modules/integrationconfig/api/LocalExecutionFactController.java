package com.uav.lowaltitude.modules.integrationconfig.api;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.flight.application.FlightExecutionFactService;
import com.uav.lowaltitude.modules.flight.domain.FlightExecutionFacts.*;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

@RestController
@Profile(SimulationPolicy.PROFILE)
@RequestMapping("/api/v1/local-interface-simulator/flight-execution-facts")
public class LocalExecutionFactController {
    private final FlightExecutionFactService service;
    public LocalExecutionFactController(FlightExecutionFactService service){this.service=service;}
    @PostMapping public ApiResponse<Accepted> accept(@Valid @RequestBody Input input){return ApiResponse.ok(service.acceptSimulation(input));}
}
