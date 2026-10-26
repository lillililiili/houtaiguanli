package com.uav.lowaltitude.modules.identity.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.FrontendActionPolicy;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.identity.infrastructure.AccessControlMapper;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@Service
public class DatabaseAccessControlService implements AccessControlService {

    private final AccessControlMapper accessControlMapper;
    private final OrgEffectivePermissionService effective;

    public DatabaseAccessControlService(AccessControlMapper accessControlMapper,
                                        OrgEffectivePermissionService effective) {
        this.accessControlMapper = accessControlMapper;
        this.effective = effective;
    }

    @Override
    public AccessDecision require(PermissionCode permission) {
        AuthUser current = requireCurrentUser();
        if (current.mustChangePassword()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
        }
        if (!FrontendActionPolicy.isAvailable(current.roleCode(), permission.value())) {
            throw forbidden();
        }
        if (!effective.permissionCodes(current.userId()).contains(permission.value())) {
            throw forbidden();
        }
        // The permission above is the user-level grant. An appointed unit administrator
        // does not need a duplicate personal role action; its scope is always OWN_ORG.
        String storedScopeMode = effective.isUnitAdministrator(current.userId()) ? "ASSIGNED"
                : accessControlMapper.findGrantedScopeMode(current.userId(), permission.value());
        if (storedScopeMode == null) {
            throw forbidden();
        }

        ScopeMode scopeMode = ScopeMode.valueOf(storedScopeMode);
        if (scopeMode == ScopeMode.NONE) {
            throw forbidden();
        }
        if (scopeMode == ScopeMode.ASSIGNED
                && accessControlMapper.countValidAssignedScopes(current.userId()) == 0) {
            throw forbidden();
        }
        return new AccessDecision(current.userId(), scopeMode);
    }

    private AuthUser requireCurrentUser() {
        try {
            return AuthContext.require();
        } catch (IllegalStateException ex) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "未登录或会话无效");
        }
    }

    private ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问");
    }
}
