package com.uav.lowaltitude.modules.identity.application;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.AppUser;
import com.uav.lowaltitude.modules.identity.infrastructure.SessionMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.AppProperties;

@Service
public class SuperAdminRecoveryService {

    private final AppProperties properties;
    private final UserMapper userMapper;
    private final SessionMapper sessionMapper;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public SuperAdminRecoveryService(AppProperties properties, UserMapper userMapper, SessionMapper sessionMapper,
            PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public void recover() {
        String account = required(properties.getSuperAdmin().getAccount(), "APP_SUPER_ADMIN_ACCOUNT");
        String password = required(properties.getSuperAdminRecovery().getPassword(),
                "APP_SUPER_ADMIN_RECOVERY_PASSWORD");
        AppUser user = userMapper.findByAccount(account);
        if (user == null || !"ROLE-ADMIN".equals(user.getRoleCode())) {
            throw new IllegalStateException("configured super administrator account does not exist or is not ROLE-ADMIN");
        }
        passwordPolicy.validateTemporary(password, account);
        userMapper.recoverSuperAdmin(user.getUserId(), passwordEncoder.encode(password), System.currentTimeMillis());
        sessionMapper.expireAllForUser(user.getUserId());
        auditService.recordStandalone(user.getUserId(), account, "ROLE-ADMIN", "system",
                "super_admin_recovered", "user", user.getUserId(),
                "{\"source\":\"environment-maintenance\"}", "SUCCESS", "127.0.0.1", "admin-recovery");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value.trim();
    }
}
