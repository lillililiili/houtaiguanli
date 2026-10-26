package com.uav.lowaltitude.modules.device.application;

import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.api.TargetVideoController.TargetVideoDto;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.EoTrackingContinuity;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository;
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
    private final EoFusionObservationSummaryService fusionObservations;
    private final EoTrackingRepository trackingRepository;

    public TargetVideoService(DeviceAccessPolicy access, TargetReadService targets, EoEdgeRepository edges,
                              DeviceRepository devices, ObjectMapper mapper, AppClock clock,
                              VideoStreamRegistry streams, VideoMediaClient media,
                              @org.springframework.beans.factory.annotation.Value("${app.eo-edge.position-max-age-millis:15000}") long reportMaxAge,
                              @org.springframework.beans.factory.annotation.Value("${app.eo-edge.heartbeat-timeout-millis:30000}") long heartbeatMaxAge,
                              EoFusionObservationSummaryService fusionObservations, EoTrackingRepository trackingRepository) {
        this.access = access; this.targets = targets; this.edges = edges;
        this.devices = devices; this.mapper = mapper; this.clock = clock;
        this.streams = streams; this.media = media;
        this.reportMaxAge=reportMaxAge;
        this.heartbeatMaxAge=heartbeatMaxAge;
        this.fusionObservations=fusionObservations;
        this.trackingRepository=trackingRepository;
    }

    public TargetVideoDto video(String targetId) {
        TargetVideoDto tracking = tracking(targetId);
        if (!"TRACKING".equals(tracking.status())) return withObservationSummary(tracking);
        var stream = streams.find(tracking.taskId());
        if (stream == null) return withObservationSummary(tracking);
        boolean ready = media.ready(stream.streamPath());
        if (ready) streams.observedReady(tracking.taskId());
        return withObservationSummary(new TargetVideoDto(targetId, tracking.taskId(), tracking.deviceId(), tracking.commandId(),
                clock.nowMillis(), tracking.status(), ready ? "HLS" : "NONE", true,
                ready ? "测试视频已接入，不代表现场画面。" : "等待测试视频推流，或视频流已中断。",
                ready ? "AVAILABLE" : stream.observedReady() ? "INTERRUPTED" : "WAITING", stream.sourceMode(), stream.streamId(),
                ready ? "/api/v1/targets/" + targetId + "/video/streams/" + stream.streamId() + "/index.m3u8" : null));
    }

    /** Summary is attached only to the already authorized JSON read, never to media segments. */
    private TargetVideoDto withObservationSummary(TargetVideoDto video) {
        var task = video.taskId() == null ? null : edges.task(video.taskId());
        return video.withEoFusionObservation(fusionObservations.current(task));
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

    /**
     * 系统自动取证（告警核实属实、反制开始）用：没有操作人，按当前任务、来源、真实成功回执和持续跟踪事实核定视频流。
     * 返回 null 表示当时没有可截取的画面，调用方如实记录，不拿别的画面代替。
     */
    public AutoCaptureSource autoCaptureSource(String targetId) {
        Map<String, Object> task = edges.latestTaskByTarget(targetId);
        if (task == null || !"OPEN".equals(text(task, "status"))) return null;
        Map<String, Object> target = trackingRepository.snapshot(targetId);
        if (target == null) return null;
        Binding binding = edges.binding(text(task, "ops_device_id"), false);
        Map<String, Object> command = devices.findCommand(text(task, "begin_command_id"));
        String block = EoTrackingContinuity.block(new EoTrackingContinuity.Target(text(target, "target_id"),
                text(target, "source_mode"), text(target, "owner_org_id"), text(target, "district_id"),
                text(target, "object_type_code"), text(target, "track_status")),
                task, binding, command, clock.nowMillis(), reportMaxAge, heartbeatMaxAge);
        if (block != null || devices.commandReceipts(text(command, "command_id")).stream()
                .noneMatch(row -> validReceipt(row, task, binding))) return null;
        var stream = streams.find(text(task, "task_id"));
        if (stream == null || !stream.taskId().equals(text(task, "task_id")) || !stream.targetId().equals(targetId)
                || !stream.deviceId().equals(text(task, "ops_device_id")) || !Objects.equals(stream.sourceMode(), binding.sourceMode())
                || !media.ready(stream.streamPath())) return null;
        return new AutoCaptureSource(new CaptureSource(targetId, stream.taskId(), stream.deviceId(), stream.streamId(),
                stream.sourceMode()), media.rtspReadUrl(stream.streamPath()));
    }

    /** readUrl 带只读凭据，只交给本机取证进程，不能返回给页面或写进日志。 */
    public record AutoCaptureSource(CaptureSource source, String readUrl) {
        @Override public String toString() { return "AutoCaptureSource[" + source + "]"; }
    }

    /**
     * 看画面只是读取（2026-10-06，OBS-03 用户确认值班员要能看光电画面）：与 eo-tracking-status 一样要设备查看权限、
     * 目标读取资格和设备范围；发起、暂停、结束跟踪等控制仍要 devices.op。
     */
    TargetVideoDto tracking(String targetId) {
        var user = access.requireDevicesRead();
        var target = targets.target(targetId);
        Map<String, Object> task = edges.latestTaskByTarget(targetId);
        if (task == null) return result(targetId, null, "NO_TASK", noTaskReason(target.ownerOrgId(), target.districtId()));
        String deviceId = text(task, "ops_device_id");
        if (!devices.canDeleteInScope(deviceId, user.userId(), user.scopeMode()))
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_SCOPE_FORBIDDEN", "当前账号无权查看关联光电设备。");
        String taskStatus = text(task, "status");
        if (!"OPEN".equals(taskStatus)) return switch (taskStatus == null ? "" : taskStatus) {
            case "ENDING" -> result(targetId, task, "ENDING", "正在结束光电跟踪。");
            case "FAILED" -> result(targetId, task, "FAILED", "光电跟踪执行失败。");
            default -> result(targetId, task, "ENDED", endedReason(task));
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
        if(receipt) {
            String block=EoTrackingContinuity.block(new EoTrackingContinuity.Target(target.targetId(),target.sourceMode(),
                    target.ownerOrgId(),target.districtId(),target.objectTypeCode(),target.trackStatus()==null?null:target.trackStatus().status()),
                    task,binding,command,clock.nowMillis(),reportMaxAge,heartbeatMaxAge);
            if(block!=null) return result(targetId,task,"LOST",trackingBlockReason(block));
        }
        return receipt
                ? result(targetId, task, "TRACKING", pendingVideoReason(binding))
                : result(targetId, task, "RECEIPT_UNAVAILABLE", "尚未取得当前跟踪任务的有效设备回执。");
    }

    private static String trackingBlockReason(String code) {
        return switch(code) {
            case "EO_REPORT_STALE" -> "当前光电任务的跟踪回报已过期，画面暂不可用；不代表目标飞离。";
            case "EO_HEARTBEAT_STALE" -> "光电设备心跳已过期或设备异常，跟踪状态待确认。";
            case "EO_TARGET_CHANGED", "EO_CLASS_UNSUPPORTED" -> "目标关联或类别已变化，原跟踪任务的画面不可继续使用。";
            default -> "当前光电任务、设备或成功回执无法确认，画面暂不可用。";
        };
    }

    private String endedReason(Map<String,Object> task) {
        var stop=edges.command(text(task,"end_command_id"));
        boolean matched=stop!=null && "EO_END_TRACK".equals(text(stop,"command_type"))
                && Objects.equals(text(task,"ops_device_id"),text(stop,"device_id"));
        String code=matched?Objects.toString(stop.get("reason"),""):"";
        String cause=switch(code) {
            case "OPERATOR_PAUSE_AUTO_TRACK", "AUTO_TRACK_STOP:OPERATOR_PAUSE_AUTO_TRACK" -> "操作员暂停自动追踪";
            case "AUTO_OBSERVATION_DEMAND_CLEARED" -> "已无自动观察需求";
            case "EO_DISPATCH_SWITCH" -> "光电已调度至其他目标";
            case "AUTO_TRACK_STOP:EO_REPORT_STALE" -> "融合位置不可用，且当前光电跟踪回报已过期";
            case "AUTO_TRACK_STOP:EO_HEARTBEAT_STALE" -> "融合位置不可用，且光电设备心跳过期或异常";
            case "AUTO_TRACK_STOP:EO_TARGET_CHANGED" -> "目标已合并或分裂，原任务不再适用";
            case "AUTO_TRACK_STOP:EO_CLASS_UNSUPPORTED" -> "当前目标类别不支持继续光电跟踪";
            case "AUTO_TRACK_STOP:EO_DEVICE_UNAVAILABLE" -> "目标与光电设备的来源或所属范围不可用";
            case "AUTO_TRACK_STOP:EO_RECEIPT_UNAVAILABLE", "AUTO_TRACK_STOP:EO_RESULT_UNKNOWN" -> "当前跟踪任务缺少可确认的成功回执";
            case "AUTO_OBSERVATION_CLEARED" -> "当时自动跟踪条件不满足，旧记录未细分具体原因";
            case "AUTO_STOP_RECOVERY" -> "停止指令重试后已确认结束";
            default -> matched && stop.get("requested_by")!=null?"操作员结束跟踪":"未记录具体结束原因";
        };
        String time=task.get("ended_at") instanceof Number n
                ?java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .format(java.time.Instant.ofEpochMilli(n.longValue())):null;
        return "光电跟踪已结束"+(time==null?"":"（北京时间 "+time+"）")+"："+cause+"。暂无当前画面。";
    }

    /** 没有跟踪任务时把光电本身的状况一起说清：没有光电、光电都不在线，还是光电在线只是没在跟这个目标（ZT-18）。 */
    private String noTaskReason(String orgId, String districtId) {
        DeviceRepository.EoAvailability eo = devices.eoAvailability(orgId, districtId);
        if (eo.total() == 0) return "当前目标没有光电跟踪任务：目标所在区域没有光电设备，暂无画面。";
        if (eo.online() == 0)
            return "当前目标没有光电跟踪任务：目标所在区域的光电设备都不在线（共 " + eo.total() + " 台，停用、离线、异常或状态未知），暂无画面。";
        return "当前目标没有光电跟踪任务，暂无可查看画面。";
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
