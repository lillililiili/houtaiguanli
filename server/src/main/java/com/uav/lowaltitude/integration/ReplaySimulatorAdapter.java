package com.uav.lowaltitude.integration;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/** Logical commissioning route for devices created by the map/device simulator. */
@Profile(SimulationPolicy.PROFILE)
@Component
public class ReplaySimulatorAdapter implements DeviceAdapterPort {

    @Override
    public String protocolCode() { return DeviceProtocolCodes.SIMULATOR_REPLAY; }

    @Override
    public SourceMode mode() { return SourceMode.replay; }

    @Override
    public AdapterResult reboot(RebootWork work) {
        return new AdapterResult(false, "DEVICE_NOT_OPERABLE", "设备模拟器仅支持逻辑调测，不执行重启");
    }

    @Override
    public AdapterResult connect(CommissionWork work) {
        return new AdapterResult(true, "SIMULATOR_CONNECTED", "设备模拟器逻辑连接已建立；不代表现场设备连通");
    }

    @Override
    public CommissionResult commission(CommissionWork work) {
        return new CommissionResult(true, "SIMULATOR_COMMISSION_PASSED",
                "设备模拟器逻辑调测完成；结果不代表现场协议验收",
                List.of(
                        new CommissionItem("SIMULATION", "模拟器场景", "PASSED", "场景身份已登记", null, "SIMULATOR"),
                        new CommissionItem("IDENTITY", "设备身份", "PASSED", "设备编号与模拟器绑定一致", null, "SIMULATOR"),
                        new CommissionItem("SCOPE", "授权范围", "PASSED", "设备处于当前组织区域范围", null, "SIMULATOR")));
    }
}
