package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.*;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceRepository.Row;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceWorkflowRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceWorkflowRepository.Metadata;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceCaseRepository.AutomaticUpdate;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class DeviceMaintenanceWorkflowService {
    private final DeviceMaintenanceRepository tasks;
    private final DeviceMaintenanceWorkflowRepository repository;
    private final DeviceMaintenanceService maintenance;
    private final DeviceRepository devices;
    private final CommissionService commissions;
    private final DeviceIncidentService incidents;
    private final DeviceAccessPolicy access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final TransactionTemplate rejectionTransaction;
    private final com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceCaseRepository cases;
    private final DeviceRecoveryService recoveryService;
    private volatile String discoveryCursor;
    private volatile String recoveryCursor;
    public DeviceMaintenanceWorkflowService(DeviceMaintenanceRepository tasks,DeviceMaintenanceWorkflowRepository repository,
            DeviceMaintenanceService maintenance,DeviceRepository devices,CommissionService commissions,DeviceIncidentService incidents,DeviceAccessPolicy access,
            IdempotencyGuard idempotency,AuditService audit,AppClock clock,ObjectMapper json,PlatformTransactionManager manager,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceCaseRepository cases,DeviceRecoveryService recoveryService){
        this.cases=cases;this.recoveryService=recoveryService;
        this.tasks=tasks;this.repository=repository;this.maintenance=maintenance;this.devices=devices;this.commissions=commissions;this.incidents=incidents;
        this.access=access;this.idempotency=idempotency;this.audit=audit;this.clock=clock;this.json=json;
        this.transaction=new TransactionTemplate(manager);this.rejectionTransaction=new TransactionTemplate(manager);
        this.rejectionTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional(readOnly=true)
    public Workflow get(String taskId){AuthUser actor=access.requireMonitoringRead();return view(required(taskId,actor),actor);}

    public Workflow action(String taskId,WorkflowAction request,String key){
        try{return transaction.execute(status->act(taskId,request,key));}
        catch(ApiException error){
            AuthUser actor=AuthContext.require();
            rejectionTransaction.executeWithoutResult(status->audit.record(actor.userId(),actor.account(),actor.roleCode(),"devices",
                    "device_maintenance_workflow_rejected","device_maintenance_task",safeId(taskId),error.getCode(),"FAILURE",null,""));
            throw error;
        }
    }
    private Workflow act(String taskId,WorkflowAction request,String key){
        AuthUser actor=access.requireMonitoringOperate();access.requireMonitoringRead();
        Row task=required(taskId,actor);
        if(request==null||request.expectedVersion()==null||request.expectedVersion()<1||request.action()==null)throw bad("动作和版本必填");
        if(key==null||key.trim().length()<8||key.trim().length()>128)throw bad("提交编号长度必须为 8–128 字符");
        String action=request.action(),note=request.note()==null?null:request.note().trim();
        if(note!=null&&note.length()>1000)throw bad("说明最多 1000 字");
        devices.lockForRecovery(task.deviceId());repository.lock(taskId);access.requireMonitoringOperate();task=required(taskId,actor);
        String requestJson=write(request);var replay=repository.replay(actor.userId(),key.trim());
        if(replay!=null){
            if(!taskId.equals(replay.taskId())||!requestJson.equals(replay.request()))throw conflict("IDEMPOTENCY_KEY_REUSED","同一提交编号不能用于不同请求");
            // Re-check today's field permissions and state; a delayed retry must not restore an older UI stage.
            return view(task,actor);
        }
        if(task.version()!=request.expectedVersion())throw conflict("MAINTENANCE_TASK_CHANGED","运维记录已更新，请刷新后重试");
        Metadata metadata=repository.metadata(taskId);String state=metadata.state(),next=state;Recovery recovery=metadata.recovery();
        if(Set.of("COMPLETED","LEGACY_HANDLED").contains(state))throw conflict("MAINTENANCE_READ_ONLY","完成记录和历史反馈只读");
        if(cases.details(taskId).version()==2&&Set.of("SUBMIT_VERIFICATION","VERIFY_RECOVERY","COMPLETE","RESUME").contains(action))
            throw conflict("MAINTENANCE_AUTOMATIC_RECOVERY","系统会自动核验恢复，请使用当前处置单的操作入口");
        switch(action){
            case "START" -> {requireState(state,"PENDING");next="PROCESSING";}
            case "SAVE_PROGRESS" -> requireState(state,"PROCESSING");
            case "SUBMIT_VERIFICATION" -> {requireState(state,"PROCESSING");requireNoActiveWork(task);next="PENDING_VERIFICATION";recovery=null;}
            case "VERIFY_RECOVERY" -> {requireState(state,"PENDING_VERIFICATION");repository.lockCurrentState(task.deviceId());recovery=evaluate(task,metadata);}
            case "COMPLETE" -> {
                requireState(state,"PENDING_VERIFICATION");
                if(recovery==null||!"PASS".equals(recovery.result())||clock.nowMillis()-recovery.checkedAt()>300000||recovery.checkedAt()>clock.nowMillis())throw conflict("MAINTENANCE_RECOVERY_REQUIRED","请先执行有效的恢复核验");
                repository.lockCurrentState(task.deviceId());
                Recovery current=evaluate(task,metadata);
                if(!"PASS".equals(current.result()))throw conflict("MAINTENANCE_RECOVERY_BLOCKED",current.reason());
                recoverIncidents(task);
                next="COMPLETED";recovery=current;
            }
            case "CONFIRM_COMPLETE" -> {
                requireState(state,"READY_TO_COMPLETE");
                var current=maintenance.evaluate(task);
                if(!current.passed())throw conflict("MAINTENANCE_RECOVERY_BLOCKED",current.reason());
                recoverIncidents(task);
                recovery=DeviceMaintenanceService.asRecovery(current);next="COMPLETED";
                cases.snapshot(taskId,write(current),fingerprint(current),recovery,clock.nowMillis()+5000,null);
            }
            case "RESUME" -> {requireState(state,"PENDING_VERIFICATION");next="PROCESSING";recovery=null;}
            case "LINK_COMMISSION" -> {
                requireState(state,"PROCESSING");
                if(request.commissionId()==null||request.commissionId().isBlank()||request.commissionId().length()>36)throw bad("调测任务编号无效");
                var linked=commissions.get(request.commissionId());var device=devices.find(task.deviceId());
                if(device==null||!task.deviceId().equals(linked.deviceId()))throw conflict("MAINTENANCE_COMMISSION_MISMATCH","调测任务必须属于当前可见设备");
                if(!java.util.Objects.equals(metadata.sourceMode(),linked.sourceMode())||!java.util.Objects.equals(metadata.sourceMode(),text(device,"source_mode"))||linked.simulated()!=task.simulated()||bool(device,"simulated")!=task.simulated())throw conflict("MAINTENANCE_SOURCE_MISMATCH","调测任务与运维设备的真实/模拟来源不一致");
                repository.link(taskId,linked.commissionId());
            }
            default -> throw bad("不支持的运维动作");
        }
        idempotency.claim(key.trim(),"device-maintenance-workflow:"+taskId+":"+requestJson);
        long now=clock.nowMillis();
        if(repository.transition(taskId,task.version(),next,actor,note,now,recovery)!=1)throw conflict("MAINTENANCE_TASK_CHANGED","运维记录已更新，请刷新后重试");
        String eventNote="VERIFY_RECOVERY".equals(action)?recovery.result()+"："+recovery.reason():note;
        if("LINK_COMMISSION".equals(action))eventNote="关联调测任务："+request.commissionId()+(note==null?"":"；"+note);
        repository.event(UUID.randomUUID().toString(),taskId,action,eventNote,actor,now,task.version()+1);
        String auditAction="device_maintenance_"+action.toLowerCase(java.util.Locale.ROOT);
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"devices",auditAction,
                "device_maintenance_task",taskId,eventNote,"SUCCESS",null,"");
        Workflow result=view(required(taskId,actor),actor);
        repository.remember(actor.userId(),key.trim(),taskId,requestJson,write(result));
        return result;
    }
    private Workflow view(Row task,AuthUser actor){
        Metadata metadata=repository.metadata(task.taskId());String state=metadata.state(),blocker=null;List<String> allowed=List.of();
        if(access.canOperateMonitoring(actor)){
            switch(state){
                case "PENDING" -> allowed=List.of("START");
                case "PROCESSING" -> {blocker=devices.hasActiveWork(task.deviceId())?"设备仍有执行中的指令、调测或跟踪任务，请先核对实际结果":null;allowed=blocker==null?List.of("SAVE_PROGRESS","SUBMIT_VERIFICATION","LINK_COMMISSION"):List.of("SAVE_PROGRESS","LINK_COMMISSION");}
                case "PENDING_VERIFICATION" -> {
                    Recovery current=evaluate(task,metadata);blocker="PASS".equals(current.result())?null:current.reason();
                    boolean passed=metadata.recovery()!=null&&"PASS".equals(metadata.recovery().result())&&clock.nowMillis()-metadata.recovery().checkedAt()<=300000&&metadata.recovery().checkedAt()<=clock.nowMillis();
                    allowed=blocker==null&&passed?List.of("VERIFY_RECOVERY","COMPLETE","RESUME"):List.of("VERIFY_RECOVERY","RESUME");
                }
                case "READY_TO_COMPLETE" -> {
                    Recovery current=DeviceMaintenanceService.asRecovery(maintenance.evaluate(task));
                    blocker="PASS".equals(current.result())?null:current.reason();
                    allowed=blocker==null?List.of("CONFIRM_COMPLETE"):List.of();
                }
                case "LEGACY_HANDLED" -> blocker="历史记录仅表示已反馈处理情况，未证明设备恢复";
                default -> { }
            }
        }
        if("LEGACY_HANDLED".equals(state))blocker="历史记录仅表示已反馈处理情况，未证明设备恢复";
        if(allowed.contains("LINK_COMMISSION")){
            try{access.requireCommissionRead();}
            catch(ApiException error){if(error.getStatus()!=HttpStatus.FORBIDDEN)throw error;allowed=allowed.stream().filter(action->!"LINK_COMMISSION".equals(action)).toList();}
        }
        if(cases.details(task.taskId()).version()==2)allowed=allowed.stream().filter(a->!Set.of("SUBMIT_VERIFICATION","VERIFY_RECOVERY","COMPLETE","RESUME").contains(a)).toList();
        var open=repository.unresolvedIncidentIds(task.deviceId()).stream().map(incidents::get)
                .map(detail->new OpenIncident(detail.incident().incidentId(),detail.incident().reason(),detail.incident().stage(),detail.allowedActions())).toList();
        var dto=maintenance.detail(task.taskId());
        return new Workflow(dto,state,task.version(),metadata.assignedToName(),repository.events(task.taskId()),repository.commissions(task.taskId()),dto.recovery(),allowed,blocker,open,
            dto.workflowVersion(),dto.currentDeviceState(),dto.primaryAction(),dto.nextStep(),maintenance.relatedPlans(task),cases.incidents(task.taskId()),relatedReports(task));
    }
    private Recovery evaluate(Row task,Metadata metadata){
        return DeviceMaintenanceService.asRecovery(maintenance.evaluate(task));
    }
    private List<RelatedReport> relatedReports(Row task){
        var visible=maintenance.relatedPlans(task).stream().map(RelatedPlan::planId).collect(java.util.stream.Collectors.toSet());
        return cases.reports(task.taskId()).stream().filter(report->report.planId()==null||visible.contains(report.planId())).toList();
    }
    private void recoverIncidents(Row task){
        var details=cases.details(task.taskId());var device=devices.findForRecovery(task.deviceId());
        String source=details.version()==1&&device!=null?text(device,"source_id"):details.sourceId();
        recoveryService.recoverIncidents(task.deviceId(),cases.sourceMode(task.taskId()),source,task.simulated(),
                details.since()==null?task.reportedAt():details.since());
    }
    /** One transaction per device/case. No browser session and no user action is required. */
    public void runAutomaticBatch(){
        var discovered=cases.undispatchedDevices(discoveryCursor);
        // Advance by returned candidates, including failed/busy ones, rather than only successful writes.
        if(!discovered.isEmpty())discoveryCursor=discovered.get(discovered.size()-1);
        for(String device:discovered){
            try{maintenance.discover(device);}
            catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Maintenance discovery deferred for device {} ({})",device,error.getClass().getSimpleName());}
        }
        var due=cases.dueDevices(clock.nowMillis(),recoveryCursor);
        if(!due.isEmpty())recoveryCursor=due.get(due.size()-1);
        for(String device:due){
            try{checkDeviceAutomatically(device);}
            catch(RuntimeException error){
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Maintenance recovery deferred for device {} ({})",device,error.getClass().getSimpleName());
            }
        }
    }
    /** One catalog/state read and one commit for a device, even when it has many legacy reports. */
    public void checkDeviceAutomatically(String deviceId){transaction.executeWithoutResult(status->{
        if(!devices.tryLockForRecovery(deviceId))return;
        var pending=cases.lockDueCases(deviceId,clock.nowMillis());if(pending.isEmpty())return;
        cases.attachIncidents(pending.stream().map(item->item.taskId()).toList());
        var facts=recoveryService.capture(deviceId);
        var updates=new java.util.ArrayList<AutomaticUpdate>();
        boolean incidentsRecovered=false;
        for(var task:pending){
            String source=task.workflowVersion()==1&&facts.device()!=null?text(facts.device(),"source_id"):task.sourceId();
            long boundary=task.since()==null?task.reportedAt():task.since();
            var evaluation=recoveryService.evaluate(facts,task.sourceMode(),source,task.simulated(),boundary);
            boolean invalidated="PASS".equals(task.previousResult())&&!evaluation.passed();
            boolean abnormal=evaluation.reasonCodes().stream().anyMatch(code->Set.of("HEALTH_NOT_GOOD","ALARM_NOT_CLEARED","CONNECTIVITY_NOT_ONLINE").contains(code));
            Long since=invalidated&&abnormal?clock.nowMillis():null;
            Long observedAnomaly=number(evaluation.currentState(),"evidence_after");
            if(observedAnomaly!=null&&(task.since()==null||observedAnomaly>task.since()))
                since=since==null?observedAnomaly:Math.max(since,observedAnomaly);
            String next=evaluation.passed()?"READY_TO_COMPLETE":task.assignee()==null?"PENDING":"PROCESSING";
            String fingerprint=fingerprint(evaluation);
            boolean changed=!next.equals(task.state())||!fingerprint.equals(task.fingerprint());
            // Natural recovery closes device incidents once. Each case still keeps its own result and boundary.
            if(evaluation.passed()&&!incidentsRecovered){
                recoveryService.recoverIncidents(deviceId,task.sourceMode(),source,task.simulated(),boundary);
                incidentsRecovered=true;
            }
            String action=evaluation.passed()?"AUTO_RECOVERED":invalidated?"RECOVERY_INVALIDATED":"AUTO_CHECK";
            updates.add(new AutomaticUpdate(task.taskId(),task.version(),write(evaluation),fingerprint,
                    DeviceMaintenanceService.asRecovery(evaluation),clock.nowMillis()+5000,since,next,changed,action));
        }
        cases.automaticSnapshots(updates);
        for(var update:updates)if(update.changed()){
            repository.systemEvent(update.taskId(),update.action(),update.recovery().reason(),clock.nowMillis(),update.version()+1);
            audit.record(null,"SYSTEM",null,"devices","device_maintenance_"+update.action().toLowerCase(java.util.Locale.ROOT),
                    "device_maintenance_task",update.taskId(),update.recovery().reason(),"SUCCESS",null,"");
        }
    });}
    public void checkAutomatically(String taskId){transaction.executeWithoutResult(status->{
        Row before=tasks.findInternal(taskId);if(before==null||!"PENDING".equals(before.status()))return;
        if(!devices.tryLockForRecovery(before.deviceId())||!repository.tryLock(taskId))return;
        Row task=tasks.findInternal(taskId);if(task==null||!"PENDING".equals(task.status()))return;
        var details=cases.details(taskId);var metadata=repository.metadata(taskId);
        cases.attachIncidents(taskId);
        var evaluation=maintenance.evaluate(task);
        boolean invalidated="PASS".equals(details.recovery()==null?null:details.recovery().result())&&!evaluation.passed();
        // Expired evidence and unfinished work revoke readiness, but are not a new device anomaly.
        boolean abnormal=evaluation.reasonCodes().stream().anyMatch(code->Set.of("HEALTH_NOT_GOOD","ALARM_NOT_CLEARED","CONNECTIVITY_NOT_ONLINE").contains(code));
        Long since=invalidated&&abnormal?clock.nowMillis():null;
        Long observedAnomaly=number(evaluation.currentState(),"evidence_after");
        if(observedAnomaly!=null&&(details.since()==null||observedAnomaly>details.since()))
            since=since==null?observedAnomaly:Math.max(since,observedAnomaly);
        String next=evaluation.passed()?"READY_TO_COMPLETE":details.assignee()==null?"PENDING":"PROCESSING";
        Recovery recovery=DeviceMaintenanceService.asRecovery(evaluation);
        String fingerprint=fingerprint(evaluation);
        if(evaluation.passed())recoverIncidents(task);
        boolean changed=!next.equals(metadata.state())||!fingerprint.equals(details.fingerprint());
        cases.snapshot(taskId,write(evaluation),fingerprint,recovery,clock.nowMillis()+5000,since);
        if(changed){
            if(repository.automaticTransition(taskId,task.version(),next)!=1)throw conflict("MAINTENANCE_TASK_CHANGED","处置单已更新");
            String action=evaluation.passed()?"AUTO_RECOVERED":invalidated?"RECOVERY_INVALIDATED":"AUTO_CHECK";
            repository.systemEvent(taskId,action,recovery.reason(),clock.nowMillis(),task.version()+1);
            audit.record(null,"SYSTEM",null,"devices","device_maintenance_"+action.toLowerCase(java.util.Locale.ROOT),
                "device_maintenance_task",taskId,recovery.reason(),"SUCCESS",null,"");
        }
    });}
    private String fingerprint(DeviceRecoveryService.Evaluation evaluation){
        var state=evaluation.currentState();var values=new java.util.ArrayList<Object>();
        values.add(evaluation.result());values.add(evaluation.reasonCodes());values.add(evaluation.ruleVersion());
        for(String key:List.of("enabled","connectivity","health_code","has_alarm","source_id","source_mode","simulated","active_work","recovery_blocking_work","evidence_after"))values.add(state.get(key));
        return com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceCaseRepository.hash(write(values));
    }
    @Transactional(readOnly=true)
    public MessagePage messages(int page,int size,boolean unreadOnly,boolean pendingOnly){AuthUser actor=access.requireMonitoringOperate();access.requireMonitoringRead();if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw bad("分页参数无效");return repository.messages(actor,page,size,unreadOnly,pendingOnly);}
    @Transactional
    public ReadReceipt markRead(String taskId){AuthUser actor=access.requireMonitoringOperate();access.requireMonitoringRead();required(taskId,actor);repository.lock(taskId);required(taskId,actor);return repository.read(actor.userId(),taskId,clock.nowMillis());}
    private Row required(String taskId,AuthUser actor){if(taskId==null||taskId.isBlank()||taskId.length()>36)throw bad("待办编号无效");Row task=tasks.find(taskId,actor);if(task==null)throw new ApiException(HttpStatus.NOT_FOUND,"MAINTENANCE_OBJECT_NOT_FOUND","运维任务不存在或不可见");return task;}
    private void requireNoActiveWork(Row task){if(devices.hasActiveWork(task.deviceId()))throw conflict("MAINTENANCE_ACTIVE_WORK","设备仍有执行中的指令、调测或跟踪任务，请先核对实际结果");}
    private static void requireState(String actual,String expected){if(!expected.equals(actual))throw conflict("MAINTENANCE_INVALID_TRANSITION","当前运维阶段不能执行此动作");}
    private static Recovery result(String result,String reason,long now){return new Recovery(result,reason,now);}
    private static boolean fresh(Long at,long reported,long now){return at!=null&&at>=reported&&at<=now&&now-at<=300000;}
    private static String text(Map<String,Object> row,String key){return row.get(key)==null?null:row.get(key).toString();}
    private static Long number(Map<String,Object> row,String key){return row.get(key) instanceof Number value?value.longValue():null;}
    private static boolean bool(Map<String,Object> row,String key){return Boolean.TRUE.equals(row.get(key));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("Cannot serialize maintenance workflow",error);}}
    private static String safeId(String id){return id==null?null:id.substring(0,Math.min(36,id.length()));}
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
