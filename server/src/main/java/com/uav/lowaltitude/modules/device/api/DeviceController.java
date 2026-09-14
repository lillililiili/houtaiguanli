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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.device.application.DeviceOnboardService;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.ConnectionProfile;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceDetail;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceFilter;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceMutation;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceOptions;
import com.uav.lowaltitude.modules.device.application.DeviceService.DevicePage;
import com.uav.lowaltitude.modules.device.application.DeviceService.ProtocolConfiguration;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService service;
    private final DeviceOnboardService onboardService;
    private final MqttConfigurationService mqtt;
    private final ObjectMapper mapper;
    private final Validator validator;

    public DeviceController(DeviceService service, DeviceOnboardService onboardService, MqttConfigurationService mqtt,
                            ObjectMapper mapper, Validator validator) {
        this.service = service;
        this.onboardService = onboardService;
        this.mqtt = mqtt;
        this.mapper = mapper;
        this.validator = validator;
    }

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
            @RequestParam(defaultValue = "priority") String sort) {
        return ApiResponse.ok(service.list(
                new DeviceFilter(keyword, typeCode, channel, region, vendor, connectivity, enabled),
                page, size, sort));
    }

    @GetMapping("/options")
    public ApiResponse<DeviceOptions> options() {
        return ApiResponse.ok(service.options());
    }

    @GetMapping("/mqtt-options")
    public ApiResponse<java.util.List<MqttConfigurationService.BrokerOption>> mqttOptions() {
        return ApiResponse.ok(mqtt.options());
    }

    @PostMapping("/onboard")
    public ApiResponse<DeviceDetail> onboard(@RequestBody JsonNode body, @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String protocol = body.path("protocol_code").asText();
        if (LingyunEnvelope.PROTOCOL.equals(protocol) || DeviceProtocolCodes.EO_EDGE_MQTT_20250826.equals(protocol))
            return ApiResponse.ok(service.detail(mqtt.register(convert(body,Registration.class),key)));
        return ApiResponse.ok(onboardService.onboard(convert(body,DeviceOnboardService.OnboardRequest.class)));
    }

    @GetMapping("/{deviceId}")
    public ApiResponse<DeviceDetail> detail(@PathVariable String deviceId) {
        return ApiResponse.ok(service.detail(deviceId));
    }

    @PostMapping
    public ApiResponse<DeviceDetail> create(@Valid @RequestBody DeviceRequest request) {
        return ApiResponse.ok(service.create(request.toMutation()));
    }

    @PutMapping("/{deviceId}")
    public ApiResponse<DeviceDetail> update(@PathVariable String deviceId, @RequestBody JsonNode body,
                                          @RequestHeader(value="Idempotency-Key",required=false) String key) {
        if (mqtt.isMqtt(deviceId)) {
            mqtt.updateDevice(deviceId,convert(body,Registration.class),key);
            return ApiResponse.ok(service.detail(deviceId));
        }
        DeviceRequest request=convert(body,DeviceRequest.class);
        if (request.version() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "version 必填");
        }
        return ApiResponse.ok(service.update(deviceId, request.version(), request.toMutation()));
    }

    @PatchMapping("/{deviceId}/enabled")
    public ApiResponse<DeviceDetail> setEnabled(@PathVariable String deviceId, @Valid @RequestBody EnabledRequest request,
                                              @RequestHeader(value="Idempotency-Key",required=false) String key) {
        if (mqtt.isMqtt(deviceId)) {
            mqtt.enableDevice(deviceId,request.version(),request.enabled(),key);
            return ApiResponse.ok(service.detail(deviceId));
        }
        return ApiResponse.ok(service.setEnabled(deviceId, request.version(), request.enabled(), request.reason()));
    }

    private <T> T convert(JsonNode body,Class<T> type) {
        final T value;
        try { value=mapper.treeToValue(body,type); }
        catch(Exception ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","请求字段类型无效"); }
        if(value==null || !validator.validate(value).isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","请填写有效的必填字段");
        return value;
    }

    public record EnabledRequest(@NotNull Boolean enabled, @NotNull Long version, @NotBlank String reason) { }

    public record DeviceRequest(
            Long version,
            String sourceId,
            String externalDeviceId,
            @NotBlank String deviceNo,
            @NotBlank String name,
            String deviceTypeCode,
            @NotBlank String deviceTypeName,
            @NotBlank String channel,
            String model,
            String vendor,
            String ownerName,
            String regionName,
            String address,
            BigDecimal longitude,
            BigDecimal latitude,
            String coordinateSystem,
            BigDecimal altitudeM,
            String altitudeDatum,
            String firmwareVersion,
            Long installedAt,
            ConnectionRequest connection,
            ProtocolConfiguration protocolConfiguration,
            String allowedCidrs) {
        DeviceMutation toMutation() {
            return new DeviceMutation(sourceId, externalDeviceId, deviceNo, name, deviceTypeCode, deviceTypeName,
                    channel, model, vendor, ownerName, regionName, address, longitude, latitude,
                    coordinateSystem, altitudeM, altitudeDatum, firmwareVersion, installedAt,
                    connection == null ? null : connection.toProfile(), protocolConfiguration, allowedCidrs);
        }
    }

    public record ConnectionRequest(
            String transport, String host, Integer port, String path, String dataFormat, String charsetName,
            String authMode, String credentialRef, Integer heartbeatIntervalSeconds, Integer reportIntervalMillis,
            BigDecimal samplingRateHz, Boolean compressionEnabled, Boolean retransmissionEnabled,
            Integer timeoutMillis, Integer retryCount, BigDecimal longitudeOffsetDeg,
            BigDecimal latitudeOffsetDeg, BigDecimal altitudeOffsetM, String timeSyncMode,
            String timeServer, String timezoneName, Integer timeSyncIntervalSeconds) {
        ConnectionProfile toProfile() {
            return new ConnectionProfile(transport, host, port, path, dataFormat, charsetName, authMode, credentialRef,
                    heartbeatIntervalSeconds, reportIntervalMillis, samplingRateHz, compressionEnabled,
                    retransmissionEnabled, timeoutMillis, retryCount, longitudeOffsetDeg, latitudeOffsetDeg,
                    altitudeOffsetM, timeSyncMode, timeServer, timezoneName, timeSyncIntervalSeconds);
        }
    }
}
