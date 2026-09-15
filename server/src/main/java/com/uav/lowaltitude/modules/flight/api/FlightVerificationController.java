package com.uav.lowaltitude.modules.flight.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.flight.api.FlightVerificationDtos.*;
import com.uav.lowaltitude.modules.flight.application.FlightVerificationService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/flight-plans/{planId}/verifications")
public class FlightVerificationController {
    private final FlightVerificationService service;
    public FlightVerificationController(FlightVerificationService service){this.service=service;}
    @GetMapping public ApiResponse<Workflow> read(@PathVariable String planId){return ApiResponse.ok(service.read(planId));}
    @GetMapping("/device-check") public ApiResponse<com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.Check> check(@PathVariable String planId){return ApiResponse.ok(service.deviceCheck(planId));}
    @PostMapping("/automatic") public ApiResponse<Verification> automatic(@PathVariable String planId,@RequestBody AutomaticRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return ApiResponse.ok(service.automatic(planId,request,key));}
    @PostMapping public ApiResponse<Verification> verify(@PathVariable String planId,@RequestBody VerifyRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return ApiResponse.ok(service.verify(planId,request,key));}
    @PostMapping("/feedback") public ApiResponse<Feedback> feedback(@PathVariable String planId,@RequestBody FeedbackRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return ApiResponse.ok(service.feedback(planId,request,key));}
}
