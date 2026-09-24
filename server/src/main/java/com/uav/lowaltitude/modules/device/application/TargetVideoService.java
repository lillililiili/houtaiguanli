package com.uav.lowaltitude.modules.device.application;

import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.api.TargetVideoController.TargetVideoDto;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** A read never starts tracking, consumes evidence, or substitutes simulation for a live stream. */
@Service
public class TargetVideoService {
    private final DeviceAccessPolicy access;
    private final TargetReadService targets;
    private final EoEdgeRepository edges;
    private final DeviceRepository devices;
    private final ObjectMapper mapper;
    private final AppClock clock;

    public TargetVideoService(DeviceAccessPolicy access, TargetReadService targets, EoEdgeRepository edges,
                              DeviceRepository devices, ObjectMapper mapper, AppClock clock) {
        this.access = access; this.targets = targets; this.edges = edges;
        this.devices = devices; this.mapper = mapper; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TargetVideoDto video(String targetId) {
        var user = access.requireDevicesOperate();
        var target = targets.target(targetId);
        Map<String, Object> task = edges.latestTaskByTarget(targetId);
        if (task == null) return result(targetId, null, "NO_TASK", "当前目标没有光电跟踪任务，暂无可查看画面。");
        String deviceId = text(task, "ops_device_id");
        if (!devices.canDeleteInScope(deviceId, user.userId(), user.scopeMode()))
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_SCOPE_FORBIDDEN", "当前账号无权查看关联光电设备。");
        String taskStatus = text(task, "status");
        if (!"OPEN".equals(taskStatus)) return switch (taskStatus == null ? "" : taskStatus) {
            case "ENDING" -> result(targetId, task, "ENDING", "正在结束光电跟踪。");
            case "FAILED" -> result(targetId, task, "FAILED", "光电跟踪执行失败。");
            default -> result(targetId, task, "ENDED", "光电跟踪已结束，暂无当前画面。");
        };
        Map<String, Object> command = devices.findCommand(text(task, "begin_command_id"));
        if (command == null || !deviceId.equals(text(command, "device_id"))
                || !"EO_BEGIN_TRACK".equals(text(command, "command_type")))
            return result(targetId, task, "RECEIPT_UNAVAILABLE", "尚未取得当前跟踪任务的有效设备回执。");
        String status = text(command, "status");
        if (!"SUCCEEDED".equals(status)) return switch (status == null ? "" : status) {
            case "QUEUED" -> result(targetId, task, "QUEUED", "跟踪指令已排队。");
            case "SENT", "ACCEPTED" -> result(targetId, task, "WAITING", "等待设备确认跟踪指令。");
            case "FAILED" -> result(targetId, task, "FAILED", "跟踪指令执行失败。");
            case "TIMED_OUT" -> result(targetId, task, "TIMED_OUT", "跟踪设备回执超时。");
            case "CANCELLED" -> result(targetId, task, "ENDED", "跟踪指令已取消。");
            default -> result(targetId, task, "RECEIPT_UNAVAILABLE", "跟踪回执状态未明确。");
        };
        Binding binding = edges.binding(deviceId, false);
        if (!simulatedMode(target.sourceMode()) || !simulatedMode(text(command, "source_mode"))
                || !Boolean.TRUE.equals(command.get("simulated")) || binding == null
                || !simulatedMode(binding.sourceMode()))
            return result(targetId, task, "NOT_INTEGRATED", "设备已确认跟踪，但实时视频流尚未接入。");
        boolean receipt = devices.commandReceipts(text(command, "command_id")).stream()
                .anyMatch(row -> validReceipt(row, task, binding));
        return receipt
                ? result(targetId, task, "AVAILABLE", "模拟跟踪已确认，可查看演示画面；不反映现场目标。")
                : result(targetId, task, "RECEIPT_UNAVAILABLE", "尚未取得当前跟踪任务的有效设备回执。");
    }

    private boolean validReceipt(Map<String, Object> receipt, Map<String, Object> task, Binding binding) {
        if (!"PROTOCOL_C".equals(text(receipt, "receipt_kind")) || !"200".equals(text(receipt, "device_result_code"))) return false;
        try {
            JsonNode payload = mapper.readTree(text(receipt, "payload"));
            if (payload == null) return false;
            JsonNode metadata = payload.path("metadata");
            return "BeginTracking".equals(payload.path("event").asText())
                    && Objects.equals(binding.edgeId(), payload.path("edgeId").asText())
                    && Objects.equals(binding.externalDeviceId(), metadata.path("deviceId").asText())
                    && Objects.equals(text(task, "task_id"), metadata.path("taskId").asText())
                    && metadata.path("codeStatus").asInt(-1) == 200;
        } catch (java.io.IOException | IllegalArgumentException ex) { return false; }
    }

    private TargetVideoDto result(String targetId, Map<String, Object> task, String status, String reason) {
        boolean simulated = "AVAILABLE".equals(status);
        return new TargetVideoDto(targetId, text(task, "task_id"), text(task, "ops_device_id"),
                text(task, "begin_command_id"), clock.nowMillis(), status,
                simulated ? "SIMULATED_CANVAS" : "NONE", simulated, reason);
    }
    private static boolean simulatedMode(String mode) { return "mock".equals(mode) || "replay".equals(mode); }
    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }
}
