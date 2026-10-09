package com.uav.lowaltitude.modules.evidence.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.evidence.domain.EvidenceCommandVisibility;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

/** Callers require evidence:read first; the repository additionally restricts each command's business scope. */
@Component
public class EvidenceCommandAccess {
    private final AccessControlService access;
    private final DeviceAccessPolicy devices;

    public EvidenceCommandAccess(AccessControlService access, DeviceAccessPolicy devices) {
        this.access = access;
        this.devices = devices;
    }

    public EvidenceCommandVisibility visibility() {
        return new EvidenceCommandVisibility(monitoring(), allowed(PermissionCode.DISPOSAL_READ), allowed(PermissionCode.TARGET_READ));
    }

    public boolean monitoring() {
        try { devices.requireMonitoringRead(); return true; }
        catch (ApiException denied) { if (denied.getStatus() == HttpStatus.FORBIDDEN) return false; throw denied; }
    }

    private boolean allowed(PermissionCode permission) {
        try { access.require(permission); return true; }
        catch (ApiException denied) { if (denied.getStatus() == HttpStatus.FORBIDDEN) return false; throw denied; }
    }
}
