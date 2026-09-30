package com.uav.lowaltitude.modules.handoff.application;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.time.OffsetDateTime;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.application.RiskNotificationService;

/** Trusted transport already checked receiver ownership and current notification permission. */
@Service
public class HandoffSimulatorReceiptService {
    private static final AccessDecision INTERNAL=new AccessDecision("system:simulator-receipt",ScopeMode.ALL);
    private final HandoffRepository handoffs;
    private final RiskRepository risks;
    private final RiskNotificationService notifications;
    public HandoffSimulatorReceiptService(HandoffRepository handoffs,RiskRepository risks,RiskNotificationService notifications){this.handoffs=handoffs;this.risks=risks;this.notifications=notifications;}
    public boolean complete(String handoffId,String kind,String sourceId,OffsetDateTime requestedAt,String marker,DeliveryOutcome outcome){
        var handoff=handoffs.lockNotification(handoffId,INTERNAL);
        if(handoff==null||!kind.equals(handoff.handoffType())||!handoff.sourceId().equals(sourceId))return false;
        var attempt=handoffs.latestDelivery(handoffId);
        // Manual notification publishes its outbox after the attempt placeholder transaction.
        // The receiver may win the race against completeNotification: retain a retryable receipt.
        if(attempt!=null&&"DELIVERY_IN_PROGRESS".equals(attempt.blockedReason())&&requestedAt!=null
                &&Math.abs(attempt.createdAt().toInstant().toEpochMilli()-requestedAt.toInstant().toEpochMilli())<=1)
            throw new ApiException(HttpStatus.CONFLICT,"SIMULATOR_RECEIPT_NOT_READY","通知发送登记尚未完成，请重新读取后重试回执");
        if(attempt==null||!marker.equals(attempt.blockedReason()))return false;
        handoffs.completeLocalSimulatorReceipt(attempt.deliveryId(),marker,outcome);
        if("RISK_NOTICE".equals(kind)&&"ACKNOWLEDGED".equals(outcome.receiptStatus())){
            var risk=risks.lock(handoff.sourceId(),INTERNAL);
            if(risk!=null&&"NOTIFIED".equals(risk.state()))notifications.acknowledged(risk.riskId(),risk.version(),handoffId,outcome.acknowledgedAt());
        }
        return true;
    }
}
