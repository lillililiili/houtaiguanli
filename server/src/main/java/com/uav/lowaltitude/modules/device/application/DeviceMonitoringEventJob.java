package com.uav.lowaltitude.modules.device.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.device-monitor-events.enabled",havingValue="true",matchIfMissing=true)
public class DeviceMonitoringEventJob {
    private final DeviceMonitoringEventService service;
    public DeviceMonitoringEventJob(DeviceMonitoringEventService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.device-monitor-events.flush-millis:1000}")
    public void flush() { service.flushClosedWindows(); }
}
