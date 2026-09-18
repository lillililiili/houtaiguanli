package com.uav.lowaltitude.modules.identity.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.AccessChangeResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.DistrictCreateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.DistrictResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.DistrictUpdateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.EnabledRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.MutationAcceptedResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.OrganizationCreateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.OrganizationResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.OrganizationUpdateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PageQuery;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PageResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PasswordResetRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PermissionAssignment;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PermissionResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RejectRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.ReviewRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.ActionAssignment;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.ActionModuleResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.ActionResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleActionResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleAccessRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleCreateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleDeletionRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleSummaryResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleUpdateRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.ScopeGrantInput;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserAccessRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserCreationRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserProfileRequest;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserResponse;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.UserStatusRequest;
import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.ActionRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.AccessChangeRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.DistrictRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.OrgRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.PendingUserRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.PermissionRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.RoleRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.UserAdminRow;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.SessionMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class SystemManagementService {

    private static final Set<String> BUILTIN_ROLES = Set.of("ROLE-ADMIN");
    /**
     * 矩阵里不能授给自定义角色的模块（决策 15-2，18-13 修订）。
     * `audit` 已经不在里面：看审计日志是审计员的本职，把它一并收成超管专有是规则过严。
     */
    private static final Set<String> PROTECTED_MATRIX_PERMISSIONS = Set.of("users", "roles", "countermeasure");
    /**
     * 动作码里不能授给自定义角色的域。这里仍然含 `audit`，与矩阵**不是同一条线**：
     * 矩阵的 `audit` 是"看审计日志"，而审计域的动作是对日志本身动手（目录里还没有这类行，
     * 用例造的那条叫清空审计日志）。看得见和能清空是两回事，放开前者不等于放开后者。
     */
    private static final Set<String> PROTECTED_ACTION_DOMAINS = Set.of("users", "roles", "audit", "countermeasure");
    // 全局切图会同时影响所有业务页面，只允许内置超级管理员执行；运维角色仍可上传和清理候选包。
    private static final Set<String> PROTECTED_ACTION_CODES = Set.of("map:activate");
    private static final String DEFAULT_USER_SCOPE_MODE = "ALL";

    private final IdentityAdminMapper mapper;
    private final UserMapper userMapper;
    private final SessionMapper sessionMapper;
    private final AccessService accessService;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AppClock appClock;
    private final IdempotencyGuard idempotencyGuard;

    public SystemManagementService(
            IdentityAdminMapper mapper,
            UserMapper userMapper,
            SessionMapper sessionMapper,
            AccessService accessService,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            ObjectMapper objectMapper,
            AppClock appClock,
            IdempotencyGuard idempotencyGuard) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.accessService = accessService;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.appClock = appClock;
        this.idempotencyGuard = idempotencyGuard;
    }

    public PageResponse<UserResponse> listUsers(String keyword, String status, String roleCode, String orgId,
            PageQuery page) {
        accessService.require("users.read");
        String query = normalized(keyword).toLowerCase(Locale.ROOT);
        Set<String> orgScope = orgScope(orgId);
        Predicate<UserAdminRow> filter = row -> (query.isEmpty()
                || contains(row.getName(), query) || contains(row.getAccount(), query)
                || contains(row.getRoleName(), query) || contains(row.getOrgName(), query)
                || contains(row.getPhone(), query))
                && (blank(status) || status.equals(row.getStatus()))
                && (blank(roleCode) || roleCode.equals(row.getRoleCode()))
                && (orgScope == null || orgScope.contains(row.getOrgId()));
        List<UserResponse> items = mapper.listUsers(appClock.nowMillis()).stream()
                .filter(filter)
                .map(this::toUser)
                .toList();
        return page(items, page);
    }

    public UserResponse getUser(String userId) {
        accessService.require("users.read");
        UserAdminRow row = requireUserRow(userId);
        return toUser(row);
    }

    @Transactional
    public UserResponse updateUserProfile(String userId, UserProfileRequest request, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "user-profile:" + userId + ":" + request);
        accessService.require("users.auth");
        UserAdminRow current = requireUserRow(userId);
        OrgRow org = requireEnabledOrg(request.orgId());
        boolean roleChanged = !blank(request.roleCode()) && !request.roleCode().trim().equals(current.getRoleCode());
        RoleRow nextRole = null;
        if (roleChanged) {
            if ("ROLE-ADMIN".equals(current.getRoleCode())) {
                throw bad("SUPER_ADMIN_PROTECTED", "超级管理员角色不能修改");
            }
            nextRole = requireAssignableRole(request.roleCode().trim());
        }
        long now = appClock.nowMillis();
        if (mapper.updateUserProfile(userId, request.name().trim(), nullable(request.phone()), org.getOrgId(),
                now, request.expectedVersion()) != 1) {
            conflict();
        }
        if (roleChanged) {
            if (mapper.updateUserAccess(userId, nextRole.getRoleCode(), current.getScopeMode(), now,
                    request.expectedVersion() + 1) != 1) {
                conflict();
            }
            sessionMapper.expireAllForUser(userId);
            String reason = blank(request.reason()) ? "超级管理员直接调整用户角色" : request.reason().trim();
            audit("users", "user_access_updated", "user", userId,
                    json(Map.of("from_role", current.getRoleCode(), "to_role", nextRole.getRoleCode(),
                            "reason", reason)), meta);
        }
        audit("users", "user_profile_updated", "user", userId,
                json(Map.of("name", request.name().trim(), "org_id", org.getOrgId())), meta);
        return toUser(requireUserRow(userId));
    }

    @Transactional
    public UserResponse setUserStatus(String userId, UserStatusRequest request, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "user-status:" + userId + ":" + request);
        accessService.require("users.auth");
        UserAdminRow current = requireUserRow(userId);
        if ("ROLE-ADMIN".equals(current.getRoleCode())) {
            throw bad("SUPER_ADMIN_PROTECTED", "超级管理员账号不能停用、启用或转移");
        }
        long now = appClock.nowMillis();
        if (mapper.setUserStatus(userId, request.status(), now, request.expectedVersion()) != 1) conflict();
        if ("DISABLED".equals(request.status())) sessionMapper.expireAllForUser(userId);
        audit("users", "user_status_changed", "user", userId,
                json(Map.of("from", current.getStatus(), "to", request.status())), meta);
        return toUser(requireUserRow(userId));
    }

    @Transactional
    public void resetPassword(String userId, PasswordResetRequest request, String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "user-password-reset:" + userId);
        accessService.require("users.auth");
        AuthUser actor = AuthContext.require();
        if (actor.userId().equals(userId)) {
            throw bad("SELF_RESET_FORBIDDEN", "请使用修改密码功能变更当前账号密码");
        }
        AppUser user = requireUser(userId);
        if ("ROLE-ADMIN".equals(user.getRoleCode())) {
            throw bad("SUPER_ADMIN_PROTECTED", "超级管理员密码只能通过本人改密或一次性维护恢复模式重置");
        }
        passwordPolicy.validateTemporary(request.temporaryPassword(), user.getAccount());
        long now = appClock.nowMillis();
        if (mapper.resetUserPassword(userId, passwordEncoder.encode(request.temporaryPassword()), now,
                request.expectedVersion()) != 1) conflict();
        sessionMapper.expireAllForUser(userId);
        audit("users", "user_password_reset", "user", userId, null, meta);
    }

    @Transactional
    public MutationAcceptedResponse requestUserCreation(UserCreationRequest request, String idempotencyKey,
            RequestMeta meta) {
        String legacyReason = request.reason() == null || request.reason().isBlank()
                ? "旧审批接口创建用户" : request.reason().trim();
        idempotencyGuard.claim(idempotencyKey, "user-create:" + request.account() + ":" + legacyReason);
        accessService.require("users.auth");
        String account = request.account().trim();
        if (!account.matches("[A-Za-z0-9._-]{3,64}")) {
            throw bad("INVALID_ACCOUNT", "登录账号只能包含字母、数字、点、下划线和连字符，长度为3至64位");
        }
        if (userMapper.findByAccount(account) != null) {
            throw conflict("DUPLICATE_ACCOUNT", "登录账号已存在");
        }
        RoleRow role = requireEnabledRole(request.roleCode());
        OrgRow org = requireEnabledOrg(request.orgId());
        List<ScopeGrantInput> scopes = List.of();
        passwordPolicy.validateTemporary(request.temporaryPassword(), account);
        String changeId = UUID.randomUUID().toString();
        UserCreationSnapshot snapshot = new UserCreationSnapshot(account, request.name().trim(),
                nullable(request.phone()), org.getOrgId(), role.getRoleCode(), DEFAULT_USER_SCOPE_MODE, scopes);
        AccessChangeRow change = newChange(changeId, "USER_CREATE", "USER", account, "{}", json(snapshot),
                legacyReason, -1);
        PendingUserRow pending = new PendingUserRow();
        pending.setChangeId(changeId);
        pending.setAccount(account);
        pending.setName(snapshot.name());
        pending.setPhone(snapshot.phone());
        pending.setOrgId(snapshot.orgId());
        pending.setRoleCode(snapshot.roleCode());
        pending.setScopeMode(snapshot.scopeMode());
        pending.setScopeGrants(json(scopes));
        pending.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
        try {
            mapper.insertAccessChange(change);
            mapper.insertPendingUser(pending);
        } catch (DuplicateKeyException ex) {
            throw conflict("DUPLICATE_ACCOUNT", "登录账号已存在或已有待审批申请");
        }
        audit("users", "user_creation_requested", "access_change", changeId,
                json(Map.of("account", account, "reason", legacyReason)), meta);
        return new MutationAcceptedResponse(changeId, "PENDING");
    }

    @Transactional
    public UserResponse createUser(UserCreationRequest request, String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey,
                "user-create-direct:" + request.account() + ":" + request.roleCode() + ":" + request.orgId());
        accessService.require("users.auth");
        String account = request.account().trim();
        if (!account.matches("[A-Za-z0-9._-]{3,64}")) {
            throw bad("INVALID_ACCOUNT", "登录账号只能包含字母、数字、点、下划线和连字符，长度为3至64位");
        }
        if (userMapper.findByAccount(account) != null) throw conflict("DUPLICATE_ACCOUNT", "登录账号已存在");
        RoleRow role = requireAssignableRole(request.roleCode());
        OrgRow org = requireEnabledOrg(request.orgId());
        passwordPolicy.validateTemporary(request.temporaryPassword(), account);
        String userId = UUID.randomUUID().toString();
        long now = appClock.nowMillis();
        try {
            mapper.insertUser(userId, account, request.name().trim(), nullable(request.phone()), org.getOrgId(),
                    role.getRoleCode(), passwordEncoder.encode(request.temporaryPassword()), DEFAULT_USER_SCOPE_MODE, now);
        } catch (DuplicateKeyException ex) {
            throw conflict("DUPLICATE_ACCOUNT", "登录账号已存在");
        }
        audit("users", "user_created", "user", userId,
                json(Map.of("account", account, "role_code", role.getRoleCode(),
                        "reason", "超级管理员直接创建用户")), meta);
        return toUser(requireUserRow(userId));
    }

    @Transactional
    public UserResponse updateUserAccess(String userId, UserAccessRequest request, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "user-access-direct:" + userId + ":" + request);
        accessService.require("users.auth");
        UserAdminRow user = requireUserRow(userId);
        if ("ROLE-ADMIN".equals(user.getRoleCode())) {
            throw bad("SUPER_ADMIN_PROTECTED", "超级管理员角色不能修改");
        }
        RoleRow role = requireAssignableRole(request.roleCode());
        if (role.getRoleCode().equals(user.getRoleCode())) throw bad("NO_CHANGES", "角色没有变化");
        if (mapper.updateUserAccess(userId, role.getRoleCode(), user.getScopeMode(), appClock.nowMillis(),
                request.expectedVersion()) != 1) conflict();
        sessionMapper.expireAllForUser(userId);
        audit("users", "user_access_updated", "user", userId,
                json(Map.of("from_role", user.getRoleCode(), "to_role", role.getRoleCode(),
                        "reason", request.reason().trim())), meta);
        return toUser(requireUserRow(userId));
    }

    @Transactional
    public void deleteUser(String userId, int expectedVersion, String reason, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey,
                "user-delete:" + userId + ":" + expectedVersion + ":" + reason);
        accessService.require("users.auth");
        UserAdminRow user = requireUserRow(userId);
        if ("ROLE-ADMIN".equals(user.getRoleCode())) {
            throw bad("SUPER_ADMIN_PROTECTED", "超级管理员账号不能删除");
        }
        AuthUser actor = AuthContext.require();
        long now = appClock.nowMillis();
        String tombstoneAccount = user.getAccount().substring(0, Math.min(39, user.getAccount().length()))
                + "~deleted~" + userId.replace("-", "").substring(0, 16);
        if (mapper.softDeleteUser(userId, tombstoneAccount, actor.userId(), now, expectedVersion) != 1) conflict();
        mapper.deleteUserScopes(userId);
        sessionMapper.expireAllForUser(userId);
        audit("users", "user_deleted", "user", userId,
                json(Map.of("account", user.getAccount(), "role_code", user.getRoleCode(),
                        "reason", reason.trim(), "deletion_type", "LOGICAL")), meta);
    }

    @Transactional
    public MutationAcceptedResponse requestUserAccess(String userId, UserAccessRequest request,
            String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "user-access:" + userId + ":" + request);
        accessService.require("users.auth");
        UserAdminRow user = requireUserRow(userId);
        requireEnabledRole(request.roleCode());
        List<ScopeGrantInput> scopes = mapper.listUserScopes(userId).stream()
                .map(s -> new ScopeGrantInput(s.getOrgId(), s.getDistrictId())).toList();
        UserAccessSnapshot before = new UserAccessSnapshot(user.getRoleCode(), user.getScopeMode(), scopes);
        UserAccessSnapshot after = new UserAccessSnapshot(request.roleCode(), user.getScopeMode(), scopes);
        if (user.getRoleCode().equals(request.roleCode())) throw bad("NO_CHANGES", "角色没有变化");
        String changeId = UUID.randomUUID().toString();
        mapper.insertAccessChange(newChange(changeId, "USER_ACCESS", "USER", userId,
                json(before), json(after), request.reason(), request.expectedVersion()));
        audit("users", "user_access_requested", "access_change", changeId,
                json(Map.of("subject_user_id", userId, "reason", request.reason())), meta);
        return new MutationAcceptedResponse(changeId, "PENDING");
    }

    public List<OrganizationResponse> listOrganizations() {
        accessService.require("users.read");
        return mapper.listOrganizations().stream().map(this::toOrganization).toList();
    }

    @Transactional
    public OrganizationResponse createOrganization(OrganizationCreateRequest request, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "organization-create:" + request.name());
        accessService.require("users.auth");
        String parentId = nullable(request.parentId());
        if (parentId != null) requireEnabledOrg(parentId);
        String name = request.name().trim();
        String orgCode = resolveOrgCode(request.orgCode());
        String id = UUID.randomUUID().toString();
        long now = appClock.nowMillis();
        try {
            mapper.insertOrganization(id, parentId, orgCode, name, now);
        } catch (DuplicateKeyException ex) {
            throw conflict("DUPLICATE_ORG_CODE", "组织编码已存在");
        }
        audit("users", "organization_created", "organization", id,
                json(Map.of("org_code", orgCode, "name", name)), meta);
        return toOrganization(mapper.findOrganization(id));
    }

    @Transactional
    public OrganizationResponse updateOrganization(String orgId, OrganizationUpdateRequest request,
            String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "organization-update:" + orgId + ":" + request);
        accessService.require("users.auth");
        requireOrganization(orgId);
        String parentId = nullable(request.parentId());
        validateOrgParent(orgId, parentId);
        if (mapper.updateOrganization(orgId, parentId, request.name().trim(), appClock.nowMillis(),
                request.expectedVersion()) != 1) conflict();
        audit("users", "organization_updated", "organization", orgId,
                json(Map.of("name", request.name().trim())), meta);
        return toOrganization(mapper.findOrganization(orgId));
    }

    public List<DistrictResponse> listDistricts() {
        accessService.require("users.read");
        return mapper.listDistricts().stream().map(this::toDistrict).toList();
    }

    @Transactional
    public DistrictResponse createDistrict(DistrictCreateRequest request, String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "district-create:" + request.districtCode());
        accessService.require("users.auth");
        String id = UUID.randomUUID().toString();
        long now = appClock.nowMillis();
        try {
            mapper.insertDistrict(id, request.districtCode().trim(), request.name().trim(), now);
        } catch (DuplicateKeyException ex) {
            throw conflict("DUPLICATE_DISTRICT_CODE", "区域编码已存在");
        }
        audit("users", "district_created", "district", id,
                json(Map.of("district_code", request.districtCode().trim(), "name", request.name().trim())), meta);
        return toDistrict(mapper.findDistrict(id));
    }

    @Transactional
    public DistrictResponse updateDistrict(String districtId, DistrictUpdateRequest request,
            String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "district-update:" + districtId + ":" + request);
        accessService.require("users.auth");
        requireDistrict(districtId);
        if (mapper.updateDistrict(districtId, request.name().trim(), appClock.nowMillis(),
                request.expectedVersion()) != 1) conflict();
        audit("users", "district_updated", "district", districtId,
                json(Map.of("name", request.name().trim())), meta);
        return toDistrict(mapper.findDistrict(districtId));
    }

    @Transactional
    public DistrictResponse setDistrictEnabled(String districtId, EnabledRequest request,
            String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "district-status:" + districtId + ":" + request);
        accessService.require("users.auth");
        requireDistrict(districtId);
        if (!request.enabled() && mapper.countDistrictScopes(districtId) > 0) {
            throw conflict("DISTRICT_IN_USE", "区域仍被数据范围引用，不能停用");
        }
        if (mapper.setDistrictEnabled(districtId, request.enabled(), appClock.nowMillis(),
                request.expectedVersion()) != 1) conflict();
        audit("users", "district_status_changed", "district", districtId,
                json(Map.of("enabled", request.enabled())), meta);
        return toDistrict(mapper.findDistrict(districtId));
    }

    public List<RoleSummaryResponse> listRoles() {
        accessService.require("roles.read");
        return mapper.listRoles().stream().map(this::toRoleSummary).toList();
    }

    public RoleResponse getRole(String roleCode) {
        accessService.require("roles.read");
        return toRole(requireRole(roleCode));
    }

    public List<PermissionResponse> permissionCatalog() {
        accessService.require("roles.read");
        return mapper.listPermissionCatalog().stream().map(this::toPermission).toList();
    }

    @Transactional
    public RoleResponse createRole(RoleCreateRequest request, String idempotencyKey, RequestMeta meta) {
        String reason = request.reason() == null || request.reason().isBlank()
                ? "超级管理员直接创建角色" : request.reason().trim();
        idempotencyGuard.claim(idempotencyKey, "role-create:" + request.name() + ":" + reason);
        accessService.require("roles.auth");
        String roleCode = "ROLE-CUSTOM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        List<PermissionAssignment> permissions = validateRolePermissions(roleCode, request.permissions());
        long now = appClock.nowMillis();
        try {
            mapper.insertRole(roleCode, request.name().trim(), normalized(request.description()), now);
            mapper.insertEmptyRolePermissions(roleCode);
            for (PermissionAssignment permission : permissions) {
                mapper.updateRolePermission(roleCode, permission.permissionCode(), permission.level(),
                        permission.menuEnabled());
            }
        } catch (DuplicateKeyException ex) {
            throw conflict("DUPLICATE_ROLE", "角色名称已存在");
        }
        audit("roles", "role_created", "role", roleCode,
                json(Map.of("name", request.name().trim(), "permissions", permissions, "reason", reason)), meta);
        return toRole(requireRole(roleCode));
    }

    @Transactional
    public RoleResponse updateRole(String roleCode, RoleUpdateRequest request, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "role-update:" + roleCode + ":" + request);
        accessService.require("roles.auth");
        RoleRow role = requireRole(roleCode);
        if (role.isBuiltin() || BUILTIN_ROLES.contains(roleCode)) {
            throw bad("BUILTIN_ROLE_PROTECTED", "超级管理员角色不能修改");
        }
        if (mapper.updateRoleDescription(roleCode, normalized(request.description()), appClock.nowMillis(),
                request.expectedVersion()) != 1) conflict();
        audit("roles", "role_description_updated", "role", roleCode, null, meta);
        return toRole(requireRole(roleCode));
    }

    @Transactional
    public RoleResponse updateRolePermissions(String roleCode, RoleAccessRequest request,
            String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "role-permissions-direct:" + roleCode + ":" + request);
        accessService.require("roles.auth");
        RoleRow role = requireRole(roleCode);
        // 超级管理员的动作权限同样固定：允许改它等于允许把自己锁在系统外（决策 15-2）。
        // 单独用 ROLE_LOCKED 而不是复用 BUILTIN_ROLE_PROTECTED，是因为前端要能分辨"这是内置角色不给改"
        // 与"这次提交里带了动作行"两种情况。
        if (request.actions() != null && ("ROLE-ADMIN".equals(roleCode) || role.isBuiltin())) {
            throw conflict("ROLE_LOCKED", "内置角色的动作权限不可修改");
        }
        if (role.isBuiltin() || BUILTIN_ROLES.contains(roleCode)) {
            throw bad("BUILTIN_ROLE_PROTECTED", "超级管理员权限固定为全部授权");
        }
        List<PermissionAssignment> permissions = validateRolePermissions(roleCode, request.permissions());
        List<PermissionAssignment> before = mapper.listPermissionsForRole(roleCode).stream()
                .map(p -> new PermissionAssignment(p.getPermissionCode(), p.getPermissionLevel(), p.isMenuEnabled()))
                .toList();
        // 只改动作、矩阵原样提交也算一次有效变更，因此 NO_CHANGES 只在**两者都没变**时才成立。
        if (request.actions() == null && json(before).equals(json(permissions))) {
            throw bad("NO_CHANGES", "角色权限没有变化");
        }
        if (mapper.bumpRoleVersion(roleCode, request.expectedVersion(), appClock.nowMillis()) != 1) conflict();
        for (PermissionAssignment permission : permissions) {
            mapper.updateRolePermission(roleCode, permission.permissionCode(), permission.level(),
                    permission.menuEnabled());
        }
        // actions 缺省表示不动动作行；给了就整组替换（决策 15-2）。
        List<ActionAssignment> actionsGranted = request.actions() == null ? null
                : replaceRoleActions(roleCode, request.actions());
        mapper.bumpPermissionVersionForRole(roleCode);
        sessionMapper.expireAllForRole(roleCode);
        String reason = blank(request.reason()) ? "超级管理员直接调整角色权限" : request.reason().trim();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", permissions);
        detail.put("reason", reason);
        // 记下实际授出去的动作：只记"改过"而不记改成了什么，事后回答不了"这个人当时凭什么能批"。
        if (actionsGranted != null) detail.put("actions_granted", actionsGranted);
        audit("roles", "role_permissions_updated", "role", roleCode, json(detail), meta);
        return toRole(requireRole(roleCode));
    }

    @Transactional
    public void deleteRole(String roleCode, int expectedVersion, String reason, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "role-delete-direct:" + roleCode + ":" + expectedVersion + ":" + reason);
        accessService.require("roles.auth");
        RoleRow role = requireRole(roleCode);
        if (role.isBuiltin() || BUILTIN_ROLES.contains(roleCode)) {
            throw bad("BUILTIN_ROLE_PROTECTED", "超级管理员角色不能删除");
        }
        if (role.getVersion() != expectedVersion) conflict();
        if (role.getUserCount() > 0) throw conflict("ROLE_IN_USE", "角色仍有用户，不能删除");
        mapper.deleteRolePermissions(roleCode);
        if (mapper.deleteCustomRole(roleCode, expectedVersion) != 1) conflict();
        audit("roles", "role_deleted", "role", roleCode,
                json(Map.of("name", role.getName(), "reason", reason.trim())), meta);
    }

    @Transactional
    public MutationAcceptedResponse requestRoleAccess(String roleCode, RoleAccessRequest request,
            String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "role-access:" + roleCode + ":" + request);
        accessService.require("roles.auth");
        RoleRow role = requireRole(roleCode);
        if ("ROLE-ADMIN".equals(roleCode)) {
            throw bad("BUILTIN_ROLE_PROTECTED", "系统管理员权限固定为全部授权");
        }
        List<PermissionAssignment> normalizedPermissions = validateRolePermissions(roleCode, request.permissions());
        List<PermissionAssignment> before = mapper.listPermissionsForRole(roleCode).stream()
                .map(p -> new PermissionAssignment(p.getPermissionCode(), p.getPermissionLevel(), p.isMenuEnabled()))
                .toList();
        if (json(before).equals(json(normalizedPermissions))) throw bad("NO_CHANGES", "角色权限没有变化");
        String changeId = UUID.randomUUID().toString();
        mapper.insertAccessChange(newChange(changeId, "ROLE_ACCESS", "ROLE", roleCode,
                json(new RoleAccessSnapshot(before)), json(new RoleAccessSnapshot(normalizedPermissions)),
                request.reason(), request.expectedVersion()));
        audit("roles", "role_access_requested", "access_change", changeId,
                json(Map.of("role_code", role.getRoleCode(), "reason", request.reason())), meta);
        return new MutationAcceptedResponse(changeId, "PENDING");
    }

    @Transactional
    public MutationAcceptedResponse requestRoleDeletion(String roleCode, RoleDeletionRequest request,
            String idempotencyKey, RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "role-delete:" + roleCode + ":" + request);
        accessService.require("roles.auth");
        RoleRow role = requireRole(roleCode);
        if (role.isBuiltin() || BUILTIN_ROLES.contains(roleCode)) {
            throw bad("BUILTIN_ROLE_PROTECTED", "内置角色不能删除");
        }
        if (role.getUserCount() > 0) throw conflict("ROLE_IN_USE", "角色仍有用户，不能删除");
        String changeId = UUID.randomUUID().toString();
        mapper.insertAccessChange(newChange(changeId, "ROLE_DELETE", "ROLE", roleCode,
                json(toRoleSummary(role)), "{}", request.reason(), request.expectedVersion()));
        audit("roles", "role_deletion_requested", "access_change", changeId,
                json(Map.of("role_code", roleCode, "reason", request.reason())), meta);
        return new MutationAcceptedResponse(changeId, "PENDING");
    }

    public PageResponse<AccessChangeResponse> listAccessChanges(String status, String changeType, PageQuery page) {
        accessService.requireReviewer();
        List<AccessChangeResponse> changes = mapper.listAccessChanges().stream()
                .filter(row -> blank(status) || status.equals(row.getStatus()))
                .filter(row -> blank(changeType) || changeType.equals(row.getChangeType()))
                .map(this::toAccessChange)
                .toList();
        return page(changes, page);
    }

    public AccessChangeResponse getAccessChange(String changeId) {
        accessService.requireReviewer();
        return toAccessChange(requireChange(changeId));
    }

    @Transactional
    public AccessChangeResponse approve(String changeId, ReviewRequest review, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "access-approve:" + changeId + ":" + review.expectedVersion());
        accessService.requireReviewer();
        AuthUser reviewer = AuthContext.require();
        AccessChangeRow change = requirePendingChange(changeId, review.expectedVersion(), reviewer);
        switch (change.getChangeType()) {
            case "USER_CREATE" -> approveUserCreate(change);
            case "USER_ACCESS" -> approveUserAccess(change);
            case "ROLE_ACCESS" -> approveRoleAccess(change);
            case "ROLE_DELETE" -> approveRoleDelete(change);
            default -> throw bad("UNSUPPORTED_CHANGE_TYPE", "不支持的权限变更类型");
        }
        long now = appClock.nowMillis();
        if (mapper.completeAccessChange(changeId, "APPROVED", reviewer.userId(),
                nullable(review.comment()), now, review.expectedVersion()) != 1) conflict();
        mapper.insertAccessChangeRecord(UUID.randomUUID().toString(), changeId, change.getRequesterId(),
                reviewer.userId(), change.getSubjectType(), change.getSubjectId(), change.getBeforeSnapshot(),
                change.getAfterSnapshot(), change.getReason(), nullable(review.comment()), now);
        audit("roles", "access_change_approved", "access_change", changeId,
                json(Map.of("change_type", change.getChangeType(), "requester_id", change.getRequesterId())), meta);
        return toAccessChange(mapper.findAccessChange(changeId));
    }

    @Transactional
    public AccessChangeResponse reject(String changeId, RejectRequest review, String idempotencyKey,
            RequestMeta meta) {
        idempotencyGuard.claim(idempotencyKey, "access-reject:" + changeId + ":" + review.expectedVersion());
        accessService.requireReviewer();
        AuthUser reviewer = AuthContext.require();
        AccessChangeRow change = requirePendingChange(changeId, review.expectedVersion(), reviewer);
        long now = appClock.nowMillis();
        if (mapper.completeAccessChange(changeId, "REJECTED", reviewer.userId(), review.comment().trim(),
                now, review.expectedVersion()) != 1) conflict();
        if ("USER_CREATE".equals(change.getChangeType())) mapper.deletePendingUser(changeId);
        audit("roles", "access_change_rejected", "access_change", changeId,
                json(Map.of("change_type", change.getChangeType(), "comment", review.comment().trim())), meta);
        return toAccessChange(mapper.findAccessChange(changeId));
    }

    private void approveUserCreate(AccessChangeRow change) {
        PendingUserRow pending = mapper.findPendingUser(change.getChangeId());
        if (pending == null) throw conflict("PENDING_USER_MISSING", "待创建用户资料不存在");
        if (userMapper.findByAccount(pending.getAccount()) != null) {
            throw conflict("DUPLICATE_ACCOUNT", "登录账号已存在");
        }
        requireEnabledRole(pending.getRoleCode());
        requireEnabledOrg(pending.getOrgId());
        List<ScopeGrantInput> scopes = readScopes(pending.getScopeGrants());
        validateScopes(pending.getScopeMode(), scopes);
        String userId = UUID.randomUUID().toString();
        mapper.insertUser(userId, pending.getAccount(), pending.getName(), pending.getPhone(), pending.getOrgId(),
                pending.getRoleCode(), pending.getPasswordHash(), pending.getScopeMode(), appClock.nowMillis());
        replaceScopes(userId, pending.getScopeMode(), scopes);
        mapper.deletePendingUser(change.getChangeId());
    }

    private void approveUserAccess(AccessChangeRow change) {
        UserAdminRow user = requireUserRow(change.getSubjectId());
        if (user.getVersion() != change.getSubjectVersion()) conflict();
        UserAccessSnapshot after = read(change.getAfterSnapshot(), UserAccessSnapshot.class);
        requireEnabledRole(after.roleCode());
        List<ScopeGrantInput> scopes = validateScopes(after.scopeMode(), after.scopeGrants());
        if ("ROLE-ADMIN".equals(user.getRoleCode()) && !"ROLE-ADMIN".equals(after.roleCode())
                && "ACTIVE".equals(user.getStatus()) && mapper.countActiveAdmins() <= 1) {
            throw conflict("LAST_ADMIN_PROTECTED", "不能移除最后一个有效系统管理员");
        }
        if (mapper.updateUserAccess(user.getUserId(), after.roleCode(), after.scopeMode(), appClock.nowMillis(),
                change.getSubjectVersion()) != 1) conflict();
        replaceScopes(user.getUserId(), after.scopeMode(), scopes);
        sessionMapper.expireAllForUser(user.getUserId());
    }

    private void approveRoleAccess(AccessChangeRow change) {
        RoleRow role = requireRole(change.getSubjectId());
        if (role.getVersion() != change.getSubjectVersion()) conflict();
        RoleAccessSnapshot snapshot = read(change.getAfterSnapshot(), RoleAccessSnapshot.class);
        List<PermissionAssignment> permissions = validateRolePermissions(role.getRoleCode(), snapshot.permissions());
        if (mapper.bumpRoleVersion(role.getRoleCode(), change.getSubjectVersion(), appClock.nowMillis()) != 1) conflict();
        for (PermissionAssignment permission : permissions) {
            mapper.updateRolePermission(role.getRoleCode(), permission.permissionCode(), permission.level(),
                    permission.menuEnabled());
        }
        mapper.bumpPermissionVersionForRole(role.getRoleCode());
        sessionMapper.expireAllForRole(role.getRoleCode());
    }

    private void approveRoleDelete(AccessChangeRow change) {
        RoleRow role = requireRole(change.getSubjectId());
        if (role.getVersion() != change.getSubjectVersion()) conflict();
        if (role.isBuiltin() || role.getUserCount() > 0) throw conflict("ROLE_IN_USE", "角色不可删除或仍有用户");
        mapper.deleteRolePermissions(role.getRoleCode());
        if (mapper.deleteCustomRole(role.getRoleCode(), change.getSubjectVersion()) != 1) conflict();
    }

    private AccessChangeRow requirePendingChange(String changeId, int expectedVersion, AuthUser reviewer) {
        AccessChangeRow change = requireChange(changeId);
        if (!"PENDING".equals(change.getStatus()) || change.getVersion() != expectedVersion) conflict();
        if (reviewer.userId().equals(change.getRequesterId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REVIEW_SELF_FORBIDDEN", "不能审批自己提交的权限变更");
        }
        return change;
    }

    private void replaceScopes(String userId, String mode, List<ScopeGrantInput> scopes) {
        mapper.deleteUserScopes(userId);
        if (!"ASSIGNED".equals(mode)) return;
        for (ScopeGrantInput scope : scopes) {
            mapper.insertUserScope(userId, scope.orgId(), scope.districtId());
        }
    }

    private List<ScopeGrantInput> validateScopes(String mode, List<ScopeGrantInput> input) {
        List<ScopeGrantInput> scopes = input == null ? List.of() : List.copyOf(input);
        if ("ASSIGNED".equals(mode) && scopes.isEmpty()) {
            throw bad("SCOPE_GRANT_REQUIRED", "ASSIGNED 数据范围至少需要一个组织与区域授权");
        }
        if (!"ASSIGNED".equals(mode) && !scopes.isEmpty()) {
            throw bad("SCOPE_GRANT_INVALID", "ALL 或 NONE 数据范围不能携带授权元组");
        }
        Set<String> unique = new HashSet<>();
        for (ScopeGrantInput scope : scopes) {
            OrgRow org = requireEnabledOrg(scope.orgId());
            DistrictRow district = requireEnabledDistrict(scope.districtId());
            if (!unique.add(org.getOrgId() + ":" + district.getDistrictId())) {
                throw bad("DUPLICATE_SCOPE_GRANT", "数据范围授权不能重复");
            }
        }
        return scopes;
    }

    private List<PermissionAssignment> validateRolePermissions(String roleCode, List<PermissionAssignment> input) {
        Map<String, PermissionRow> catalog = new LinkedHashMap<>();
        for (PermissionRow row : mapper.listPermissionCatalog()) catalog.put(row.getPermissionCode(), row);
        if (input == null || input.size() != catalog.size()) {
            throw bad("PERMISSION_SET_INCOMPLETE", "必须提交完整的权限矩阵");
        }
        Set<String> unique = new HashSet<>();
        List<PermissionAssignment> result = new ArrayList<>();
        for (PermissionAssignment item : input) {
            PermissionRow permission = catalog.get(item.permissionCode());
            if (permission == null || !unique.add(item.permissionCode())) {
                throw bad("INVALID_PERMISSION", "权限项不存在或重复");
            }
            String level = item.level();
            boolean menu = item.menuEnabled();
            if (permission.getRouteKey() == null) menu = false;
            if (menu && AccessService.level(level) < AccessService.level("READ")) {
                throw bad("MENU_REQUIRES_READ", "开启菜单必须至少具备查看权限");
            }
            if (!"ROLE-ADMIN".equals(roleCode) && PROTECTED_MATRIX_PERMISSIONS.contains(item.permissionCode())) {
                if (!"NONE".equals(level) || menu) {
                    throw bad("SYSTEM_PERMISSION_PROTECTED",
                            "自定义角色不能取得用户、角色或反制/干扰权限");
                }
                menu = false;
            }
            result.add(new PermissionAssignment(item.permissionCode(), level, menu));
        }
        return result;
    }

    private void validateOrgParent(String orgId, String parentId) {
        if (parentId == null) return;
        if (orgId.equals(parentId)) throw bad("ORG_CYCLE", "组织不能以自己作为上级");
        OrgRow parent = requireEnabledOrg(parentId);
        Set<String> visited = new HashSet<>();
        while (parent != null) {
            if (!visited.add(parent.getOrgId()) || orgId.equals(parent.getOrgId())) {
                throw bad("ORG_CYCLE", "组织层级存在循环");
            }
            parent = parent.getParentId() == null ? null : mapper.findOrganization(parent.getParentId());
        }
    }

    private UserResponse toUser(UserAdminRow row) {
        return new UserResponse(row.getUserId(), row.getAccount(), row.getName(), row.getPhone(), row.getOrgId(),
                row.getOrgName(), row.getRoleCode(), row.getRoleName(), row.getStatus(),
                row.isMustChangePassword(), row.isOnline(), row.getLastLoginAt(), row.getLastLoginIp(),
                row.getCreatedAt(), row.getVersion());
    }

    private OrganizationResponse toOrganization(OrgRow row) {
        return new OrganizationResponse(row.getOrgId(), row.getParentId(), row.getOrgCode(), row.getName(),
                row.isEnabled(), row.getVersion());
    }

    private DistrictResponse toDistrict(DistrictRow row) {
        return new DistrictResponse(row.getDistrictId(), row.getDistrictCode(), row.getName(), row.isEnabled(),
                row.getVersion());
    }

    private RoleSummaryResponse toRoleSummary(RoleRow row) {
        return new RoleSummaryResponse(row.getRoleCode(), row.getName(), row.getDescription(), row.isBuiltin(),
                row.isEnabled(), row.getUserCount(), row.getVersion());
    }

    private RoleResponse toRole(RoleRow row) {
        return new RoleResponse(row.getRoleCode(), row.getName(), row.getDescription(), row.isBuiltin(),
                row.isEnabled(), row.getUserCount(), row.getVersion(),
                mapper.listPermissionsForRole(row.getRoleCode()).stream().map(this::toPermission).toList(),
                mapper.listActionsForRole(row.getRoleCode()).stream()
                        .map(a -> new RoleActionResponse(a.getPermissionCode(), a.getLevel())).toList());
    }

    /* ---- 动作权限（决策 15-1 / 15-2）---- */

    /** 动作目录，按模块分组。与 MODULE 矩阵分开：矩阵要求整组提交，动作是逐项授予的。 */
    public List<ActionModuleResponse> listActionCatalog() {
        accessService.require("roles.read");
        Map<String, List<ActionRow>> byModule = new LinkedHashMap<>();
        for (ActionRow row : mapper.listActionCatalog()) {
            byModule.computeIfAbsent(row.getModuleCode(), code -> new ArrayList<>()).add(row);
        }
        List<ActionModuleResponse> modules = new ArrayList<>();
        for (Map.Entry<String, List<ActionRow>> entry : byModule.entrySet()) {
            List<ActionRow> rows = entry.getValue();
            modules.add(new ActionModuleResponse(entry.getKey(), rows.get(0).getModuleName(),
                    rows.stream().map(r -> new ActionResponse(r.getPermissionCode(), r.getActionCode(), r.getName()))
                            .toList()));
        }
        return modules;
    }

    /**
     * 把提交上来的动作行整组替换掉（决策 15-2）。
     *
     * 返回实际授出去的行，供审计记录——只记"改过动作"而不记改成了什么，事后没法回答"这个人当时凭什么能批"。
     */
    private List<ActionAssignment> replaceRoleActions(String roleCode, List<ActionAssignment> input) {
        Map<String, ActionRow> catalog = new LinkedHashMap<>();
        for (ActionRow row : mapper.listActionCatalog()) catalog.put(row.getPermissionCode(), row);
        List<ActionAssignment> granted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ActionAssignment item : input) {
            ActionRow action = catalog.get(item.permissionCode());
            // 模块码不是动作码：拿它来授会绕过矩阵那套完整性校验。
            if (action == null || !seen.add(item.permissionCode())) {
                throw bad("INVALID_PERMISSION", "动作权限项不存在或重复");
            }
            // 用户、角色、审计这些域的动作不能授给自定义角色（决策 15-2）。
            // 目前目录里还没有这类动作行，这条守卫先于它们存在，否则谁加谁就顺手把它授出去了。
            // 18-13 放开的是矩阵里的 `audit`（看审计日志），不是审计域的动作（对日志本身动手）。
            if (PROTECTED_ACTION_DOMAINS.contains(action.getModuleCode())
                    || PROTECTED_ACTION_CODES.contains(action.getPermissionCode())) {
                throw bad("SYSTEM_PERMISSION_PROTECTED", "该高风险动作仅允许超级管理员执行");
            }
            if (PermissionCode.DISPOSAL_DIRECT.value().equals(item.permissionCode())
                    && !Set.of("NONE", "OP").contains(item.level())) {
                throw bad("INVALID_PERMISSION_LEVEL", "直接反制权限只允许无或允许");
            }
            if (!"NONE".equals(item.level())) granted.add(item);
        }
        mapper.deleteRoleActions(roleCode);
        for (ActionAssignment item : granted) {
            mapper.insertRoleAction(roleCode, item.permissionCode(), item.level());
        }
        return granted;
    }

    private PermissionResponse toPermission(PermissionRow row) {
        return new PermissionResponse(row.getPermissionCode(), row.getModuleName(), row.getRouteKey(),
                row.getSortOrder(), row.getPermissionLevel(), row.isMenuEnabled());
    }

    private AccessChangeResponse toAccessChange(AccessChangeRow row) {
        return new AccessChangeResponse(row.getChangeId(), row.getChangeType(), row.getSubjectType(),
                row.getSubjectId(), row.getRequesterId(), row.getRequesterName(), row.getBeforeSnapshot(),
                row.getAfterSnapshot(), row.getReason(), row.getSubjectVersion(), row.getStatus(),
                row.getReviewerId(), row.getReviewerName(), row.getReviewComment(), row.getRequestedAt(),
                row.getReviewedAt(), row.getVersion());
    }

    private AccessChangeRow newChange(String id, String type, String subjectType, String subjectId,
            String before, String after, String reason, int subjectVersion) {
        AccessChangeRow row = new AccessChangeRow();
        row.setChangeId(id);
        row.setChangeType(type);
        row.setSubjectType(subjectType);
        row.setSubjectId(subjectId);
        row.setRequesterId(AuthContext.require().userId());
        row.setBeforeSnapshot(before);
        row.setAfterSnapshot(after);
        row.setReason(reason.trim());
        row.setSubjectVersion(subjectVersion);
        row.setRequestedAt(appClock.nowMillis());
        return row;
    }

    private UserAdminRow requireUserRow(String userId) {
        UserAdminRow row = mapper.findAdminUser(userId, appClock.nowMillis());
        if (row == null || "DELETED".equals(row.getStatus())) throw notFound("USER_NOT_FOUND", "用户不存在");
        return row;
    }

    private AppUser requireUser(String userId) {
        AppUser user = userMapper.findById(userId);
        if (user == null || "DELETED".equals(user.getStatus())) throw notFound("USER_NOT_FOUND", "用户不存在");
        return user;
    }

    private RoleRow requireRole(String roleCode) {
        RoleRow role = mapper.findRole(roleCode);
        if (role == null) throw notFound("ROLE_NOT_FOUND", "角色不存在");
        return role;
    }

    private RoleRow requireEnabledRole(String roleCode) {
        RoleRow role = requireRole(roleCode);
        if (!role.isEnabled()) throw bad("ROLE_DISABLED", "角色已停用");
        return role;
    }

    private RoleRow requireAssignableRole(String roleCode) {
        RoleRow role = requireEnabledRole(roleCode);
        if (role.isBuiltin() || "ROLE-ADMIN".equals(roleCode)) {
            throw bad("SUPER_ADMIN_ASSIGNMENT_FORBIDDEN", "新用户和普通用户不能被授予超级管理员角色");
        }
        return role;
    }

    private OrgRow requireOrganization(String id) {
        OrgRow row = mapper.findOrganization(id);
        if (row == null) throw notFound("ORG_NOT_FOUND", "组织不存在");
        return row;
    }

    private OrgRow requireEnabledOrg(String id) {
        OrgRow row = requireOrganization(id);
        if (!row.isEnabled()) throw bad("ORG_DISABLED", "组织已停用");
        return row;
    }

    private DistrictRow requireDistrict(String id) {
        DistrictRow row = mapper.findDistrict(id);
        if (row == null) throw notFound("DISTRICT_NOT_FOUND", "行政区域不存在");
        return row;
    }

    private DistrictRow requireEnabledDistrict(String id) {
        DistrictRow row = requireDistrict(id);
        if (!row.isEnabled()) throw bad("DISTRICT_DISABLED", "行政区域已停用");
        return row;
    }

    private AccessChangeRow requireChange(String changeId) {
        AccessChangeRow row = mapper.findAccessChange(changeId);
        if (row == null) throw notFound("ACCESS_CHANGE_NOT_FOUND", "权限变更申请不存在");
        return row;
    }

    private <T> PageResponse<T> page(List<T> all, PageQuery page) {
        int from = Math.min((page.page() - 1) * page.size(), all.size());
        int to = Math.min(from + page.size(), all.size());
        return new PageResponse<>(all.subList(from, to), page.page(), page.size(), all.size());
    }

    private void audit(String module, String action, String objectType, String objectId, String detail,
            RequestMeta meta) {
        AuthUser actor = AuthContext.require();
        auditService.record(actor.userId(), actor.account(), actor.roleCode(), module, action, objectType, objectId,
                detail, "SUCCESS", meta.ip(), meta.userAgent());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize access change", ex);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANGE_PAYLOAD_INVALID", "权限变更数据无法解析");
        }
    }

    private List<ScopeGrantInput> readScopes(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<ScopeGrantInput>>() { });
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANGE_PAYLOAD_INVALID", "用户数据范围无法解析");
        }
    }

    private static String resolveOrgCode(String input) {
        String code = nullable(input);
        if (code != null) return code;
        return "ORG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private Set<String> orgScope(String orgId) {
        if (blank(orgId)) return null;
        Set<String> ids = new HashSet<>();
        ids.add(orgId);
        boolean grew;
        do {
            grew = false;
            for (OrgRow org : mapper.listOrganizations()) {
                if (org.getParentId() != null && ids.contains(org.getParentId()) && ids.add(org.getOrgId())) {
                    grew = true;
                }
            }
        } while (grew);
        return ids;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullable(String value) {
        String normalized = normalized(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static void conflict() {
        throw conflict("VERSION_CONFLICT", "数据已被其他操作修改，请刷新后重试");
    }

    public record RequestMeta(String ip, String userAgent) {
    }

    private record UserCreationSnapshot(
            String account, String name, String phone, String orgId, String roleCode,
            String scopeMode, List<ScopeGrantInput> scopeGrants) {
    }

    private record UserAccessSnapshot(String roleCode, String scopeMode, List<ScopeGrantInput> scopeGrants) {
    }

    private record RoleAccessSnapshot(List<PermissionAssignment> permissions) {
    }
}
