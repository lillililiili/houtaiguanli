package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.NoCounterDtos;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.modules.handoff.application.MeasuredPunishmentBasis;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffFollowupRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.prediction.application.PredictionEventGate;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** Read projection consumes independent historical basis/completion, never manufactures a verdict. */
class UavAdvisoryHandoffProjectionTest {
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final UavAdvisoryRepository advisory=mock(UavAdvisoryRepository.class);
    private final AccessControlService access=mock(AccessControlService.class);
    private final AutoSmsService sms=mock(AutoSmsService.class);
    private final AutoVoiceService voice=mock(AutoVoiceService.class);
    private final HandoffRepository handoffs=mock(HandoffRepository.class);
    private final DisposalRepository disposals=mock(DisposalRepository.class);
    private final UavRiskCaseIdentity cases=mock(UavRiskCaseIdentity.class);
    private final PredictionEventGate predictions=mock(PredictionEventGate.class);
    private final MeasuredPunishmentBasis measured=mock(MeasuredPunishmentBasis.class);
    private final String event=id(),risk=id(),authorization=id();
    private EventRow row;
    private UavAdvisoryService service;

    @BeforeEach void setup(){
        var now=OffsetDateTime.now();row=new EventRow(event,id(),id(),"PENDING_VERIFICATION",id(),id(),now,now,3,"replay");
        when(access.require(any())).thenReturn(new AccessDecision("viewer",ScopeMode.ALL));
        when(events.find(eq(event),any())).thenReturn(row);
        when(advisory.counterBlockReason(event)).thenReturn("");when(advisory.counterLaunchBlockReason(event)).thenReturn("");
        when(sms.currentRecipient(row)).thenReturn(mock(RecipientSnapshot.class));
        when(voice.departureSnapshot(event)).thenReturn(new DepartureObservation(event,"SMS","RECORDED","UNKNOWN",null,null,System.currentTimeMillis()));
        when(cases.hasCases(event)).thenReturn(true);when(cases.confirmedForDisposal(event)).thenReturn(true);
        when(handoffs.enabledRecipients("UAV_PUNISHMENT")).thenReturn(List.of(new HandoffRepository.RecipientRow(id(),"Recipient","UAV_PUNISHMENT")));
        var decisions=mock(NoCounterService.class);when(decisions.status(any(EventRow.class))).thenReturn(mock(NoCounterDtos.Status.class));
        service=new UavAdvisoryService(events,advisory,access,mock(AdvisorySmsPort.class),new ObjectMapper(),new AppClock(),mock(AuditService.class),sms,voice,
                mock(PilotDepartureWatch.class),handoffs,disposals,decisions,mock(HandoffFollowupRepository.class));
        service.responseOrder(null,cases);service.setPredictionEventGate(predictions);service.measuredPunishment(measured);
        when(measured.derived(event)).thenReturn(true);
    }

