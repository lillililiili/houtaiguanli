package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Presence;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.platform.time.AppClock;

@ExtendWith(MockitoExtension.class)
class AutomaticCounterNotificationGateTest {
    @Mock UavEventRepository events;
    @Mock AutoSmsRepository sms;
    @Mock AutoVoiceRepository voice;
    @Mock AdvisoryVoiceRecording recordings;
    @Mock NotificationDirectoryService directory;
    @Mock PilotDepartureWatch departure;
    private final String id = UUID.randomUUID().toString();
    private long now = Instant.parse("2026-10-17T05:06:07Z").toEpochMilli();
    private final AdvisoryVoiceRecording.Recording recording = new AdvisoryVoiceRecording.Recording("recording", "notice", "leave", "hash");

    @BeforeEach void event() {
        when(events.find(eq(id), any())).thenAnswer(call -> new EventRow(id, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "CONFIRMED", "org", "district", Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC),
                Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC), 1L, "replay"));
    }

    @ParameterizedTest @ValueSource(longs = {0, 17_000})
    void deliveryGapAndPlaybackGapWaitForTheirOwnNewPosition(long shift) {
        now += shift;
        long delivered = now - 1_000, played = now - 100;
        delivered(delivered);
        when(departure.assess(id, delivered, now)).thenReturn(Presence.UNKNOWN);
        assertThat(gate(true, true).allows(id)).isFalse();
        when(departure.assess(id, delivered, now)).thenReturn(Presence.STILL_PRESENT);
        when(recordings.current()).thenReturn(recording);
        when(voice.find(id)).thenReturn(call("WAITING", 0, null, null));
        assertThat(gate(true, true).allows(id)).as("A new inside position permits calling, not countering").isFalse();
        when(voice.find(id)).thenReturn(call("CALLING", 1, now - 500, null));
        assertThat(gate(true, true).allows(id)).isFalse();
        when(voice.find(id)).thenReturn(call("SIMULATED_PLAYED", 1, now - 500, played));
        when(departure.assess(id, delivered, played, now)).thenReturn(Presence.UNKNOWN, Presence.LEFT, Presence.STILL_PRESENT);
        assertThat(gate(true, true).allows(id)).as("A pre-play position cannot authorize a start after playback").isFalse();
        assertThat(gate(true, true).allows(id)).isFalse();
        assertThat(gate(true, true).allows(id)).isTrue();
    }

    @ParameterizedTest @ValueSource(longs = {0, 29_000})
    void deliveryGapThenDepartureNeverStartsEvenBeforeDepartureIsFrozen(long shift) {
        now += shift;
        long delivered = now;
        delivered(delivered);
        when(departure.assess(id, delivered, now)).thenReturn(Presence.UNKNOWN, Presence.LEFT);
        assertThat(gate(true, true).allows(id)).isFalse();
        assertThat(gate(true, true).allows(id)).isFalse();
        verifyNoInteractions(directory, recordings);
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING", "BLOCKED", "UNAVAILABLE", "SENDING", "FAILED", "UNKNOWN"})
    void unreceivedSmsAndTransientStatusNeverMeanNotificationIsImpossible(String status) {
        when(sms.find(id)).thenReturn(smsTask(status, 0, now));
        if (java.util.Set.of("WAITING", "BLOCKED", "UNAVAILABLE").contains(status))
            when(sms.facts(id)).thenReturn(new AutoSmsRepository.Facts(now, "UAV", now, now));
        assertThat(gate(true, true).allows(id)).isFalse();
    }

    @Test void explicitMissingPilotMaySkipButCannotOverrideAnAttemptInFlight() {
        when(sms.facts(id)).thenReturn(new AutoSmsRepository.Facts(now, "UAV", now, now));
        when(directory.cannotNotifyPilotFromConfiguration("ADVISORY_SMS", id)).thenReturn(true);
        assertThat(gate(true, true).allows(id)).isTrue();
        when(sms.find(id)).thenReturn(smsTask("SENDING", 0, now));
        assertThat(gate(true, true).allows(id)).isFalse();
        verify(directory).cannotNotifyPilotFromConfiguration("ADVISORY_SMS", id);
    }

    @Test void phoneWaitMayBeWaivedOnlyForExplicitlyUnattemptedNotification() {
        when(sms.facts(id)).thenReturn(new AutoSmsRepository.Facts(now, "UAV", now, now));
        when(directory.cannotNotifyPilotFromConfiguration("ADVISORY_SMS", id)).thenReturn(true);
        assertThat(gate(true,true).phoneWaitNotRequired(id)).isTrue();
        for (String status : java.util.List.of("CALLING","FAILED","UNKNOWN","SIMULATED_PLAYED")) {
            when(voice.find(id)).thenReturn(call(status,1,null,null));
            assertThat(gate(true,true).phoneWaitNotRequired(id)).as(status).isFalse();
        }
        when(voice.find(id)).thenReturn(call("WAITING",0,now,null));
        assertThat(gate(true,true).phoneWaitNotRequired(id)).isFalse();
    }

    @Test void unavailablePhoneConfigurationSkipsWaitButCallablePhoneAndReadFailuresDoNot() {
        long delivered=now-1_000;
        delivered(delivered);
        when(departure.assess(id,delivered,now)).thenReturn(Presence.STILL_PRESENT);
        when(recordings.current()).thenReturn(recording);
        assertThat(gate(true,true).phoneWaitNotRequired(id)).isFalse();
        when(directory.cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",id)).thenReturn(true);
        assertThat(gate(true,true).phoneWaitNotRequired(id)).isTrue();
        when(directory.cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",id)).thenThrow(new IllegalStateException("read unavailable"));
        assertThat(gate(true,true).phoneWaitNotRequired(id)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"BLOCKED", "UNAVAILABLE", "WAITING", "CALLING", "FAILED", "UNKNOWN"})
    void temporaryVoiceBlockOrOfflineReceiverDoesNotSkipPlayback(String status) {
        long delivered = now - 1_000;
        delivered(delivered);
        when(departure.assess(id, delivered, now)).thenReturn(Presence.STILL_PRESENT);
        when(voice.find(id)).thenReturn(call(status, 0, null, null));
        if (java.util.Set.of("WAITING", "BLOCKED", "UNAVAILABLE").contains(status))
            when(recordings.current()).thenReturn(recording);
        assertThat(gate(true, true).allows(id)).isFalse();
    }

    @Test void missingPostSmsPositionCannotBeBypassedByDisabledVoice() {
        delivered(now);
        when(departure.assess(id, now, now)).thenReturn(Presence.UNKNOWN);
        assertThat(gate(true, false).allows(id)).isFalse();
        verifyNoInteractions(directory, recordings);
    }

    @Test void frozenDepartureWinsEvenIfNotificationsAreNowDisabled() {
        when(voice.departure(id)).thenReturn(mock(com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation.class));
        assertThat(gate(false, false).allows(id)).isFalse();
        verifyNoInteractions(sms, directory, recordings);
    }

    @Test void unreadableConfiguredRecordingIsNotAbsentConfiguration() {
        long delivered = now - 1_000;
        delivered(delivered);
        when(departure.assess(id, delivered, now)).thenReturn(Presence.STILL_PRESENT);
        when(recordings.hasConfiguration()).thenReturn(true, false);
        assertThat(gate(true, true).allows(id)).isFalse();
        assertThat(gate(true, true).allows(id)).as("Only an explicitly absent recording configuration can skip an unattempted call").isTrue();
    }

    @Test void changedRecordingCannotReuseAnUnattemptedTaskAsAnException() {
        long delivered = now - 1_000;
        delivered(delivered);
        when(departure.assess(id, delivered, now)).thenReturn(Presence.STILL_PRESENT);
        when(recordings.current()).thenReturn(recording);
        when(voice.find(id)).thenReturn(new AutoVoiceRepository.Task(id,"BLOCKED","任务录音已变更",null,null,null,null,now,0,null,null,null,
                "other-recording","old-name","old-hash","old-transcript",null,null));
        assertThat(gate(true, true).allows(id)).isFalse();
    }

    @Test void malformedOrFuturePlaybackNeverCountsAsCompleted() {
        long delivered = now - 1_000;
        delivered(delivered);
        when(departure.assess(id, delivered, now)).thenReturn(Presence.STILL_PRESENT);
        for (Long[] times : new Long[][]{{null,now-100},{now-500,null},{now-500,now-600},{now-500,now+1},{delivered-1,now-100}}) {
            when(voice.find(id)).thenReturn(call("SIMULATED_PLAYED",1,times[0],times[1]));
            assertThat(gate(true,true).allows(id)).isFalse();
        }
    }

    @Test void staleNotificationAndReadErrorsCannotBecomeMissingRecipients() {
        when(sms.facts(id)).thenReturn(new AutoSmsRepository.Facts(now-300_001,"UAV",now,now),
                new AutoSmsRepository.Facts(now,"UAV",now,now));
        assertThat(gate(true,true).allows(id)).isFalse();
        when(directory.cannotNotifyPilotFromConfiguration("ADVISORY_SMS",id)).thenThrow(new IllegalStateException("directory unavailable"));
        assertThat(gate(true,true).allows(id)).isFalse();
    }

    private void delivered(long at) {
        when(sms.find(id)).thenReturn(smsTask("SIMULATED_DELIVERED",1,at));
        when(sms.deliveredAt(id)).thenReturn(at);
    }
    private AutoSmsRepository.Task smsTask(String status,int attempts,long updated) {
        return new AutoSmsRepository.Task(id,status,"含飞手/任务文案也不是许可",null,null,null,null,updated-1,updated,attempts,null,null,null);
    }
    private AutoVoiceRepository.Task call(String status,int attempts,Long answered,Long played) {
        return new AutoVoiceRepository.Task(id,status,"临时原因不是许可",null,null,null,null,now,attempts,null,null,null,null,null,null,null,answered,played);
    }
    private AutomaticCounterNotificationGate gate(boolean smsEnabled,boolean voiceEnabled) {
        MockEnvironment env=new MockEnvironment(); env.setActiveProfiles("test");
        return new AutomaticCounterNotificationGate(events,sms,voice,new AutoSmsPolicy(env,smsEnabled,120,300),
                new AutoVoicePolicy(env,voiceEnabled),recordings,directory,departure,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)));
    }
}
