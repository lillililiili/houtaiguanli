package com.uav.lowaltitude.modules.identity.api;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.*;

public final class UnitDelegationDtos {
    private UnitDelegationDtos() {}
    public record DelegationRequest(String administratorUserId, @Min(0) int expectedVersion,
            @NotNull @Valid List<PermissionAssignment> permissions,
            @NotNull @Valid List<ActionAssignment> actions, String previewToken,
            @Valid List<ManagementPermission> managementPermissions,
            @Valid List<DelegationNewRole> newRoles,
            @Valid List<MemberRoleAssignment> memberAssignments, List<@NotBlank String> retireRoleCodes) {
        public DelegationRequest {
            newRoles=newRoles==null?List.of():List.copyOf(newRoles);
            memberAssignments=memberAssignments==null?List.of():List.copyOf(memberAssignments);
            retireRoleCodes=retireRoleCodes==null?List.of():List.copyOf(retireRoleCodes);
        }
        public DelegationRequest(String administratorUserId, int expectedVersion,
                List<PermissionAssignment> permissions, List<ActionAssignment> actions, String previewToken) {
            this(administratorUserId,expectedVersion,permissions,actions,previewToken,null,List.of(),List.of(),List.of());
        }
        public DelegationRequest(String administratorUserId, int expectedVersion,
                List<PermissionAssignment> permissions, List<ActionAssignment> actions, String previewToken,
                List<ManagementPermission> managementPermissions) {
            this(administratorUserId,expectedVersion,permissions,actions,previewToken,managementPermissions,List.of(),List.of(),List.of());
        }
    }
    public record DelegationNewRole(@NotBlank @Size(max=64) String clientKey,
            @NotBlank @Size(max=64) String name, @Size(max=512) String description,
            @Valid List<PermissionAssignment> permissions, @Valid List<ActionAssignment> actions) {
        public DelegationNewRole {
            permissions=permissions==null?List.of():List.copyOf(permissions);
            actions=actions==null?List.of():List.copyOf(actions);
        }
    }
    public record MemberRoleAssignment(@NotBlank String userId, String roleCode, String newRoleKey) { }
    public record ManagementPermission(@NotBlank String moduleKey,
            @NotBlank @Pattern(regexp="NONE|READ|OP") String level) {}
    public record Catalog(List<PermissionResponse> permissions, List<ActionModuleResponse> actionModules) {}
    public record DelegationResponse(String orgId, String orgName, boolean managed, int version,
            String administratorUserId, List<PermissionResponse> permissions, List<ActionAssignment> actions,
            List<UserResponse> administratorCandidates, Catalog catalog,
            List<ManagementPermission> managementPermissions, List<UserResponse> members, List<UnitRole> unitRoles) {}
    public record UnitCatalog(String orgId, String orgName, String administratorUserId,
            List<PermissionResponse> permissions, List<ActionModuleResponse> actionModules,
            List<ActionAssignment> actions) {}
    public record UserImpact(String userId, String account, String name, String fromRoleCode,
            String fromRoleName, String fromDataScope, String toDataScope, boolean willCloneRole) {}
    public record RoleImpact(String roleCode, String name, List<String> removedPermissions) {}
    public record CrossOrgImpact(String userId, String account, String orgId, String orgName,
            String resourceType, long total) {}
    public record PreviewResponse(String previewToken, long expiresAt, String orgId, int expectedVersion,
            List<UserImpact> users, List<RoleImpact> roles, List<CrossOrgImpact> crossOrgImpacts, List<String> warnings) {}
    public record UnitRole(String roleCode, String name, String description, boolean builtin, boolean enabled,
            int userCount, int version, List<PermissionResponse> permissions, List<RoleActionResponse> actions,
            boolean platformMaintained, RoleCopyOrigin copyOrigin) {}
    public record UnitRoleCreateRequest(@NotBlank @Size(max=64) String name, @Size(max=512) String description,
            @NotNull @Valid List<PermissionAssignment> permissions,
            @Valid List<ActionAssignment> actions, @Size(max=1000) String reason) {}
}
