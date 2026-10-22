package com.uav.lowaltitude.modules.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.uav.lowaltitude.modules.identity.application.OrganizationDeletionService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.security.BackendOnly;

@RestController
@BackendOnly
@RequestMapping("/api/v1/organizations")
public class OrganizationDeletionController {
    private final OrganizationDeletionService service;

    public OrganizationDeletionController(OrganizationDeletionService service) {
        this.service = service;
    }

    @DeleteMapping("/{orgId}")
    public ApiResponse<Void> delete(@PathVariable String orgId,
            @RequestParam("expected_version") int expectedVersion,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        service.delete(orgId, expectedVersion, key, UserAdminController.meta(request));
        return ApiResponse.ok(null);
    }
}
