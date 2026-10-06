package com.uav.lowaltitude.modules.identity.api;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
            String sourceMode,
            // ZT-14：数据范围（ALL/OWN_ORG/OWN_ORG_TREE/CUSTOM/NONE），个人资料页展示用。
            String dataScope,
            // ZT-28：本人改资料时作为 expected_version 回传。
            int version) {
    }

    /** 本人可改的资料只有姓名和电话；单位、角色、数据范围由管理员在用户管理里调整。 */
    public record ProfileUpdateRequest(
            @NotBlank(message = "姓名不能为空") @Size(max = 64, message = "姓名不能超过64个字符") String name,
            @Size(max = 32, message = "联系电话不能超过32个字符")
            @Pattern(regexp = "[0-9+()\\- ]*", message = "联系电话只能填写数字、空格和 + - ( )") String phone,
            @Min(0) int expectedVersion) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 6, max = 32, message = "新密码长度必须在6到32位之间") String newPassword) {
        @Override public String toString() { return "ChangePasswordRequest[currentPassword=***, newPassword=***]"; }
    }
}
