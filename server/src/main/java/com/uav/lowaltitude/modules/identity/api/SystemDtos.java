package com.uav.lowaltitude.modules.identity.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SystemDtos {

    private SystemDtos() {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }

    public record PageQuery(@Min(1) int page, @Min(1) @Max(100) int size) {
        public static PageQuery of(Integer page, Integer size) {
            return new PageQuery(page == null ? 1 : page, size == null ? 20 : size);
        }
    }

    public record ScopeGrantInput(@NotBlank String orgId, @NotBlank String districtId) {
    }

    public record UserResponse(
            String userId, String account, String name, String phone,
            String orgId, String orgName, String roleCode, String roleName,
            String status,
            boolean mustChangePassword, boolean online, Long lastLoginAt,
            String lastLoginIp, long createdAt, int version) {
    }

    public record UserCreationRequest(
            @NotBlank @Size(max = 64) String account,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 32) String phone,
            @NotBlank String orgId,
            @NotBlank String roleCode,
            @NotBlank(message = "临时密码不能为空")
            @Size(min = 6, max = 32, message = "临时密码长度必须在6到32位之间")
            String temporaryPassword,
            @Size(max = 1000) String reason) {
        @Override public String toString() {
            return "UserCreationRequest[account=" + account + ", roleCode=" + roleCode
                    + ", temporaryPassword=***]";
        }
    }

    public record UserProfileRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 32) String phone,
            @NotBlank String orgId,
            @Size(max = 64) String roleCode,
            @Size(max = 1000) String reason,
            @Min(0) int expectedVersion) {
    }

    public record UserStatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
            @Min(0) int expectedVersion) {
    }

    public record UserDeletionRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record PasswordResetRequest(
            @NotBlank(message = "临时密码不能为空")
            @Size(min = 6, max = 32, message = "临时密码长度必须在6到32位之间")
            String temporaryPassword,
            @Min(0) int expectedVersion) {
        @Override public String toString() {
            return "PasswordResetRequest[temporaryPassword=***, expectedVersion=" + expectedVersion + "]";
        }
    }

    public record UserAccessRequest(
            @NotBlank String roleCode,
            @Min(0) int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record OrganizationResponse(
            String orgId, String parentId, String orgCode, String name, boolean enabled, int version) {
    }

    public record OrganizationCreateRequest(
            @Size(max = 64) String orgCode,
            @NotBlank @Size(max = 128) String name,
            String parentId) {
    }

    public record OrganizationUpdateRequest(
            @NotBlank @Size(max = 128) String name,
            String parentId,
            @Min(0) int expectedVersion) {
    }

    public record EnabledRequest(boolean enabled, @Min(0) int expectedVersion) {
    }

    public record DistrictResponse(
            String districtId, String districtCode, String name, boolean enabled, int version) {
    }

    public record DistrictCreateRequest(
            @NotBlank @Size(max = 64) String districtCode,
            @NotBlank @Size(max = 128) String name) {
    }

    public record DistrictUpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Min(0) int expectedVersion) {
    }

    public record PermissionAssignment(
            @NotBlank String permissionCode,
            @NotBlank @Pattern(regexp = "NONE|READ|OP|AUTH") String level,
            boolean menuEnabled) {
    }

    public record PermissionResponse(
            String permissionCode, String moduleName, String routeKey, int sortOrder,
            String level, boolean menuEnabled) {
    }

    public record RoleSummaryResponse(
            String roleCode, String name, String description, boolean builtin,
            boolean enabled, int userCount, int version) {
    }

    public record RoleResponse(
            String roleCode, String name, String description, boolean builtin,
            boolean enabled, int userCount, int version, List<PermissionResponse> permissions,
            /* 只列该角色**已授**的动作行（决策 15-1）：把全目录都列出来、未授的标 NONE，
               会让页面分不清"没授给它"和"系统里没这一项"。 */
            List<RoleActionResponse> actions) {
    }

    public record RoleActionResponse(String permissionCode, String level) { }

    /** 动作权限目录，按模块分组；module_name 必须给，否则页面只能显示 disposal/punishment 这种码。 */
    public record ActionModuleResponse(String moduleCode, String moduleName, List<ActionResponse> actions) { }

    public record ActionResponse(String permissionCode, String actionCode, String name) { }

    public record ActionAssignment(
            @NotBlank String permissionCode,
            @NotBlank @Pattern(regexp = "NONE|READ|OP") String level) {
    }

    public record RoleCreateRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 512) String description,
            @NotEmpty @Valid List<PermissionAssignment> permissions,
            @Size(max = 1000) String reason) {
    }

    public record RoleUpdateRequest(@Size(max = 512) String description, @Min(0) int expectedVersion) {
    }

    public record RoleAccessRequest(
            @NotEmpty @Valid List<PermissionAssignment> permissions,
            @Min(0) int expectedVersion,
            @Size(max = 1000) String reason,
            /* 缺省（null）表示**不动**该角色的动作行；给了就是整组替换，NONE 即删除（决策 15-2）。
               把"没提交"当成"清空"会让一次无关的矩阵调整悄悄收走别人的动作权限。 */
            @Valid List<ActionAssignment> actions) {
    }

    public record RoleDeletionDirectRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record RoleDeletionRequest(@Min(0) int expectedVersion, @NotBlank @Size(max = 1000) String reason) {
    }

    public record AccessChangeResponse(
            String changeId, String changeType, String subjectType, String subjectId,
            String requesterId, String requesterName, String beforeSnapshot, String afterSnapshot,
            String reason, int subjectVersion, String status, String reviewerId,
            String reviewerName, String reviewComment, long requestedAt, Long reviewedAt, int version) {
    }

    public record ReviewRequest(@Min(0) int expectedVersion, @Size(max = 1000) String comment) {
    }

    public record RejectRequest(
            @Min(0) int expectedVersion,
            @NotBlank @Size(max = 1000) String comment) {
    }

    public record MutationAcceptedResponse(String changeId, String status) {
    }
}
