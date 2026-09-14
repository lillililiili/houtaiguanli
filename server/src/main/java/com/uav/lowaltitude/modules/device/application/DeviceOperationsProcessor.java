package com.uav.lowaltitude.modules.device.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.DeviceAdapterPort;
import com.uav.lowaltitude.integration.DeviceAdapterRegistry;
import com.uav.lowaltitude.integration.DeviceAdapterPort.AdapterResult;
import com.uav.lowaltitude.integration.DeviceAdapterPort.CommissionResult;
import com.uav.lowaltitude.integration.SourceMode;
import com.uav.lowaltitude.modules.device.infrastructure.CommissionRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class DeviceOperationsProcessor {

    private final DeviceRepository devices;
    private final CommissionRepository commissions;
    private final DeviceAdapterRegistry adapters;
    private final AppClock clock;
    private final ObjectMapper objectMapper;
    private final EoEdgeCommandService eoCommands;
    private final LingyunControlService lingyunControl;
    private final Countermeasure4ChControlService countermeasureControl;

    public DeviceOperationsProcessor(DeviceRepository devices, CommissionRepository commissions,
                                     DeviceAdapterRegistry adapters, AppProperties properties,
                                     AppClock clock, ObjectMapper objectMapper, EoEdgeCommandService eoCommands,
                                     LingyunControlService lingyunControl,
                                     Countermeasure4ChControlService countermeasureControl) {
        this.devices = devices;
        this.commissions = commissions;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.adapters = adapters;
        this.eoCommands = eoCommands;
        this.lingyunControl = lingyunControl;
        this.countermeasureControl = countermeasureControl;
    }

    @Transactional
    public void process(String topic, String payload) {
        switch (topic) {
            case "device.reboot" -> reboot(payload);
            case "commission.connect" -> connect(payload);
            case "commission.run" -> commission(payload);
            case "eo.track.begin", "eo.track.end", "eo.camera.status" -> eoCommands.dispatch(topic, payload);
            case "device.control.lingyun" -> lingyunControl.dispatch(payload);
            case "device.control.countermeasure" -> countermeasureControl.dispatch(payload);
            default -> throw new IllegalArgumentException("Unsupported outbox topic " + topic);
        }
    }

    @Transactional
    public void expireCommands(long now) {
        for (Map<String, Object> row : devices.expiredCommands(now, 100)) {
            String commandId = text(row, "command_id");
            String type = text(row, "command_type");
            if (type != null && type.startsWith("EO_")) { eoCommands.timeout(commandId, "设备适配器未在截止时间前返回回执"); continue; }
            if (LingyunControlService.TYPE.equals(type)) { lingyunControl.timeout(commandId, "设备适配器未在截止时间前返回回执"); continue; }
            if (Countermeasure4ChControlService.TYPE.equals(type)) {
                countermeasureControl.timeout(commandId, "设备适配器未在截止时间前返回回执");
                continue;
            }
            String status = text(row, "status");
            if (devices.updateCommand(commandId, status, "TIMED_OUT", now, "ADAPTER_TIMEOUT",
                    "设备适配器未在截止时间前返回回执") == 1) {
                devices.addEvent(UUID.randomUUID().toString(), text(row, "device_id"), "REBOOT_TIMED_OUT", "ERROR",
                        "设备重启指令等待回执超时", now, true);
                revertIncident(commandId);
            }
        }
    }

    @Transactional
    public void timeout(String topic, String payload, String detail) {
        long now = clock.nowMillis();
        if (topic != null && topic.startsWith("eo.")) { eoCommands.timeout(payload, detail); return; }
        if (LingyunControlService.TOPIC.equals(topic)) { lingyunControl.timeout(payload, detail); return; }
        if (Countermeasure4ChControlService.TOPIC.equals(topic)) {
            countermeasureControl.timeout(payload, detail);
            return;
        }
        if ("device.reboot".equals(topic)) {
            Map<String, Object> command = devices.findCommand(payload);
            if (command != null && !terminal(text(command, "status"))) {
                devices.updateCommand(payload, text(command, "status"), "TIMED_OUT", now,
                        "ADAPTER_RETRY_EXHAUSTED", detail);
                devices.addEvent(UUID.randomUUID().toString(), text(command, "device_id"), "REBOOT_TIMED_OUT", "ERROR", detail, now, true);
                revertIncident(payload);
            }
            return;
        }
        Map<String, Object> task = commissions.findTask(payload);
        if (task == null) return;
        if ("commission.connect".equals(topic) && "CONNECTING".equals(text(task, "status"))) {
            if (commissions.completeConnect(payload, false, now, detail) == 1)
                commissions.addEvent(UUID.randomUUID().toString(), payload, "UNTESTABLE", "ERROR", detail, now, true);
        } else if ("commission.run".equals(topic) && "RUNNING".equals(text(task, "status"))) {
            Map<String, Object> criteria = Map.of("source", "ADAPTER_RETRY_POLICY", "confirmed", false);
            Map<String, Object> result = Map.of("result_code", "ADAPTER_RETRY_EXHAUSTED", "detail", detail, "items", List.of());
            if (commissions.completeRun(payload, "UNTESTABLE", json(criteria), json(result), now) == 1)
                commissions.addEvent(UUID.randomUUID().toString(), payload, "UNTESTABLE", "ERROR", detail, now, true);
        }
    }

    private void reboot(String commandId) {
        Map<String, Object> command = devices.findCommand(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        Map<String, Object> device = devices.find(text(command, "device_id"));
        if (device == null) throw new IllegalStateException("device disappeared for command " + commandId);
        long now = clock.nowMillis();
        String status = text(command, "status");
        if ("QUEUED".equals(status)) {
            devices.updateCommand(commandId, "QUEUED", "SENT", now, null, null);
            status = "SENT";
        }
        DeviceAdapterPort adapter = adapter(device);
        AdapterResult result = adapter.reboot(new DeviceAdapterPort.RebootWork(commandId,
                text(command, "command_no"), text(command, "device_id"), text(command, "device_no"),
                text(command, "reason")));
        long completed = clock.nowMillis();
        if (result.success()) {
            if (devices.updateCommand(commandId, status, "SUCCEEDED", completed, result.resultCode(), result.detail()) == 1) {
                String inboxId = UUID.randomUUID().toString();
                devices.addReceipt(UUID.randomUUID().toString(), commandId, inboxId, result.resultCode(), completed,
                        json(Map.of("simulated", bool(command, "simulated"), "detail", result.detail())));
                if (bool(command, "simulated")) devices.markDeviceRecovered(text(command, "device_id"), completed);
                devices.addEvent(UUID.randomUUID().toString(), text(command, "device_id"), "REBOOT_SUCCEEDED", "INFO",
                        bool(command, "simulated") ? "模拟重启回执已接收，设备状态恢复在线" : "设备重启回执已接收",
                        completed, bool(command, "simulated"));
                devices.advanceIncidentByCommand(commandId, "PROCESSING", "PENDING_VERIFICATION");
            }
        } else {
            devices.updateCommand(commandId, status, "FAILED", completed, result.resultCode(), result.detail());
            devices.addEvent(UUID.randomUUID().toString(), text(command, "device_id"), "REBOOT_FAILED", "ERROR",
                    (bool(command, "simulated") ? "模拟重启失败：" : "设备重启失败：") + result.detail(),
                    completed, bool(command, "simulated"));
            revertIncident(commandId);
        }
    }

    private void connect(String taskId) {
        Map<String, Object> row = commissions.findTask(taskId);
        if (row == null || !"CONNECTING".equals(text(row, "status"))) return;
        DeviceAdapterPort adapter = adapter(row);
        AdapterResult result = adapter.connect(new DeviceAdapterPort.CommissionWork(taskId, text(row, "commission_no"),
                text(row, "device_id"), text(row, "device_no"), text(row, "resolved_protocol_code"), adapterConfiguration(row)));
        long now = clock.nowMillis();
        if (commissions.completeConnect(taskId, result.success(), now, result.detail()) == 1) {
            commissions.addEvent(UUID.randomUUID().toString(), taskId,
                    result.success() ? "CONNECTED" : "UNTESTABLE", result.success() ? "INFO" : "ERROR",
                    result.detail(), now, bool(row, "simulated"));
        }
    }

    private void commission(String taskId) {
        Map<String, Object> row = commissions.findTask(taskId);
        if (row == null || !"RUNNING".equals(text(row, "status"))) return;
        DeviceAdapterPort adapter = adapter(row);
        CommissionResult result = adapter.commission(new DeviceAdapterPort.CommissionWork(taskId,
                text(row, "commission_no"), text(row, "device_id"), text(row, "device_no"),
                text(row, "resolved_protocol_code"), adapterConfiguration(row)));
        long now = clock.nowMillis();
        Map<String, Object> criteria = new LinkedHashMap<>();
        boolean simulated = bool(row, "simulated");
        criteria.put("source", simulated ? "DEVELOPMENT_SIMULATION" : text(row, "resolved_protocol_code"));
        criteria.put("confirmed", false);
        String protocol = text(row, "resolved_protocol_code");
        criteria.put("warning", simulated ? "甲方设备协议与正式调测判据尚未确认"
                : "COUNTERMEASURE_TCP_4CH_V2_0".equals(protocol)
                    ? "协议链路调测结果；不包含射频发射验证"
                    : "RADAR_TCP_V3_0_0".equals(protocol)
                        ? "协议链路调测结果；雷达待机无航迹时结论可为不可判定"
                        : "协议链路调测结果");
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("result_code", result.resultCode());
        results.put("detail", result.detail());
        results.put("items", result.items());
        String status = result.success() ? "PASSED" : result.resultCode().endsWith("UNTESTABLE") ? "UNTESTABLE" : "FAILED";
        if (commissions.completeRun(taskId, status, json(criteria), json(results), now) == 1) {
            for (DeviceAdapterPort.CommissionItem item : result.items()) {
                commissions.addEvent(UUID.randomUUID().toString(), taskId, item.code(),
                        "PASSED".equals(item.result()) ? "INFO" : "UNTESTABLE".equals(item.result()) ? "WARN" : "ERROR",
                        item.label() + "：" + item.value(), now, simulated);
            }
            commissions.addEvent(UUID.randomUUID().toString(), taskId, status,
                    result.success() ? "INFO" : "ERROR",
                    result.detail(), now, simulated);
        }
    }

    /** 异常单重启失败/超时回到待处理；成功只进入待验证，不把指令成功写成已恢复。 */
    private void revertIncident(String commandId) {
        devices.advanceIncidentByCommand(commandId, "PROCESSING", "PENDING");
    }

    private DeviceAdapterPort adapter(Map<String, Object> row) {
        return adapters.require(SourceMode.valueOf(text(row, "source_mode")), text(row, "resolved_protocol_code"));
    }

    private String adapterConfiguration(Map<String, Object> row) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            String taskJson = text(row, "configuration_json");
            Object connection = taskJson == null ? devices.findProfile(text(row, "device_id"))
                    : objectMapper.readValue(taskJson, Object.class);
            root.put("connection", connection == null ? Map.of() : connection);
            root.put("allowed_cidrs", text(row, "resolved_allowed_cidrs"));
            root.put("credential_ref", text(row, "resolved_source_credential_ref"));
            String protocolJson = text(row, "protocol_configuration_json");
            Object protocol = protocolJson == null
                    ? devices.findProtocolProfile(text(row, "device_id"), text(row, "resolved_protocol_code"))
                    : objectMapper.readValue(protocolJson, Object.class);
            root.put("protocol_configuration", protocol == null ? Map.of() : protocol);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot build device adapter configuration", ex);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize adapter result", ex); }
    }

    private static boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(status);
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
