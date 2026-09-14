package com.uav.lowaltitude.modules.identity.application;

import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

public interface AccessControlService {

    AccessDecision require(PermissionCode permission);
}