    @Test void verifiedActualRiskWithIndependentCompletedBasisCanCreateWithoutRewritingRawVerdict(){
        when(disposals.punishmentCompletion(event)).thenReturn(completion());
        var result=service.view(row);
        assertThat(result.canHandoff()).isTrue();assertThat(result.autoHandoff().status()).isEqualTo("WAITING");
        assertThat(result.autoHandoff().reason()).contains("处置完成依据");assertThat(row.state()).isEqualTo("PENDING_VERIFICATION");
        verify(disposals,times(1)).punishmentCompletion(event);verify(measured,never()).independentMeasuredEvent(anyString());
        verify(events,never()).update(anyString(),anyLong(),anyString(),any());
    }
    @Test void purePredictionNeverEnablesPunishmentEvenWithVerifiedRisk(){
        when(predictions.isPrediction(event)).thenReturn(true);
        var result=service.view(row);
        assertThat(result.canHandoff()).isFalse();assertThat(result.autoHandoff().status()).isEqualTo("NOT_REQUIRED");
        verify(disposals,never()).punishmentCompletion(anyString());
    }
    @Test void missingIndependentMeasuredFactsAreExplicitAndCannotBecomePunishment(){
        var result=service.view(row);
        assertThat(result.canHandoff()).isFalse();assertThat(result.autoHandoff().status()).isEqualTo("BLOCKED");
        assertThat(result.autoHandoff().reason()).contains("独立的实测违法依据");
    }
    @Test void measuredEntryWithoutSuccessfulCompletionStillCannotCreate(){
        when(measured.independentMeasuredEvent(event)).thenReturn(true);
        var result=service.view(row);
        assertThat(result.canHandoff()).isFalse();assertThat(result.autoHandoff().reason()).contains("停止确认后");
    }
    @Test void handoffPermissionRemainsRequiredAfterCompletion(){
        when(disposals.punishmentCompletion(event)).thenReturn(completion());
        when(access.require(PermissionCode.HANDOFF_CREATE)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        assertThat(service.view(row).canHandoff()).isFalse();
    }
    @Test void independentConfirmedMeasuredEventKeepsOriginalManualHandoffEligibility(){
        when(measured.derived(event)).thenReturn(false);
        when(cases.hasCases(event)).thenReturn(false);
        var confirmed=new EventRow(row.eventId(),row.alarmId(),row.targetId(),"CONFIRMED",row.ownerOrgId(),row.districtId(),row.createdAt(),row.updatedAt(),row.version(),row.sourceMode());
        when(events.find(eq(event),any())).thenReturn(confirmed);
        when(sms.currentRecipient(confirmed)).thenReturn(mock(RecipientSnapshot.class));
        assertThat(service.view(confirmed).canHandoff()).isTrue();
    }
    @Test void alreadyCreatedPendingRuleTaskExposesMachineReadableWaitingFlag(){
        String handoff=id();existing(handoff,"PENDING_DELIVERY",HandoffSubmissionService.WAITING_RULES,null);
        var result=service.view(row);
        assertThat(result.canHandoff()).isFalse();assertThat(result.autoHandoff().waitingForRules()).isTrue();
        assertThat(result.autoHandoff().handoffId()).isEqualTo(handoff);assertThat(result.autoHandoff().status()).isEqualTo("PENDING");
        verify(disposals,never()).punishmentCompletion(anyString());
    }
    @ParameterizedTest @ValueSource(strings={"accepted","other-block","delivered","failed"})
    void pendingRuleFlagNeverOverridesActualChannelResults(String outcome){
        String delivery=switch(outcome){case "delivered"->"DELIVERED";case "failed"->"FAILED";default->"PENDING_DELIVERY";};
        String reason="other-block".equals(outcome)?"CHANNEL_UNAVAILABLE":HandoffSubmissionService.WAITING_RULES;
        existing(id(),delivery,reason,"accepted".equals(outcome)?OffsetDateTime.now():null);
        assertThat(service.view(row).autoHandoff().waitingForRules()).isFalse();
    }
    @Test void existingDeliveredMaterialRemainsVisibleAfterCurrentEvidenceBecomesPredictionOnly(){
        String handoff=id();existing(handoff,"DELIVERED",null,OffsetDateTime.now());when(predictions.isPrediction(event)).thenReturn(true);
        var result=service.view(row);
        assertThat(result.canHandoff()).isFalse();assertThat(result.autoHandoff().handoffId()).isEqualTo(handoff);
        assertThat(result.autoHandoff().status()).isEqualTo("SUBMITTED");
    }
    @Test void missingDeliveryRecordDoesNotPretendAnExistingHandoffWasSubmitted(){
        when(handoffs.existingPunishment(event)).thenReturn(id());
        var result=service.view(row).autoHandoff();
        assertThat(result.status()).isEqualTo("BLOCKED");assertThat(result.waitingForRules()).isFalse();
    }
    private void existing(String id,String status,String reason,OffsetDateTime submitted){
        when(handoffs.existingPunishment(event)).thenReturn(id);
        when(handoffs.latestDelivery(id)).thenReturn(new HandoffRepository.DeliveryRow(id(),id,1,status,"NOT_EXPECTED",reason,OffsetDateTime.now(),submitted,null,null));
    }
    private DisposalRepository.PunishmentCompletionRow completion(){return new DisposalRepository.PunishmentCompletionRow(id(),"COUNTERMEASURE_COMPLETED",authorization,System.currentTimeMillis()-1000);}
    private static String id(){return UUID.randomUUID().toString();}
}
