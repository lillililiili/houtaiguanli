package com.uav.lowaltitude.modules.identity.api;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.ActionModuleResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PermissionResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleAccessRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleCreateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleDeletionDirectRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleSummaryResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleUpdateRequest;
import com.uav.lowaltitude.modules.identity.application.SystemManagementService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class RoleAdminController {

    private final SystemManagementService service;

    public RoleAdminController(SystemManagementService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleSummaryResponse>> roles() {
        return ApiResponse.ok(service.listRoles());
    }

    @GetMapping("/roles/{roleCode}")
    public ApiResponse<RoleResponse> role(@PathVariable String roleCode) {
        return ApiResponse.ok(service.getRole(roleCode));
    }

    @GetMapping("/permissions/catalog")
    public ApiResponse<List<PermissionResponse>> catalog() {
        return ApiResponse.ok(service.permissionCatalog());
    }

    /**
     * 动作权限目录（决策 15-1）。与 `/permissions/catalog` 分开：矩阵要求整组提交，动作是逐项授予的，
     * 混在一起会让"提交完整矩阵"这条校验把动作也算进去。
     */
    @GetMapping("/permissions/actions")
    public ApiResponse<List<ActionModuleResponse>> actionCatalog() {
        return ApiResponse.ok(service.listActionCatalog());
    }

    @PostMapping("/roles")
    public ApiResponse<RoleResponse> create(@Valid @RequestBody RoleCreateRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.createRole(body, key, UserAdminController.meta(request)));
    }

    @PatchMapping("/roles/{roleCode}")
    public ApiResponse<RoleResponse> update(@PathVariable String roleCode,
            @Valid @RequestBody RoleUpdateRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.updateRole(roleCode, body, key, UserAdminController.meta(request)));
    }

    @PostMapping("/roles/{roleCode}/access-change-requests")
    public ApiResponse<Void> removedAccessRequest(@PathVariable String roleCode) {
        throw UserAdminController.approvalFlowRemoved();
    }

    @PostMapping("/roles/{roleCode}/deletion-requests")
    public ApiResponse<Void> removedDeletionRequest(@PathVariable String roleCode) {
        throw UserAdminController.approvalFlowRemoved();
    }

    @PutMapping("/roles/{roleCode}/permissions")
    public ApiResponse<RoleResponse> updatePermissions(@PathVariable String roleCode,
            @Valid @RequestBody RoleAccessRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.updateRolePermissions(roleCode, body, key, UserAdminController.meta(request)));
    }

    @DeleteMapping("/roles/{roleCode}")
    public ApiResponse<Void> delete(@PathVariable String roleCode,
            @RequestParam("expected_version") int expectedVersion,
            @Valid @RequestBody RoleDeletionDirectRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        service.deleteRole(roleCode, expectedVersion, body.reason(), key, UserAdminController.meta(request));
        return ApiResponse.ok(null);
    }
}
