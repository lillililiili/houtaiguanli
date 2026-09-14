package com.uav.lowaltitude.modules.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.platform.audit.AuditService;

@Service
public class LoginFailureRecorder {

    private final UserMapper userMapper;
    private final AuditService auditService;

    public LoginFailureRecorder(UserMapper userMapper, AuditService auditService) {
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unknownAccount(String account, String ip, String userAgent) {
        auditService.record(null, account, null, "authentication", "login_fail", "user", account,
                "unknown_account", "FAILURE", ip, userAgent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void locked(AppUser user, String account, String ip, String userAgent) {
        auditService.record(user.getUserId(), account, user.getRoleCode(), "authentication", "login_fail",
                "user", user.getUserId(), "locked", "FAILURE", ip, userAgent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void disabled(AppUser user, String account, String ip, String userAgent) {
        auditService.record(user.getUserId(), account, user.getRoleCode(), "authentication", "login_fail",
                "user", user.getUserId(), "disabled", "FAILURE", ip, userAgent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void badPassword(AppUser user, String account, String ip, String userAgent,
            long now, int failLimit, int lockMinutes) {
        int fails = user.getFailCount() + 1;
        Long lockedUntil = fails >= failLimit ? now + lockMinutes * 60_000L : null;
        userMapper.updateLock(user.getUserId(), fails, lockedUntil);
        auditService.record(user.getUserId(), account, user.getRoleCode(), "authentication", "login_fail",
                "user", user.getUserId(), "bad_password", "FAILURE", ip, userAgent);
    }
}
