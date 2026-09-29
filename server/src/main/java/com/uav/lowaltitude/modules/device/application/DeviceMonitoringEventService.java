package com.uav.lowaltitude.modules.device.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMonitoringEventRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** Durable report windows survive application restarts; one transaction per device. */
@Service
public class DeviceMonitoringEventService {
    private final DeviceMonitoringEventRepository events;
    private final TransactionTemplate transactions;
    private final AppClock clock;
    public DeviceMonitoringEventService(DeviceMonitoringEventRepository events,PlatformTransactionManager manager,AppClock clock) {
        this.events=events; this.transactions=new TransactionTemplate(manager); this.clock=clock;
    }
    public void flushClosedWindows() {
        long now=clock.nowMillis();
        for(String device:events.dueDevices(now)) transactions.executeWithoutResult(s -> events.flush(device,now));
    }
}
