package com.uav.lowaltitude.modules.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.identity.api.UnitDelegationDtos.*;
import com.uav.lowaltitude.modules.identity.application.UnitDelegationService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.security.BackendOnly;

@RestController
@BackendOnly
@RequestMapping("/api/v1/organizations/{orgId}/delegation")
public class OrganizationDelegationController {
    private final UnitDelegationService service;
    public OrganizationDelegationController(UnitDelegationService service) { this.service=service; }
    @GetMapping public ApiResponse<DelegationResponse> get(@PathVariable String orgId) {
        return ApiResponse.ok(service.get(orgId));
    }
    @PostMapping("/preview") public ApiResponse<PreviewResponse> preview(@PathVariable String orgId,
            @Valid @RequestBody DelegationRequest body) { return ApiResponse.ok(service.preview(orgId,body)); }
    @PutMapping public ApiResponse<DelegationResponse> apply(@PathVariable String orgId,
            @Valid @RequestBody DelegationRequest body,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.apply(orgId,body,key,UserAdminController.meta(request)));
    }
}
