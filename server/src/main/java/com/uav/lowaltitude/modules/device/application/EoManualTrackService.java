package com.uav.lowaltitude.modules.device.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LocationDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetDetailDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetStateDto;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;

@Service
public class EoManualTrackService {
    public static final String BEGIN_REASON = "OPERATOR_BEGIN_TRACK";
    public static final String END_REASON = "OPERATOR_END_TRACK";

    private final DeviceAccessPolicy access;
    private final TargetReadService targets;
    private final EoEdgeRepository edges;
    private final EoEdgeCommandService commands;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;

    public EoManualTrackService(DeviceAccessPolicy access, TargetReadService targets, EoEdgeRepository edges,
                                EoEdgeCommandService commands, IdempotencyGuard idempotency, AuditService audit) {
        this.access = access;
        this.targets = targets;
        this.edges = edges;
        this.commands = commands;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional
    public EoTrackingTask begin(String targetId, String deviceId, String reason, String idempotencyKey) {
        AuthUser user = access.requireDevicesOperate();
        String id = pathId(targetId);
        idempotency.claim(idempotencyKey, "eo-track-begin:" + id + ":" + blank(deviceId) + ":" + blank(reason));
        TargetDetailDto target = targets.lockForTracking(id);
        TargetStateDto state = target.latestState();
        LocationDto location = state == null ? null : state.location();
        if (location == null || location.longitude() == null || location.latitude() == null)
            throw unprocessable("TARGET_POSITION_UNAVAILABLE", "目标没有可用经纬度，无法引导光电跟踪");
        if (edges.targetHasOpenTask(id))
            throw new ApiException(HttpStatus.CONFLICT, "TRACK_ALREADY_OPEN", "该目标已有进行中的光电跟踪任务");
        if (target.ownerOrgId() == null || target.districtId() == null)
            throw unprocessable("EO_DEVICE_UNAVAILABLE", "目标没有组织区域，无法匹配空闲光电");
        Binding device = pickDevice(blank(deviceId), target);
        if (device == null) throw unprocessable("EO_DEVICE_UNAVAILABLE", "当前范围没有空闲光电");
        edges.binding(device.opsDeviceId(), true);
        // Another target may have claimed this device since the availability read.
        device = pickDevice(device.opsDeviceId(), target);
        String notes = notes(state, target.objectTypeCode());
        Map<String, Object> bootstrap = bootstrap(id, target.objectTypeCode(), state, location);
        String commandId = commands.enqueueBegin(device, UUID.randomUUID().toString(), id, null, notes, bootstrap,
                user.userId(), BEGIN_REASON);
        Map<String, Object> task = edges.taskByBegin(commandId);
        audit.record(user.userId(), user.account(), "eo_track_requested", "eo_tracking_task",
                text(task, "task_id"), blank(reason), null);
        return dto(task, commandId);
    }

    @Transactional(readOnly = true)
    public EoTrackingTask current(String targetId) {
        access.requireDevicesOperate();
        String id = pathId(targetId);
        targets.target(id);
        Map<String, Object> task = edges.openTaskByTarget(id);
        if (task == null) return null;
        return dto(task, text(task, "begin_command_id"));
    }

    @Transactional(readOnly = true)
    public EoTrackingAvailability availability(String targetId) {
        access.requireDevicesOperate();
        String id = pathId(targetId);
        TargetDetailDto target = targets.target(id);
        LocationDto location = target.latestState() == null ? null : target.latestState().location();
        if (location == null || location.longitude() == null || location.latitude() == null)
            return new EoTrackingAvailability(false, "目标位置尚未提供，无法引导光电追踪");
        if (edges.targetHasOpenTask(id))
            return new EoTrackingAvailability(false, "已有跟踪任务，无需重复发起");
        if (target.ownerOrgId() == null || target.districtId() == null)
            return new EoTrackingAvailability(false, "目标所属范围尚未提供，无法匹配光电设备");
        Binding device = pickDevice(null, target);
        return new EoTrackingAvailability(device != null, device == null ? "当前范围无空闲可追踪设备" : null);
    }

    @Transactional
    public EoTrackingTask end(String taskId, String idempotencyKey) {
        AuthUser user = access.requireDevicesOperate();
        String id = pathId(taskId);
        idempotency.claim(idempotencyKey, "eo-track-end:" + id);
        Map<String, Object> task = edges.task(id);
        if (task == null) throw new ApiException(HttpStatus.NOT_FOUND, "EO_TRACK_NOT_FOUND", "跟踪任务不存在");
        String status = text(task, "status");
        if (!"OPEN".equals(status) && !"ENDING".equals(status))
            throw new ApiException(HttpStatus.CONFLICT, "TRACK_NOT_OPEN", "跟踪任务已结束");
        String targetId = text(task, "target_id");
        if (targetId != null && !targetId.isBlank()) targets.target(targetId);
        Binding device = edges.binding(text(task, "ops_device_id"), true);
        if (device == null) throw unprocessable("EO_DEVICE_UNAVAILABLE", "光电设备不可用");
        String commandId = commands.enqueue(device, EoEdgeCommandService.END, EoEdgeCommandService.TOPIC_END,
                END_REASON, user.userId());
        audit.record(user.userId(), user.account(), "eo_track_ended", "eo_tracking_task", id, null, null);
        Map<String, Object> updated = edges.task(id);
        return dto(updated, commandId);
    }

    private Binding pickDevice(String deviceId, TargetDetailDto target) {
        String org = target.ownerOrgId(), district = target.districtId();
        // Mock/replay observations must never steer live equipment (and vice versa).
        String mode = switch (target.sourceMode() == null ? "" : target.sourceMode()) {
            case "mock", "replay" -> "replay";
            case "live" -> "live";
            default -> null;
        };
        if (mode == null) return null;
        Binding specified = edges.idleDeviceForMode(org, district, deviceId, mode);
        if (specified != null || deviceId == null) return specified;
        Binding existing = edges.binding(deviceId, false);
        if (existing == null || !org.equals(existing.ownerOrgId()) || !district.equals(existing.districtId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "光电设备不存在");
        throw unprocessable("EO_DEVICE_UNAVAILABLE", "指定光电正忙、未空闲或与目标数据模式不匹配");
    }

    private static Map<String, Object> bootstrap(String targetId, String classCode, TargetStateDto state,
                                                 LocationDto location) {
        boolean uav = "UAV".equals(classCode);
        boolean bird = "BIRD".equals(classCode);
        Map<String, Object> objectData = new LinkedHashMap<>();
        objectData.put("latitude", location.latitude().doubleValue());
        objectData.put("longitude", location.longitude().doubleValue());
        objectData.put("altitude", number(state == null ? null : state.altitudeAmslM(), 0d));
        double[] ned = ned(state);
        objectData.put("speedX", ned[0]);
        objectData.put("speedY", ned[1]);
        objectData.put("speedZ", 0d);
        objectData.put("dataId", targetId);
        objectData.put("length", 0d);
        objectData.put("width", 0d);
        objectData.put("height", 0d);
        objectData.put("objectType", bird && !uav ? 40 : 30);
        objectData.put("probability", number(state == null ? null : state.classificationConfidence(), 0d));
        Map<String, Object> aiData = new LinkedHashMap<>();
        aiData.put("className", bird && !uav ? "bird" : "drone");
        aiData.put("isDetect", 1);
        aiData.put("isTrack", 1);
        Map<String, Object> extention = new LinkedHashMap<>();
        extention.put("mode", "operator");
        extention.put("bootstrapSourceId", targetId);
        extention.put("bootstrapSourceType", 0);
        extention.put("msgId", UUID.randomUUID().toString());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objectData", objectData);
        root.put("aiData", aiData);
        root.put("extention", extention);
        return root;
    }

    private static String notes(TargetStateDto state, String classCode) {
        StringBuilder notes = new StringBuilder();
        if (state == null || state.speedMps() == null || state.headingDeg() == null)
            notes.append("HORIZONTAL_SPEED_NOT_PROVIDED ");
        if (state == null || state.altitudeAmslM() == null) notes.append("ALTITUDE_NOT_PROVIDED ");
        if (!"UAV".equals(classCode) && !"BIRD".equals(classCode)) notes.append("CLASS_UNCONFIRMED ");
        notes.append("VERTICAL_SPEED_NOT_PROVIDED SIZE_NOT_PROVIDED");
        return notes.toString().trim();
    }

    private static double[] ned(TargetStateDto state) {
        if (state == null || state.speedMps() == null || state.headingDeg() == null) return new double[] {0d, 0d};
        double speed = state.speedMps().doubleValue();
        double rad = Math.toRadians(state.headingDeg().doubleValue());
        return new double[] {speed * Math.cos(rad), speed * Math.sin(rad)};
    }

    private static EoTrackingTask dto(Map<String, Object> task, String commandId) {
        return new EoTrackingTask(text(task, "task_id"), text(task, "target_id"), text(task, "ops_device_id"),
                commandId, text(task, "status"));
    }

    private static String pathId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 36)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return normalized;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double number(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public record EoTrackingAvailability(boolean available, String blockReason) { }

    public record EoTrackingTask(String taskId, String targetId, String deviceId, String commandId, String status) { }
}
