package com.uav.lowaltitude.modules.device.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name="app.device-maintenance.recovery.enabled",havingValue="true",matchIfMissing=true)
public class DeviceMaintenanceRecoveryJob {
    private final DeviceMaintenanceWorkflowService workflows;
    public DeviceMaintenanceRecoveryJob(DeviceMaintenanceWorkflowService workflows){this.workflows=workflows;}
    @Scheduled(fixedDelayString="${app.device-maintenance.recovery.interval-ms:5000}",initialDelay=5000)
    public void tick(){workflows.runAutomaticBatch();}
}
