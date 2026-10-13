package com.uav.lowaltitude.platform.audit;

import java.util.UUID;
import java.util.Objects;

import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditMapper auditMapper;
    private final AppClock clock;

    public AuditService(AuditMapper auditMapper, AppClock clock) {
        this.auditMapper = auditMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String userId, String account, String action, String objectType, String objectId, String detail, String ip) {
        record(userId, account, null, "system", action, objectType, objectId, detail, "SUCCESS", ip, "");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String userId, String account, String roleCode, String moduleCode, String action,
            String objectType, String objectId, String detail, String result, String ip, String userAgent) {
        AuthUser actor = AuthContext.get();
        boolean sameActor = actor != null && userId != null && Objects.equals(actor.userId(), userId);
        if (sameActor && blank(roleCode)) roleCode = actor.roleCode();
        // Only the authenticated actor's event may inherit this request. Background/other actors
        // must not acquire the operator's role, address or browser by accident.
        if (sameActor && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            if (blank(ip)) ip = attributes.getRequest().getRemoteAddr();
            if (blank(userAgent)) userAgent = attributes.getRequest().getHeader("User-Agent");
        }
        AuditLog log = new AuditLog();
        log.setAuditId(UUID.randomUUID().toString());
        log.setUserId(userId);
        log.setAccount(account);
        log.setRoleCode(roleCode);
        log.setModuleCode(AuditMenus.resolve(moduleCode, action, objectType, objectId));
        log.setAction(action);
        log.setObjectType(objectType);
        log.setObjectId(objectId);
        log.setDetail(detail);
        log.setOccurredAt(clock.nowMillis());
        log.setIp(ip);
        log.setResult(result == null ? "SUCCESS" : result);
        log.setUserAgent(userAgent == null ? "" : userAgent.substring(0, Math.min(userAgent.length(), 512)));
        auditMapper.insert(log);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    @Transactional
    public void recordStandalone(String userId, String account, String action, String objectType, String objectId, String detail, String ip) {
        record(userId, account, action, objectType, objectId, detail, ip);
    }

    @Transactional
    public void recordStandalone(String userId, String account, String roleCode, String moduleCode, String action,
            String objectType, String objectId, String detail, String result, String ip, String userAgent) {
        record(userId, account, roleCode, moduleCode, action, objectType, objectId, detail, result, ip, userAgent);
    }
}
