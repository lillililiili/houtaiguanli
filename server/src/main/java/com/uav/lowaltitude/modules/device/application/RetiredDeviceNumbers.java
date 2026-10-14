package com.uav.lowaltitude.modules.device.application;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 已退役（逻辑删除）的设备编号不能再新登记，台账保留着它的历史（2026-10-14 方案 A）。
 * 各登记入口（MQTT 和 TCP 接入、手工建档、天气传感器）写库前先查一次，如实说明是哪台设备、已退役，
 * 并分清模拟设备（可恢复）和其他设备（换新编号），不再报笼统的“编号已存在”或“该操作已提交”。
 */
@Component
public class RetiredDeviceNumbers {
    private final DeviceRepository devices;

    public RetiredDeviceNumbers(DeviceRepository devices) {
        this.devices = devices;
    }

    public void reject(String deviceNo) {
        if (deviceNo == null || deviceNo.isBlank()) return;
        Map<String, Object> retired = devices.retiredByDeviceNo(deviceNo);
        if (retired != null) throw new ApiException(HttpStatus.CONFLICT, "DEVICE_RETIRED", message(retired));
    }

    private static String message(Map<String, Object> device) {
        boolean simulated = "replay".equals(device.get("source_mode")) && Boolean.TRUE.equals(device.get("simulated"));
        return "设备编号 " + device.get("device_no") + "（" + device.get("name") + "）已退役，台账保留着它的历史，这个编号不能再新登记。"
                + (simulated ? "它是设备模拟器登记的模拟设备，可以恢复后继续使用（模拟器启动时会自动恢复）。" : "请换一个新的设备编号接入。");
    }
}
