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
    public record BatchFrameInput(Long frameTimestamp,Long mediaTimeMs,List<DetectionInput> detections) { }
    public record BatchInput(String streamId,String taskId,List<BatchFrameInput> frames) { }
    public record FrameResult(int index,Long frameTimestamp,Long mediaTimeMs,boolean accepted,String errorCode,
            String reason,List<VideoStreamRegistry.Detection> detections) { }
    public record BatchResult(String streamId,String taskId,int acceptedCount,int rejectedCount,List<FrameResult> frames) { }
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
    private final EoTrackingPolicy trackingPolicy;
    public QaDeviceVideoService(DeviceAccessPolicy access,DeviceVideoService video,VideoStreamRegistry streams,EoEdgeRepository edges,
            TargetVideoService targets,VideoDetectionAssociationService associations,AuditService audit,AppClock clock,
            com.uav.lowaltitude.modules.target.application.TargetReadService targetReads,EoTrackingPolicy trackingPolicy) {
        this.access=access;this.video=video;this.streams=streams;this.edges=edges;this.targets=targets;
        this.associations=associations;this.audit=audit;this.clock=clock;this.targetReads=targetReads;this.trackingPolicy=trackingPolicy;
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
        validateFrame(input);
        var stream=current(deviceId,input.taskId(),input.streamId());
        var frame=buildFrame(input,(id,time)->associations.resolve(stream,id,time));
        streams.appendFrame(stream,frame);return frame;
    }
    public synchronized BatchResult batch(String deviceId,BatchInput input) {
        if(input==null || input.streamId()==null || input.taskId()==null || input.frames()==null
                || input.frames().isEmpty() || input.frames().size()>30)throw invalid();
        var stream=current(deviceId,input.taskId(),input.streamId());
        var initialBinding=edges.binding(deviceId,false);
        if(initialBinding==null)throw conflict();
        String source=sourceIdentity(initialBinding);
        // Snapshot the expensive association query once, without extending its validity.
        var candidates=associations.current(stream);
        var results=new ArrayList<FrameResult>();int accepted=0;ApiException terminal=null;
        for(int index=0;index<input.frames().size();index++) {
            var item=input.frames().get(index);
            Long timestamp=item==null?null:item.frameTimestamp(),media=item==null?null:item.mediaTimeMs();
            try {
                if(terminal!=null)throw terminal;
                current(deviceId,input.taskId(),input.streamId());
                if(!sameCurrentTask(deviceId,stream,source))throw conflict();
                var frameInput=item==null?null:new FrameInput(input.streamId(),input.taskId(),timestamp,media,item.detections());
                validateFrame(frameInput);
                var frame=buildFrame(frameInput,(id,time)->associations.resolve(candidates,id,time));
                streams.appendBatchFrame(stream,frame);
                results.add(new FrameResult(index,timestamp,media,true,null,null,frame.detections()));accepted++;
            } catch(ApiException rejected) {
                if(!rejected.getStatus().is4xxClientError())throw rejected;
                if(!rejected.getCode().startsWith("VIDEO_FRAME_"))terminal=rejected;
                results.add(new FrameResult(index,timestamp,media,false,rejected.getCode(),rejected.getMessage(),List.of()));
            }
        }
        return new BatchResult(input.streamId(),input.taskId(),accepted,results.size()-accepted,List.copyOf(results));
    }
    private boolean sameCurrentTask(String deviceId,VideoStreamRegistry.Stream stream,String source) {
        var task=edges.currentTaskByDevice(deviceId);var binding=edges.binding(deviceId,false);
        return task!=null && "OPEN".equals(task.get("status")) && Objects.equals(stream.taskId(),task.get("task_id"))
                && Objects.equals(stream.targetId(),task.get("target_id")) && binding!=null && binding.enabled()
                && Objects.equals(stream.sourceMode(),binding.sourceMode())
                && !trackingPolicy.taskIdentityChanged(task)
                && Objects.equals(source,sourceIdentity(binding));
    }
    private static void validateFrame(FrameInput input) {
        if(input==null || input.taskId()==null || input.streamId()==null || input.frameTimestamp()==null || input.mediaTimeMs()==null
                || input.detections()==null || input.detections().size()>32)throw invalid();
    }
    private VideoStreamRegistry.Frame buildFrame(FrameInput input,java.util.function.BiFunction<String,Long,VideoDetectionAssociationService.Association> resolver) {
        List<VideoStreamRegistry.Detection> detections=new ArrayList<>();var ids=new HashSet<String>();var observations=new HashSet<String>();
        for(var detection:input.detections()) {
            if(detection==null || detection.detectionId()==null || !detection.detectionId().matches("[A-Za-z0-9_.:-]{1,100}")
                    || !ids.add(detection.detectionId()) || !unit(detection.x()) || !unit(detection.y())
                    || !unit(detection.width()) || !unit(detection.height()) || detection.width()<=0 || detection.height()<=0
                    || detection.x()+detection.width()>1.00000001 || detection.y()+detection.height()>1.00000001
                    || detection.confidence()!=null && !unit(detection.confidence())
                    || detection.classCode()!=null && !detection.classCode().matches("[A-Z0-9_]{1,40}"))throw invalid();
            var linked=resolver.apply(detection.observationId(),input.frameTimestamp());
            if(linked!=null)try {targetReads.target(linked.targetId());} catch(ApiException ex) {
                if(ex.getStatus()!=HttpStatus.FORBIDDEN && ex.getStatus()!=HttpStatus.NOT_FOUND)throw ex;linked=null;
            }
            if(linked!=null && !observations.add(linked.observationId()))throw invalid();
            detections.add(new VideoStreamRegistry.Detection(detection.detectionId(),new VideoStreamRegistry.Box(detection.x(),detection.y(),detection.width(),detection.height()),
                    detection.classCode(),detection.confidence(),linked==null?"UNLINKED":"LINKED",linked==null?null:linked.targetId(),
                    linked==null?null:linked.associationId(),linked==null?null:linked.observationId()));
        }
        return new VideoStreamRegistry.Frame(input.frameTimestamp(),input.mediaTimeMs(),List.copyOf(detections));
    }
    private static boolean unit(Double value){return value!=null && Double.isFinite(value) && value>=0 && value<=1;}
    static String sourceIdentity(com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding binding) {
        return java.util.Arrays.asList(binding.opsSourceId(),binding.sourceId(),binding.deviceId(),binding.edgeId(),
                binding.externalDeviceId(),binding.brokerId(),binding.ownerOrgId(),binding.districtId()).toString();
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VIDEO_FRAME_INVALID","视频帧、检测标识或归一化边界无效");}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"VIDEO_TASK_MISMATCH","仅允许为来源一致的当前模拟光电设备和已确认任务接入测试视频");}
}
