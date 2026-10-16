package com.uav.lowaltitude.modules.device.application;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.infrastructure.*;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.*;
import static com.uav.lowaltitude.modules.device.application.EoTrackingPolicy.text;

/** A page read never queues a request. Switching reserves a device until its stop receipt. */
@Service
public class EoDispatchService {
    private final EoDispatchRepository queue;
    private final EoTrackingRepository tracking;
    private final EoEdgeRepository edges;
    private final EoTrackingPolicy policy;
    private final EoEdgeCommandService commands;
    private final DeviceAccessPolicy access;
    private final TargetReadService targets;
    private final UserMapper users;
    private final AuditService audit;
    private final com.uav.lowaltitude.modules.identity.application.AccessService permissions;
    private final com.uav.lowaltitude.modules.identity.application.AccessControlService targetAccess;
    public EoDispatchService(EoDispatchRepository queue,EoTrackingRepository tracking,EoEdgeRepository edges,
            EoTrackingPolicy policy,EoEdgeCommandService commands,DeviceAccessPolicy access,
            TargetReadService targets,UserMapper users,AuditService audit,
            com.uav.lowaltitude.modules.identity.application.AccessService permissions,
            com.uav.lowaltitude.modules.identity.application.AccessControlService targetAccess) {
        this.queue=queue;this.tracking=tracking;this.edges=edges;this.policy=policy;this.commands=commands;
        this.access=access;this.targets=targets;this.users=users;this.audit=audit;
        this.permissions=permissions;this.targetAccess=targetAccess;
    }
    @Transactional(readOnly=true)
    public View view(String target) {
        var user=access.requireDevicesRead();targets.target(target);
        var snapshot=tracking.snapshot(target);
        var task=edges.openTaskByTarget(target);
        var request=queue.pending(target);
        boolean operate=access.canOperateDevices(user);
        String block=policy.block(snapshot);
        var options=new ArrayList<SwitchOption>();
        var occupation=new ArrayList<String>();
        boolean protectedBusy=false,available=false;
        if(block==null && task==null) for(String id:devices(snapshot)) {
            if(!tracking.deviceScope(id,user.userId(),user.scopeMode())) continue;
            var occupied=edges.openTask(id);
            if(occupied==null) {
                if(!queue.reserved(id) && idle(snapshot,id)!=null) available=true;
                continue;
            }
            String oldTarget=text(occupied,"target_id");
            var old=tracking.snapshot(oldTarget);
            if(old!=null && permissions.canAccessTuple(text(old,"owner_org_id"),text(old,"district_id"))) {
                String state="ENDING".equals(text(occupied,"status"))?"等待停止确认"
                        :occupationState(policy.trackingBlock(old,occupied,edges.binding(id,false)));
                occupation.add(tracking.deviceName(id)+" → "+text(old,"target_no")+"（"+state+"）");
            }
            if(queue.protectedTarget(oldTarget)) { protectedBusy=true;continue; }
            if(!operate || !switchable(occupied,snapshot,id)) continue;
            if(old!=null && permissions.canAccessTuple(text(old,"owner_org_id"),text(old,"district_id")))
                options.add(new SwitchOption(text(occupied,"task_id"),id,tracking.deviceName(id),text(old,"target_no")));
        }
        String status,message;
        if(task!=null) { status="ACTIVE";message="已有光电任务，正在读取设备回执及视频状态。"; }
        else if(request!=null && "STOPPING".equals(text(request,"status"))) {
            status="SWITCHING";message="正在切换：等待原跟踪任务停止回执，确认后启动当前目标。";
            options.clear();
        } else if(block!=null) { status="BLOCKED";message=EoTrackingStatusService.blockMessage(block);options.clear(); }
        else if(request!=null) {
            status="WAITING_DEVICE";
            message=protectedBusy?"等待光电跟踪：设备正在支撑处置，当前任务不可打断。"
                    :available?"已进入跟踪队列，等待后台分配设备。":"等待光电跟踪：设备正在执行其他任务或暂无可用设备。";
            if(!available && !occupation.isEmpty()) message="等待光电跟踪。当前设备占用："+String.join("；",occupation)+"。"
                    +(protectedBusy?"处置中的观察不可被普通请求打断。":"");
        } else {
            var last=queue.latest(target);
            status="IDLE";
            message=available?"当前没有光电跟踪任务，可请求跟踪此目标。"
                    :protectedBusy?"光电正在支撑处置，可请求排队等待。":"当前没有光电跟踪任务；可请求排队，或选择允许切换的设备。";
            if(last!=null && "FAILED".equals(text(last,"status"))) {status="FAILED";message=text(last,"message");}
        }
        boolean canRequest=operate && block==null && task==null && (request==null
                || "WAITING".equals(text(request,"status")) && "AUTO".equals(text(request,"origin")));
        boolean canCancel=operate && request!=null && "MANUAL".equals(text(request,"origin"));
        return new View(target,status,message,request==null?null:text(request,"request_id"),canRequest,canCancel,
                task==null?List.copyOf(options):List.of());
    }
    private static String occupationState(String block) {
        if(block==null) return "正在跟踪";
        return switch(block) {
            case "EO_REPORT_STALE" -> "跟踪回报已过期，等待后台核查";
            case "EO_HEARTBEAT_STALE" -> "设备状态待确认";
            case "EO_RECEIPT_UNAVAILABLE" -> "跟踪回执待确认";
            default -> "跟踪条件已变化，等待后台核查";
        };
    }
    @Transactional
    public View request(String target,String expectedTask,String key) {
        var actor=access.requireDevicesOperate();checkKey(key);
        edges.lockCursor();tracking.lockActor(actor.userId());targets.lockForTracking(target);
        String action="REQUEST:"+Objects.toString(expectedTask,"");
        if(replay(actor.userId(),key,target,action)) return view(target);
        var snapshot=tracking.snapshot(target);String block=policy.block(snapshot);
        if(block!=null) throw conflict(EoTrackingStatusService.blockMessage(block));
        if(edges.targetHasOpenTask(target)) throw conflict("该目标已有跟踪任务，请刷新视频状态。");
        var req=queue.pending(target);
        if(req!=null && "STOPPING".equals(text(req,"status"))) throw conflict("当前目标已经在切换，等待设备回执。");
        if(req!=null && "MANUAL".equals(text(req,"origin")) && !actor.userId().equals(text(req,"requested_by")))
            throw conflict("其他操作员已为此目标请求跟踪，请等待当前请求或取消后重新申请。");
        String id=req==null?UUID.randomUUID().toString():text(req,"request_id");
        if(req==null) queue.create(id,target,actor.userId(),policy.now());
        else queue.manual(id,actor.userId(),policy.now());
        if(expectedTask!=null && !expectedTask.isBlank()) {
            var old=edges.task(expectedTask);
            if(old==null) throw conflict("原跟踪任务已变化，请重新选择。");
            String device=text(old,"ops_device_id"),oldTarget=text(old,"target_id");
            targets.target(oldTarget);
            if(!tracking.deviceScope(device,actor.userId(),actor.scopeMode())) throw forbidden();
            if(!switchable(old,snapshot,device)) throw conflict("设备占用已变化或原任务不可打断，请刷新后重新选择。");
            if(!tracking.tryLock(oldTarget)) throw conflict("原目标正在更新，请重试。");
            stop(id,old,actor.userId());
        }
        queue.saveAction(actor.userId(),key,target,action,policy.now());
        audit.record(actor.userId(),actor.account(),"eo_dispatch_requested","eo_dispatch_request",id,action,null);
        return view(target);
    }
    @Transactional
    public View cancel(String target,String key) {
        var actor=access.requireDevicesOperate();checkKey(key);edges.lockCursor();tracking.lockActor(actor.userId());targets.target(target);
        if(replay(actor.userId(),key,target,"CANCEL_DISPATCH")) return view(target);
        var req=queue.pending(target);
        if(req!=null) finish(req,"CANCELLED",null,"操作员取消等待；已下发的停止指令仍等待真实回执。");
        queue.saveAction(actor.userId(),key,target,"CANCEL_DISPATCH",policy.now());
        audit.record(actor.userId(),actor.account(),"eo_dispatch_cancelled","target",target,null,null);
        return view(target);
    }
    /** Called within the scheduler transaction, under the same cursor used by all EO commands. */
    public int poll(int maximum) {
        if(policy.enabled()) for(String target:tracking.candidates(policy.cutoff(),policy.now(),maximum)) {
            var snapshot=tracking.snapshot(target);
            if(!policy.enabledFor(snapshot)||policy.block(snapshot)!=null||tracking.paused(target)||policy.demand(target).isEmpty()) continue;
            if(queue.pending(target)!=null || edges.targetHasOpenTask(target)) continue;
            var previous=queue.latest(target);
            var oldTask=edges.latestTaskByTarget(target);
            if(previous!=null && "FAILED".equals(text(previous,"status"))) continue;
            if(oldTask!=null && "FAILED".equals(text(oldTask,"status"))) continue;
            queue.create(UUID.randomUUID().toString(),target,null,policy.now());
        }
        var pending=new ArrayList<>(queue.waiting());
        // Stable sort preserves the durable FIFO order within a priority.
        pending.sort(Comparator.comparingInt((Map<String,Object> r)->priority(text(r,"target_id"))).reversed());
        int count=0;
        for(var req:pending) {
            if(count>=maximum) break;
            String target=text(req,"target_id"),id=text(req,"request_id");
            if(!tracking.tryLock(target)) continue;
            var snapshot=tracking.snapshot(target);
            boolean manual="MANUAL".equals(text(req,"origin"));
            if(manual && !actorAllowed(req,null)) {finish(req,"FAILED",null,"请求人的权限或目标访问范围已变化，请重新申请。");continue;}
            if(policy.block(snapshot)!=null) {finish(req,manual || "STOPPING".equals(text(req,"status"))?"FAILED":"CANCELLED",null,EoTrackingStatusService.blockMessage(policy.block(snapshot)));continue;}
            if(!manual && (!policy.enabledFor(snapshot)||tracking.paused(target)||policy.demand(target).isEmpty())) {
                finish(req,"CANCELLED",null,"当前已无有效自动观察需求。");continue;
            }
            if(edges.targetHasOpenTask(target)) {finish(req,"STARTED",text(edges.openTaskByTarget(target),"task_id"),"目标已有跟踪任务。");continue;}
            Binding picked=null;
            if("STOPPING".equals(text(req,"status"))) {
                var old=edges.task(text(req,"stop_task_id"));
                var command=old==null?null:edges.command(text(old,"end_command_id"));
                if(command==null || Set.of("FAILED","TIMED_OUT","CANCELLED").contains(text(command,"status"))) {
                    finish(req,"FAILED",null,"原跟踪任务停止未确认，未启动新目标；请核查设备停止结果。");continue;
                }
                if(!"SUCCEEDED".equals(text(command,"status")) || !"ENDED".equals(text(old,"status"))) continue;
                // Reservation is deliberately bypassed only by its owner after a successful stop receipt.
                picked=edges.idleDeviceForDispatch(text(snapshot,"owner_org_id"),text(snapshot,"district_id"),text(req,"device_id"),
                        EoTrackingPolicy.mode(snapshot),policy.heartbeatCutoff(),policy.now(),id);
                if(picked==null) {
                    // Fresh idle heartbeat can lag the stop receipt; keep the reservation briefly.
                    if(policy.now()-((Number)req.get("updated_at")).longValue()<30000) continue;
                    finish(req,"FAILED",null,"原任务已停止，但设备尚未恢复可用，未启动新目标。");continue;
                }
            } else for(String device:devices(snapshot)) {
                if(queue.reserved(device) || manual && !actorAllowed(req,device)) continue;
                picked=idle(snapshot,device);if(picked!=null) break;
            }
            if(picked!=null) {
                if(manual && !actorAllowed(req,picked.opsDeviceId())) {finish(req,"FAILED",null,"设备操作权限已变化。");continue;}
                String task=UUID.randomUUID().toString();
                commands.enqueueBegin(picked,task,target,null,"UNREPORTED_KINEMATICS_AND_SIZE_USE_PROTOCOL_DEFAULTS",policy.bootstrap(snapshot),
                        manual?text(req,"requested_by"):null,manual?"OPERATOR_DISPATCH_TRACK":"BUSINESS_OBSERVATION_AUTO_TRACK");
                if(!manual) tracking.automatic(task);
                finish(req,"STARTED",task,"已提交跟踪指令，等待设备回执。");count++;continue;
            }
            if(!manual && "WAITING".equals(text(req,"status"))) {
                var choices=new ArrayList<Map<String,Object>>();
                for(String device:devices(snapshot)) {
                    var old=edges.openTask(device);
                    if(old!=null && ("AUTO".equals(text(old,"origin")) || priority(target)==100) && switchable(old,snapshot,device)
                            && priority(target)>priority(text(old,"target_id"))) choices.add(old);
                }
                choices.sort(Comparator.comparingInt(t->priority(text(t,"target_id"))));
                if(!choices.isEmpty() && tracking.tryLock(text(choices.get(0),"target_id"))) {
                    stop(id,choices.get(0),null);count++;
                }
            }
        }
        return count;
    }
    private int priority(String target) {return queue.priority(target,policy.now()-policy.demandMaxAge(),policy.now());}
    public boolean protectedObservation(String target) {return queue.protectedTarget(target);}
    private List<String> devices(Map<String,Object> snapshot) {
        return queue.devices(text(snapshot,"owner_org_id"),text(snapshot,"district_id"),EoTrackingPolicy.mode(snapshot),policy.heartbeatCutoff(),policy.now());
    }
    private Binding idle(Map<String,Object> snapshot,String device) {
        return edges.idleDeviceForMode(text(snapshot,"owner_org_id"),text(snapshot,"district_id"),device,EoTrackingPolicy.mode(snapshot),policy.heartbeatCutoff(),policy.now());
    }
    private boolean switchable(Map<String,Object> task,Map<String,Object> incoming,String device) {
        if(!"OPEN".equals(text(task,"status")) || queue.reserved(device) || queue.protectedTarget(text(task,"target_id"))) return false;
        var current=edges.openTask(device);
        if(current==null || !text(current,"task_id").equals(text(task,"task_id"))) return false;
        if(!devices(incoming).contains(device)) return false;
        var binding=edges.binding(device,false);
        return policy.trackingBlock(tracking.snapshot(text(task,"target_id")),task,binding)==null;
    }
    private void stop(String id,Map<String,Object> old,String actor) {
        String device=text(old,"ops_device_id");
        queue.reserve(id,device,text(old,"task_id"),policy.now());
        commands.enqueue(edges.binding(device,true),EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,"EO_DISPATCH_SWITCH",actor);
        audit.record(actor,actor==null?"system":"operator","eo_dispatch_switch_requested","eo_dispatch_request",id,"等待原任务停止回执",null);
    }
    private boolean actorAllowed(Map<String,Object> req,String device) {
        var user=users.findById(text(req,"requested_by"));
        if(user==null || !"ACTIVE".equals(user.getStatus()) || !tracking.roleEnabled(user.getRoleCode())) return false;
        AuthUser previous=AuthContext.get();
        try {
            AuthContext.set(new AuthUser(user.getUserId(),user.getAccount(),user.getName(),user.getRoleCode(),user.getPermissionVersion(),user.isMustChangePassword(),user.getScopeMode()));
            access.requireDevicesOperate();targetAccess.require(com.uav.lowaltitude.modules.identity.domain.PermissionCode.TARGET_READ);
            var target=tracking.snapshot(text(req,"target_id"));
            if(target==null || !permissions.canAccessTuple(text(target,"owner_org_id"),text(target,"district_id"))) return false;
            return device==null || tracking.deviceScope(device,user.getUserId(),user.getScopeMode());
        } catch(ApiException ex) {return false;}
        finally {if(previous==null) AuthContext.clear();else AuthContext.set(previous);}
    }
    private void finish(Map<String,Object> req,String status,String task,String message) {
        queue.finish(text(req,"request_id"),status,task,message,policy.now());
        audit.record(null,"system","eo_dispatch_"+status.toLowerCase(Locale.ROOT),"eo_dispatch_request",text(req,"request_id"),message,null);
    }
    private boolean replay(String actor,String key,String target,String action) {
        var old=queue.action(actor,key);
        if(old==null) return false;
        if(!target.equals(text(old,"target_id"))||!action.equals(text(old,"action"))) throw conflict("该请求标识已用于其他操作。");
        return true;
    }
    private static void checkKey(String key) {
        if(key==null||key.length()<8||key.length()>128) throw new ApiException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","请求标识必须为8至128个字符");
    }
    private static ApiException conflict(String message) {return new ApiException(HttpStatus.CONFLICT,"EO_DISPATCH_CONFLICT",message);}
    private static ApiException forbidden() {return new ApiException(HttpStatus.FORBIDDEN,"DEVICE_SCOPE_FORBIDDEN","无权操作该光电设备");}
    public record SwitchOption(String taskId,String deviceId,String deviceName,String targetNo) { }
    public record View(String targetId,String status,String message,String requestId,boolean canRequest,boolean canCancel,List<SwitchOption> switchOptions) { }
}
