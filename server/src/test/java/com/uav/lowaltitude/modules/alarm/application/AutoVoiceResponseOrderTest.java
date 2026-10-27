package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.ResponseOrderBasis;
import com.uav.lowaltitude.modules.alarm.infrastructure.*;
import com.uav.lowaltitude.modules.automationrule.api.ResponseOrderDtos.Summary;
import com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

class AutoVoiceResponseOrderTest {
    final long now=Instant.parse("2027-03-06T07:20:01Z").toEpochMilli();
    final String id=UUID.randomUUID().toString();
    final AutoVoiceRepository tasks=mock(AutoVoiceRepository.class);
    final UavEventRepository events=mock(UavEventRepository.class);
    final AutoSmsRepository sms=mock(AutoSmsRepository.class);
    final AutoVoicePolicy policy=mock(AutoVoicePolicy.class);
    final AdvisoryVoiceRecording recordings=mock(AdvisoryVoiceRecording.class);
    final AdvisoryVoicePort port=mock(AdvisoryVoicePort.class);
    final ResponseOrderService order=mock(ResponseOrderService.class);
    final NotificationDirectoryService directory=mock(NotificationDirectoryService.class);
    final AtomicReference<AutoVoiceRepository.Task> task=new AtomicReference<>();
    final AdvisoryVoiceRecording.Recording recording=new AdvisoryVoiceRecording.Recording("r","Recording","turn away","hash");
    UavEventRepository.EventRow event;
    AutoVoiceService service;
    @BeforeEach void setup(){
        var clock=new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        event=new UavEventRepository.EventRow(id,"alarm","target","CONFIRMED","org","area",clock.now().atOffset(ZoneOffset.UTC),clock.now().atOffset(ZoneOffset.UTC),2,"replay");
        when(events.lock(eq(id),any())).thenReturn(event);when(tasks.find(id)).thenAnswer(call->task.get());
        when(policy.enabled()).thenReturn(true);when(recordings.current()).thenReturn(recording);when(port.simulationAvailable("replay")).thenReturn(true);
        when(directory.forPilotEvent(eq("ADVISORY_VOICE"),eq(id),isNull())).thenReturn(new RecipientSnapshot("pilot","Pilot","org","Org","contact","Pilot","***","API","simulator","setting",1L,true,null,now));
        doAnswer(call->{task.set(new AutoVoiceRepository.Task(id,"CALLING","calling","COUNTER_FIRST",null,null,now,now,1,now+60_000,call.getArgument(1),"provider","r","Recording","hash","turn away",null,null));return null;}).when(tasks).claim(eq(id),anyString(),any(),any(),anyLong());
        when(port.simulate(anyString(),any(),any(),anyString())).thenReturn(new AdvisoryVoicePort.Delivery(true,"SUBMITTED",null,null,null));
        service=new AutoVoiceService(tasks,events,sms,mock(PilotDepartureWatch.class),policy,recordings,port,clock,mock(AuditService.class),manager,directory,mock(NoCounterRepository.class));
        service.responseOrder(order,null);
    }
    @ParameterizedTest @ValueSource(strings={"WAIT_TIMEOUT","START_CONFIRMED","START_FAILED","START_UNAVAILABLE"})
    void freezesFinalAllowedDecisionRatherThanTheEarlierClaimDecision(String status){
        when(order.summary(id)).thenReturn(summary("WAIT_TIMEOUT",true),summary(status,true));
        service.process(id);
        verify(tasks).prepareSend(eq(id),anyString(),same(recording),eq(now),eq(basis(status)));
        verify(port).simulate(eq("replay"),any(),same(recording),eq("provider"));
    }
    @ParameterizedTest @ValueSource(strings={"WAITING_START","PAUSED"})
    void waitingOrLostEvidenceAtSendGuardDoesNotFreezeOrSend(String status){
        when(order.summary(id)).thenReturn(summary("WAIT_TIMEOUT",true),summary(status,false));
        service.process(id);
        verify(tasks,never()).prepareSend(anyString(),anyString(),any(),anyLong(),any());verify(port,never()).simulate(anyString(),any(),any(),anyString());
    }
    @Test void waitingBeforeClaimNeverCreatesASendTimeBasis(){
        when(order.summary(id)).thenReturn(summary("WAITING_START",false));service.process(id);
        verify(tasks,never()).claim(anyString(),anyString(),any(),any(),anyLong());
        verify(tasks,never()).prepareSend(anyString(),anyString(),any(),anyLong(),any());
    }
    @ParameterizedTest @ValueSource(strings={"SIMULATED_PLAYED","UNKNOWN","FAILED"})
    void terminalResultReturnsFrozenBasisWithoutReinterpretingCurrentOrder(String status){
        task.set(new AutoVoiceRepository.Task(id,status,"real receipt","COUNTER_FIRST",null,null,now-1000,now,1,null,null,"provider","r","Recording","hash","turn away",now-500,now,basis("START_CONFIRMED")));
        var view=service.overview(event,false);
        assertThat(view.responseOrderBasis()).isEqualTo(basis("START_CONFIRMED"));assertThat(view.status()).isEqualTo(status);
        verify(order,never()).summary(anyString());
    }
    Summary summary(String status,boolean allowed){return new Summary("COUNTER_FIRST",true,status,"reason "+status,7,now-60_000,now,60,allowed,allowed?"reason "+status:null,true,status,now,allowed);}
    ResponseOrderBasis basis(String status){return new ResponseOrderBasis(status,"reason "+status,60,now-60_000,now,7,now);}
}
