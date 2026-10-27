package com.uav.lowaltitude.modules.device.application;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A bounded, scoped read model. Reading never requests or changes a camera task. */
@Service
public class EoSituationService {
    private final DeviceService devices;
    private final EoEdgeRepository edges;
    private final TargetReadService targets;
    private final EoTrackingPolicy policy;
    private final ObjectMapper json;
    private final AppClock clock;
    private final long maxAge;
    public EoSituationService(DeviceService devices,EoEdgeRepository edges,TargetReadService targets,
            EoTrackingPolicy policy,ObjectMapper json,AppClock clock,
            @Value("${app.eo-edge.camera-max-age-millis:30000}") long maxAge) {
        this.devices=devices;this.edges=edges;this.targets=targets;this.policy=policy;
        this.json=json;this.clock=clock;this.maxAge=maxAge;
    }
    @Transactional(readOnly=true)
    public Page read(int page,int size) {
        var visible=devices.list(new DeviceService.DeviceFilter(null,"EO",null,null,null,null,true),page,size,null);
        var evidence=visible.items().stream().map(this::evidence).toList();
        // READ_COMMITTED may see a heartbeat committed during these reads. Take
        // the common validation time after the evidence, never before it.
        long now=clock.nowMillis();
        var items=evidence.stream().map(e->item(e,now)).toList();
        return new Page(now,items,visible.page(),visible.size(),visible.total());
    }
    private Evidence evidence(DeviceService.DeviceSummary device) {
        var row=edges.cameraSnapshot(device.deviceId());
        var task=edges.openTask(device.deviceId());
        String taskId=null,target=null;
        if(task!=null) {
            try {targets.target(Objects.toString(task.get("target_id"),""));
                taskId=Objects.toString(task.get("task_id"),null);target=Objects.toString(task.get("target_id"),null);
            } catch(ApiException denied) { }
        }
        return new Evidence(device,row,task,taskId==null?null:edges.command(Objects.toString(task.get("begin_command_id"),"")),
                taskId==null?null:edges.binding(device.deviceId(),false),taskId,target,
                taskId!=null && policy.taskIdentityChanged(task));
    }
    private Item item(Evidence evidence,long now) {
        var device=evidence.device();var row=evidence.camera();var task=evidence.task();
        JsonNode camera=null;
        try {if(row!=null && row.get("camera_status_json")!=null)camera=json.readTree(row.get("camera_status_json").toString());}
        catch(Exception ignored) { }
        Camera measured=validate(camera,now,maxAge);
        String taskId=evidence.taskId(),target=evidence.targetId(),state="UNKNOWN";
        if(measured.reason()==null && camera!=null) {
            String reported=camera.path("trackingState").asText("UNKNOWN");
            if(task==null && Set.of("IDLE","STOPPED").contains(reported)) state=reported;
            if(taskId!=null && taskId.equals(camera.path("platformTaskId").asText())) {
                if(Set.of("TURNING","LOST","UNKNOWN").contains(reported))state=reported;
                var command=evidence.command();var binding=evidence.binding();
                if("TRACKING".equals(reported) && edges.freshTrackingReport(task,policy.cutoff(now),now)
                        && command!=null && "SUCCEEDED".equals(command.get("status"))
                        && binding!=null && binding.lastHeartbeatAt()!=null && binding.lastHeartbeatAt()>=policy.heartbeatCutoff(now)
                        && binding.lastHeartbeatAt()<=now && !evidence.identityChanged()
                        && "OPEN".equals(task.get("status")))state="TRACKING";
            }
        }
        return new Item(device.deviceId(),measured.observedAt(),measured.validUntil(),
                measured.reason()==null?"READY":"UNKNOWN",measured.reason(),measured.azimuth(),measured.elevation(),
                measured.hfov(),measured.vfov(),taskId,target,state,device.simulated(),device.sourceMode());
    }
    private record Evidence(DeviceService.DeviceSummary device,Map<String,Object> camera,Map<String,Object> task,
            Map<String,Object> command,com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding binding,
            String taskId,String targetId,boolean identityChanged) { }
    static Camera validate(JsonNode c,long now,long age) {
        if(c==null || !c.isObject())return unknown(null,null,"CAMERA_REPORT_MISSING");
        Long observed=c.path("platformObservedAt").isIntegralNumber()?c.path("platformObservedAt").longValue():null;
        Long until=observed==null?null:observed+age;
        if(observed==null || observed>now || observed<now-age)return unknown(observed,until,"CAMERA_REPORT_STALE");
        if(!"TRUE_NORTH".equals(c.path("angleReference").asText()) || !"DEG".equals(c.path("angleUnit").asText())
                || !c.path("calibrated").isBoolean() || !c.path("calibrated").booleanValue())
            return unknown(observed,until,"CAMERA_REFERENCE_UNKNOWN");
        Double az=number(c,"panOrientAngle"),el=number(c,"tiltOrientAngle"),hf=number(c,"hfov"),vf=number(c,"vfov");
        if(az==null || az<0 || az>=360 || el==null || el< -90 || el>90 || hf==null || hf<=0 || hf>180 || vf==null || vf<=0 || vf>180)
            return unknown(observed,until,"CAMERA_ANGLES_INVALID");
        return new Camera(observed,until,null,az,el,hf,vf);
    }
    private static Double number(JsonNode c,String field) {
        var n=c.path(field);return n.isNumber() && Double.isFinite(n.doubleValue())?n.doubleValue():null;
    }
    private static Camera unknown(Long observed,Long until,String reason) {return new Camera(observed,until,reason,null,null,null,null);}
    record Camera(Long observedAt,Long validUntil,String reason,Double azimuth,Double elevation,Double hfov,Double vfov) { }
    public record Page(long checkedAt,List<Item> items,int page,int size,long total) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Item(String deviceId,Long observedAt,Long validUntil,String status,String reason,Double azimuthDeg,
            Double elevationDeg,Double horizontalFovDeg,Double verticalFovDeg,String taskId,String targetId,
            String trackingState,boolean simulated,String sourceMode) { }
}
