package com.uav.lowaltitude.modules.integrationconfig.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsService;
import com.uav.lowaltitude.modules.alarm.application.AutoVoiceService;
import com.uav.lowaltitude.modules.handoff.application.HandoffSimulatorReceiptService;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightVerificationRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceNoticeRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

class RealtimeSimulatorReceiptProjectorTest {
    private final HandoffSimulatorReceiptService handoffs=mock(HandoffSimulatorReceiptService.class);
    private final FlightVerificationRepository feedback=mock(FlightVerificationRepository.class);
    private final DeviceMaintenanceNoticeRepository maintenance=mock(DeviceMaintenanceNoticeRepository.class);
    private final AuditService audit=mock(AuditService.class);
    private final ObjectMapper json=new ObjectMapper();
    @SuppressWarnings("unchecked")
    private final RealtimeSimulatorReceiptProjector projector=new RealtimeSimulatorReceiptProjector(mock(ObjectProvider.class),mock(ObjectProvider.class),handoffs,feedback,maintenance,json,audit);
    @BeforeEach void actor(){AuthContext.set(new AuthUser("receiver","receiver-account","Receiver","ROLE-RECEIVER",1,false,"ALL"));}
    @AfterEach void clear(){AuthContext.clear();}
    @Test void feedbackReceiptUsesExactMarkerAndNoInventedProcessingResult(){
        when(feedback.completeSimulatorReceipt(anyString(),anyString(),any())).thenReturn(true);
        projector.apply("simn-1","PLAN_FEEDBACK","feedback-1",json.createObjectNode().put("handoff_id","feedback-1"),"ACKNOWLEDGED",10L,null,null,20L);
        verify(feedback).completeSimulatorReceipt(eq("feedback-1"),eq("SIMULATOR_WAITING:simn-1"),argThat(o->o.receiptResult()==null&&o.acknowledgedAt().toInstant().toEpochMilli()==20&&o.blockedReason()==null));
        verify(audit).record(eq("receiver"),eq("receiver-account"),eq("ROLE-RECEIVER"),anyString(),anyString(),anyString(),eq("simn-1"),contains("APPLIED"),eq("SUCCESS"),anyString(),anyString());
    }
    @Test void maintenanceTimeoutPreservesOriginalDeliveryFactAndUnknownOutcome(){
        projector.apply("simn-1","DEVICE_MAINTENANCE","attempt",json.createObjectNode().put("handoff_id","attempt").put("source_id","task"),"TIMEOUT",10L,null,null,null);
        verify(maintenance).completeSimulatorReceipt(eq("attempt"),eq("task"),eq("SIMULATOR_WAITING:simn-1"),argThat(o->"DELIVERED".equals(o.deliveryStatus())&&"TIMEOUT".equals(o.receiptStatus())&&o.deliveredAt().toInstant().toEpochMilli()==10),eq("UNKNOWN"));
    }
    @Test void mismatchedSubjectCannotUpdateBusinessRecord(){
        projector.apply("simn-1","UAV_PUNISHMENT","one",json.createObjectNode().put("handoff_id","different"),"DELIVERED",10L,null,null,null);
        verifyNoInteractions(handoffs,feedback,maintenance);
        verify(audit).record(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),contains("IGNORED_STALE_ATTEMPT"),anyString(),anyString(),anyString());
    }
}
