package com.uav.lowaltitude.modules.flight.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightCheckScheduleRepository;
import com.uav.lowaltitude.platform.time.AppClock;

@Component
@ConditionalOnProperty(prefix="app.flight-device-check.schedule",name="enabled",havingValue="true")
public class FlightDeviceCheckJob {
    private final FlightCheckScheduleRepository schedules;
    private final FlightScheduledCheckService service;
    private final AppClock clock;
    public FlightDeviceCheckJob(FlightCheckScheduleRepository schedules,FlightScheduledCheckService service,AppClock clock){this.schedules=schedules;this.service=service;this.clock=clock;}
    @Scheduled(fixedDelay=60000)
    public void poll(){
        // Due times are advanced per plan so failed/large batches do not starve following pages.
        for(int batch=0;batch<100;batch++){
            var ids=schedules.due(clock.nowMillis(),100);if(ids.isEmpty())return;
            for(String id:ids)try{service.check(id);}catch(RuntimeException error){
                LoggerFactory.getLogger(getClass()).warn("Scheduled device check failed for plan {} ({})",id,error.getClass().getSimpleName());
                try{service.failed(id);}catch(RuntimeException persist){LoggerFactory.getLogger(getClass()).error("Could not persist check failure for plan {}",id);}
            }
            if(ids.size()<100)return;
        }
    }
}
