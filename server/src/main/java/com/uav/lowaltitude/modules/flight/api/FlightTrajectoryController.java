package com.uav.lowaltitude.modules.flight.api;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.flight.application.FlightTrajectoryService;
import com.uav.lowaltitude.platform.api.ApiResponse;
@RestController
public class FlightTrajectoryController {
    private final FlightTrajectoryService service;
    public FlightTrajectoryController(FlightTrajectoryService service){this.service=service;}
    @GetMapping("/api/v1/flight-plans/{planId}/trajectory")
    public ApiResponse<FlightTrajectoryService.Trajectory> trajectory(@PathVariable String planId){return ApiResponse.ok(service.read(planId));}
    @GetMapping("/api/v1/legality-evaluations/{evaluationId}/trajectory")
    public ApiResponse<FlightTrajectoryService.Trajectory> evaluationTrajectory(@PathVariable String evaluationId){return ApiResponse.ok(service.readEvaluation(evaluationId));}
}
