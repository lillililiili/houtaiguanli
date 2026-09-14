package com.uav.lowaltitude.modules.flight.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.ActualsDto;
import com.uav.lowaltitude.modules.flight.application.FlightActualsService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 飞行计划的对照聚合；计划与航线的只读接口仍在 FlightReadController。外部授权登记已按 F8 裁定撤除。 */
@RestController
@RequestMapping("/api/v1")
public class FlightActualsController {
    private final FlightActualsService actuals;

    public FlightActualsController(FlightActualsService actuals) {
        this.actuals = actuals;
    }

    @GetMapping("/flight-plans/{planId}/actuals")
    public ApiResponse<ActualsDto> actuals(@PathVariable String planId) {
        return ApiResponse.ok(actuals.actuals(planId));
    }

}
