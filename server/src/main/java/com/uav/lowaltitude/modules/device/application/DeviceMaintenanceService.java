package com.uav.lowaltitude.modules.device.application;

import java.util.Set;
import java.util.UUID;
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
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
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
    private final com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceCaseRepository cases;
    private final com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository;
    private final DeviceRecoveryService recoveryService;

    public DeviceMaintenanceService(DeviceMaintenanceRepository tasks,DeviceService devices,DeviceAccessPolicy deviceAccess,
            FlightDeviceCheckService checks,FlightReadRepository plans,AccessControlService access,
            IdempotencyGuard idempotency,AuditService audit,AppClock clock,com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService notifications,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceNoticeRepository noticeAttempts,
            @org.springframework.beans.factory.annotation.Value("${app.device-maintenance.resend-cooldown-seconds:60}") long resendCooldownSeconds,
            @org.springframework.beans.factory.annotation.Value("${app.device-maintenance.resend-observation-max-age-seconds:300}") long observationMaxAgeSeconds,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceCaseRepository cases,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository,DeviceRecoveryService recoveryService) {
        this.cases=cases;this.deviceRepository=deviceRepository;this.recoveryService=recoveryService;
        this.notifications=notifications;this.noticeAttempts=noticeAttempts;
        this.resendCooldownMillis=Math.max(60,resendCooldownSeconds)*1000L;
        if(observationMaxAgeSeconds<1)throw new IllegalArgumentException("设备再次通知观测有效期必须大于零");
        this.resendObservationMaxAgeMillis=observationMaxAgeSeconds*1000L;
        this.tasks=tasks;this.devices=devices;this.deviceAccess=deviceAccess;this.checks=checks;
        this.plans=plans;this.access=access;this.idempotency=idempotency;this.audit=audit;this.clock=clock;
    }

    @Transactional
    public Task create(String planId,CreateRequest body,String requestKey) {
        AuthUser actor=requireReporter();
        String key=key(requestKey),planKey=id(planId),deviceId=id(body==null?null:body.deviceId());
        var plan=plans.findPlan(planKey,access.require(PermissionCode.FLIGHT_READ));
        if(plan==null)throw missing();
        var device=devices.detail(deviceId).device();
        // 同一设备上的创建/处理串行化，配合 active_key 唯一约束避免并发重复待办。
        tasks.lockDevice(deviceId);
        Row replay=tasks.submission(actor.userId(),key);
        if(replay!=null) {
            if(!cases.submittedFor(actor.userId(),key,planKey)||!deviceId.equals(replay.deviceId()))
                throw conflict("IDEMPOTENCY_KEY_REUSED","同一提交编号不能用于其他任务或设备");
            return dto(replay,actor,true);
        }
        var checked=checks.read(planKey);
        var row=checked.rows().stream().filter(item->deviceId.equals(item.deviceId())).findFirst()
                .orElseThrow(()->conflict("DEVICE_NOT_NEAR_PLAN","设备已不在本任务可检查的附近范围内，请重新检查"));
        if(!row.abnormal()&&row.incidents().stream().noneMatch(item->item.closedAt()==null))
            throw conflict("DEVICE_NOT_ABNORMAL","重新检查未发现当前异常或未关闭告警，无需生成运维待办");
        // CDX-P09：先写设备现在真正的问题，再写还没关的旧异常记录；已恢复上报的心跳超时只注一句待核验。
        var facts=devices.inspectPlanDevices(plan.ownerOrgId(),plan.districtId(),false).stream()
                .filter(item->deviceId.equals(item.device().deviceId())).findFirst().orElseThrow(DeviceMaintenanceService::missing);
        String reasons=MaintenanceReason.text(row,facts.state().hasAlarm(),clock.nowMillis());
        String actorName=actor.name()==null?actor.account():actor.name();
        Row existing=tasks.pending(planKey,deviceId);
        if(existing!=null) {
            cases.attach(existing.taskId(),planKey,actor.userId(),actorName,reasons,clock.nowMillis(),actor.userId()+":"+key);
            if(noticeAttempts.forTask(existing.taskId()).isEmpty())sendNotice(existing,actor,null,null,1,"首次人工上报",null);
            remember(actor,key,existing.taskId(),planKey);
            return dto(existing,actor,true);
        }
        Row task=new Row(UUID.randomUUID().toString(),planKey,deviceId,plan.ownerOrgId(),plan.districtId(),
                plan.planNo(),device.deviceNo(),row.name(),reasons,row.connectivity(),row.healthCode(),row.observedAt(),
                row.lastHeartbeatAt(),row.simulated(),"PENDING",actor.userId(),actorName,clock.nowMillis(),null,null,null,1);
        idempotency.claim(key,"device-maintenance-create:"+planKey+":"+deviceId);
        tasks.insert(task);
        cases.attach(task.taskId(),planKey,actor.userId(),actorName,reasons,task.reportedAt(),actor.userId()+":"+key);
        sendNotice(task,actor,null,null,1,"首次通知",null);
        remember(actor,key,task.taskId(),planKey);
        audit.record(actor.userId(),actor.account(),"device_maintenance_reported","device_maintenance_task",
                task.taskId(),"通知设备异常；设备="+deviceId+"；任务="+planKey,null);
        return dto(task,actor,false);
    }

    @Transactional(readOnly=true)
    public DeviceReport forDevice(String deviceId) {
        AuthUser actor=deviceAccess.requireDevicesRead();String deviceKey=id(deviceId);
        devices.detail(deviceKey);
        return deviceReport(deviceKey,tasks.currentSourceCase(deviceKey),actor,false);
    }

    /** A sensing-map report has no invented flight-plan association. Shares the device lock and active key. */
    @Transactional
    public DeviceReport reportDevice(String deviceId,String requestKey) {
        AuthUser actor=requireReporter();String deviceKey=id(deviceId),key=key(requestKey);
        tasks.lockDevice(deviceKey);
        devices.detail(deviceKey); // Recheck scope after acquiring the same catalog lock as workflow actions.
        Row replay=tasks.submission(actor.userId(),key);
        if(replay!=null) {
            var current=tasks.currentSourceCase(deviceKey);
            if(!deviceKey.equals(replay.deviceId())||!cases.submittedFor(actor.userId(),key,null)
                    ||current==null||!current.taskId().equals(replay.taskId()))
                throw conflict("IDEMPOTENCY_KEY_REUSED","同一提交编号不能用于其他任务、设备或来源");
            return deviceReport(deviceKey,replay,actor,true);
        }
        String reason=cases.reportableReason(deviceKey);
        if(reason==null)throw conflict("DEVICE_NOT_ABNORMAL","未发现当前明确异常，无需上报；状态未知不等于设备故障");
        idempotency.claim(key,"device-maintenance-device-report:"+deviceKey);
        Row task=tasks.pending(null,deviceKey);boolean reused=task!=null;
        String actorName=actor.name()==null?actor.account():actor.name();long now=clock.nowMillis();
        if(task==null) {
            var facts=deviceRepository.findForRecovery(deviceKey);var state=CurrentDeviceState.from(facts);
            task=new Row(UUID.randomUUID().toString(),null,deviceKey,null,null,null,
                (String)facts.get("device_no"),(String)facts.get("name"),reason,state.connectivity(),state.healthCode(),
                state.observedAt(),state.lastHeartbeatAt(),state.simulated(),"PENDING",actor.userId(),actorName,now,null,null,null,1);
            tasks.insert(task);
        }
        cases.attach(task.taskId(),null,actor.userId(),actorName,reason,now,actor.userId()+":"+key);
        if(noticeAttempts.forTask(task.taskId()).isEmpty())sendNotice(task,actor,null,null,1,"融合感知上报",null);
        remember(actor,key,task.taskId(),null);
        audit.record(actor.userId(),actor.account(),"device_maintenance_reported","device_maintenance_task",
            task.taskId(),"融合感知上报设备异常；设备="+deviceKey,null);
        return deviceReport(deviceKey,task,actor,reused);
    }

    private DeviceReport deviceReport(String device,Row task,AuthUser actor,boolean reused) {
        String reason=cases.reportableReason(device),blocker=null;
        try { access.require(PermissionCode.HANDOFF_CREATE); }
        catch(ApiException error) {if(error.getStatus()!=HttpStatus.FORBIDDEN)throw error;blocker="没有上报设备异常权限";}
        if(blocker==null&&reason==null)blocker="未发现当前明确异常，无需上报；状态未知不等于设备故障";
        CaseProgress progress=task==null?null:new CaseProgress(task.taskId(),task.deviceName(),tasks.workflowState(task.taskId()),
            task.reportedAt(),tasks.myDeviceReportAt(task.taskId(),actor.userId()),task.simulated());
        return new DeviceReport(device,blocker==null,blocker,reason,clock.nowMillis(),progress,reused);
    }

    /** Background-only creation. Records an internal work item; never invokes any notification channel. */
    @Transactional
    public String createScheduled(com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow plan,
            FlightDeviceCheckService.DeviceRow row) {
        if(!row.abnormal() && row.incidents().stream().noneMatch(i->i.closedAt()==null))
            throw conflict("DEVICE_NOT_ABNORMAL","当前没有设备异常");
        tasks.lockDevice(row.deviceId());
        Row existing=tasks.pending(plan.planId(),row.deviceId());
        String deviceId=row.deviceId();
        row=checks.scheduled(plan).rows().stream().filter(item->deviceId.equals(item.deviceId())).findFirst()
            .orElseThrow(()->conflict("DEVICE_NOT_NEAR_PLAN","设备已不在本计划可检查范围内"));
        if(!row.abnormal()&&row.incidents().stream().noneMatch(i->i.closedAt()==null))
            throw conflict("DEVICE_NOT_ABNORMAL","锁定后重新检查未发现当前异常");
        var inspected=devices.inspectPlanDevices(plan.ownerOrgId(),plan.districtId(),true).stream()
            .filter(d->d.device().deviceId().equals(deviceId)).findFirst().orElseThrow(DeviceMaintenanceService::missing);
        var device=inspected.device();
        // CDX-P09：系统到点检查生成的待办同样先写设备现在真正的问题，再写还没关的旧异常记录。
        String reason=MaintenanceReason.text(row,inspected.state().hasAlarm(),clock.nowMillis());
        if(existing!=null){
            cases.attach(existing.taskId(),plan.planId(),null,"系统自动检查",reason,clock.nowMillis(),"plan:"+plan.planId());
            return existing.taskId();
        }
        Row task=new Row(UUID.randomUUID().toString(),plan.planId(),row.deviceId(),plan.ownerOrgId(),plan.districtId(),
            plan.planNo(),device.deviceNo(),row.name(),reason,row.connectivity(),row.healthCode(),row.observedAt(),
            row.lastHeartbeatAt(),row.simulated(),"PENDING",null,"系统自动检查",clock.nowMillis(),null,null,null,1);
        tasks.insert(task);
        cases.attach(task.taskId(),plan.planId(),null,"系统自动检查",reason,task.reportedAt(),"plan:"+plan.planId());
        audit.record(null,"flight-device-check",null,"flight","device_maintenance_created","device_maintenance_task",
            task.taskId(),"系统到点检查；计划="+plan.planId(),"SUCCESS","","");
        return task.taskId();
    }

    @Transactional(readOnly=true)
    public Page list(String status,int page,int size) {
        return list(status,page,size,null);
    }
    @Transactional(readOnly=true)
    public Page list(String status,int page,int size,String deviceId) {
        AuthUser actor=deviceAccess.requireMonitoringRead();
        if(!Set.of("PENDING","HANDLED","ALL","ACTIVE","NOT_STARTED","PROCESSING","PENDING_VERIFICATION","READY_TO_COMPLETE","COMPLETED").contains(status)||page<1||size<1||size>100
                ||(long)(page-1)*size>Integer.MAX_VALUE)throw bad("待办筛选或分页参数无效");
        String deviceKey=deviceId==null?null:id(deviceId);
        return new Page(tasks.list(status,page,size,actor,deviceKey,visiblePlanScope()).stream().map(row->dto(row,actor,false)).toList(),
                page,size,tasks.count(status,actor,deviceKey));
    }

    @Transactional(readOnly=true)
    public Page forPlan(String planId,String deviceId,int page,int size) {
        var scope=access.require(PermissionCode.FLIGHT_READ);
        AuthUser actor=com.uav.lowaltitude.platform.security.AuthContext.require();
        String planKey=id(planId),deviceKey=deviceId==null?null:id(deviceId);
        if(plans.findPlan(planKey,scope)==null)throw missing();
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw bad("分页参数无效");
        return new Page(tasks.forPlan(planKey,deviceKey,page,size,actor,scope).stream().map(row->dto(row,actor,false)).toList(),page,size,tasks.countForPlan(planKey,deviceKey,actor));
    }

    /** Task-list attention uses the same saved plan links and device visibility as the progress entry. */
    @Transactional(readOnly=true)
    public java.util.List<PlanTask> openForPlans(java.util.List<String> planIds) {
        var scope=access.require(PermissionCode.FLIGHT_READ);
        AuthUser actor=com.uav.lowaltitude.platform.security.AuthContext.require();
        return tasks.openForPlans(planIds,actor,scope);
    }

    private com.uav.lowaltitude.modules.identity.domain.AccessDecision visiblePlanScope() {
        try { return access.require(PermissionCode.FLIGHT_READ); }
        catch(ApiException error) { if(error.getStatus()!=HttpStatus.FORBIDDEN)throw error;return null; }
    }

    @Transactional
    public Task handle(String taskId,HandleRequest body,String requestKey) {
        AuthUser actor=deviceAccess.requireMonitoringOperate();
        deviceAccess.requireMonitoringRead();
        String taskKey=id(taskId);
        Row task=tasks.find(taskKey,actor);
        if(task==null)throw missing();
        throw conflict("MAINTENANCE_WORKFLOW_REQUIRED","请进入运维流程完成处理和恢复核验；填写反馈不能直接办结");
    }

    @Transactional(readOnly=true)
    public Task detail(String taskId) {
        AuthUser actor=deviceAccess.requireMonitoringRead();
        Row task=tasks.find(id(taskId),actor);
        if(task==null)throw missing();
        return dto(task,actor,false);
    }

    private void remember(AuthUser actor,String key,String taskId,String planId) {
        try { tasks.recordSubmission(actor.userId(),key,taskId,planId); }
        catch(DuplicateKeyException error) { throw conflict("IDEMPOTENCY_KEY_REUSED","提交编号已被使用，请使用原请求重试确认结果"); }
    }

    private Task dto(Row r,AuthUser actor,boolean reused) {
        boolean planVisible=false;
        try { planVisible=com.uav.lowaltitude.platform.security.UnitOperationsContext.active()
                ? r.planId()!=null && relatedPlans(r).stream().anyMatch(p->r.planId().equals(p.planId()))
                : r.planId()!=null&&plans.findPlan(r.planId(),access.require(PermissionCode.FLIGHT_READ))!=null; }
        catch(ApiException error) { if(error.getStatus()!=HttpStatus.FORBIDDEN)throw error; }
        var history=noticeAttempts.forTask(r.taskId());
        var latest=history.isEmpty()?null:history.get(0);
        String blocker=resendBlocker(r,latest,actor,true);
        Long availableAt=latest==null?null:latest.requestedAt()+resendCooldownMillis;
        var details=cases.details(r.taskId());
        var evaluation=evaluate(r);
        String state=tasks.workflowState(r.taskId());
        Recovery recovery="PENDING".equals(r.status())?asRecovery(evaluation):details.recovery();
        String primary=DeviceMaintenancePresentation.primary(state,recovery,deviceAccess.canOperateMonitoring(actor));
        return new Task(r.taskId(),planVisible?r.planId():null,planVisible?r.planNo():null,r.deviceId(),r.deviceNo(),
                r.deviceName(),r.reason(),r.connectivity(),r.healthCode(),r.observedAt(),r.lastHeartbeatAt(),r.simulated(),
                r.status(),r.reportedByName(),r.reportedAt(),r.handledByName(),r.handledAt(),r.handlingNote(),r.version(),
                "PENDING".equals(r.status())&&deviceAccess.canOperateMonitoring(actor),reused,latest==null?null:latest.recipientSnapshot(),
                latest==null?null:latest.deliveryStatus(),latest==null?null:latest.receiptStatus(),latest==null?null:latest.blockedReason(),
                history,latest==null?0:latest.attemptNo(),blocker==null,blocker,availableAt,state,
                details.version(),CurrentDeviceState.from(evaluation.currentState()),recovery,primary,DeviceMaintenancePresentation.next(state,recovery),reportSummary(r,planVisible));
    }
    private ReportSummary reportSummary(Row task,boolean originalPlanVisible){
        var related=relatedPlans(task);
        var report=cases.latestManualReport(task.taskId(),related.stream().map(RelatedPlan::planId).toList());
        ManualReport manual=null;
        if(report!=null){
            String planNo=related.stream().filter(plan->plan.planId().equals(report.planId()))
                .map(RelatedPlan::planNo).findFirst().orElse(null);
            manual=new ManualReport(report.reportId(),report.actorName(),report.reportedAt(),report.planId(),planNo);
        }else if(task.reportedBy()!=null&&(task.planId()==null||originalPlanVisible)){
            // Legacy reports predate the report-link table; retain their recorded original facts.
            manual=new ManualReport(null,task.reportedByName(),task.reportedAt(),task.planId(),task.planNo());
        }
        return new ReportSummary(task.reportedBy()==null?"SYSTEM":"USER",manual,related);
    }
    public DeviceRecoveryService.Evaluation evaluate(Row task){
        var details=cases.details(task.taskId());
        var device=deviceRepository.findForRecovery(task.deviceId());
        String source=details.version()==1&&device!=null?(String)device.get("source_id"):details.sourceId();
        return recoveryService.evaluate(task.deviceId(),casesSourceMode(task.taskId()),source,task.simulated(),details.since()==null?task.reportedAt():details.since());
    }
    private String casesSourceMode(String task){return cases.sourceMode(task);}
    public static Recovery asRecovery(DeviceRecoveryService.Evaluation evaluation){return new Recovery(evaluation.result(),evaluation.reason(),evaluation.checkedAt(),evaluation.reasonCodes(),evaluation.ruleVersion(),CurrentDeviceState.from(evaluation.currentState()));}
    public java.util.List<RelatedPlan> relatedPlans(Row task){
        if(com.uav.lowaltitude.platform.security.UnitOperationsContext.active())
            return cases.relatedPlansInUnit(task.taskId(),com.uav.lowaltitude.platform.security.UnitOperationsContext.orgId());
        try{
            var scope=access.require(PermissionCode.FLIGHT_READ);
            return cases.planIds(task.taskId()).stream().map(id->plans.findPlan(id,scope)).filter(java.util.Objects::nonNull)
                .map(plan->new RelatedPlan(plan.planId(),plan.planNo())).toList();
        }catch(ApiException error){if(error.getStatus()!=HttpStatus.FORBIDDEN)throw error;return java.util.List.of();}
    }

    /** Backend anomaly discovery; no business-plan or notification entitlement is invented. */
    @Transactional
    public void discover(String deviceId){
        if(!deviceRepository.tryLockForRecovery(deviceId))return;
        var device=deviceRepository.findForRecovery(deviceId);
        if(device==null||!Boolean.TRUE.equals(device.get("enabled")))return;
        if(tasks.pending(null,deviceId)!=null)return;
        String reason=cases.incidentReason(deviceId);long now=clock.nowMillis();
        if(reason==null)return;
        var state=CurrentDeviceState.from(device);
        Row task=new Row(UUID.randomUUID().toString(),null,deviceId,null,null,null,
            (String)device.get("device_no"),(String)device.get("name"),reason,state.connectivity(),state.healthCode(),
            state.observedAt(),state.lastHeartbeatAt(),state.simulated(),"PENDING",null,"系统自动发现",now,null,null,null,1);
        tasks.insert(task);cases.attach(task.taskId(),null,null,"系统自动发现",reason,now,"device:"+deviceId);
        audit.record(null,"SYSTEM","device_maintenance_created","device_maintenance_task",task.taskId(),reason,null);
    }
    @Transactional
    public Task resend(String taskId,ResendRequest body,String requestKey) {
        AuthUser actor=requireReporter();
        String taskKey=id(taskId),key=key(requestKey);
        if(body==null||body.expectedAttemptNo()==null||body.expectedAttemptNo()<1)throw bad("请先刷新通知记录再操作");
        String reason=body.reason()==null||body.reason().isBlank()?"人工再次通知":body.reason().trim();
        if(reason.length()>500)throw bad("再次通知原因不能超过500字");
        String requestPlanId=body.planId()==null?null:id(body.planId());
        Row task=tasks.find(taskKey,actor);if(task==null)throw missing();
        var plan=reportingPlan(task,requestPlanId);if(plan==null)throw missing();
        devices.detail(task.deviceId());
        tasks.lockDevice(task.deviceId());
        task=tasks.find(taskKey,actor);if(task==null)throw missing();
        var replay=noticeAttempts.submission(actor.userId(),key);
        if(replay!=null){
            if(!taskKey.equals(replay.taskId())||body.expectedAttemptNo()!=replay.expectedAttemptNo()||!reason.equals(replay.reason())
                    ||!java.util.Objects.equals(requestPlanId,replay.requestPlanId()))
                throw conflict("IDEMPOTENCY_KEY_REUSED","同一提交编号不能用于其他再次通知请求");
            return dto(task,actor,true);
        }
        var history=noticeAttempts.forTask(taskKey);var latest=history.isEmpty()?null:history.get(0);
        if(latest==null||body.expectedAttemptNo()!=latest.attemptNo())throw conflict("MAINTENANCE_NOTICE_CHANGED","通知记录已更新，请刷新后查看");
        String blocker=resendBlocker(task,latest,actor,false);
        if(blocker!=null)throw conflict("MAINTENANCE_RESEND_BLOCKED",blocker);
        var checked=checks.read(plan.planId());
        String deviceKey=task.deviceId();
        var current=checked.rows().stream().filter(row->deviceKey.equals(row.deviceId())).findFirst()
                .orElseThrow(()->conflict("DEVICE_NOT_NEAR_PLAN","设备已不在本任务可检查的附近范围内，请重新检查"));
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
        // Keep omitted-plan legacy requests distinct from explicit choices, including retries after associations change.
        String operation="device-maintenance-resend:"+taskKey+":"+body.expectedAttemptNo()+":"+reason;
        if(requestPlanId!=null)operation="device-maintenance-resend-plan:"+requestPlanId.length()+":"+requestPlanId+":"+operation;
        idempotency.claim(key,operation);
        sendNotice(task,actor,key,body.expectedAttemptNo(),latest.attemptNo()+1,reason,requestPlanId);
        audit.record(actor.userId(),actor.account(),"device_maintenance_notice_resent","device_maintenance_task",taskKey,"再次通知；原因="+reason,null);
        return dto(task,actor,false);
    }

    private void sendNotice(Row task,AuthUser actor,String requestKey,Integer expected,int number,String reason,String requestPlanId){
        var target=notifications.backendMaintenanceTarget();
        String attemptId=UUID.randomUUID().toString();long at=clock.nowMillis();
        var submitted=clock.now().atOffset(java.time.ZoneOffset.UTC);
        // 设备异常上报本身就是发往后台运维待办；不经过短信、上级交接或数据模拟器通道。
        var outcome=new DeliveryOutcome("DELIVERED","NOT_EXPECTED",null,null,submitted,submitted,null);
        noticeAttempts.insert(attemptId,task.taskId(),number,actor,requestKey,expected,at,reason,requestPlanId,target,outcome,"COMPLETED");
        // 原待办上的首条通知摘要保持兼容；全部后续快照与时间追加到 attempt，API 汇总取最新一次。
        if(number==1)notifications.freezeMaintenance(task.taskId(),target,outcome);
    }

    private String resendBlocker(Row task,NoticeAttempt latest,AuthUser actor,boolean checkPermissions){
        if(!"PENDING".equals(task.status()))return "运维待办已处理，不再重复通知；设备仍异常时可另行报告";
        if(latest==null)return "尚未取得原通知记录，请先刷新核对";
        if(!Set.of("NOT_SENT","COMPLETED").contains(latest.outcomeState()))return "原通知正在发送或结果未知，请先核对原通知结果";
        if(checkPermissions){
            try{requireReporter();
                if(reportingPlan(task,null)==null)return "没有可见的关联飞行任务";
                devices.detail(task.deviceId());
            }catch(ApiException error){if(error.getStatus()!=HttpStatus.FORBIDDEN&&error.getStatus()!=HttpStatus.NOT_FOUND)throw error;return "没有再次通知权限或关联对象不可见";}
        }
        if(clock.nowMillis()<latest.requestedAt()+resendCooldownMillis)return "距离上次通知不足"+(resendCooldownMillis/1000)+"秒，请稍后再试";
        return null;
    }

    private FlightReadRepository.PlanRow reportingPlan(Row task,String requestedPlan){
        var scope=access.require(PermissionCode.FLIGHT_READ);
        var related=cases.planIds(task.taskId());
        if(requestedPlan!=null){
            String selected=id(requestedPlan);
            if(!related.contains(selected))throw missing();
            return plans.findPlan(selected,scope);
        }
        // Legacy callers omit plan_id. Use a real, visible association; never assume the first reporter owns the case.
        return related.stream().sorted().map(plan->plans.findPlan(plan,scope)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** Reporting is a business action; processing and recovery remain backend monitoring actions. */
    private AuthUser requireReporter() {
        access.require(PermissionCode.HANDOFF_CREATE);
        return deviceAccess.requireDevicesRead();
    }

    private static String id(String v) { if(v==null||v.isBlank()||v.trim().length()>36)throw bad("对象编号无效");return v.trim(); }
    private static String key(String v) { if(v==null||v.trim().length()<8||v.trim().length()>128)throw bad("提交编号长度必须为 8–128 字符");return v.trim(); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"MAINTENANCE_OBJECT_NOT_FOUND","待办或关联任务不存在或不可见"); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
}
