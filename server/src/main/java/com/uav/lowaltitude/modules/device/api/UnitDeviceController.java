package com.uav.lowaltitude.modules.device.api;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.web.bind.annotation.RequestHeader;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.DeviceOnboardService;
import com.uav.lowaltitude.modules.device.application.SimulatedDeviceRestoreService;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.ConnectionProfile;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceDetail;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceFilter;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceMutation;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceOptions;
import com.uav.lowaltitude.modules.device.application.DeviceService.DevicePage;
import com.uav.lowaltitude.modules.device.application.DeviceService.ProtocolConfiguration;
import com.uav.lowaltitude.modules.device.application.DeviceService.SensingProfileMutation;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.api.ApiResponse;

import com.uav.lowaltitude.platform.security.UnitOperationsContext;
import static com.uav.lowaltitude.platform.security.UnitOperationsContext.Family.*;
import com.uav.lowaltitude.modules.device.api.DeviceController.*;

@RestController
@RequestMapping("/api/v1/unit-admin/operations/devices")
public class UnitDeviceController {
    private final DeviceController delegate;
    private final UnitOperationsContext context;
    public UnitDeviceController(DeviceController delegate, UnitOperationsContext context) { this.delegate=delegate; this.context=context; }

    @GetMapping
    public ApiResponse<DevicePage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "type_code", required = false) String typeCode,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) String connectivity,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(name = "formal_only", defaultValue = "false") boolean formalOnly,
            @RequestParam(name = "statistics_scope", defaultValue = "false") boolean statisticsScope,
            @RequestParam(defaultValue = "priority") String sort) {
        return context.deviceRead(() -> delegate.list(page,size,keyword,typeCode,channel,region,vendor,connectivity,enabled,formalOnly,statisticsScope,sort));
    }

    @GetMapping("/overview")
    public ApiResponse<DeviceService.DeviceOverview> overview(
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "type_code", required = false) String typeCode,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) String connectivity,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(name = "formal_only", defaultValue = "false") boolean formalOnly,
            @RequestParam(name = "statistics_scope", defaultValue = "false") boolean statisticsScope) {
        return context.deviceRead(() -> delegate.overview(keyword,typeCode,channel,region,vendor,connectivity,enabled,formalOnly,statisticsScope));
    }

    @GetMapping("/options")
    public ApiResponse<DeviceOptions> options(@RequestParam(name = "formal_only", defaultValue = "false") boolean formalOnly) {
        return context.deviceRead(() -> delegate.options(formalOnly));
    }

    @GetMapping("/mqtt-options")
    public ApiResponse<java.util.List<MqttConfigurationService.BrokerOption>> mqttOptions() {
        return context.call(DEVICES, "devices.op", () -> delegate.mqttOptions());
    }

    @GetMapping("/onboarding-options")
    public ApiResponse<DeviceOnboardService.OnboardingOptions> onboardingOptions() {
        return context.call(DEVICES,"devices.op",delegate::onboardingOptions);
    }

    @PostMapping("/onboard")
    public ApiResponse<DeviceDetail> onboard(@RequestBody JsonNode body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return context.call(DEVICES,"devices.op",()-> {
            String protocol=body.path("protocol_code").asText();
            if(!java.util.Set.of(DeviceProtocolCodes.RADAR_TCP_V3_0_0,DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0).contains(protocol))
                throw new ApiException(HttpStatus.FORBIDDEN,"UNIT_PROTOCOL_FORBIDDEN","单位设备接入仅支持已开放的 TCP 协议");
            UnitOperationsContext.requireOwner(body.path("owner_org_id").isTextual()?body.path("owner_org_id").asText():null);
            var fixed=body.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)fixed).put("owner_org_id",UnitOperationsContext.orgId());
            return delegate.onboard(fixed,key);
        });
    }

    @GetMapping("/{deviceId}")
    public ApiResponse<DeviceDetail> detail(@PathVariable String deviceId) {
        return context.deviceRead(() -> delegate.detail(deviceId));
    }

    @GetMapping("/{deviceId}/catalog-blockers")
    public ApiResponse<DeviceService.CatalogBlockers> catalogBlockers(@PathVariable String deviceId) {
        return context.deviceRead(() -> delegate.catalogBlockers(deviceId));
    }

    @PostMapping
    public ApiResponse<DeviceDetail> create(@Valid @RequestBody DeviceRequest request) {
        return context.call(DEVICES, "devices.op", () -> delegate.create(request));
    }

    @PutMapping("/{deviceId}")
    public ApiResponse<DeviceDetail> update(@PathVariable String deviceId, @RequestBody JsonNode body,
                                          @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return context.call(DEVICES, "devices.op", () -> { delegate.detail(deviceId); return delegate.update(deviceId,body,key); });
    }

    @PatchMapping("/{deviceId}/enabled")
    public ApiResponse<DeviceDetail> setEnabled(@PathVariable String deviceId, @Valid @RequestBody EnabledRequest request,
                                              @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return context.call(DEVICES, "devices.op", () -> { delegate.detail(deviceId); return delegate.setEnabled(deviceId,request,key); });
    }

    @DeleteMapping("/{deviceId}")
    public ApiResponse<DeviceService.DeviceDeletion> delete(@PathVariable String deviceId,
            @Valid @RequestBody DeleteRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return context.call(DEVICES, "devices.op", () -> { delegate.detail(deviceId); return delegate.delete(deviceId,request,key); });
    }

    @PutMapping("/{deviceId}/sensing-profile")
    public ApiResponse<DeviceDetail> upsertSensingProfile(
            @PathVariable String deviceId,
            @RequestBody SensingProfileRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return context.call(DEVICES, "devices.op", () -> delegate.upsertSensingProfile(deviceId,request,idempotencyKey));
    }

    @DeleteMapping("/{deviceId}/sensing-profile")
    public ApiResponse<DeviceDetail> deleteSensingProfile(
            @PathVariable String deviceId,
            @RequestBody SensingProfileVersionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return context.call(DEVICES, "devices.op", () -> delegate.deleteSensingProfile(deviceId,request,idempotencyKey));
    }
}
