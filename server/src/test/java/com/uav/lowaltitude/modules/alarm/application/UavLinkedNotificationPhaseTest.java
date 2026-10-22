package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.NoCounterDtos;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.*;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow.Phase;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.prediction.application.PredictionEventGate;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.http.HttpStatus;

/** The notification cycle shares receipt facts, never records, verification or action permission. */
class UavLinkedNotificationPhaseTest {
    private final long now=Instant.parse("2026-10-21T11:00:00Z").toEpochMilli();
    private final String observedId=id(),ownerId=id(),target=id(),org=id(),district=id();
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final UavAdvisoryRepository repository=mock(UavAdvisoryRepository.class);
    private final AccessControlService access=mock(AccessControlService.class);
    private final AutoSmsService sms=mock(AutoSmsService.class);
    private final AutoVoiceService voice=mock(AutoVoiceService.class);
    private final PilotDepartureWatch departure=mock(PilotDepartureWatch.class);
    private final PredictionEventGate predictions=mock(PredictionEventGate.class);
    private final NoCounterService noCounter=mock(NoCounterService.class);
    private final UavAdvisoryService service=new UavAdvisoryService(events,repository,access,
            mock(AdvisorySmsPort.class),new ObjectMapper(),new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),
            null,sms,voice,departure,mock(HandoffRepository.class),mock(DisposalRepository.class),noCounter,null);
    private EventRow observed,owner;
    private AutoSms ownSms;
    private AutoVoice ownVoice;

    @BeforeEach void setup() {
        observed=event(observedId,"CONFIRMED",target,org,district,"replay");
        owner=event(ownerId,"CONFIRMED",target,org,district,"replay");
        when(events.find(eq(observedId),any())).thenAnswer(i->observed);
        when(events.find(eq(ownerId),any())).thenAnswer(i->owner);
        when(access.require(any())).thenReturn(new AccessDecision("reader",ScopeMode.ALL));
        when(repository.counterLaunchBlockReason(observedId)).thenReturn("");
        when(repository.counterBlockReason(observedId)).thenReturn("");
        when(noCounter.status(any())).thenReturn(mock(NoCounterDtos.Status.class));
        when(sms.currentRecipient(any())).thenReturn(mock(RecipientSnapshot.class));
        when(departure.assess(eq(observedId),anyLong(),anyLong(),anyLong()))
                .thenReturn(PilotDepartureWatch.Presence.STILL_PRESENT);
        when(departure.assess(eq(observedId),anyLong(),anyLong()))
                .thenReturn(PilotDepartureWatch.Presence.STILL_PRESENT);
        ownSms=sms("BLOCKED");ownVoice=call("BLOCKED");
        when(sms.overview(eq(observed),anyBoolean())).thenReturn(ownSms);
        when(voice.overview(eq(observed),anyBoolean())).thenReturn(ownVoice);
        var facts=mock(PredictionEventGate.Facts.class);
        when(facts.role()).thenReturn("OBSERVED");
        when(predictions.facts(observedId)).thenReturn(facts);
        when(predictions.notificationOwner(observedId)).thenReturn(ownerId);
        service.setPredictionEventGate(predictions);
        ownerReceipts("SIMULATED_DELIVERED","SIMULATED_PLAYED");
    }

    @Test void completedOwnerReceiptsEnableThePhaseWithoutCopyingAnyPublicHistory() {
        var record=mock(com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Record.class);
        when(repository.records(observedId)).thenReturn(List.of(record));
        var result=service.overview(observedId);
        assertThat(result.notifyPhase()).isEqualTo("AWAIT_COUNTER");
        assertThat(result.counterLaunchVisible()).isTrue();
        assertThat(result.canRequestCounter()).isTrue();
        assertThat(result.autoSms()).isSameAs(ownSms);
        assertThat(result.autoVoice()).isSameAs(ownVoice);
        assertThat(result.records()).containsExactly(record);
        verify(sms).deliveredAt(ownerId);
        verify(departure).assess(observedId,now-2000,now-1000,now);
        verify(departure,never()).assess(eq(ownerId),anyLong(),anyLong(),anyLong());
        verify(sms,never()).retry(any());verify(voice,never()).retry(any());
        verify(sms,never()).process(any());verify(voice,never()).process(any());
        verify(repository,never()).records(ownerId);
    }

    @ParameterizedTest @ValueSource(strings={"PENDING_VERIFICATION","FALSE_POSITIVE"})
    void currentEventMustHaveItsOwnConfirmation(String state) {
        observed=event(observedId,state,target,org,district,"replay");
        assertThat(service.phaseForAutomation(observedId)).isNull();
        verify(predictions,never()).notificationOwner(any());
        verify(sms,never()).overview(eq(owner),anyBoolean());
    }

    @ParameterizedTest @ValueSource(strings={"PENDING_VERIFICATION","FALSE_POSITIVE"})
    void unconfirmedOwnerCannotSupplyThePhase(String state) {
        owner=event(ownerId,state,target,org,district,"replay");
        assertThat(service.phaseForAutomation(observedId)).isNull();
        verify(sms,never()).overview(eq(owner),anyBoolean());
    }

    @Test void pendingSmsAndPendingCallKeepTheirRealPhase() {
        ownerReceipts("SENDING","WAITING");
        assertThat(service.phaseForAutomation(observedId)).isEqualTo(Phase.AUTO_SMS);
        ownerReceipts("SIMULATED_DELIVERED","WAITING");
        assertThat(service.phaseForAutomation(observedId)).isEqualTo(Phase.AUTO_CALL);
    }

    @Test void unknownReceiptDoesNotUnlockCounterOrPretendTheCallCompleted() {
        ownerReceipts("UNKNOWN","WAITING");
        assertThat(service.phaseForAutomation(observedId)).isNull();
        ownerReceipts("SIMULATED_DELIVERED","UNKNOWN");
        assertThat(service.overview(observedId).counterLaunchVisible()).isFalse();
        assertThat(service.phaseForAutomation(observedId)).isNull();
    }

    @Test void missingDeliveryTimestampDoesNotBorrowTheCurrentEventsTimestamp() {
        when(sms.deliveredAt(ownerId)).thenReturn(null);
        when(sms.deliveredAt(observedId)).thenReturn(now-2000);
        assertThat(service.phaseForAutomation(observedId)).isNull();
    }

    @Test void currentMeasuredDepartureStopsTheProjectedPhase() {
        when(departure.assess(observedId,now-2000,now-1000,now)).thenReturn(PilotDepartureWatch.Presence.LEFT);
        when(repository.counterBlockReason(observedId)).thenReturn("当前观测已离开");
        var result=service.overview(observedId);
        assertThat(result.notifyPhase()).isNull();
        assertThat(result.counterLaunchVisible()).isFalse();
        assertThat(result.canRequestCounter()).isFalse();
    }

    @Test void completedNotificationCannotOverrideCurrentEvidenceOrActionPermission() {
        when(repository.counterBlockReason(observedId)).thenReturn("当前实测证据已过期");
        var blocked=service.overview(observedId);
        assertThat(blocked.canRequestCounter()).isFalse();
        assertThat(blocked.canDirectCounter()).isFalse();
        assertThat(blocked.counterBlockReason()).isEqualTo("当前实测证据已过期");
        when(repository.counterBlockReason(observedId)).thenReturn("");
        when(access.require(PermissionCode.DISPOSAL_REQUEST)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","无申请权限"));
        when(access.require(PermissionCode.DISPOSAL_DIRECT)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","无直接权限"));
        var denied=service.overview(observedId);
        assertThat(denied.canRequestCounter()).isFalse();assertThat(denied.canDirectCounter()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"target","org","district","source","missing"})
    void inconsistentOwnerIdentityCannotBeReadAsSharedNotification(String mismatch) {
        owner="missing".equals(mismatch)?null:event(ownerId,"CONFIRMED",
                "target".equals(mismatch)?id():target,"org".equals(mismatch)?id():org,
                "district".equals(mismatch)?id():district,"source".equals(mismatch)?"live":"replay");
        assertThat(service.phaseForAutomation(observedId)).isNull();
        verify(sms,never()).deliveredAt(ownerId);
    }

    @Test void sameTargetWithNoSameCycleOwnerDoesNotBorrowOldReceipts() {
        when(predictions.notificationOwner(observedId)).thenReturn(observedId);
        assertThat(service.phaseForAutomation(observedId)).isNull();
        verify(events,never()).find(eq(ownerId),any());
        verify(sms,never()).deliveredAt(ownerId);
    }

    @Test void ordinaryEventsAndEventsWithoutPredictionGateUseTheirOwnReceipts() {
        when(predictions.facts(observedId)).thenReturn(null);
        when(sms.overview(eq(observed),anyBoolean())).thenReturn(sms("SIMULATED_DELIVERED"));
        when(voice.overview(eq(observed),anyBoolean())).thenReturn(call("SIMULATED_PLAYED"));
        when(sms.deliveredAt(observedId)).thenReturn(now-2000);
        assertThat(service.phaseForAutomation(observedId)).isEqualTo(Phase.AWAIT_COUNTER);
        verify(predictions,never()).notificationOwner(any());
        service.setPredictionEventGate(null);
        assertThat(service.phaseForAutomation(observedId)).isEqualTo(Phase.AWAIT_COUNTER);
    }

    private void ownerReceipts(String smsStatus,String callStatus) {
        when(sms.overview(eq(owner),anyBoolean())).thenReturn(sms(smsStatus));
        when(voice.overview(eq(owner),anyBoolean())).thenReturn(call(callStatus));
        when(sms.deliveredAt(ownerId)).thenReturn("SIMULATED_DELIVERED".equals(smsStatus)?now-2000:null);
    }
    private AutoSms sms(String status) {return new AutoSms(true,status,"原归属事实",now-3000,now-2000,false,1,"policy","AUTO",now-4000,now-4000,null);}
    private AutoVoice call(String status) {return new AutoVoice(true,status,"原归属事实",now-1800,now-1000,false,1,"policy","AUTO",now-4000,now-4000,null,null,
            "SIMULATED_PLAYED".equals(status)?now-1500:null,"SIMULATED_PLAYED".equals(status)?now-1000:null,null);}
    private static EventRow event(String id,String state,String target,String org,String district,String source) {
        return new EventRow(id,id(),target,state,org,district,null,null,1,source);
    }
    private static String id(){return UUID.randomUUID().toString();}
}
