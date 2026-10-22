package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.VideoMediaClient;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** Device picture selection is a read: it never schedules a tracking or a media command. */
@Service
public class DeviceVideoService {
    public record StreamView(String streamId,String streamKind,String videoStatus,String playbackUrl) { }
    public record Metadata(String status,String deviceId,String streamId,String taskId,List<VideoStreamRegistry.Frame> frames) { }
    public record Video(String deviceId,long checkedAt,String status,String playbackType,String streamKind,
            String streamId,String playbackUrl,String taskId,String targetId,String sourceMode,boolean simulated,
            String reason,StreamView rawStream,StreamView aiStream,Metadata metadata) { }
    private final DeviceAccessPolicy access;
    private final DeviceRepository devices;
    private final EoEdgeRepository edges;
    private final TargetVideoService tracking;
    private final VideoStreamRegistry streams;
    private final VideoMediaClient media;
    private final AppClock clock;
    private final TargetReadService targets;
    private final VideoDetectionAssociationService associations;
    public DeviceVideoService(DeviceAccessPolicy access,DeviceRepository devices,EoEdgeRepository edges,
            TargetVideoService tracking,VideoStreamRegistry streams,VideoMediaClient media,AppClock clock,
            TargetReadService targets,VideoDetectionAssociationService associations) {
        this.access=access;this.devices=devices;this.edges=edges;this.tracking=tracking;this.streams=streams;
        this.media=media;this.clock=clock;this.targets=targets;this.associations=associations;
    }
    public Video video(String deviceId) {
        return video(deviceId,true);
    }
    private Video video(String deviceId,boolean includeMetadata) {
        var device=authorize(deviceId);String mode=text(device,"source_mode");
        var binding=edges.binding(deviceId,false);
        boolean simulated=Boolean.TRUE.equals(device.get("simulated"));
        boolean qaDevice=simulated && ("mock".equals(mode) || "replay".equals(mode));
        boolean usable=Boolean.TRUE.equals(device.get("enabled")) && "ONLINE".equals(text(device,"connectivity"))
                && Boolean.TRUE.equals(device.get("source_enabled")) && binding!=null && binding.enabled()
                && Objects.equals(binding.opsSourceId(),device.get("source_id")) && Objects.equals(binding.sourceMode(),mode);
        var raw=usable && qaDevice?streams.findRaw(deviceId):null;
        if(raw!=null && !Objects.equals(raw.sourceIdentity(),QaDeviceVideoService.sourceIdentity(binding)))raw=null;
        var rawView=view(deviceId,raw);
        var task=usable?edges.currentTaskByDevice(deviceId):null;
        String taskId=text(task,"task_id"),targetId=text(task,"target_id");
        String reason=!usable?"光电设备未启用、离线或接入绑定不可用。":"当前没有已确认的有效跟踪任务，显示设备原始画面。";
        VideoStreamRegistry.Stream ai=null;
        if(task!=null) {
            try {
                var state=tracking.tracking(targetId);
                if("TRACKING".equals(state.status()) && Objects.equals(deviceId,state.deviceId())
                        && Objects.equals(taskId,state.taskId()) && qaDevice)ai=streams.find(taskId);
                reason=ai==null?state.reason():"AI 视频尚未就绪或已中断，显示同设备原始画面。";
            } catch(ApiException error) {
                if(error.getStatus()!=HttpStatus.FORBIDDEN && error.getStatus()!=HttpStatus.NOT_FOUND)throw error;
                // A device viewer without target scope may still see its RAW feed, never the target/task identity.
                taskId=null;targetId=null;reason="当前跟踪目标不可访问，显示设备原始画面。";
            }
        }
        var aiView=view(deviceId,ai);
        var selected="AVAILABLE".equals(aiView.videoStatus())?ai:"AVAILABLE".equals(rawView.videoStatus())?raw:null;
        String status=selected==null?"UNAVAILABLE":selected.streamKind();
        if("AI".equals(status))reason="当前任务已确认跟踪，显示 AI 识别画面；测试视频不代表现场画面。";
        else if("RAW".equals(status) && task!=null)reason=reason+" 当前显示同设备原始画面。";
        else if(selected==null && "live".equals(mode))reason="真实设备视频适配器尚未接入，当前不支持播放。";
        else if(selected==null)reason=reason+" 原始视频未接入或已中断。";
        List<VideoStreamRegistry.Frame> frames=includeMetadata && "AI".equals(status)?visibleFrames(ai):List.of();
        return new Video(deviceId,clock.nowMillis(),status,selected==null?"NONE":"HLS",selected==null?null:selected.streamKind(),
                selected==null?null:selected.streamId(),selected==null?null:url(deviceId,selected),taskId,targetId,mode,simulated,
                reason,rawView,aiView,new Metadata(frames.isEmpty()?"NONE":"CURRENT",deviceId,
                "AI".equals(status)?ai.streamId():null,"AI".equals(status)?taskId:null,frames));
    }
    private List<VideoStreamRegistry.Frame> visibleFrames(VideoStreamRegistry.Stream ai) {
        var links=new java.util.HashMap<String,VideoDetectionAssociationService.Association>();
        associations.current(ai).forEach(a->links.put(a.observationId(),a));
        var visibility=new java.util.HashMap<String,Boolean>();
        return streams.frames(ai).stream().map(frame->new VideoStreamRegistry.Frame(frame.frameTimestamp(),frame.mediaTimeMs(),
                frame.detections().stream().map(d->{
                    if(d.targetId()==null)return d;
                    var link=links.get(d.observationId());
                    boolean visible=link!=null && Objects.equals(link.targetId(),d.targetId())
                            && Objects.equals(link.associationId(),d.associationId()) && Math.abs(frame.frameTimestamp()-link.observedAt())<=2000;
                    if(visible)visible=visibility.computeIfAbsent(d.targetId(),key->{try {targets.target(key);return true;} catch(ApiException ex) {
                        if(ex.getStatus()!=HttpStatus.FORBIDDEN && ex.getStatus()!=HttpStatus.NOT_FOUND)throw ex;return false;
                    }});
                    return visible?d:new VideoStreamRegistry.Detection(d.detectionId(),d.bbox(),d.classCode(),d.confidence(),
                            "UNLINKED",null,null,null);
                }).toList())).toList();
    }
    private StreamView view(String deviceId,VideoStreamRegistry.Stream stream) {
        if(stream==null)return new StreamView(null,null,"NOT_CONFIGURED",null);
        boolean ready=media.ready(stream.streamPath());if(ready && "AI".equals(stream.streamKind()))streams.observedReady(stream.taskId());
        if(ready && "RAW".equals(stream.streamKind()))streams.observedRawReady(deviceId);
        return new StreamView(stream.streamId(),stream.streamKind(),ready?"AVAILABLE":stream.observedReady()?"INTERRUPTED":"WAITING",ready?url(deviceId,stream):null);
    }
    public byte[] resource(String deviceId,String streamId,String resource,String session) {
        VideoMediaClient.validateResource(resource);VideoMediaClient.validateSession(session);
        Video current=video(deviceId,false);
        if(!Objects.equals(streamId,current.streamId()))throw new ApiException(HttpStatus.NOT_FOUND,"VIDEO_STREAM_NOT_CURRENT","当前设备没有该视频流");
        var stream="AI".equals(current.streamKind())?streams.find(current.taskId()):streams.findRaw(deviceId);
        if(stream==null || !Objects.equals(streamId,stream.streamId()))throw new ApiException(HttpStatus.NOT_FOUND,"VIDEO_STREAM_NOT_CURRENT","当前设备视频流已变化");
        return session==null?media.resource(stream.streamPath(),resource):media.resource(stream.streamPath(),resource,session);
    }
    Map<String,Object> authorize(String deviceId) {
        var user=access.requireDevicesRead();
        if(!devices.canDeleteInScope(deviceId,user.userId(),user.scopeMode()))
            throw new ApiException(HttpStatus.FORBIDDEN,"DEVICE_SCOPE_FORBIDDEN","当前账号无权查看光电设备");
        var device=devices.find(deviceId);
        if(device==null)throw new ApiException(HttpStatus.NOT_FOUND,"DEVICE_NOT_FOUND","设备不存在");
        if(!java.util.Set.of("EO","OE").contains(Objects.toString(device.get("device_type_code"),"").toUpperCase(java.util.Locale.ROOT)))throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"VIDEO_DEVICE_UNSUPPORTED","仅光电设备支持视频");
        return device;
    }
    private static String url(String deviceId,VideoStreamRegistry.Stream stream) {return "/api/v1/devices/"+deviceId+"/video/streams/"+stream.streamId()+"/index.m3u8";}
    private static String text(Map<String,Object> row,String key){Object v=row==null?null:row.get(key);return v==null?null:v.toString();}
}
