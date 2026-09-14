package com.uav.lowaltitude.modules.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.modules.identity.api.SystemDtos.DistrictCreateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.DistrictResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.DistrictUpdateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.EnabledRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.OrganizationCreateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.OrganizationResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.OrganizationUpdateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PageResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PasswordResetRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserAccessRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserCreationRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserDeletionRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserProfileRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserStatusRequest;
import com.uav.lowaltitude.modules.identity.application.SystemManagementService;
import com.uav.lowaltitude.platform.api.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class UserAdminController {

    private final SystemManagementService service;

    public UserAdminController(SystemManagementService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<UserResponse>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.listUsers(keyword, status, roleCode, orgId, page(page, size)));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<UserResponse> user(@PathVariable String userId) {
        return ApiResponse.ok(service.getUser(userId));
    }

    @PatchMapping("/users/{userId}")
    public ApiResponse<UserResponse> updateUser(@PathVariable String userId,
            @Valid @RequestBody UserProfileRequest body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.updateUserProfile(userId, body, key, meta(request)));
    }

    @PutMapping("/users/{userId}/status")
    public ApiResponse<UserResponse> status(@PathVariable String userId,
            @Valid @RequestBody UserStatusRequest body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.setUserStatus(userId, body, key, meta(request)));
    }

    @PostMapping("/users/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable String userId,
            @Valid @RequestBody PasswordResetRequest body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        service.resetPassword(userId, body, key, meta(request));
        return ApiResponse.ok(null);
    }

    @PostMapping("/users/creation-requests")
    public ApiResponse<Void> removedUserCreationRequest() {
        throw approvalFlowRemoved();
    }

    @PostMapping("/users/{userId}/access-change-requests")
    public ApiResponse<Void> removedUserAccessRequest(@PathVariable String userId) {
        throw approvalFlowRemoved();
    }

    @PostMapping("/users")
    public ApiResponse<UserResponse> createUser(@Valid @RequestBody UserCreationRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.createUser(body, key, meta(request)));
    }

    @PutMapping("/users/{userId}/access")
    public ApiResponse<UserResponse> updateUserAccess(@PathVariable String userId,
            @Valid @RequestBody UserAccessRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.updateUserAccess(userId, body, key, meta(request)));
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable String userId,
            @RequestParam("expected_version") int expectedVersion,
            @Valid @RequestBody UserDeletionRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        service.deleteUser(userId, expectedVersion, body.reason(), key, meta(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/organizations")
    public ApiResponse<List<OrganizationResponse>> organizations() {
        return ApiResponse.ok(service.listOrganizations());
    }

    @PostMapping("/organizations")
    public ApiResponse<OrganizationResponse> createOrganization(@Valid @RequestBody OrganizationCreateRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.createOrganization(body, key, meta(request)));
    }

    @PatchMapping("/organizations/{orgId}")
    public ApiResponse<OrganizationResponse> updateOrganization(@PathVariable String orgId,
            @Valid @RequestBody OrganizationUpdateRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.updateOrganization(orgId, body, key, meta(request)));
    }

    @GetMapping("/districts")
    public ApiResponse<List<DistrictResponse>> districts() {
        return ApiResponse.ok(service.listDistricts());
    }

    @PostMapping("/districts")
    public ApiResponse<DistrictResponse> createDistrict(@Valid @RequestBody DistrictCreateRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.createDistrict(body, key, meta(request)));
    }

    @PatchMapping("/districts/{districtId}")
    public ApiResponse<DistrictResponse> updateDistrict(@PathVariable String districtId,
            @Valid @RequestBody DistrictUpdateRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.updateDistrict(districtId, body, key, meta(request)));
    }

    @PutMapping("/districts/{districtId}/status")
    public ApiResponse<DistrictResponse> districtStatus(@PathVariable String districtId,
            @Valid @RequestBody EnabledRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ApiResponse.ok(service.setDistrictEnabled(districtId, body, key, meta(request)));
    }

    public static SystemManagementService.RequestMeta meta(HttpServletRequest request) {
        String ip = request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
        String ua = request.getHeader("User-Agent");
        if (ua == null) ua = "";
        if (ua.length() > 512) ua = ua.substring(0, 512);
        return new SystemManagementService.RequestMeta(ip, ua);
    }

    public static SystemDtos.PageQuery page(Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? 20 : size;
        if (p < 1 || s < 1 || s > 100) {
            throw new com.uav.lowaltitude.platform.api.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page 从1开始，size 必须为1至100");
        }
        return new SystemDtos.PageQuery(p, s);
    }

    public static com.uav.lowaltitude.platform.api.ApiException approvalFlowRemoved() {
        return new com.uav.lowaltitude.platform.api.ApiException(
                HttpStatus.GONE, "APPROVAL_FLOW_REMOVED", "人工复核流程已取消，请改用直接生效接口");
    }
}
