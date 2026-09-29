package com.uav.lowaltitude.modules.device.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Separate transaction bean avoids scheduled self-invocation bypassing locks. */
@Service
public class EoAutoTrackService {
    private final EoTrackingScheduler scheduler;
    public EoAutoTrackService(EoTrackingScheduler scheduler) {this.scheduler=scheduler;}
    @Scheduled(fixedDelayString="${app.eo-edge.poll-millis:1000}")
    public void scheduled() {scheduler.poll();}
    public int poll() {return scheduler.poll();}
}
