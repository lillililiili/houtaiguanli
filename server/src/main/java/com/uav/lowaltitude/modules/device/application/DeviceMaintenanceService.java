package com.uav.lowaltitude.modules.device.application;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.*;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceRepository.Row;
import com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class DeviceMaintenanceService {
    private final com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService notifications;
    private final DeviceMaintenanceRepository tasks;
    private final com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceNoticeRepository noticeAttempts;
    private final long resendCooldownMillis;
    private final long resendObservationMaxAgeMillis;
    private final DeviceService devices;
    private final DeviceAccessPolicy deviceAccess;
    private final FlightDeviceCheckService checks;
    private final FlightReadRepository plans;
    private final AccessControlService access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;

    public DeviceMaintenanceService(DeviceMaintenanceRepository tasks,DeviceService devices,DeviceAccessPolicy deviceAccess,
            FlightDeviceCheckService checks,FlightReadRepository plans,AccessControlService access,
            IdempotencyGuard idempotency,AuditService audit,AppClock clock,com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService notifications,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceNoticeRepository noticeAttempts,
            @org.springframework.beans.factory.annotation.Value("${app.device-maintenance.resend-cooldown-seconds:60}") long resendCooldownSeconds,
            @org.springframework.beans.factory.annotation.Value("${app.device-maintenance.resend-observation-max-age-seconds:300}") long observationMaxAgeSeconds) {
        this.notifications=notifications;this.noticeAttempts=noticeAttempts;
        this.resendCooldownMillis=Math.max(60,resendCooldownSeconds)*1000L;
        if(observationMaxAgeSeconds<1)throw new IllegalArgumentException("设备再次通知观测有效期必须大于零");
        this.resendObservationMaxAgeMillis=observationMaxAgeSeconds*1000L;
        this.tasks=tasks;this.devices=devices;this.deviceAccess=deviceAccess;this.checks=checks;
        this.plans=plans;this.access=access;this.idempotency=idempotency;this.audit=audit;this.clock=clock;
    }

    @Transactional
    public Task create(String planId,CreateRequest body,String requestKey) {
        access.require(PermissionCode.HANDOFF_CREATE);
        AuthUser actor=deviceAccess.requireMonitoringRead();
        deviceAccess.requireDevicesRead();
        String key=key(requestKey),planKey=id(planId),deviceId=id(body==null?null:body.deviceId());
        var plan=plans.findPlan(planKey,access.require(PermissionCode.FLIGHT_READ));
        if(plan==null)throw missing();
        var device=devices.detail(deviceId).device();
        // 同一设备上的创建/处理串行化，配合 active_key 唯一约束避免并发重复待办。
        tasks.lockDevice(deviceId);
        Row replay=tasks.submission(actor.userId(),key);
        if(replay!=null) {
            if(!planKey.equals(replay.planId())||!deviceId.equals(replay.deviceId()))
                throw conflict("IDEMPOTENCY_KEY_REUSED","同一提交编号不能用于其他计划或设备");
            return dto(replay,actor,true);
        }
        Row existing=tasks.pending(planKey,deviceId);
        if(existing!=null) {
            remember(actor,key,existing.taskId());
            return dto(existing,actor,true);
        }
        var checked=checks.read(planKey);
        var row=checked.rows().stream().filter(item->deviceId.equals(item.deviceId())).findFirst()
                .orElseThrow(()->conflict("DEVICE_NOT_NEAR_PLAN","设备已不在本计划可检查的附近范围内，请重新检查"));
        if(!row.abnormal()&&row.incidents().stream().noneMatch(item->item.closedAt()==null))
            throw conflict("DEVICE_NOT_ABNORMAL","重新检查未发现当前异常或未关闭告警，无需生成运维待办");
        String reasons=row.incidents().stream().filter(item->item.closedAt()==null).map(item->item.reason())
                .filter(item->item!=null&&!item.isBlank()).distinct().collect(Collectors.joining("；"));
        if(reasons.isBlank())reasons="设备自动检查发现异常，尚无未关闭告警说明，请运维核查。";
        String actorName=actor.name()==null?actor.account():actor.name();
        Row task=new Row(UUID.randomUUID().toString(),planKey,deviceId,plan.ownerOrgId(),plan.districtId(),
                plan.planNo(),device.deviceNo(),row.name(),reasons,row.connectivity(),row.healthCode(),row.observedAt(),
                row.lastHeartbeatAt(),row.simulated(),"PENDING",actor.userId(),actorName,clock.nowMillis(),null,null,null,1);
        idempotency.claim(key,"device-maintenance-create:"+planKey+":"+deviceId);
        tasks.insert(task);
        sendNotice(task,actor,null,null,1,"首次通知",body.notificationSettingId(),plan.sourceMode());
        remember(actor,key,task.taskId());
        audit.record(actor.userId(),actor.account(),"device_maintenance_reported","device_maintenance_task",
                task.taskId(),"通知设备异常；设备="+deviceId+"；计划="+planKey,null);
        return dto(task,actor,false);
    }

    @Transactional(readOnly=true)
    public Page list(String status,int page,int size) {
        AuthUser actor=deviceAccess.requireMonitoringRead();
        if(!Set.of("PENDING","HANDLED","ALL").contains(status)||page<1||size<1||size>100
                ||(long)(page-1)*size>Integer.MAX_VALUE)throw bad("待办筛选或分页参数无效");
        return new Page(tasks.list(status,page,size,actor).stream().map(row->dto(row,actor,false)).toList(),
                page,size,tasks.count(status,actor));
    }

    @Transactional(readOnly=true)
    public Page forPlan(String planId,String deviceId,int page,int size) {
        var scope=access.require(PermissionCode.FLIGHT_READ);
        AuthUser actor=com.uav.lowaltitude.platform.security.AuthContext.require();
        String planKey=id(planId),deviceKey=deviceId==null?null:id(deviceId);
        if(plans.findPlan(planKey,scope)==null)throw missing();
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw bad("分页参数无效");
        return new Page(tasks.forPlan(planKey,deviceKey,page,size,actor).stream().map(row->dto(row,actor,false)).toList(),page,size,tasks.countForPlan(planKey,deviceKey,actor));
    }

    @Transactional
    public Task handle(String taskId,HandleRequest body,String requestKey) {
        AuthUser actor=deviceAccess.requireMonitoringOperate();
        deviceAccess.requireMonitoringRead();
        String taskKey=id(taskId);
        Row task=tasks.find(taskKey,actor);
        if(task==null)throw missing();
        if(body==null||body.expectedVersion()==null||body.expectedVersion()<1||body.note()==null
                ||body.note().trim().length()<2||body.note().trim().length()>1000)throw bad("请填写 2–1000 字的处理结果");
        String note=body.note().trim();
        tasks.lockDevice(task.deviceId());
        idempotency.claim(key(requestKey),"device-maintenance-handle:"+taskKey+":"+body.expectedVersion()+":"+note);
        if(tasks.handle(taskKey,body.expectedVersion(),note,actor,clock.nowMillis())!=1)
            throw conflict("MAINTENANCE_TASK_CHANGED","待办已被处理或更新，请刷新后查看");
        audit.record(actor.userId(),actor.account(),"device_maintenance_handled","device_maintenance_task",taskKey,note,null);
        return dto(tasks.find(taskKey,actor),actor,false);
    }

    private void remember(AuthUser actor,String key,String taskId) {
        try { tasks.recordSubmission(actor.userId(),key,taskId); }
        catch(DuplicateKeyException error) { throw conflict("IDEMPOTENCY_KEY_REUSED","提交编号已被使用，请使用原请求重试确认结果"); }
    }

    private Task dto(Row r,AuthUser actor,boolean reused) {
        boolean planVisible=false;
        try { planVisible=plans.findPlan(r.planId(),access.require(PermissionCode.FLIGHT_READ))!=null; }
        catch(ApiException error) { if(error.getStatus()!=HttpStatus.FORBIDDEN)throw error; }
        var history=noticeAttempts.forTask(r.taskId());
        var latest=history.isEmpty()?null:history.get(0);
        String blocker=resendBlocker(r,latest,actor,true);
        Long availableAt=latest==null?null:latest.requestedAt()+resendCooldownMillis;
        return new Task(r.taskId(),planVisible?r.planId():null,planVisible?r.planNo():null,r.deviceId(),r.deviceNo(),
                r.deviceName(),r.reason(),r.connectivity(),r.healthCode(),r.observedAt(),r.lastHeartbeatAt(),r.simulated(),
                r.status(),r.reportedByName(),r.reportedAt(),r.handledByName(),r.handledAt(),r.handlingNote(),r.version(),
                "PENDING".equals(r.status())&&deviceAccess.canOperateMonitoring(actor),reused,latest==null?null:latest.recipientSnapshot(),
                latest==null?null:latest.deliveryStatus(),latest==null?null:latest.receiptStatus(),latest==null?null:latest.blockedReason(),
                history,latest==null?0:latest.attemptNo(),blocker==null,blocker,availableAt);
    }
    @Transactional
    public Task resend(String taskId,ResendRequest body,String requestKey) {
        access.require(PermissionCode.HANDOFF_CREATE);
        AuthUser actor=deviceAccess.requireMonitoringRead();deviceAccess.requireDevicesRead();
        String taskKey=id(taskId),key=key(requestKey);
        if(body==null||body.expectedAttemptNo()==null||body.expectedAttemptNo()<1)throw bad("请先刷新通知记录再操作");
        String reason=body.reason()==null||body.reason().isBlank()?"人工再次通知":body.reason().trim();
        if(reason.length()>500)throw bad("再次通知原因不能超过500字");
        Row task=tasks.find(taskKey,actor);if(task==null)throw missing();
        var plan=plans.findPlan(task.planId(),access.require(PermissionCode.FLIGHT_READ));if(plan==null)throw missing();
        devices.detail(task.deviceId());
        tasks.lockDevice(task.deviceId());
        task=tasks.find(taskKey,actor);if(task==null)throw missing();
        var replay=noticeAttempts.submission(actor.userId(),key);
        if(replay!=null){
            if(!taskKey.equals(replay.taskId())||body.expectedAttemptNo()!=replay.expectedAttemptNo()||!reason.equals(replay.reason()))
                throw conflict("IDEMPOTENCY_KEY_REUSED","同一提交编号不能用于其他再次通知请求");
            return dto(task,actor,true);
        }
        var history=noticeAttempts.forTask(taskKey);var latest=history.isEmpty()?null:history.get(0);
        if(latest==null||body.expectedAttemptNo()!=latest.attemptNo())throw conflict("MAINTENANCE_NOTICE_CHANGED","通知记录已更新，请刷新后查看");
        String blocker=resendBlocker(task,latest,actor,false);
        if(blocker!=null)throw conflict("MAINTENANCE_RESEND_BLOCKED",blocker);
        var checked=checks.read(task.planId());
        String deviceKey=task.deviceId();
        var current=checked.rows().stream().filter(row->deviceKey.equals(row.deviceId())).findFirst()
                .orElseThrow(()->conflict("DEVICE_NOT_NEAR_PLAN","设备已不在本计划可检查的附近范围内，请重新检查"));
        long now=clock.nowMillis();
        // 离线/停用是服务端当前状态，不要求故障设备重新上报才能提醒；其他异常仍须有有效观测。
        boolean persistentFailure=current.abnormal()&&Set.of("OFFLINE","DISABLED").contains(current.connectivity());
        boolean currentCheck=checked.checkedAt()<=now&&now-checked.checkedAt()<=resendObservationMaxAgeMillis;
        boolean futureObservation=current.observedAt()!=null&&current.observedAt()>now;
        boolean freshObservation=current.observedAt()!=null&&now-current.observedAt()<=resendObservationMaxAgeMillis;
        if(!currentCheck||futureObservation||(!persistentFailure&&!freshObservation))
            throw conflict("MAINTENANCE_OBSERVATION_STALE","设备当前观测缺失或已过期，请刷新设备检查并核对异常后再通知");
        if(!current.abnormal()&&current.incidents().stream().noneMatch(item->item.closedAt()==null))
            throw conflict("DEVICE_NOT_ABNORMAL","重新检查未发现当前异常或未关闭告警，无需再次通知");
        // 在任何渠道调用前争用用户级幂等键；设备锁本身不能阻止同键跨设备并发。
        idempotency.claim(key,"device-maintenance-resend:"+taskKey+":"+body.expectedAttemptNo()+":"+reason);
        String selected=latest.recipientSnapshot()==null?null:latest.recipientSnapshot().settingId();
        sendNotice(task,actor,key,body.expectedAttemptNo(),latest.attemptNo()+1,reason,selected,plan.sourceMode());
        audit.record(actor.userId(),actor.account(),"device_maintenance_notice_resent","device_maintenance_task",taskKey,"再次通知；原因="+reason,null);
        return dto(task,actor,false);
    }

    private void sendNotice(Row task,AuthUser actor,String requestKey,Integer expected,int number,String reason,String selected,String sourceMode){
        var target=notifications.forMaintenance(selected);
        String attemptId=UUID.randomUUID().toString();long at=clock.nowMillis();
        var outcome=notifications.dispatchMaintenance(task.taskId(),attemptId,target,sourceMode,
                "设备="+task.deviceNo()+"；计划="+task.planNo()+"；异常="+task.reason()+"；通知次数="+number);
        noticeAttempts.insert(attemptId,task.taskId(),number,actor,requestKey,expected,at,reason,target,outcome.result(),outcome.state());
        // 原待办上的首条通知摘要保持兼容；全部后续快照与时间追加到 attempt，API 汇总取最新一次。
        if(number==1)notifications.freezeMaintenance(task.taskId(),target,outcome.result());
    }

    private String resendBlocker(Row task,NoticeAttempt latest,AuthUser actor,boolean checkPermissions){
        if(!"PENDING".equals(task.status()))return "运维待办已处理，不再重复通知；设备仍异常时可另行报告";
        if(latest==null)return "尚未取得原通知记录，请先刷新核对";
        if(!Set.of("NOT_SENT","COMPLETED").contains(latest.outcomeState()))return "原通知正在发送或结果未知，请先核对原通知结果";
        if(checkPermissions){
            try{access.require(PermissionCode.HANDOFF_CREATE);deviceAccess.requireMonitoringRead();deviceAccess.requireDevicesRead();
                if(plans.findPlan(task.planId(),access.require(PermissionCode.FLIGHT_READ))==null)return "关联计划不可见";
                devices.detail(task.deviceId());
            }catch(ApiException error){if(error.getStatus()!=HttpStatus.FORBIDDEN&&error.getStatus()!=HttpStatus.NOT_FOUND)throw error;return "没有再次通知权限或关联对象不可见";}
        }
        if(clock.nowMillis()<latest.requestedAt()+resendCooldownMillis)return "距离上次通知不足"+(resendCooldownMillis/1000)+"秒，请稍后再试";
        return null;
    }

    private static String id(String v) { if(v==null||v.isBlank()||v.trim().length()>36)throw bad("对象编号无效");return v.trim(); }
    private static String key(String v) { if(v==null||v.trim().length()<8||v.trim().length()>128)throw bad("提交编号长度必须为 8–128 字符");return v.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"MAINTENANCE_OBJECT_NOT_FOUND","待办或关联计划不存在或不可见"); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
}
