package com.uav.lowaltitude.modules.device.application;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@Component
public class DeviceAccessPolicy {

    private final AccessService accessService;

    public DeviceAccessPolicy(AccessService accessService) {
        this.accessService = accessService;
    }

    public AuthUser requireDevicesRead() { return require("devices.read"); }
    public AuthUser requireDevicesOperate() { return require("devices.op"); }
    public AuthUser requireMonitoringRead() { return require("monitoring.read"); }
    public AuthUser requireOverviewRead() {
        AuthUser actor = AuthContext.require();
        String permission = accessService.permissionCodes(actor.roleCode()).contains("devices.read")
                ? "devices.read" : "monitoring.read";
        return require(permission);
    }
    public AuthUser requireMonitoringOperate() { return require("monitoring.op"); }
    public AuthUser requireCommissionRead() { return require("commissioning.read"); }
    public AuthUser requireCommissionOperate() { return require("commissioning.op"); }
    public AuthUser requireInterfacesRead() { return require("interfaces.read"); }
    public AuthUser requireInterfacesOperate() { return require("interfaces.op"); }
    /** 登记设备（devices.op）和维护 MQTT 连接（interfaces.op）都要选择单位与区域，任一权限即可读取可选范围。 */
    public AuthUser requireScopeOptionsRead() {
        AuthUser actor = AuthContext.require();
        String permission = accessService.permissionCodes(actor.roleCode()).contains("interfaces.op")
                ? "interfaces.op" : "devices.op";
        return require(permission);
    }

    public boolean canOperateDevices(AuthUser user) {
        return user != null && accessService.permissionCodes(user.roleCode()).contains("devices.op");
    }

    public boolean canOperateMonitoring(AuthUser user) {
        return user != null && accessService.permissionCodes(user.roleCode()).contains("monitoring.op");
    }

    private AuthUser require(String permissionCode) {
        accessService.requireBusinessData(permissionCode);
        return AuthContext.require();
    }
}
