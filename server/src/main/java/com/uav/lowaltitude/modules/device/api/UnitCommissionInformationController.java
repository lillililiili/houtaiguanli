package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.uav.lowaltitude.modules.device.application.CommissionDeviceInformationService;
import com.uav.lowaltitude.platform.api.ApiResponse;

import com.uav.lowaltitude.platform.security.UnitOperationsContext;
import static com.uav.lowaltitude.platform.security.UnitOperationsContext.Family.*;
import com.uav.lowaltitude.modules.device.api.CommissionDeviceInformationController.*;

@RestController
public class UnitCommissionInformationController {
    private final CommissionDeviceInformationController delegate;
    private final UnitOperationsContext context;
    public UnitCommissionInformationController(CommissionDeviceInformationController delegate, UnitOperationsContext context) { this.delegate=delegate; this.context=context; }

    @GetMapping("/api/v1/unit-admin/operations/commission-tasks/device-information/{deviceId}")
    public ApiResponse<CommissionDeviceInformationService.Information> get(@PathVariable String deviceId) {
        return context.call(COMMISSIONING, "commissioning.read", () -> delegate.get(deviceId));
    }
}
