package com.uav.lowaltitude.modules.device.application;

import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.api.TargetVideoController.TargetVideoDto;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.device.infrastructure.VideoMediaClient;

/** A read never starts tracking, consumes evidence, or substitutes simulation for a live stream. */
@Service
public class TargetVideoService {
    private final DeviceAccessPolicy access;
    private final TargetReadService targets;
    private final EoEdgeRepository edges;
    private final DeviceRepository devices;
    private final ObjectMapper mapper;
    private final AppClock clock;
    private final VideoStreamRegistry streams;
    private final VideoMediaClient media;
    private final long reportMaxAge;
    private final long heartbeatMaxAge;

    public TargetVideoService(DeviceAccessPolicy access, TargetReadService targets, EoEdgeRepository edges,
                              DeviceRepository devices, ObjectMapper mapper, AppClock clock,
                              VideoStreamRegistry streams, VideoMediaClient media,
                              @org.springframework.beans.factory.annotation.Value("${app.eo-edge.position-max-age-millis:15000}") long reportMaxAge,
                              @org.springframework.beans.factory.annotation.Value("${app.eo-edge.heartbeat-timeout-millis:30000}") long heartbeatMaxAge) {
        this.access = access; this.targets = targets; this.edges = edges;
        this.devices = devices; this.mapper = mapper; this.clock = clock;
        this.streams = streams; this.media = media;
        this.reportMaxAge=reportMaxAge;
        this.heartbeatMaxAge=heartbeatMaxAge;
    }

    public TargetVideoDto video(String targetId) {
        TargetVideoDto tracking = tracking(targetId);
        if (!"TRACKING".equals(tracking.status())) return tracking;
        var stream = streams.find(tracking.taskId());
        if (stream == null) return tracking;
        boolean ready = media.ready(stream.streamPath());
        if (ready) streams.observedReady(tracking.taskId());
        return new TargetVideoDto(targetId, tracking.taskId(), tracking.deviceId(), tracking.commandId(),
                clock.nowMillis(), tracking.status(), ready ? "HLS" : "NONE", true,
                ready ? "测试视频已接入，不代表现场画面。" : "等待测试视频推流，或视频流已中断。",
                ready ? "AVAILABLE" : stream.observedReady() ? "INTERRUPTED" : "WAITING", stream.sourceMode(), stream.streamId(),
                ready ? "/api/v1/targets/" + targetId + "/video/streams/" + stream.streamId() + "/index.m3u8" : null);
    }

    public byte[] resource(String targetId, String streamId, String resource) {
        return resource(targetId, streamId, resource, null);
    }
    public byte[] resource(String targetId, String streamId, String resource, String session) {
        VideoMediaClient.validateResource(resource);
        VideoMediaClient.validateSession(session);
        TargetVideoDto tracking = tracking(targetId);
        var stream = streams.find(tracking.taskId());
        if (!"TRACKING".equals(tracking.status()) || stream == null || !stream.streamId().equals(streamId)
                || !stream.targetId().equals(targetId) || !stream.deviceId().equals(tracking.deviceId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "VIDEO_STREAM_NOT_CURRENT", "当前任务没有该视频流");
        return session == null ? media.resource(stream.streamPath(), resource) : media.resource(stream.streamPath(), resource, session);
    }

    /**
     * 截图、录像只能取自当前跟踪任务正在登记的视频流：设备、任务、流都由这里核定，不信任客户端。
     * 跟踪回报刚过期（LOST）时流仍可能在播，已录下的画面仍可入库；任务结束或换了任务则拒绝。
     */
    public CaptureSource captureSource(String targetId, String streamId) {
        TargetVideoDto tracking = tracking(targetId);
        if (!"TRACKING".equals(tracking.status()) && !"LOST".equals(tracking.status()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EO_CAPTURE_NOT_TRACKING", "当前没有进行中的光电跟踪，不能截图或录像。");
        var stream = streams.find(tracking.taskId());
        if (stream == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EO_VIDEO_NOT_AVAILABLE", simulatedMode(tracking.sourceMode())
                    ? "测试视频尚未推流或已中断，暂不能截图或录像。"
                    : "现场光电视频尚未接入平台，暂不能在平台上截图或录像。");
        if (streamId == null || !stream.streamId().equals(streamId) || !stream.targetId().equals(targetId)
                || !stream.deviceId().equals(tracking.deviceId()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_STREAM_NOT_CURRENT", "画面已换成新的跟踪任务，请刷新视频后重新截图或录像。");
        return new CaptureSource(targetId, tracking.taskId(), tracking.deviceId(), stream.streamId(), stream.sourceMode());
    }

    public record CaptureSource(String targetId, String taskId, String deviceId, String streamId, String sourceMode) { }

    private TargetVideoDto tracking(String targetId) {
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
        if (binding == null || !binding.enabled() || !sameSourceDomain(target.sourceMode(), binding.sourceMode())
                || !Objects.equals(binding.sourceMode(), text(command, "source_mode"))
                || (simulatedMode(target.sourceMode()) && !Boolean.TRUE.equals(command.get("simulated"))))
            return result(targetId, task, "NOT_INTEGRATED", "当前目标、任务与设备来源不匹配，视频不可用。");
        boolean receipt = devices.commandReceipts(text(command, "command_id")).stream()
                .anyMatch(row -> validReceipt(row, task, binding));
        if(receipt && (!edges.freshTrackingReport(task,clock.nowMillis()-reportMaxAge,clock.nowMillis())
                || binding.lastHeartbeatAt()==null || binding.lastHeartbeatAt()<clock.nowMillis()-heartbeatMaxAge
                || target.latestState()==null || target.latestState().observedAt()<clock.nowMillis()-reportMaxAge))
            return result(targetId,task,"LOST","光电实时跟踪回报已过期，不代表目标飞离。");
        return receipt
                ? result(targetId, task, "TRACKING", pendingVideoReason(binding))
                : result(targetId, task, "RECEIPT_UNAVAILABLE", "尚未取得当前跟踪任务的有效设备回执。");
    }

    private static String pendingVideoReason(Binding binding) {
        return binding != null && simulatedMode(binding.sourceMode())
                ? "设备已确认跟踪，等待模拟器光电设备推送视频流。"
                : "设备已确认跟踪，现场视频源尚未配置。";
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
        Binding binding = task == null ? null : edges.binding(text(task, "ops_device_id"), false);
        String mode = binding == null ? null : binding.sourceMode();
        boolean simulated = simulatedMode(mode);
        return new TargetVideoDto(targetId, text(task, "task_id"), text(task, "ops_device_id"),
                text(task, "begin_command_id"), clock.nowMillis(), status,
                "NONE", simulated, reason, "NOT_CONFIGURED", mode, null, null);
    }
    private static boolean simulatedMode(String mode) { return "mock".equals(mode) || "replay".equals(mode); }
    static boolean sameSourceDomain(String targetMode, String deviceMode) {
        return ("live".equals(targetMode) && "live".equals(deviceMode)) || (simulatedMode(targetMode) && simulatedMode(deviceMode));
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }
}
