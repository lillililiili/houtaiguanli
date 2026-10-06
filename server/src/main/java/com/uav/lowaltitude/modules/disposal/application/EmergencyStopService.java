package com.uav.lowaltitude.modules.disposal.application;

import java.time.ZoneOffset;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.device.application.DeviceStopCoordinator;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.api.EmergencyStopDtos.*;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.*;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EmergencyStopService {
    private static final Set<String> ACTIVE=Set.of("REQUESTED","APPROVED","EXECUTING");
    private static final String PENDING="操作员急停本次处置，原因待补充";
    private static final String SHARED_DEVICE_NOTE="同一台设备上另一起处置按了急停，已向设备下发全关（整台设备的输出都会关掉）。"
            +"本授权没有撤销，但要等现场核查确认设备已停下，才能再下发启动。";
    private static final Logger log=LoggerFactory.getLogger(EmergencyStopService.class);
    private final AccessControlService access;
    private final UavEventRepository events;
    private final EmergencyStopRepository stops;
    private final DisposalRepository authorizations;
    private final DeviceRepository devices;
    private final DisposalExecutionGateway gateway;
    private final DeviceStopCoordinator commands;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate separate;
    public EmergencyStopService(AccessControlService access,UavEventRepository events,EmergencyStopRepository stops,
            DisposalRepository authorizations,DeviceRepository devices,DisposalExecutionGateway gateway,
            DeviceStopCoordinator commands,AppClock clock,AuditService audit,ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.access=access;this.events=events;this.stops=stops;this.authorizations=authorizations;this.devices=devices;
        this.gateway=gateway;this.commands=commands;this.clock=clock;this.audit=audit;this.json=json;
        this.separate=new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional(readOnly=true)
    public Overview overview(String eventId) {
        scope(eventId,false);
        return view(eventId);
    }
    @Transactional
    public Overview stop(String eventId,String key,String raw) {
        // Remember only this request's visible active chain before waiting for the event lock.
        // A device reply may complete it while we wait; that must not discard an already requested stop.
        AccessDecision initialScope=access.require(PermissionCode.DISPOSAL_STOP);
        if(events.find(eventId,initialScope)==null) throw missing();
        Set<String> observed=new HashSet<>();
        current(rows(eventId,false)).forEach(row->observed.add(row.authorizationId()));
        scope(eventId,true);
        String note=parse(raw,false);
        if(replay(key,"STOP:"+eventId,note)) return view(eventId);
        List<AuthorizationRow> rows=rows(eventId,true);
        List<AuthorizationRow> current=current(rows,observed);
        if(current.isEmpty()) {
            Map<String,Object> existing=stops.latest(eventId);
            if(existing!=null) { remember(key,"STOP:"+eventId,note,text(existing,"stop_id"));return view(eventId); }
            throw conflict("NO_ACTIVE_DISPOSAL","本事件没有正在执行或待执行的反制处置");
        }
        if(revokeOnly(current)&&current.stream().anyMatch(r->!actorPending().equals(r.requestedBy())))
            throw conflict("NOT_REQUESTER","只有发起人可以撤销尚未执行的反制");
        // 急停只看本事件实际在做的事：同一设备上别的事件挂着的授权不能挡住急停（ZT-42）。
        // 设备锁仍按 事件→授权→设备→指令 的顺序取，与执行、续链和排队中的启动互斥。
        for(String device:current.stream().map(AuthorizationRow::deviceId).filter(Objects::nonNull).distinct().sorted().toList())
            stops.lockDevice(device);
        String id=UUID.randomUUID().toString();long now=clock.nowMillis();AuthUser actor=AuthContext.require();
        stops.insert(id,eventId,actor.userId(),now,PENDING);
        Set<String> taskDevices=new HashSet<>();
        Map<String,String> allOff=new TreeMap<>();
        // Executed tasks first so a pending authorization cannot hide an active action on the same device.
        List<AuthorizationRow> ordered=new ArrayList<>(current);
        ordered.sort(Comparator.comparing((AuthorizationRow r)->!physical(r)));
        for(AuthorizationRow row:ordered) {
            if(!ACTIVE.contains(row.status())&&!"COMPLETED".equals(row.status())&&!"FAILED".equals(row.status())) continue;
            stops.cover(id,row.authorizationId());
            if(ACTIVE.contains(row.status())) {
                if(authorizations.transition(row.authorizationId(),row.version(),"STOPPED",clock.now().atOffset(ZoneOffset.UTC),
                        null,"STOPPED_BY_OPERATOR",PENDING)!=1) throw conflict("VERSION_CONFLICT","处置已更新，请查询后重试");
                authorizations.insertEvent(UUID.randomUUID().toString(),row.authorizationId(),"STOP",actor.userId(),PENDING,
                        "{\"status\":\"STOPPED\"}",clock.now().atOffset(ZoneOffset.UTC));
            }
            commands.cancelAuthorizationStarts(row.authorizationId(),now);
            String device=row.deviceId()==null?"manual-"+row.authorizationId():row.deviceId();
            if(!taskDevices.add(device)) continue;
            Map<String,Object> hardware=row.deviceId()==null?null:devices.find(row.deviceId());
            Dispatch dispatched=physical(row)?dispatch(row.deviceId(),row.channel(),row.authorizationId(),id+"-"+row.authorizationId()+"-0",hardware)
                    :new Dispatch(null,"NOT_REQUIRED","尚未执行，已阻止启动，无需设备停止");
            stops.task(id,device,row.authorizationId(),hardware==null?"人工处置现场核查":text(hardware,"name"),row.channel(),
                    hardware==null?row.sourceMode():text(hardware,"source_mode"),hardware==null||bool(hardware,"simulated"),
                    dispatched.commandId(),dispatched.status(),dispatched.detail());
            if("QUEUED".equals(dispatched.status())) allOff.put(row.deviceId(),dispatched.commandId());
        }
        append(id,"STOP",PENDING);remember(key,"STOP:"+eventId,note,id);
        noteSharedDevice(eventId,allOff,actor.userId());
        return view(eventId);
    }
    @Transactional
    public Overview followUp(String eventId,String stopId,String deviceId,String action,String key,String raw) {
        scope(eventId,true);
        if(stops.stop(eventId,stopId)==null) throw missing();
        String note=parse(raw,!"RETRY".equals(action));
        String operation=action+":"+eventId+":"+stopId+":"+Objects.toString(deviceId,"");
        if(replay(key,operation,note)) return view(eventId);
        if("NOTE".equals(action)) stops.note(stopId,note);
        else {
            Map<String,Object> task=stops.tasks(stopId).stream().filter(t->deviceId.equals(text(t,"device_id"))).findFirst().orElseThrow(EmergencyStopService::missing);
            Device state=device(task,true);
            if("MANUAL_CONFIRM".equals(action)) {
                if(!state.allowedActions().contains("MANUAL_CONFIRM")) throw conflict("INVALID_TRANSITION","该设备已确认或无需停止");
                // Do not leave a queued all-off to run after a later, freshly authorized action.
                commands.cancelDelivery(text(task,"command_id"),clock.nowMillis());
                stops.confirm(stopId,deviceId,AuthContext.require().userId(),clock.nowMillis(),note);
            } else {
                if(!state.allowedActions().contains("RETRY_STOP")) throw conflict("RETRY_NOT_ALLOWED","先查询设备反馈；当前状态不允许重试停止");
                stops.lockDevice(deviceId);
                Dispatch result=dispatch(deviceId,text(task,"channel"),text(task,"authorization_id"),
                        stopId+"-"+text(task,"authorization_id")+"-"+(number(task,"attempt")+1),devices.find(deviceId));
                stops.retry(stopId,deviceId,result.commandId(),result.status(),result.detail());
                note="重试停止："+deviceId+"；"+result.detail();
                if("QUEUED".equals(result.status())) noteSharedDevice(eventId,Map.of(deviceId,result.commandId()),AuthContext.require().userId());
            }
        }
        append(stopId,action,note);
        // Store normalized request content, not the generated retry event description.
        remember(key,operation,parse(raw,!"RETRY".equals(action)),stopId);
        return view(eventId);
    }
    private Overview view(String eventId) {
        boolean canStop=canStop();List<AuthorizationRow> rows=rows(eventId,false);
        List<AuthorizationRow> current=current(rows);
        boolean active=current.stream().anyMatch(r->ACTIVE.contains(r.status()));
        boolean requiresDeviceStop=current.stream().anyMatch(EmergencyStopService::physical);
        String block=canStop?null:"无停止处置权限";
        boolean mayAct=canStop&&(!revokeOnly(current)||isRequester(current));
        List<String> actions=new ArrayList<>();if(active&&block==null&&mayAct) actions.add("EMERGENCY_STOP");
        Map<String,Object> latest=stops.latest(eventId);Stop stop=null;
        if(latest!=null) {
            String id=text(latest,"stop_id");if(canStop) actions.add("ADD_NOTE");
            if(current.isEmpty()) {
                List<String> covered=stops.coveredIds(id);
                current=rows.stream().filter(r->covered.contains(r.authorizationId())).toList();
            }
            stop=new Stop(id,number(latest,"requested_at"),text(latest,"requested_by_name"),bool(latest,"reason_pending"),text(latest,"note"),
                    stops.tasks(id).stream().map(t->device(t,canStop)).toList(),stops.events(id).stream().map(e->new Event(text(e,"event_id"),text(e,"kind"),
                    text(e,"actor_name"),number(e,"occurred_at"),text(e,"note"))).toList());
        }
        return new Overview(eventId,active,requiresDeviceStop,actions,block,current.stream().map(r->new Authorization(r.authorizationId(),r.actionType(),r.status(),r.deviceId(),r.channel())).toList(),stop);
    }
    /** Only the active chain and its explicitly linked parent; old event history is never a control target. */
    private List<AuthorizationRow> current(List<AuthorizationRow> rows) {
        return current(rows,Set.of());
    }
    private List<AuthorizationRow> current(List<AuthorizationRow> rows,Set<String> observed) {
        Set<String> selected=new HashSet<>();
        for(AuthorizationRow row:rows) if((ACTIVE.contains(row.status())
                || (observed.contains(row.authorizationId())&&Set.of("COMPLETED","FAILED").contains(row.status())))
                &&!stops.covered(row.authorizationId())) {
            selected.add(row.authorizationId());
            if("JAMMING".equals(row.actionType())) {
                String parent=stops.parent(row.authorizationId());
                if(parent!=null&&!stops.covered(parent))selected.add(parent);
            }
        }
        return rows.stream().filter(r->selected.contains(r.authorizationId())).toList();
    }
    /**
     * 全关作用于整台设备。同一设备上别的事件尚未了结的授权不替人撤销（急停人未必看得到那些事件，也无权收回别人的批准），
     * 只在它们的记录里如实补一条“已下发全关”；在这台设备的急停经现场核查确认之前，它们的执行、续链和排队中的启动
     * 都会被设备核查拦下（见 DisposalCommandGuard）。
     * 等急停事务提交后另起事务写：给别的授权记事件要碰那条授权，放在急停事务里会和对方的执行互相等锁。
     * 这条记录只是告知，写不成也不能影响已经提交的急停。
     */
    private void noteSharedDevice(String eventId,Map<String,String> allOff,String actorId) {
        if(allOff.isEmpty()) return;
        Runnable work=()->{
            try {
                separate.executeWithoutResult(status->allOff.forEach((deviceId,commandId)->{
                    for(String other:stops.othersOnDevice(deviceId,eventId))
                        authorizations.insertEvent(UUID.randomUUID().toString(),other,"DEVICE_ALL_OFF_ISSUED",actorId,SHARED_DEVICE_NOTE,
                                snapshot(deviceId,commandId),clock.now().atOffset(ZoneOffset.UTC));
                }));
            } catch(RuntimeException ex) {
                log.warn("emergency stop of event {} issued all-off, but other authorizations on the device were not annotated: {}",
                        eventId,ex.getMessage());
            }
        };
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { work.run(); }
            });
        else work.run();
    }
    private String snapshot(String deviceId,String commandId) {
        try { return json.writeValueAsString(Map.of("device_id",deviceId,"command_id",commandId,"source","EMERGENCY_STOP_OTHER_EVENT")); }
        catch(Exception ex) { throw new IllegalStateException("cannot serialize disposal event snapshot",ex); }
    }
    private Device device(Map<String,Object> task,boolean writable) {
        String status=text(task,"stop_status"),command=text(task,"command_status"),detail=text(task,"detail");
        if(command!=null) {
            status=switch(command) {case "QUEUED"->"QUEUED";case "SUCCEEDED"->"CONTROLLER_ALL_OFF_ACK";
                case "FAILED","CANCELLED"->"FAILED";case "TIMED_OUT"->"TIMED_OUT";default->"WAITING_FEEDBACK";};
            detail=switch(status) {case "QUEUED"->"停止命令正在排队";case "CONTROLLER_ALL_OFF_ACK"->"已收到全关确认，实际停机仍需现场核查";
                case "WAITING_FEEDBACK"->"已发出停止命令，等待设备反馈";default->Objects.toString(text(task,"command_detail"),"设备停止尚未确认");};
        }
        Long confirmed=task.get("confirmed_at")==null?null:number(task,"confirmed_at");
        if(confirmed!=null) {status="MANUALLY_CONFIRMED";detail="已登记现场核查确认停止";}
        List<String> actions=new ArrayList<>();actions.add("QUERY");
        if(writable&&confirmed==null&&!"NOT_REQUIRED".equals(status)) {
            actions.add("MANUAL_CONFIRM");
            if(Set.of("FAILED","TIMED_OUT","OFFLINE").contains(status)&&"COUNTERMEASURE_4CH".equals(text(task,"channel"))) actions.add("RETRY_STOP");
        }
        return new Device(text(task,"device_id"),text(task,"device_name"),text(task,"channel"),text(task,"source_mode"),bool(task,"simulated"),
                text(task,"command_id"),command,status,detail,actions,text(task,"confirmed_by_name"),confirmed,text(task,"confirmation_note"));
    }
    private Dispatch dispatch(String deviceId,String channel,String auth,String suffix,Map<String,Object> hardware) {
        if(hardware==null||!"COUNTERMEASURE_4CH".equals(channel)||!"live".equals(text(hardware,"source_mode"))
                ||!DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(text(hardware,"protocol_code")))
            return new Dispatch(null,"UNSUPPORTED","该处置不支持远程停止，请登记现场核查");
        DisposalExecutionGateway.Result result=gateway.stop4ch(AuthContext.require(),deviceId,"estop-"+suffix,auth,PENDING);
        if(result instanceof DisposalExecutionGateway.Accepted accepted) return new Dispatch(accepted.commandId(),"QUEUED","停止命令正在排队");
        DisposalExecutionGateway.Rejected rejected=(DisposalExecutionGateway.Rejected)result;
        return new Dispatch(null,"DEVICE_OFFLINE".equals(rejected.eventKind())?"OFFLINE":"UNSUPPORTED",rejected.detail());
    }
    private void scope(String eventId,boolean write) {
        AccessDecision decision=access.require(write?PermissionCode.DISPOSAL_STOP:PermissionCode.DISPOSAL_READ);
        if(write)stops.lockActor(AuthContext.require().userId());
        if((write?events.lock(eventId,decision):events.find(eventId,decision))==null) throw missing();
    }
    private List<AuthorizationRow> rows(String eventId,boolean lock) {
        AccessDecision decision;
        if(lock) decision=access.require(PermissionCode.DISPOSAL_STOP);
        else {
            try {decision=access.require(PermissionCode.DISPOSAL_READ);}
            catch(ApiException denied) {decision=access.require(PermissionCode.DISPOSAL_STOP);}
        }
        List<AuthorizationRow> result=new ArrayList<>();
        for(String id:stops.authorizationIds(eventId)) {
            AuthorizationRow row=lock?authorizations.lock(id,decision):authorizations.find(id,decision);
            if(lock&&row==null)throw missing();
            if(row!=null)result.add(row);
        }
        return result;
    }
    private boolean canStop() { try {access.require(PermissionCode.DISPOSAL_STOP);return true;}catch(ApiException denied){return false;} }
    /** 还没下发到设备时，按钮是撤销，只给发起人。 */
    private static boolean revokeOnly(List<AuthorizationRow> current) {
        return !current.isEmpty()&&current.stream().allMatch(r->Set.of("REQUESTED","APPROVED").contains(r.status()));
    }
    private boolean isRequester(List<AuthorizationRow> current) {
        String userId;try { userId=AuthContext.require().userId(); } catch(ApiException denied) { return false; }
        return current.stream().allMatch(r->userId.equals(r.requestedBy()));
    }
    private static String actorPending() { return AuthContext.require().userId(); }
    private boolean replay(String key,String operation,String note) {
        if(key==null||key.isBlank()||key.length()<8||key.length()>128)throw new ApiException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key 必须为8至128个字符");
        Map<String,Object> old=stops.request(AuthContext.require().userId(),key);
        if(old==null)return false;
        if(!operation.equals(text(old,"operation"))||!note.equals(text(old,"request_note")))throw conflict("IDEMPOTENCY_CONFLICT","同一幂等键对应了不同请求");
        return true;
    }
    private void remember(String key,String operation,String note,String stopId) {stops.request(AuthContext.require().userId(),key,operation,note,stopId);}
    private void append(String stopId,String action,String note) {
        AuthUser user=AuthContext.require();stops.event(UUID.randomUUID().toString(),stopId,action,user.userId(),clock.nowMillis(),note);
        String auditAction=switch(action) {case "STOP"->"emergency_stop_requested";case "NOTE"->"emergency_stop_note_added";
            case "RETRY"->"emergency_stop_retried";case "MANUAL_CONFIRM"->"emergency_stop_manually_confirmed";
            default->throw new IllegalArgumentException("Unknown emergency stop action");};
        audit.record(user.userId(),user.account(),user.roleCode(),"disposal",auditAction,
                "disposal_emergency_stop",stopId,note,"SUCCESS","","");
    }
    private String parse(String raw,boolean noteRequired) {
        try {
            JsonNode node;
            try(var parser=json.getFactory().createParser(raw==null?"{}":raw)) {
                node=json.readTree(parser);
                if(parser.nextToken()!=null)throw bad();
            }
            if(node==null||!node.isObject())throw bad();
            var names=node.fieldNames();while(names.hasNext())if(!noteRequired||!"note".equals(names.next()))throw bad();
            if(!noteRequired)return "";
            JsonNode value=node.get("note");if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>500)throw bad();
            return value.asText().trim();
        }catch(ApiException ex){throw ex;}catch(Exception ex){throw bad();}
    }
    private static boolean physical(AuthorizationRow row) {return Set.of("EXECUTING","COMPLETED","FAILED").contains(row.status())||row.executionCommandId()!=null;}
    private static String text(Map<String,Object> row,String name){return row.get(name)==null?null:String.valueOf(row.get(name));}
    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private static boolean bool(Map<String,Object> row,String name){return Boolean.TRUE.equals(row.get(name));}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","事件或停止记录不存在或不可见");}
    private static ApiException conflict(String code,String detail){return new ApiException(HttpStatus.CONFLICT,code,detail);}
    private static ApiException bad(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","请求体字段无效；说明须为1至500字");}
    private record Dispatch(String commandId,String status,String detail) { }
}
