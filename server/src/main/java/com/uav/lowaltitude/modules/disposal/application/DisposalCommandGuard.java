package com.uav.lowaltitude.modules.disposal.application;

import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;

/** Serializes direct device starts with a known disposal authorization's stop transaction. */
@Component
public class DisposalCommandGuard {
    private final EmergencyStopRepository stops;
    public DisposalCommandGuard(EmergencyStopRepository stops) { this.stops=stops; }

    /** External protocol authorization references remain compatible; known stopped authorizations cannot restart. */
    public boolean mayStart(String authorizationId) {
        String status=stops.lockAuthorizationStatus(authorizationId);
        return status==null || (!"STOPPED".equals(status) && !stops.covered(authorizationId));
    }
}
