package com.uav.lowaltitude.modules.device.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** Explicit local QA ingestion, separate from any future authenticated vendor media adapter. */
@Service
public class QaDeviceVideoService {
    public record DetectionInput(String detectionId,Double x,Double y,Double width,Double height,String classCode,Double confidence,String observationId) { }
    public record FrameInput(String streamId,String taskId,Long frameTimestamp,Long mediaTimeMs,List<DetectionInput> detections) { }
    public record ObservationAssociation(String observationId,long observedAt,String classCode,String targetId) { }
    private final DeviceAccessPolicy access;
    private final DeviceVideoService video;
    private final VideoStreamRegistry streams;
    private final EoEdgeRepository edges;
    private final TargetVideoService targets;
    private final VideoDetectionAssociationService associations;
    private final AuditService audit;
    private final AppClock clock;
    private final com.uav.lowaltitude.modules.target.application.TargetReadService targetReads;
    public QaDeviceVideoService(DeviceAccessPolicy access,DeviceVideoService video,VideoStreamRegistry streams,EoEdgeRepository edges,
            TargetVideoService targets,VideoDetectionAssociationService associations,AuditService audit,AppClock clock,
            com.uav.lowaltitude.modules.target.application.TargetReadService targetReads) {
        this.access=access;this.video=video;this.streams=streams;this.edges=edges;this.targets=targets;
        this.associations=associations;this.audit=audit;this.clock=clock;this.targetReads=targetReads;
    }
    private void authorize(String deviceId) {
        streams.requireEnabled();access.requireDevicesOperate();var device=video.authorize(deviceId);var binding=edges.binding(deviceId,false);
        if(binding==null || !binding.enabled() || !Boolean.TRUE.equals(device.get("enabled"))
                || !Boolean.TRUE.equals(device.get("simulated")) || !Boolean.TRUE.equals(device.get("source_enabled"))
                || !Objects.equals(binding.opsSourceId(),device.get("source_id"))
                || !Objects.equals(binding.sourceMode(),device.get("source_mode"))
                || !java.util.Set.of("mock","replay").contains(binding.sourceMode()))throw conflict();
    }
    public synchronized VideoStreamRegistry.Stream register(String deviceId) {
        authorize(deviceId);var binding=edges.binding(deviceId,false);
        return streams.registerRaw(deviceId,binding.sourceMode(),sourceIdentity(binding),()->record("qa_device_video_register",deviceId));
    }
    public synchronized void remove(String deviceId) {
        authorize(deviceId);if(streams.findRaw(deviceId)!=null){record("qa_device_video_unregister",deviceId);streams.removeRaw(deviceId);}
    }
    private void record(String action,String deviceId) {
        var user=AuthContext.require();audit.recordStandalone(user.userId(),user.account(),action,"device",deviceId,"设备原始测试视频租约（模拟画面）",null);
    }
    private VideoStreamRegistry.Stream current(String deviceId,String taskId,String streamId) {
        authorize(deviceId);var task=edges.currentTaskByDevice(deviceId);
        if(task==null || !Objects.equals(taskId,task.get("task_id")))throw conflict();
        var tracking=targets.tracking(Objects.toString(task.get("target_id"),null));
        var stream=streams.find(taskId);
        if(!"TRACKING".equals(tracking.status()) || !Objects.equals(deviceId,tracking.deviceId())
                || !Objects.equals(taskId,tracking.taskId()) || stream==null || !Objects.equals(deviceId,stream.deviceId())
                || streamId!=null && !Objects.equals(streamId,stream.streamId()))throw conflict();
        return stream;
    }
    public List<ObservationAssociation> observationAssociations(String deviceId,String taskId) {
        var stream=current(deviceId,taskId,null);long cutoff=clock.nowMillis()-5000;
        return associations.current(stream).stream().filter(a->a.observedAt()>=cutoff)
                // The current task's target was already scope-checked. Other source links are not exported by QA helpers.
                .filter(a->Objects.equals(a.targetId(),stream.targetId()))
                .map(a->new ObservationAssociation(a.observationId(),a.observedAt(),a.classCode(),a.targetId())).toList();
    }
    public synchronized VideoStreamRegistry.Frame frame(String deviceId,FrameInput input) {
        if(input==null || input.taskId()==null || input.streamId()==null || input.frameTimestamp()==null || input.mediaTimeMs()==null
                || input.detections()==null || input.detections().size()>32)throw invalid();
        var stream=current(deviceId,input.taskId(),input.streamId());
        List<VideoStreamRegistry.Detection> detections=new ArrayList<>();var ids=new HashSet<String>();var observations=new HashSet<String>();
        for(var detection:input.detections()) {
            if(detection==null || detection.detectionId()==null || !detection.detectionId().matches("[A-Za-z0-9_.:-]{1,100}")
                    || !ids.add(detection.detectionId()) || !unit(detection.x()) || !unit(detection.y())
                    || !unit(detection.width()) || !unit(detection.height()) || detection.width()<=0 || detection.height()<=0
                    || detection.x()+detection.width()>1.00000001 || detection.y()+detection.height()>1.00000001
                    || detection.confidence()!=null && !unit(detection.confidence())
                    || detection.classCode()!=null && !detection.classCode().matches("[A-Z0-9_]{1,40}"))throw invalid();
            var linked=associations.resolve(stream,detection.observationId(),input.frameTimestamp());
            if(linked!=null)try {targetReads.target(linked.targetId());} catch(ApiException ex) {
                if(ex.getStatus()!=HttpStatus.FORBIDDEN && ex.getStatus()!=HttpStatus.NOT_FOUND)throw ex;linked=null;
            }
            if(linked!=null && !observations.add(linked.observationId()))throw invalid();
            detections.add(new VideoStreamRegistry.Detection(detection.detectionId(),new VideoStreamRegistry.Box(detection.x(),detection.y(),detection.width(),detection.height()),
                    detection.classCode(),detection.confidence(),linked==null?"UNLINKED":"LINKED",linked==null?null:linked.targetId(),
                    linked==null?null:linked.associationId(),linked==null?null:linked.observationId()));
        }
        var frame=new VideoStreamRegistry.Frame(input.frameTimestamp(),input.mediaTimeMs(),List.copyOf(detections));
        streams.appendFrame(stream,frame);return frame;
    }
    private static boolean unit(Double value){return value!=null && Double.isFinite(value) && value>=0 && value<=1;}
    static String sourceIdentity(com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding binding) {
        return java.util.Arrays.asList(binding.opsSourceId(),binding.sourceId(),binding.deviceId(),binding.edgeId(),
                binding.externalDeviceId(),binding.brokerId(),binding.ownerOrgId(),binding.districtId()).toString();
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VIDEO_FRAME_INVALID","视频帧、检测标识或归一化边界无效");}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"VIDEO_TASK_MISMATCH","仅允许为来源一致的当前模拟光电设备和已确认任务接入测试视频");}
}
