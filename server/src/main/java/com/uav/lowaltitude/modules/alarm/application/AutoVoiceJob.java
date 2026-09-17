package com.uav.lowaltitude.modules.alarm.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AutoVoiceJob {
    private final AutoVoiceService service;
    public AutoVoiceJob(AutoVoiceService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.advisory.auto-voice.poll-millis:10000}",initialDelayString="${app.advisory.auto-voice.initial-delay-millis:10000}")
    public void poll(){service.poll();}
}
