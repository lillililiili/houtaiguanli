package com.uav.lowaltitude.modules.device.application;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
import static com.uav.lowaltitude.modules.device.application.EoTrackingPolicy.text;

@Service
public class EoTrackingStatusService {
    private final DeviceAccessPolicy access;
    private final TargetReadService targets;
    private final EoEdgeRepository edges;
    private final EoTrackingRepository repository;
    private final EoTrackingPolicy policy;
    private final EoEdgeCommandService commands;
    private final AuditService audit;
    private final AppClock clock;
    private final com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository devices;
    public EoTrackingStatusService(DeviceAccessPolicy access,TargetReadService targets,EoEdgeRepository edges,
            EoTrackingRepository repository,EoTrackingPolicy policy,EoEdgeCommandService commands,AuditService audit,AppClock clock,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository devices) {
        this.access=access;this.targets=targets;this.edges=edges;this.repository=repository;this.policy=policy;
        this.commands=commands;this.audit=audit;this.clock=clock;
        this.devices=devices;
    }
    @Transactional(readOnly=true)
    public TrackingStatus status(String target) {
        var user=access.requireDevicesRead();targets.target(target);
        return view(target,access.canOperateDevices(user));
    }
    @Transactional
    public TrackingStatus control(String target, boolean paused, String key) {
        var user=access.requireDevicesOperate();
        if(key==null || key.isBlank() || key.length()<8 || key.length()>128)
            throw new ApiException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key 必须为8至128个字符");
        edges.lockCursor();repository.lockActor(user.userId());targets.lockForTracking(target);
        String action=paused?"PAUSE":"RESUME";
        var previous=repository.request(user.userId(),key);
        if(previous!=null) {
            if(!target.equals(text(previous,"target_id"))||!action.equals(text(previous,"action")))
                throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED","该请求标识已用于其他操作");
            return view(target,true);
        }
        repository.pause(target,paused,user.userId(),clock.nowMillis());
        var task=edges.openTaskByTarget(target);
        if(paused && task==null) {
            task=edges.uncertainTaskByTarget(target);
            if(task!=null) {
                checkTaskScope(task);
                var device=edges.binding(text(task,"ops_device_id"),true);
                if(device==null || edges.openTask(device.opsDeviceId())!=null)
                    throw new ApiException(HttpStatus.CONFLICT,"EO_DEVICE_HAS_NEWER_TASK","设备存在当前任务，不能用历史任务停止它");
                edges.updateTask(text(task,"task_id"),"FAILED","OPEN",null,clock.nowMillis());
                task=edges.task(text(task,"task_id"));
            }
        }
        checkTaskScope(task);
        if(paused && task!=null && "OPEN".equals(text(task,"status"))) {
            var device=edges.binding(text(task,"ops_device_id"),true);
            if(device==null) throw new ApiException(HttpStatus.CONFLICT,"EO_DEVICE_UNAVAILABLE","设备绑定不可用，无法提交停止任务");
            commands.enqueue(device,EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,"OPERATOR_PAUSE_AUTO_TRACK",user.userId());
        }
        repository.saveRequest(user.userId(),key,target,action,clock.nowMillis());
        audit.record(user.userId(),user.account(),paused?"eo_auto_paused":"eo_auto_resumed","target",target,null,null);
        return view(target,true);
    }
    private TrackingStatus view(String target,boolean operate) {
        var snapshot=repository.snapshot(target);
        var demand=policy.demand(target);
        boolean paused=repository.paused(target);
        String block=policy.block(snapshot);
        var task=edges.latestTaskByTarget(target);
        checkTaskScope(task);
        var actions=new ArrayList<String>();
        String status, message;
        Task dto=null;
        boolean open=task!=null && Set.of("OPEN","ENDING").contains(text(task,"status"));
        if(task!=null) {
            boolean ending="ENDING".equals(text(task,"status"));
            String commandId=text(task,ending?"end_command_id":"begin_command_id");
            var command=edges.command(commandId);
            String commandStatus=command==null?"UNKNOWN":text(command,"status");
            String state=text(task,"status");
            if(ending) state=Set.of("TIMED_OUT","FAILED","CANCELLED","UNKNOWN").contains(commandStatus)?"END_UNCONFIRMED":"ENDING";
            else if("OPEN".equals(state)) {
                if(Set.of("TIMED_OUT","UNKNOWN").contains(commandStatus)) state="LOST";
                else if("SUCCEEDED".equals(commandStatus)) {
                    var binding=edges.binding(text(task,"ops_device_id"),false);
                    boolean current=binding!=null && binding.enabled() && binding.lastHeartbeatAt()!=null
                            && binding.lastHeartbeatAt()>=policy.heartbeatCutoff() && binding.lastHeartbeatAt()<=policy.now()
                            && binding.sourceMode().equals(EoTrackingPolicy.mode(snapshot));
                    state=block==null && current && edges.freshTrackingReport(task,policy.cutoff(),policy.now())?"TRACKING":"LOST";
                }
                else state="STARTING";
            }
            dto=new Task(text(task,"task_id"),state,text(task,"origin"),text(task,"ops_device_id"),
                    repository.deviceName(text(task,"ops_device_id")),commandStatus);
        }
        if(open) {
            status=dto.status();
            message=switch(status) {
                case "TRACKING" -> "已收到跟踪回执；视频可用性独立显示";
                case "ENDING" -> "停止指令处理中，设备继续保留占用";
                case "END_UNCONFIRMED" -> commands.unconfirmedStopMessage(task);
                case "LOST" -> "目标位置或执行结果未知，不代表飞离，不自动重试";
                default -> "跟踪指令已提交，等待设备回执";
            };
        } else if("EO_RESULT_UNKNOWN".equals(block)) {status="LOST";message=blockMessage(block);}
        else if(paused) {status="PAUSED";message="此目标已暂停自动追踪";}
        else if(block!=null) {status="EO_RESULT_UNKNOWN".equals(block)?"LOST":"BLOCKED";message=blockMessage(block);}
        else if(task!=null && "FAILED".equals(text(task,"status"))) {status="FAILED";message="上次跟踪明确失败，需要人工决定是否重试";}
        else if(!policy.enabledFor(snapshot)) {status="DISABLED";message="当前来源模式的自动追踪未启用";}
        else if(!demand.isEmpty()) {status="WAITING_DEVICE";message="存在观察需求，等待后台调度可用设备";}
        else {status=task==null?"IDLE":"ENDED";message="当前没有自动观察需求";}
        if(operate) {
            if(paused) actions.add("RESUME"); else actions.add("PAUSE");
            if(paused && open && "OPEN".equals(text(task,"status"))) actions.add("PAUSE");
            if(paused && !open && "EO_RESULT_UNKNOWN".equals(block)) actions.add("PAUSE");
            if(!open && block==null) {
                var b=edges.idleDeviceForMode(text(snapshot,"owner_org_id"),text(snapshot,"district_id"),null,EoTrackingPolicy.mode(snapshot),policy.heartbeatCutoff(),policy.now());
                var user=com.uav.lowaltitude.platform.security.AuthContext.require();
                if(policy.deviceReady(b) && devices.canDeleteInScope(b.opsDeviceId(),user.userId(),user.scopeMode())) actions.add("FAILED".equals(status)?"RETRY":"BEGIN");
                else message=message+"；当前范围没有可用空闲光电设备";
            }
        }
        return new TrackingStatus(target,status,message,policy.enabledFor(snapshot),paused,demand,dto,List.copyOf(actions));
    }
    public static String blockMessage(String block) {
        return switch(block) {
            case "TARGET_POSITION_UNAVAILABLE" -> "当前没有可用位置，不能引导光电";
            case "TARGET_POSITION_STALE" -> "目标位置已过期或轨迹不可用，不能以失联推断飞离";
            case "EO_CLASS_UNSUPPORTED" -> "当前目标类别缺少光电引导协议，不能猜测为无人机";
            case "EO_RESULT_UNKNOWN" -> "历史指令曾下发但结果未知，保留设备占用；请暂停并核查停止回执";
            default -> "目标数据模式或范围不可用";
        };
    }
    private void checkTaskScope(Map<String,Object> task) {
        if(task==null) return;
        var user=com.uav.lowaltitude.platform.security.AuthContext.require();
        if(!devices.canDeleteInScope(text(task,"ops_device_id"),user.userId(),user.scopeMode()))
            throw new ApiException(HttpStatus.FORBIDDEN,"DEVICE_SCOPE_FORBIDDEN","当前账号无权查看或操作关联光电设备");
    }
    public record TrackingStatus(String targetId,String status,String message,boolean autoEnabled,boolean autoPaused,
            List<EoTrackingPolicy.DemandReason> demandReasons,Task task,List<String> allowedActions) { }
    public record Task(String taskId,String status,String origin,String deviceId,String deviceName,String commandStatus) { }
}
