package com.uav.lowaltitude.modules.device.api;

import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.security.UnitOperationsContext;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.application.DeviceTrendService;
import java.util.List;
import java.util.Map;
import static com.uav.lowaltitude.platform.security.UnitOperationsContext.Family.*;

@RestController
@RequestMapping("/api/v1/unit-admin/operations")
public class UnitOperationsOptionsController {
    private final UnitOperationsContext context;
    private final MqttConfigurationService mqtt;
    private final DeviceTrendService trends;
    public UnitOperationsOptionsController(UnitOperationsContext context,MqttConfigurationService mqtt,DeviceTrendService trends) {
        this.context=context;this.mqtt=mqtt;this.trends=trends;
    }
    @GetMapping("/scopes")
    public ApiResponse<List<Map<String,Object>>> scopes() {
        return context.call(DEVICES,"devices.op",()->ApiResponse.ok(mqtt.scopes()));
    }
    @GetMapping("/device-monitor/devices/{id}/trends")
    public ApiResponse<DeviceTrendService.Trends> trends(@PathVariable String id,@RequestParam(defaultValue="1h") String range) {
        return context.call(MONITORING,"monitoring.read",()->ApiResponse.ok(trends.get(id,range)));
    }
}
