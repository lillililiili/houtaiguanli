package com.uav.lowaltitude.modules.alarm.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.*;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

class SimulatorAdvisoryReceiptTest {
    private final AppClock clock=new AppClock(Clock.fixed(Instant.ofEpochMilli(100000),ZoneOffset.UTC));
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final AutoSmsRepository smsTasks=mock(AutoSmsRepository.class);
    private final AutoVoiceRepository voiceTasks=mock(AutoVoiceRepository.class);
    private final UavAdvisoryRepository records=mock(UavAdvisoryRepository.class);
    private final NotificationDirectoryService directory=mock(NotificationDirectoryService.class);
    private final RecipientSnapshot recipient=new RecipientSnapshot("pilot","Pilot","org","Org","contact","Pilot","***","API","local-data-simulator","setting",1L,true,null,90000L);
    private final AdvisoryVoiceRecording.Recording recording=new AdvisoryVoiceRecording.Recording("r","Recording","Stop flying","hash");
    private final AutoSmsService sms=new AutoSmsService(smsTasks,events,records,mock(AutoSmsPolicy.class),mock(AdvisorySmsPort.class),clock,new ObjectMapper(),mock(AuditService.class),mock(PlatformTransactionManager.class),directory);
    private final AutoVoiceService voice=new AutoVoiceService(voiceTasks,events,smsTasks,mock(PilotDepartureWatch.class),mock(AutoVoicePolicy.class),mock(AdvisoryVoiceRecording.class),mock(AdvisoryVoicePort.class),clock,mock(AuditService.class),mock(PlatformTransactionManager.class),directory);
    private void event(){
        var at=clock.now().atOffset(ZoneOffset.UTC);
        when(events.lock(eq("event"),any())).thenReturn(new UavEventRepository.EventRow("event","alarm","target","CONFIRMED","org","district",at,at,2,"live"));
        when(events.update(eq("event"),eq(2L),eq("CONFIRMED"),any())).thenReturn(1);
    }
    private void smsTask(String token,long lease){
        event();
        when(smsTasks.find("event")).thenReturn(new AutoSmsRepository.Task("event","SENDING","",null,null,null,null,90000L,90000L,1,lease,token,"sms-key"));
    }
    private void voiceTask(String token,long lease,Long answered){
        event();
        when(voiceTasks.find("event")).thenReturn(new AutoVoiceRepository.Task("event","CALLING","",null,null,null,90000L,answered==null?90000L:answered,1,lease,token,"voice-key","r","Recording","hash","Stop flying",answered,null));
    }
    @Test void smsUsesActualDeliveryTimeForWatchAndFrozenRecipient(){
        smsTask("token",150000);
        assertTrue(sms.completeSimulatorReceipt("event","sms-key","token",recipient,"DELIVERED",95000L,90000));
        verify(records).appendAutomatic(anyString(),eq("event"),eq(3L),eq(95000L),anyString(),eq(AutoSmsPolicy.CODE));
        verify(smsTasks).finish(eq("event"),eq("token"),eq("SIMULATED_DELIVERED"),anyString(),anyString(),eq(95000L));
        verify(directory).freezeAdvisoryRecord(eq("ADVISORY_SMS"),anyString(),same(recipient));
    }
    @Test void smsRejectsOldClaimAndFutureDelivery(){
        smsTask("new-token",150000);
        assertFalse(sms.completeSimulatorReceipt("event","sms-key","old-token",recipient,"DELIVERED",95000L,90000));
        assertFalse(sms.completeSimulatorReceipt("event","sms-key","new-token",recipient,"DELIVERED",100001L,90000));
        verifyNoInteractions(records);
    }
    @Test void expiredSmsCannotBeConvertedToSuccess(){
        smsTask("token",99999);
        assertFalse(sms.completeSimulatorReceipt("event","sms-key","token",recipient,"DELIVERED",95000L,90000));
        verifyNoInteractions(records);
    }
    @Test void timeoutDoesNotCreateSmsDeliveryFact(){
        smsTask("token",150000);
        assertTrue(sms.completeSimulatorReceipt("event","sms-key","token",recipient,"TIMEOUT",null,90000));
        verify(smsTasks).finish(eq("event"),eq("token"),eq("UNKNOWN"),anyString(),isNull(),eq(100000L));
        verifyNoInteractions(records);
    }
    @Test void answerKeepsOriginalClaimCalling(){
        voiceTask("token",150000,null);
        assertTrue(voice.completeSimulatorReceipt("event","voice-key","token",recipient,recording,"simn-call","ANSWERED",95000L,null,90000));
        verify(voiceTasks).answer("event","token","simn-call",95000L,100000L);
        verify(voiceTasks,never()).finish(anyString(),anyString(),anyString(),anyString(),any(),any(),any(),any(),anyLong());
    }
    @Test void playedRequiresPriorMatchingAnswerAndPreservesPlaybackTime(){
        voiceTask("token",150000,95000L);
        assertFalse(voice.completeSimulatorReceipt("event","voice-key","token",recipient,recording,"simn-call","PLAYED",94000L,98000L,90000));
        assertTrue(voice.completeSimulatorReceipt("event","voice-key","token",recipient,recording,"simn-call","PLAYED",95000L,98000L,90000));
        verify(voiceTasks).append(anyString(),eq("event"),eq(3L),eq(100000L),same(recording),eq("simn-call"),eq(95000L),eq(98000L),eq(AutoVoicePolicy.CODE));
        verify(directory).freezeAdvisoryRecord(eq("ADVISORY_VOICE"),anyString(),same(recipient));
    }
    @Test void playedWithoutAnswerAndOldVoiceClaimAreIgnored(){
        voiceTask("token",150000,null);
        assertFalse(voice.completeSimulatorReceipt("event","voice-key","token",recipient,recording,"simn-call","PLAYED",95000L,98000L,90000));
        assertFalse(voice.completeSimulatorReceipt("event","voice-key","previous",recipient,recording,"simn-call","ANSWERED",95000L,null,90000));
        verify(voiceTasks,never()).finish(anyString(),anyString(),anyString(),anyString(),any(),any(),any(),any(),anyLong());
    }
    @Test void timeoutAfterAnswerPreservesAnswerWithoutInventingPlayback(){
        voiceTask("token",150000,95000L);
        assertTrue(voice.completeSimulatorReceipt("event","voice-key","token",recipient,recording,"simn-call","TIMEOUT",95000L,null,90000));
        verify(voiceTasks).finish(eq("event"),eq("token"),eq("UNKNOWN"),anyString(),isNull(),eq("simn-call"),eq(95000L),isNull(),eq(100000L));
        verify(directory,never()).freezeAdvisoryRecord(anyString(),anyString(),any());
    }
}
