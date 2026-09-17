package com.uav.lowaltitude.modules.alarm.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AutoSmsJob {
    private final AutoSmsService service;
    public AutoSmsJob(AutoSmsService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.advisory.auto-sms.poll-millis:10000}",initialDelayString="${app.advisory.auto-sms.initial-delay-millis:10000}")
    public void poll(){service.poll();}
}
