package com.uav.lowaltitude.modules.identity.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/access-change-requests")
public class AccessChangeController {

    @GetMapping
    public ApiResponse<Void> list() {
        throw UserAdminController.approvalFlowRemoved();
    }

    @GetMapping("/{changeId}")
    public ApiResponse<Void> detail(@PathVariable String changeId) {
        throw UserAdminController.approvalFlowRemoved();
    }

    @PostMapping("/{changeId}/approve")
    public ApiResponse<Void> approve(@PathVariable String changeId) {
        throw UserAdminController.approvalFlowRemoved();
    }

    @PostMapping("/{changeId}/reject")
    public ApiResponse<Void> reject(@PathVariable String changeId) {
        throw UserAdminController.approvalFlowRemoved();
    }
}
