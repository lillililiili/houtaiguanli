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
    private final DeviceMaintenanceRepository tasks;
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
            IdempotencyGuard idempotency,AuditService audit,AppClock clock) {
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
        tasks.insert(task);
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
        return new Task(r.taskId(),planVisible?r.planId():null,planVisible?r.planNo():null,r.deviceId(),r.deviceNo(),
                r.deviceName(),r.reason(),r.connectivity(),r.healthCode(),r.observedAt(),r.lastHeartbeatAt(),r.simulated(),
                r.status(),r.reportedByName(),r.reportedAt(),r.handledByName(),r.handledAt(),r.handlingNote(),r.version(),
                "PENDING".equals(r.status())&&deviceAccess.canOperateMonitoring(actor),reused);
    }
    private static String id(String v) { if(v==null||v.isBlank()||v.trim().length()>36)throw bad("对象编号无效");return v.trim(); }
    private static String key(String v) { if(v==null||v.trim().length()<8||v.trim().length()>128)throw bad("提交编号长度必须为 8–128 字符");return v.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"MAINTENANCE_OBJECT_NOT_FOUND","待办或关联计划不存在或不可见"); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
}
