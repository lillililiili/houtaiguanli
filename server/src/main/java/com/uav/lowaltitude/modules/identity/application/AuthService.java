package com.uav.lowaltitude.modules.identity.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.AppSession;
import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.RoleRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.ScopeGrantRow;
import com.uav.lowaltitude.modules.identity.infrastructure.SessionMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.LoginResponse;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.MeResponse;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.ScopeGrantResponse;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final SessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final LoginFailureRecorder loginFailureRecorder;
    private final IdentityAdminMapper identityAdminMapper;
    private final AccessService accessService;
    private final PasswordPolicy passwordPolicy;
    private final AppProperties appProperties;
    private final AppClock appClock;

    public AuthService(
            UserMapper userMapper,
            SessionMapper sessionMapper,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            LoginFailureRecorder loginFailureRecorder,
            IdentityAdminMapper identityAdminMapper,
            AccessService accessService,
            PasswordPolicy passwordPolicy,
            AppProperties appProperties,
            AppClock appClock) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.loginFailureRecorder = loginFailureRecorder;
        this.identityAdminMapper = identityAdminMapper;
        this.accessService = accessService;
        this.passwordPolicy = passwordPolicy;
        this.appProperties = appProperties;
        this.appClock = appClock;
    }

    @Transactional
    public LoginResponse login(String account, String password, String ip, String userAgent) {
        String normalizedAccount = account == null ? "" : account.trim();
        AppUser user = userMapper.findByAccount(normalizedAccount);
        if (user == null) {
            loginFailureRecorder.unknownAccount(normalizedAccount, ip, userAgent);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        long now = appClock.nowMillis();
        if (user.getLockedUntil() != null && user.getLockedUntil() > now) {
            loginFailureRecorder.locked(user, normalizedAccount, ip, userAgent);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED", "账号已锁定");
        }
        RoleRow role = identityAdminMapper.findRole(user.getRoleCode());
        if (!"ACTIVE".equals(user.getStatus()) || role == null || !role.isEnabled()) {
            loginFailureRecorder.disabled(user, normalizedAccount, ip, userAgent);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "账号已停用");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginFailureRecorder.badPassword(
                    user,
                    normalizedAccount,
                    ip,
                    userAgent,
                    now,
                    appProperties.getLogin().getFailLimit(),
                    appProperties.getLogin().getLockMinutes());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        userMapper.recordSuccessfulLogin(user.getUserId(), now, ip == null ? "" : ip);
        AppSession session = new AppSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(user.getUserId());
        session.setExpireAt(now + appProperties.getSession().getTtlHours() * 3600_000L);
        session.setIp(ip == null ? "" : ip);
        session.setPermissionVersion(user.getPermissionVersion());
        sessionMapper.insert(session);
        auditService.record(user.getUserId(), user.getAccount(), user.getRoleCode(), "authentication",
                "login_success", "user", user.getUserId(), null, "SUCCESS", ip, userAgent);

        return new LoginResponse(user.getUserId(), user.getAccount(), user.getName(), user.getRoleCode(),
                session.getSessionId(), session.getExpireAt(), user.isMustChangePassword());
    }

    @Transactional
    public void logout(String sessionId, AuthUser current, String ip, String userAgent) {
        sessionMapper.expire(sessionId);
        auditService.record(current.userId(), current.account(), current.roleCode(), "authentication",
                "logout", "user", current.userId(), null, "SUCCESS", ip, userAgent);
    }

    public MeResponse me(AuthUser current) {
        AppUser user = userMapper.findById(current.userId());
        RoleRow role = identityAdminMapper.findRole(current.roleCode());
        List<ScopeGrantResponse> scopes = identityAdminMapper.listUserScopes(current.userId()).stream()
                .map(this::toScopeResponse)
                .toList();
        return new MeResponse(user.getUserId(), user.getAccount(), user.getName(), user.getPhone(),
                user.getOrgId(), identityAdminMapper.findAdminUser(user.getUserId(), appClock.nowMillis()).getOrgName(),
                user.getRoleCode(), role == null ? user.getRoleCode() : role.getName(), user.getScopeMode(), scopes,
                accessService.menuKeys(user.getRoleCode()), accessService.permissionCodes(user.getRoleCode()),
                user.getPermissionVersion(), user.isMustChangePassword(), appProperties.getSourceMode());
    }

    @Transactional
    public void changePassword(AuthUser current, String oldPassword, String newPassword, String ip, String userAgent) {
        AppUser user = userMapper.findById(current.userId());
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "当前密码错误");
        }
        passwordPolicy.validate(newPassword, user.getAccount());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_REUSE", "新密码不能与当前密码相同");
        }
        long now = appClock.nowMillis();
        userMapper.changePassword(user.getUserId(), passwordEncoder.encode(newPassword), now);
        sessionMapper.expireAllForUser(user.getUserId());
        auditService.record(user.getUserId(), user.getAccount(), user.getRoleCode(), "authentication",
                "password_changed", "user", user.getUserId(), null, "SUCCESS", ip, userAgent);
    }

    public AuthUser resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        AppSession session = sessionMapper.findById(sessionId);
        if (session == null || session.getExpireAt() <= appClock.nowMillis()) {
            return null;
        }
        AppUser user = userMapper.findById(session.getUserId());
        RoleRow role = user == null ? null : identityAdminMapper.findRole(user.getRoleCode());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || role == null || !role.isEnabled()
                || session.getPermissionVersion() != user.getPermissionVersion()) {
            return null;
        }
        return new AuthUser(user.getUserId(), user.getAccount(), user.getName(), user.getRoleCode(),
                user.getPermissionVersion(), user.isMustChangePassword(), user.getScopeMode());
    }

    private ScopeGrantResponse toScopeResponse(ScopeGrantRow row) {
        return new ScopeGrantResponse(row.getOrgId(), row.getOrgName(), row.getDistrictId(), row.getDistrictName());
    }
}
