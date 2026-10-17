package com.uav.lowaltitude.modules.alarm.application;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.time.AppClock;

/** Automatic starts require notification evidence; the UI's manual-decision phase is not authorization. */
@Component
public class AutomaticCounterNotificationGate {
    private static final Set<String> UNATTEMPTED = Set.of("WAITING", "BLOCKED", "UNAVAILABLE", "DISABLED");
    private final UavEventRepository events;
    private final AutoSmsRepository sms;
    private final AutoVoiceRepository voice;
    private final AutoSmsPolicy smsPolicy;
    private final AutoVoicePolicy voicePolicy;
    private final AdvisoryVoiceRecording recordings;
    private final NotificationDirectoryService directory;
    private final PilotDepartureWatch departure;
    private final AppClock clock;

    public AutomaticCounterNotificationGate(UavEventRepository events, AutoSmsRepository sms,
            AutoVoiceRepository voice, AutoSmsPolicy smsPolicy, AutoVoicePolicy voicePolicy,
            AdvisoryVoiceRecording recordings, NotificationDirectoryService directory,
            PilotDepartureWatch departure, AppClock clock) {
        this.events = events; this.sms = sms; this.voice = voice; this.smsPolicy = smsPolicy;
        this.voicePolicy = voicePolicy; this.recordings = recordings; this.directory = directory;
        this.departure = departure; this.clock = clock;
    }

    /** Called with the event lock held at initial authorization and again before queued socket dispatch. */
    public boolean allows(String eventId) {
        try {
            EventRow event = events.find(eventId, new AccessDecision("system:auto-counter", ScopeMode.ALL));
            return event != null && "CONFIRMED".equals(event.state()) && ready(event, clock.nowMillis());
        } catch (RuntimeException unavailable) {
            // A read failure is neither a missing recipient nor proof that notification is impossible.
            return false;
        }
    }

    private boolean ready(EventRow event, long now) {
        String id = event.eventId();
        if (voice.departure(id) != null) return false;
        var smsTask = sms.find(id);
        if (smsTask == null || !"SIMULATED_DELIVERED".equals(smsTask.status())) {
            // Disabled/configuration-only exceptions cannot consume an in-flight, failed or unknown attempt.
            if (smsTask != null && (smsTask.attempts() != 0 || !UNATTEMPTED.contains(smsTask.status()))) return false;
            var current = sms.facts(id);
            if (current == null || !smsPolicy.fresh(current.receivedAt(), now, smsPolicy.eventMillis())) return false;
            return !smsPolicy.enabled() || directory.cannotNotifyPilotFromConfiguration("ADVISORY_SMS", id);
        }
        Long deliveredAt = sms.deliveredAt(id);
        if (deliveredAt == null || deliveredAt > now) return false;
        // A zero observation delay does not turn an observation taken before delivery into a later one.
        if (departure.assess(id, deliveredAt, now) != PilotDepartureWatch.Presence.STILL_PRESENT) return false;

        var call = voice.find(id);
        if (call != null && "SIMULATED_PLAYED".equals(call.status())) {
            Long answered = call.answeredAt(), played = call.playbackCompletedAt();
            if (answered == null || played == null || answered < deliveredAt || played < answered || played > now) return false;
            // Wait for evidence, not a fixed delay. Pre-play positions cannot authorize an automatic start.
            return departure.assess(id, deliveredAt, played, now) == PilotDepartureWatch.Presence.STILL_PRESENT;
        }
        if (call != null && (call.attempts() != 0 || !UNATTEMPTED.contains(call.status()))) return false;
        var recording = recordings.current();
        if (call != null && !call.recordingMatches(recording)) return false;
        if (recording == null && recordings.hasConfiguration()) return false;
        // Receiver heartbeat/offline status and human-readable BLOCKED reasons are deliberately not used.
        return !voicePolicy.enabled() || recording == null
                || directory.cannotNotifyPilotFromConfiguration("ADVISORY_VOICE", id);
    }
}
