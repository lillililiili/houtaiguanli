package com.uav.lowaltitude.modules.device.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.DeviceProtocolService;
import com.uav.lowaltitude.modules.device.application.DeviceProtocolService.ProtocolDescriptor;
import com.uav.lowaltitude.platform.api.ApiResponse;

import com.uav.lowaltitude.platform.security.UnitOperationsContext;
import static com.uav.lowaltitude.platform.security.UnitOperationsContext.Family.*;
import com.uav.lowaltitude.modules.device.api.DeviceProtocolController.*;

@RestController
@RequestMapping("/api/v1/unit-admin/operations/device-protocols")
public class UnitDeviceProtocolController {
    private final DeviceProtocolController delegate;
    private final UnitOperationsContext context;
    public UnitDeviceProtocolController(DeviceProtocolController delegate, UnitOperationsContext context) { this.delegate=delegate; this.context=context; }

    @GetMapping
    public ApiResponse<List<ProtocolDescriptor>> catalog() {
        return context.deviceRead(() -> delegate.catalog());
    }
}
