package com.uav.lowaltitude.modules.device.application;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.application.DeviceService.ConnectionProfile;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceDetail;
import com.uav.lowaltitude.modules.device.application.DeviceService.DeviceMutation;
import com.uav.lowaltitude.modules.device.application.DeviceService.ProtocolConfiguration;
import com.uav.lowaltitude.modules.device.application.IntegrationSourceService.Mutation;
import com.uav.lowaltitude.modules.device.application.IntegrationSourceService.Source;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthUser;

@Service
public class DeviceOnboardService {

    private final DeviceAccessPolicy access;
    private final DeviceService devices;
    private final IntegrationSourceService sources;

    public DeviceOnboardService(DeviceAccessPolicy access, DeviceService devices, IntegrationSourceService sources) {
        this.access = access;
        this.devices = devices;
        this.sources = sources;
    }

    @Transactional
    public DeviceDetail onboard(OnboardRequest request) {
        AuthUser user = access.requireDevicesOperate();
        Catalog catalog = catalog(request.protocolCode());
        String deviceNo = required(request.deviceNo(), "设备编号");
        String name = required(request.name(), "设备名称");
        String host = required(request.host(), "连接主机");
        if (request.port() == null || request.port() < 1 || request.port() > 65535)
            throw bad("端口必须在 1 到 65535 之间");
        String cidrs = required(request.allowedCidrs(), "设备网络 CIDR");
        Source source;
        try {
            source = sources.insertLive(new Mutation(deviceNo, name, request.protocolCode(), null,
                    blank(request.credentialRef()), cidrs));
        } catch (ApiException ex) {
            if ("INTEGRATION_SOURCE_CONFLICT".equals(ex.getCode()))
                throw new ApiException(HttpStatus.CONFLICT, "DEVICE_NO_CONFLICT", "设备编号或接入编码已存在");
            throw ex;
        }
        ConnectionProfile connection = new ConnectionProfile("TCP", host, request.port(), blank(request.path()),
                blank(request.dataFormat()) == null ? "BINARY" : request.dataFormat().trim(), "UTF-8", "Token",
                blank(request.credentialRef()), 30, 1000, null, false, true,
                request.timeoutMillis() == null ? 3000 : request.timeoutMillis(),
                request.retryCount() == null ? 3 : request.retryCount(),
                null, null, null, "NTP", null, "Asia/Shanghai", 60);
        ProtocolConfiguration protocol = new ProtocolConfiguration(
                blank(request.loginRole()) == null ? "DATA" : request.loginRole().trim(),
                blank(request.recognitionCodeRef()),
                Boolean.TRUE.equals(request.rtkEnabled()),
                Boolean.TRUE.equals(request.coordinateTransformEnabled()),
                request.deviceAddress() == null ? 1 : request.deviceAddress(),
                blank(request.wireEncoding()) == null ? "AUTO" : request.wireEncoding().trim(),
                request.pollIntervalMillis() == null ? 5000 : request.pollIntervalMillis());
        DeviceMutation mutation = new DeviceMutation(source.sourceId(), deviceNo, deviceNo, name,
                catalog.typeCode(), catalog.typeName(), catalog.channel(), blank(request.model()),
                blank(request.vendor()), blank(request.ownerName()), blank(request.regionName()),
                blank(request.address()), request.longitude(), request.latitude(),
                request.longitude() == null ? null : "WGS-84", request.altitudeM(), blank(request.altitudeDatum()),
                blank(request.firmwareVersion()), null, connection, protocol, cidrs);
        DeviceDetail created;
        try {
            created = devices.create(mutation);
        } catch (DataIntegrityViolationException | ApiException ex) {
            throw ex instanceof ApiException api ? api
                    : new ApiException(HttpStatus.CONFLICT, "DEVICE_NO_CONFLICT", "设备编号或接入编码已存在");
        }
        sources.activate(source.sourceId(), source.version(), "接入设备 " + deviceNo + "，操作人 " + user.account());
        return created;
    }

    private static Catalog catalog(String protocolCode) {
        if (DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(protocolCode))
            return new Catalog("radar", "雷达", "雷达直连");
        if (DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(protocolCode))
            return new Catalog("countermeasure", "反制", "反制直连");
        throw bad("仅支持雷达 TCP v3.0.0 与四通道反制协议");
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw bad(label + "必填");
        return value.trim();
    }

    private static String blank(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }

    private record Catalog(String typeCode, String typeName, String channel) { }

    public record OnboardRequest(@NotBlank String protocolCode, @NotBlank String deviceNo, @NotBlank String name,
                                 @NotBlank String host, @NotNull Integer port, @NotBlank String allowedCidrs,
                                 String path, String dataFormat, String credentialRef,
                                 Integer timeoutMillis, Integer retryCount, String regionName, String vendor,
                                 String model, String ownerName, String address, BigDecimal longitude,
                                 BigDecimal latitude, BigDecimal altitudeM, String altitudeDatum,
                                 String firmwareVersion, String loginRole, String recognitionCodeRef,
                                 Boolean rtkEnabled, Boolean coordinateTransformEnabled, Integer deviceAddress,
                                 String wireEncoding, Integer pollIntervalMillis) { }
}
