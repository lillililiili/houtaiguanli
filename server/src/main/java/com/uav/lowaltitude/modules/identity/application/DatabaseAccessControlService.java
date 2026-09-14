package com.uav.lowaltitude.modules.identity.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.identity.infrastructure.AccessControlMapper;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@Service
public class DatabaseAccessControlService implements AccessControlService {

    private final AccessControlMapper accessControlMapper;

    public DatabaseAccessControlService(AccessControlMapper accessControlMapper) {
        this.accessControlMapper = accessControlMapper;
    }

    @Override
    public AccessDecision require(PermissionCode permission) {
        AuthUser current = requireCurrentUser();
        String storedScopeMode = accessControlMapper.findGrantedScopeMode(
                current.userId(), permission.value());
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
