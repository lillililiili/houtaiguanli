package com.uav.lowaltitude.modules.risk.application;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;

/** 提交成功记已通知；可信确认回执记已回执。投递状态独立保存。 */
@Service
public class RiskNotificationService {
    private final RiskRepository repository;
    private final AuditService audit;

    public RiskNotificationService(RiskRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public void submitted(String riskId, long version, String handoffId, OffsetDateTime at) {
        if (repository.markNotified(riskId, version, at) == 1) {
            var actor = AuthContext.require();
            audit.record(actor.userId(), actor.account(), actor.roleCode(), "risk", "risk_notified", "flight_risk", riskId,
                    "handoff_id=" + handoffId + "; notification=SUBMITTED", "SUCCESS", "", "");
            return;
        }
        if (repository.notificationRecorded(riskId)) return;
        throw new IllegalStateException("风险状态已变更，不能写入通知状态");
    }

    @Transactional
    public void acknowledged(String riskId, long version, String handoffId, OffsetDateTime at) {
        if (repository.markAcknowledged(riskId, version, at) != 1) {
            throw new IllegalStateException("风险通知状态已变更，不能写入成功回执");
        }
        var actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "risk", "risk_acknowledged", "flight_risk", riskId,
                "handoff_id=" + handoffId + "; channel_receipt=ACKNOWLEDGED", "SUCCESS", "", "");
    }
}
