package com.uav.lowaltitude.modules.automationrule.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.automationrule.api.ResponseOrderDtos.*;
import com.uav.lowaltitude.modules.automationrule.application.ResponseOrderPolicyService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@com.uav.lowaltitude.platform.security.BackendOnly
@RequestMapping("/api/v1/response-order-policy")
public class ResponseOrderController {
    private final ResponseOrderPolicyService service;
    public ResponseOrderController(ResponseOrderPolicyService service){this.service=service;}
    @GetMapping public ApiResponse<Policy> get(){return ApiResponse.ok(service.get());}
    @PutMapping public ApiResponse<Policy> save(@Valid @RequestBody Input input,@RequestHeader("Idempotency-Key")String key){return ApiResponse.ok(service.save(input,key));}
    @GetMapping("/history") public ApiResponse<Page> history(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.ok(service.history(page,size));}
}
