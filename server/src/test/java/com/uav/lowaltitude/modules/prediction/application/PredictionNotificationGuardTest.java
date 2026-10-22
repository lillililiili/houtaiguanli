package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsPolicy;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsService;
import com.uav.lowaltitude.modules.alarm.application.AdvisorySmsPort;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Task;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

class PredictionNotificationGuardTest {
    private final String event=UUID.randomUUID().toString();
    private final Instant now=Instant.parse("2026-10-20T13:00:00Z");
    private AutoSmsService service;
    private AutoSmsRepository tasks;
    private UavAdvisoryRepository records;
    private UavEventRepository events;
    private AdvisorySmsPort provider;
    private PredictionEventGate gate;
    private RecipientSnapshot recipient;
    private AtomicReference<Task> current;

    @BeforeEach void setup() {
        tasks=mock(AutoSmsRepository.class);records=mock(UavAdvisoryRepository.class);
        events=mock(UavEventRepository.class);provider=mock(AdvisorySmsPort.class);
        gate=mock(PredictionEventGate.class);
        var directory=mock(NotificationDirectoryService.class);var policy=mock(AutoSmsPolicy.class);
        var transaction=mock(PlatformTransactionManager.class);
        when(transaction.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(policy.enabled()).thenReturn(true);when(policy.eventMillis()).thenReturn(300_000L);
        var at=now.atOffset(ZoneOffset.UTC);
        when(events.lock(eq(event),any())).thenReturn(new UavEventRepository.EventRow(event,UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),"CONFIRMED","org","district",at,at,1,"replay"));
        when(events.update(eq(event),anyLong(),anyString(),any())).thenReturn(1);
        when(tasks.facts(event)).thenReturn(new AutoSmsRepository.Facts(now.toEpochMilli(),"UAV",now.toEpochMilli(),now.toEpochMilli()));
        recipient=new RecipientSnapshot("pilot","已核验飞手","org","单位","pilot","已核验飞手","***",
                "MOCK",null,"setting",1L,true,null,now.toEpochMilli());
        when(directory.forPilotEvent(eq("ADVISORY_SMS"),eq(event),isNull())).thenReturn(recipient);
        when(provider.automaticSimulationAvailable("replay")).thenReturn(true);
        when(provider.simulateAutomatic(anyString(),anyString(),anyString(),anyString()))
                .thenReturn(new AdvisorySmsPort.Delivery(true,"SUBMITTED"));
        when(gate.notificationBlock(event)).thenReturn("");
        when(gate.manualCounterOnly(event)).thenReturn(true);when(gate.isPrediction(event)).thenReturn(true);
        when(gate.notificationRevision(event)).thenReturn(4L);
        current=new AtomicReference<>();
        when(tasks.find(event)).thenAnswer(invocation->current.get());
        doAnswer(invocation->{
            current.set(new Task(event,"SENDING","",invocation.getArgument(2),null,null,null,now.toEpochMilli(),
                    now.toEpochMilli(),1,now.toEpochMilli()+60_000,invocation.getArgument(1),"provider-"+event));
            return null;
        }).when(tasks).claim(eq(event),anyString(),anyString(),any(),any(),anyLong());
        doAnswer(invocation->{
            current.set(new Task(event,invocation.getArgument(1),invocation.getArgument(2),"AUTO",null,null,null,null,
                    now.toEpochMilli(),0,null,null,"provider-"+event));return null;
        }).when(tasks).block(eq(event),anyString(),anyString(),anyString(),any(),any(),anyLong());
        service=new AutoSmsService(tasks,events,records,policy,provider,new AppClock(Clock.fixed(now,ZoneOffset.UTC)),
                new ObjectMapper(),mock(AuditService.class),transaction,directory);
        service.setPredictionEventGate(gate);
    }

    @Test void beforeManualVerificationNoNotificationIsClaimedOrSent() {
        when(gate.notificationBlock(event)).thenReturn("预测风险尚未人工核实");
        service.process(event);
        verify(tasks,never()).claim(anyString(),anyString(),anyString(),any(),any(),anyLong());
        verify(provider,never()).simulateAutomatic(anyString(),anyString(),anyString(),anyString());
    }
    @Test void recoveryDoesNotDispatchTheOldClaimAndDoesNotInventDelivery() {
        when(gate.notificationClaimInterrupted(event,4L)).thenReturn(true);
        service.process(event);
        verify(provider,never()).simulateAutomatic(anyString(),anyString(),anyString(),anyString());
        verify(records,never()).appendAutomatic(anyString(),anyString(),anyLong(),anyLong(),anyString(),anyString());
        assertThat(current.get().status()).isEqualTo("WAITING");
        assertThat(current.get().token()).isNull();
    }
    @Test void acceptedSendKeepsItsReceiptIdentityEvenAfterWarningPauses() {
        service.process(event);
        Task accepted=current.get();
        verify(provider).simulateAutomatic(eq("replay"),eq("已核验飞手"),contains("预测不代表已经进入"),eq("provider-"+event));
        when(gate.notificationBlock(event)).thenReturn("预测依据待确认");
        assertThat(service.completeSimulatorReceipt(event,accepted.providerKey(),accepted.token(),recipient,
                "DELIVERED",now.toEpochMilli(),now.toEpochMilli())).isTrue();
        verify(records).appendAutomatic(anyString(),eq(event),eq(2L),eq(now.toEpochMilli()),contains("预测不代表已经进入"),anyString());
        verify(provider,times(1)).simulateAutomatic(anyString(),anyString(),anyString(),anyString());
    }
}
