package com.uav.lowaltitude.modules.identity.api;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.*;
import com.uav.lowaltitude.modules.identity.api.UnitDelegationDtos.*;
import com.uav.lowaltitude.modules.identity.application.UnitDelegationService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** The service checks an active appointment for every request; platform identity does not impersonate a unit. */
@RestController
@RequestMapping("/api/v1/unit-admin")
public class UnitAdminController {
    private final UnitDelegationService service;
    public UnitAdminController(UnitDelegationService service) { this.service=service; }
    @GetMapping("/catalog") public ApiResponse<UnitCatalog> catalog() { return ApiResponse.ok(service.catalog()); }
    @GetMapping("/users") public ApiResponse<PageResponse<UserResponse>> users(
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(required=false) String roleCode,@RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.listUsers(keyword,status,roleCode,UserAdminController.page(page,size)));
    }
    @GetMapping("/users/{id}") public ApiResponse<UserResponse> user(@PathVariable String id) { return ApiResponse.ok(service.getUser(id)); }
    @PostMapping("/users") public ApiResponse<UserResponse> create(@Valid @RequestBody UserCreationRequest body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.createUser(body,key,UserAdminController.meta(request)));
    }
    @PatchMapping("/users/{id}") public ApiResponse<UserResponse> update(@PathVariable String id,
            @Valid @RequestBody UserProfileRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.updateUser(id,body,key,UserAdminController.meta(request)));
    }
    @PutMapping("/users/{id}/access") public ApiResponse<UserResponse> access(@PathVariable String id,
            @Valid @RequestBody UserAccessRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.updateAccess(id,body,key,UserAdminController.meta(request)));
    }
    @PutMapping("/users/{id}/status") public ApiResponse<UserResponse> status(@PathVariable String id,
            @Valid @RequestBody UserStatusRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.status(id,body,key,UserAdminController.meta(request)));
    }
    @PostMapping("/users/{id}/reset-password") public ApiResponse<Void> password(@PathVariable String id,
            @Valid @RequestBody PasswordResetRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        service.resetPassword(id,body,key,UserAdminController.meta(request)); return ApiResponse.ok(null);
    }
    @DeleteMapping("/users/{id}") public ApiResponse<Void> deleteUser(@PathVariable String id,
            @RequestParam("expected_version") int version,@Valid @RequestBody UserDeletionRequest body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        service.deleteUser(id,version,body.reason(),key,UserAdminController.meta(request)); return ApiResponse.ok(null);
    }
    @GetMapping("/roles") public ApiResponse<List<UnitRole>> roles() { return ApiResponse.ok(service.roles()); }
    @GetMapping("/roles/{code}") public ApiResponse<UnitRole> role(@PathVariable String code) { return ApiResponse.ok(service.getRole(code)); }
    @PostMapping("/roles") public ApiResponse<UnitRole> createRole(@Valid @RequestBody UnitRoleCreateRequest body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.createRole(body,key,UserAdminController.meta(request)));
    }
    @PatchMapping("/roles/{code}") public ApiResponse<UnitRole> updateRole(@PathVariable String code,
            @Valid @RequestBody RoleUpdateRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.updateRole(code,body,key,UserAdminController.meta(request)));
    }
    @PutMapping("/roles/{code}/permissions") public ApiResponse<UnitRole> permissions(@PathVariable String code,
            @Valid @RequestBody RoleAccessRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.updateRolePermissions(code,body,key,UserAdminController.meta(request)));
    }
    @PutMapping("/roles/{code}/status") public ApiResponse<UnitRole> roleStatus(@PathVariable String code,
            @Valid @RequestBody EnabledRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return ApiResponse.ok(service.roleStatus(code,body,key,UserAdminController.meta(request)));
    }
    @DeleteMapping("/roles/{code}") public ApiResponse<Void> deleteRole(@PathVariable String code,
            @RequestParam("expected_version") int version,@Valid @RequestBody RoleDeletionDirectRequest body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        service.deleteRole(code,version,body.reason(),key,UserAdminController.meta(request)); return ApiResponse.ok(null);
    }
}
