package com.uav.lowaltitude.modules.identity.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String account, @NotBlank String password) {
        @Override public String toString() { return "LoginRequest[account=" + account + ", password=***]"; }
    }

    public record LoginResponse(
            String userId,
            String account,
            String name,
            String roleCode,
            String sessionId,
            long expireAt,
            boolean mustChangePassword) {
    }

    public record ScopeGrantResponse(String orgId, String orgName, String districtId, String districtName) {
    }

    public record MeResponse(
            String userId,
            String account,
            String name,
            String phone,
            String orgId,
            String orgName,
            String roleCode,
            String roleName,
            String scopeMode,
            List<ScopeGrantResponse> scopeGrants,
            List<String> menuKeys,
            List<String> permissionCodes,
            int permissionVersion,
            boolean mustChangePassword,
            String sourceMode) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 6, max = 32, message = "新密码长度必须在6到32位之间") String newPassword) {
        @Override public String toString() { return "ChangePasswordRequest[currentPassword=***, newPassword=***]"; }
    }
}
