package com.uav.lowaltitude.modules.identity.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.domain.AppSession;
import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.domain.DataScope;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.RoleRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.ScopeGrantRow;
import com.uav.lowaltitude.modules.identity.infrastructure.SessionMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.LoginResponse;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.MeResponse;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.ProfileUpdateRequest;
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
    private final ObjectMapper objectMapper;

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
            AppClock appClock,
            ObjectMapper objectMapper) {
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
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoginResponse login(String account, String password, String ip, String userAgent) {
        return login(account, password, ip, userAgent, false);
    }

    @Transactional
    public LoginResponse login(String account, String password, String ip, String userAgent, boolean backend) {
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
        if (backend && com.uav.lowaltitude.modules.identity.domain.UserType.forRole(user.getRoleCode())
                != com.uav.lowaltitude.modules.identity.domain.UserType.BACKEND) {
            loginFailureRecorder.backendDenied(user, ip, userAgent);
            throw new ApiException(HttpStatus.FORBIDDEN, "BACKEND_ACCESS_DENIED", "该账号是前台用户，不能登录后台管理系统");
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
                user.getPermissionVersion(), user.isMustChangePassword(), appProperties.getSourceMode(),
                DataScope.of(user.getScopeMode(), user.getScopeOrgRule()).name(), user.getVersion(),
                com.uav.lowaltitude.modules.identity.domain.UserType.forRole(user.getRoleCode()).name());
    }

    /** 本人修改姓名和电话（ZT-28）。不动权限版本，当前会话保持有效。 */
    @Transactional
    public MeResponse updateProfile(AuthUser current, ProfileUpdateRequest request, String ip, String userAgent) {
        AppUser user = userMapper.findById(current.userId());
        if (user == null) throw unauthenticated();
        String name = request.name().trim();
        String phone = request.phone() == null || request.phone().isBlank() ? null : request.phone().trim();
        if (name.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "姓名不能为空");
        if (userMapper.updateOwnProfile(user.getUserId(), name, phone, appClock.nowMillis(),
                request.expectedVersion()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资料已被其他操作修改，请刷新后重试");
        }
        auditService.record(user.getUserId(), user.getAccount(), user.getRoleCode(), "users",
                "profile_updated", "user", user.getUserId(), json(Map.of("name", name)), "SUCCESS", ip, userAgent);
        return me(current);
    }

    @Transactional
    public void changePassword(AuthUser current, String oldPassword, String newPassword, String ip, String userAgent) {
        AppUser user = userMapper.findById(current.userId());
        if (user == null) throw unauthenticated();
        long attemptAt = appClock.nowMillis();
        // 当前密码输错不是会话失效：返回 400/429 让页面留在原处提示，不能用 401 把人踢回登录页（ZT-28）。
        // 输错次数与登录共用计数和锁定，免得拿着会话在这里无限次试密码。
        if (user.getLockedUntil() != null && user.getLockedUntil() > attemptAt) {
            throw passwordAttemptsLocked(user.getLockedUntil() - attemptAt);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            int lockMinutes = appProperties.getLogin().getLockMinutes();
            boolean locked = loginFailureRecorder.badCurrentPassword(user, ip, userAgent, attemptAt,
                    appProperties.getLogin().getFailLimit(), lockMinutes);
            if (locked) throw passwordAttemptsLocked(lockMinutes * 60_000L);
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT", "当前密码不正确，请重新输入");
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
        long now = appClock.nowMillis();
        if (session == null || session.getExpireAt() <= now) {
            return null;
        }
        AppUser user = userMapper.findById(session.getUserId());
        RoleRow role = user == null ? null : identityAdminMapper.findRole(user.getRoleCode());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || role == null || !role.isEnabled()
                || session.getPermissionVersion() != user.getPermissionVersion()) {
            return null;
        }
        renewSessionIfDue(sessionId, now);
        return new AuthUser(user.getUserId(), user.getAccount(), user.getName(), user.getRoleCode(),
                user.getPermissionVersion(), user.isMustChangePassword(), user.getScopeMode());
    }

    private void renewSessionIfDue(String sessionId, long now) {
        if (!appProperties.getSession().isRollingEnabled()) return;
        long ttl = appProperties.getSession().getTtlHours() * 3600_000L;
        if (ttl <= 0) return;
        sessionMapper.renewIfDue(sessionId, now, now + ttl / 2, now + ttl);
    }

    private ScopeGrantResponse toScopeResponse(ScopeGrantRow row) {
        return new ScopeGrantResponse(row.getOrgId(), row.getOrgName(), row.getDistrictId(), row.getDistrictName());
    }

    private static ApiException passwordAttemptsLocked(long remainingMillis) {
        long minutes = Math.max(1, (remainingMillis + 59_999L) / 60_000L);
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_ATTEMPTS_LOCKED",
                "当前密码连续输错次数过多，请" + minutes + "分钟后再试；这段时间内也不能重新登录");
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录或会话已失效");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize profile audit", ex);
        }
    }
}
