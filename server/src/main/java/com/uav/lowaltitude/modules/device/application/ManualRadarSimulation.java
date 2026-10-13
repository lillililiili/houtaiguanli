package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.infrastructure.LocalQaDeviceRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.security.AuthUser;

/** Explicit simulated source declaration for manual onboarding; never inferred from a device name or port. */
@Service
public class ManualRadarSimulation {
    private final SimulationPolicy simulation;
    private final boolean enabled;
    private final AccessService access;
    private final LocalQaDeviceRepository repository;
    private final AuditService audit;

    public ManualRadarSimulation(SimulationPolicy simulation,
            @Value("${app.qa.device-setup.enabled:false}") boolean enabled, AccessService access,
            LocalQaDeviceRepository repository, AuditService audit) {
        this.simulation=simulation; this.enabled=enabled; this.access=access;
        this.repository=repository; this.audit=audit;
    }

    public boolean allowed(AuthUser user) {
        return simulation.allowed() && enabled && "ALL".equals(user.scopeMode())
                && access.permissionCodes(user.roleCode()).containsAll(List.of("interfaces.op", "devices.auth", "devices.op"));
    }

    public void validate(AuthUser user, DeviceOnboardService.OnboardRequest request) {
        simulation.requireSimulation();
        access.requireBackend(); access.require("interfaces.op"); access.require("devices.auth");
        if (!allowed(user)) throw new ApiException(HttpStatus.FORBIDDEN, "QA_DEVICE_SETUP_DISABLED",
                "当前环境或账号未开放本机模拟设备登记");
        if (!DeviceProtocolCodes.RADAR_TCP_V3_0_0.equals(request.protocolCode())
                || !"127.0.0.1".equals(request.host().trim())
                || !"127.0.0.1/32".equals(request.allowedCidrs().trim()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "模拟测试仅支持本机 TCP 雷达，请使用 127.0.0.1 和 127.0.0.1/32");
    }

    public void markSource(String sourceId) { repository.markNewSourceSimulated(sourceId); }

    public void record(AuthUser user, String deviceId) {
        audit.record(user.userId(), user.account(), "local_qa_device_prepare", "device", deviceId,
                "后台手动登记 TCP 模拟雷达；来源由操作者明确声明，连接及上报通过原生协议验证", null);
    }
}
