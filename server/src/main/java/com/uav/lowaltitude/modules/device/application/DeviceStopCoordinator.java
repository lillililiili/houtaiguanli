package com.uav.lowaltitude.modules.device.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceStopRepository;

/** Cancel platform delivery/re-delivery; cancellation does not assert physical device state. */
@Service
public class DeviceStopCoordinator {
    private final DeviceStopRepository repository;
    public DeviceStopCoordinator(DeviceStopRepository repository) { this.repository=repository; }
    @Transactional
    public void cancelDelivery(String commandId,long at) { if(commandId!=null) repository.cancelDelivery(commandId,at); }
    @Transactional
    public void cancelAuthorizationStarts(String authorizationId,long at) {
        for(String commandId:repository.authorizationStarts(authorizationId)) repository.cancelDelivery(commandId,at);
    }
    public void lockCommand(String commandId) { repository.lock(commandId); }
}
