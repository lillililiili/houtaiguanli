package com.uav.lowaltitude.modules.evidence.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EvidencePreviewAuditService {
    private final EvidenceRepository repository;
    private final AuditService audit;
    private final AppClock clock;
    public EvidencePreviewAuditService(EvidenceRepository repository, AuditService audit, AppClock clock) {
        this.repository = repository; this.audit = audit; this.clock = clock;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String visibleEvidenceId, String action, String result, String reason) {
        var user = AuthContext.require();
        if (visibleEvidenceId != null) repository.insertAccess(UUID.randomUUID().toString(), visibleEvidenceId,
                user.userId(), action, result, reason, clock.now());
        audit.record(user.userId(), user.account(), user.roleCode(), "evidence",
                "THUMBNAIL".equals(action) ? "evidence_thumbnail" : "evidence_preview", "evidence_file", visibleEvidenceId,
                reason == null ? "内容读取已授权，不代表用户已阅读" : reason,
                "GRANTED".equals(result) ? "SUCCESS" : "FAILURE", "", "");
    }
}
