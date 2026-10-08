package com.uav.lowaltitude.modules.handoff.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.DeliveryRow;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.HandoffRow;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.application.RiskNotificationService;
import com.uav.lowaltitude.platform.api.ApiException;

class HandoffSimulatorReceiptServiceTest {
    private final HandoffRepository handoffs=mock(HandoffRepository.class);
    private final RiskRepository risks=mock(RiskRepository.class);
    private final RiskNotificationService notifications=mock(RiskNotificationService.class);
    private final HandoffSimulatorReceiptService service=new HandoffSimulatorReceiptService(handoffs,risks,notifications);
    private final OffsetDateTime at=OffsetDateTime.parse("2026-09-29T12:00:00Z");
    private final DeliveryOutcome receipt=new DeliveryOutcome("DELIVERED","PENDING",null,"marker",null,at,null);
    private void pending(String marker){
        var handoff=mock(HandoffRow.class);
        when(handoff.handoffType()).thenReturn("UAV_PUNISHMENT");when(handoff.sourceId()).thenReturn("event");
        when(handoffs.lockNotification(eq("handoff"),any())).thenReturn(handoff);
        when(handoffs.latestDelivery("handoff")).thenReturn(new DeliveryRow("delivery","handoff",1,"SUBMITTED","PENDING",marker,at,null,null,null));
    }
    @Test void earlyReceiptIsRetryableUntilDispatchMarkerCommits(){
        pending("DELIVERY_IN_PROGRESS");
        ApiException error=assertThrows(ApiException.class,()->service.complete("handoff","UAV_PUNISHMENT","event",at,"marker",receipt));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,error.getStatus());
        verify(handoffs,never()).completeLocalSimulatorReceipt(anyString(),anyString(),any());
    }
    @Test void receiptCannotOverwriteLaterAttempt(){
        pending("new-marker");
        assertFalse(service.complete("handoff","UAV_PUNISHMENT","event",at,"old-marker",receipt));
        verify(handoffs,never()).completeLocalSimulatorReceipt(anyString(),anyString(),any());
    }
    @Test void matchingReceiptDoesNotInventPunishmentCompletion(){
        pending("marker");
        assertTrue(service.complete("handoff","UAV_PUNISHMENT","event",at,"marker",receipt));
        verify(handoffs).completeLocalSimulatorReceipt("delivery","marker",receipt);
        verifyNoInteractions(risks,notifications);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DISPERSED","NOT_DISPERSED"})
    void matchingRiskReceiptPreservesExplicitProcessingResult(String result){
        var handoff=mock(HandoffRow.class);
        String id=java.util.UUID.randomUUID().toString();
        when(handoff.handoffType()).thenReturn("RISK_NOTICE");
        when(handoff.sourceId()).thenReturn(id);
        when(handoffs.lockNotification(eq("handoff"),any())).thenReturn(handoff);
        when(handoffs.latestDelivery("handoff")).thenReturn(new DeliveryRow("delivery","handoff",1,"DELIVERED","PENDING","marker",at,at,at,null));
        var outcome=new DeliveryOutcome("DELIVERED","ACKNOWLEDGED",result,null,at,at,at);
        assertTrue(service.complete("handoff","RISK_NOTICE",id,at,"marker",outcome));
        verify(handoffs).updateReceiptResult("handoff",result);
    }
}
