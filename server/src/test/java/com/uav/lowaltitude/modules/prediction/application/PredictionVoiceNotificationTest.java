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
import com.uav.lowaltitude.modules.alarm.application.*;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording;
import com.uav.lowaltitude.modules.alarm.infrastructure.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository.Task;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

class PredictionVoiceNotificationTest {
    private final String event=UUID.randomUUID().toString();
    private final Instant now=Instant.parse("2026-10-20T15:30:00Z");
    private AutoVoiceService service;
    private AutoVoiceRepository tasks;
    private AdvisoryVoiceRecording recordings;
    private AdvisoryVoicePort provider;
    private PredictionEventGate gate;
    private UavEventRepository.EventRow row;
    private RecipientSnapshot recipient;
    private AtomicReference<Task> current;

    @BeforeEach void setup() {
        tasks=mock(AutoVoiceRepository.class);recordings=mock(AdvisoryVoiceRecording.class);
        provider=mock(AdvisoryVoicePort.class);gate=mock(PredictionEventGate.class);
        var events=mock(UavEventRepository.class);var sms=mock(AutoSmsRepository.class);
        var departure=mock(PilotDepartureWatch.class);var policy=mock(AutoVoicePolicy.class);
        var directory=mock(NotificationDirectoryService.class);var transaction=mock(PlatformTransactionManager.class);
        when(transaction.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        row=new UavEventRepository.EventRow(event,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                "CONFIRMED","org","district",now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC),2,"replay");
        when(events.lock(eq(event),any())).thenReturn(row);
        when(policy.enabled()).thenReturn(true);when(sms.deliveredAt(event)).thenReturn(now.toEpochMilli()-1000);
        when(departure.assess(eq(event),anyLong(),anyLong())).thenReturn(PilotDepartureWatch.Presence.STILL_PRESENT);
        when(provider.simulationAvailable("replay")).thenReturn(true);
        when(provider.simulate(anyString(),any(RecipientSnapshot.class),any(Recording.class),anyString()))
                .thenReturn(new AdvisoryVoicePort.Delivery(true,"SUBMITTED",null,null,null));
        when(gate.isPrediction(event)).thenReturn(true);when(gate.manualCounterOnly(event)).thenReturn(true);
        when(gate.notificationBlock(event)).thenReturn("");when(gate.notificationRevision(event)).thenReturn(7L);
        recipient=new RecipientSnapshot("pilot","飞手","org","单位","pilot","飞手","***",
                "MOCK",null,"setting",1L,true,null,now.toEpochMilli());
        when(directory.forPilotEvent(eq("ADVISORY_VOICE"),eq(event),isNull())).thenReturn(recipient);
        current=new AtomicReference<>();
        when(tasks.find(event)).thenAnswer(i->current.get());
        doAnswer(i->{
            Recording recording=i.getArgument(3);
            current.set(new Task(event,"CALLING","等待通道",null,null,null,now.toEpochMilli(),now.toEpochMilli(),
                    1,now.toEpochMilli()+60_000,i.getArgument(1),"provider-"+event,recording.id(),recording.name(),
                    recording.sha256(),recording.transcript(),null,null));
            return null;
        }).when(tasks).claim(eq(event),anyString(),any(),any(Recording.class),anyLong());
        service=new AutoVoiceService(tasks,events,sms,departure,policy,recordings,provider,
                new AppClock(Clock.fixed(now,ZoneOffset.UTC)),mock(AuditService.class),transaction,directory,mock(NoCounterRepository.class));
        service.setPredictionEventGate(gate);
    }

    @Test void genericAudioCannotSatisfyAPredictiveNotification() {
        when(recordings.current()).thenReturn(new Recording("ordinary","已入区","您已经进入管制区域。","ordinary-hash"));
        service.process(event);
        assertThat(service.overview(row,false).reason()).contains("预测进入禁飞区专用录音");
        verify(recordings,never()).current();
        verify(tasks,never()).claim(anyString(),anyString(),any(),any(),anyLong());
        verify(provider,never()).simulate(anyString(),any(RecipientSnapshot.class),any(),anyString());
    }

    @Test void predictionUsesTheDeclaredAudioAndWaitsForActualPlaybackReceipt() {
        Recording prediction=new Recording(UUID.randomUUID().toString(),"预测入区","预计30秒内进入禁飞区，请调整航向。","forecast-hash");
        when(recordings.current(AdvisoryVoiceRecording.PREDICTED_AIRSPACE_ENTRY)).thenReturn(prediction);
        service.process(event);
        verify(provider).simulate("replay",recipient,prediction,"provider-"+event);
        verify(recordings,never()).current();
        verify(tasks,never()).append(anyString(),anyString(),anyLong(),anyLong(),any(),anyString(),anyLong(),anyLong(),anyString());
        assertThat(current.get().status()).isEqualTo("CALLING");
    }

    @Test void pauseAfterClaimPreventsEvenCorrectPredictiveAudioFromBeingSent() {
        when(recordings.current(AdvisoryVoiceRecording.PREDICTED_AIRSPACE_ENTRY))
                .thenReturn(new Recording(UUID.randomUUID().toString(),"预测入区","预计进入禁飞区。","forecast-hash"));
        when(gate.notificationClaimInterrupted(event,7L)).thenReturn(true);
        service.process(event);
        verify(provider,never()).simulate(anyString(),any(RecipientSnapshot.class),any(),anyString());
        verify(tasks).block(eq(event),argThat(e->e.status().equals("WAITING")),anyLong());
    }

    @Test void ordinaryMeasuredAlarmKeepsItsExistingAudioSelection() {
        when(gate.isPrediction(event)).thenReturn(false);when(gate.manualCounterOnly(event)).thenReturn(false);
        Recording ordinary=new Recording(UUID.randomUUID().toString(),"普通告警","您已进入管制区域。","ordinary-hash");
        when(recordings.current()).thenReturn(ordinary);
        service.process(event);
        verify(provider).simulate("replay",recipient,ordinary,"provider-"+event);
        verify(recordings,never()).current(AdvisoryVoiceRecording.PREDICTED_AIRSPACE_ENTRY);
    }
}
