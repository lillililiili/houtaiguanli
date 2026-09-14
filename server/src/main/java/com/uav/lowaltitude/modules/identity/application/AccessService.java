package com.uav.lowaltitude.modules.identity.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.uav.lowaltitude.modules.identity.domain.IdentityRows.PermissionRow;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@Service
public class AccessService {

    private final IdentityAdminMapper mapper;

    public AccessService(IdentityAdminMapper mapper) {
        this.mapper = mapper;
    }

    public void require(String permissionCode) {
        AuthUser user = AuthContext.require();
        if (user.mustChangePassword()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
        }
        if (!permissionCodes(user.roleCode()).contains(permissionCode)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号没有执行此操作的权限");
        }
    }

    /** Compatibility guard for the retired approval service methods; no review permission is exposed. */
    public void requireReviewer() {
        require("roles.auth");
    }

    public void requireBusinessData(String permissionCode) {
        require(permissionCode);
        AuthUser user = AuthContext.require();
        if ("NONE".equals(user.scopeMode())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DATA_SCOPE_FORBIDDEN", "当前账号没有业务数据范围");
        }
    }

    public boolean canAccessTuple(String orgId, String districtId) {
        AuthUser user = AuthContext.require();
        if ("ALL".equals(user.scopeMode())) return true;
        if (!"ASSIGNED".equals(user.scopeMode()) || orgId == null || districtId == null) return false;
        return mapper.countUserScopeTuple(user.userId(), orgId, districtId) > 0;
    }

    public void requireTuple(String orgId, String districtId) {
        if (!canAccessTuple(orgId, districtId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DATA_SCOPE_FORBIDDEN", "当前账号无权访问该组织与区域的数据");
        }
    }

    public List<String> menuKeys(String roleCode) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("workbench");
        List<PermissionRow> permissions = "ROLE-ADMIN".equals(roleCode)
                ? mapper.listPermissionCatalog() : mapper.listPermissionsForRole(roleCode);
        for (PermissionRow permission : permissions) {
            if ("ROLE-ADMIN".equals(roleCode)) {
                if (permission.getRouteKey() != null) keys.add(permission.getRouteKey());
                continue;
            }
            if (permission.isMenuEnabled() && permission.getRouteKey() != null
                    && level(permission.getPermissionLevel()) >= level("READ")) {
                keys.add(permission.getRouteKey());
            }
        }
        return List.copyOf(keys);
    }

    public List<String> permissionCodes(String roleCode) {
        List<String> codes = new ArrayList<>();
        boolean superAdmin = "ROLE-ADMIN".equals(roleCode);
        List<PermissionRow> permissions = superAdmin
                ? mapper.listPermissionCatalog() : mapper.listPermissionsForRole(roleCode);
        for (PermissionRow permission : permissions) {
            int value = superAdmin ? level("AUTH") : level(permission.getPermissionLevel());
            if (value >= 1) codes.add(permission.getPermissionCode() + ".read");
            if (value >= 2) codes.add(permission.getPermissionCode() + ".op");
            if (value >= 3) codes.add(permission.getPermissionCode() + ".auth");
        }
        // 决策 16-4：模块码之后追加动作码**原文**（如 disposal:approve）。动作本身就是一个动作，
        // 没有 read/op/auth 三级之分，套后缀反而要前端再拆一次。此前不下发，前端只能硬编码或等 403——
        // 用户点下去才知道没权限。模块码与 menu_keys 一概不动。
        codes.addAll(superAdmin ? mapper.listActionCodeCatalog() : mapper.listActionCodesForRole(roleCode));
        return List.copyOf(codes);
    }

    public List<PermissionRow> grants(String roleCode) {
        return mapper.listPermissionsForRole(roleCode);
    }

    public static int level(String value) {
        return switch (value == null ? "NONE" : value) {
            case "READ" -> 1;
            case "OP" -> 2;
            case "AUTH" -> 3;
            default -> 0;
        };
    }
}
