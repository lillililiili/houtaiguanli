package com.uav.lowaltitude.modules.device.api;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/mqtt-brokers")
public class MqttBrokerController {
    private final MqttConfigurationService service;
    public MqttBrokerController(MqttConfigurationService service) { this.service=service; }
    @GetMapping public ApiResponse<List<Broker>> list() { return ApiResponse.ok(service.list()); }
    @GetMapping("/scopes") public ApiResponse<List<Map<String,Object>>> scopes() { return ApiResponse.ok(service.scopes()); }
    @GetMapping("/{id}") public ApiResponse<Broker> get(@PathVariable String id) { return ApiResponse.ok(service.get(id)); }
    @PostMapping public ApiResponse<Broker> create(@RequestBody BrokerInput body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.create(body,key));
    }
    @PutMapping("/{id}") public ApiResponse<Broker> update(@PathVariable String id,@RequestBody BrokerInput body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.update(id,body,key));
    }
    @PatchMapping("/{id}/enabled") public ApiResponse<Broker> enable(@PathVariable String id,@Valid @RequestBody Enabled body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return ApiResponse.ok(service.enable(id,body.version(),body.enabled(),key));
    }
    public record Enabled(@NotNull Long version,@NotNull Boolean enabled) { }
}
