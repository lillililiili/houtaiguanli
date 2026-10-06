package com.uav.lowaltitude.modules.alarm.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.AutoSms;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryEligibilityService.Eligibility;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/** 后台自动短信独立于页面和登录会话。claim/完成各自提交，渠道调用不占数据库事务。 */
@Service
public class AutoSmsService {
    private static final AccessDecision SYSTEM_SCOPE=new AccessDecision("system:auto-advisory-sms",ScopeMode.ALL);
    private static final String TRIGGER="ALARM_EVENT";
    /** 告警超过自动通知时效后，有人核对了最新情况并登记发送；只这一条任务不再按时效拦截。 */
    static final String RECHECK="MANUAL_RECHECK";
    /** 页面按这个开头识别“需核对最新情况”，改文案时同步改页面。 */
    public static final String STALE_REASON="事件已超过自动通知时效，需核对最新情况";
    public static final String FALSE_POSITIVE_REASON="已核实为误报，不需要发送飞手短信";
    private final com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
    private final AutoSmsRepository tasks;
    private final UavEventRepository events;
    private final UavAdvisoryRepository records;
    private final AutoSmsPolicy policy;
    private final AdvisorySmsPort sms;
    private final AppClock clock;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public AutoSmsService(AutoSmsRepository tasks,UavEventRepository events,UavAdvisoryRepository records,AutoSmsPolicy policy,
            AdvisorySmsPort sms,AppClock clock,ObjectMapper json,AuditService audit,PlatformTransactionManager manager,com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory) {
        this.directory=directory;
        this.tasks=tasks;this.events=events;this.records=records;this.policy=policy;this.sms=sms;this.clock=clock;this.json=json;this.audit=audit;this.tx=new TransactionTemplate(manager);
    }
    public void poll() {
        var candidates=policy.enabled()?tasks.candidates():tasks.sendingCandidates();
        for(String id:candidates) {
            try { process(id); } catch(RuntimeException failed) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("automatic SMS cycle failed for event {}; pending work will be reconciled",id);
            }
        }
    }
    /** 无 HTTP 调用、无用户身份即可执行；每个事件由数据库锁及稳定渠道键防重。 */
    public void process(String eventId) {
        Claim claim=tx.execute(s->claim(eventId));
        if(claim==null)return;
        AdvisorySmsPort.Delivery delivery=null;
        try { delivery=sms.simulateAutomatic(claim.mode(),claim.recipient().recipientName(),content(eventId),claim.providerKey()); }
        catch(RuntimeException failed) { /* 通道可能已受理，未知结果独立持久化，不能盲目重发。 */ }
        final AdvisorySmsPort.Delivery receipt=delivery;
        if(receipt!=null&&"SUBMITTED".equals(receipt.status()))return;
        tx.executeWithoutResult(s->finish(claim,receipt));
    }
    private Claim claim(String eventId) {
        EventRow event=events.lock(eventId,SYSTEM_SCOPE);if(event==null)return null;
        long now=clock.nowMillis();
        Task current=tasks.find(eventId);
        if(current!=null&&"SENDING".equals(current.status())) {
            if(current.leaseUntil()!=null&&current.leaseUntil()<now) tasks.finish(eventId,current.token(),"UNKNOWN","上次发送未取得明确结果，须先向通道对账，禁止盲目补发",null,now);
            return null;
        }
        if(!policy.enabled()||(current!=null&&Set.of("SIMULATED_DELIVERED","FAILED","UNKNOWN").contains(current.status())))return null;
        Eligibility eligible=eligible(event,current,false);
        tasks.initialize(eventId,now,AutoSmsPolicy.CODE);
        if(!eligible.allowed()) {tasks.block(eventId,eligible.status(),eligible.reason(),eligible.source(),eligible.evaluation(),eligible.observedAt(),now);return null;}
        var recipient=requiredPilot(eventId);
        if(recipient==null||!recipient.configured()){tasks.block(eventId,"BLOCKED",pilotReason(recipient),eligible.source(),eligible.evaluation(),eligible.observedAt(),now);return null;}
        String token=UUID.randomUUID().toString();
        tasks.claim(eventId,token,eligible.source(),eligible.evaluation(),eligible.observedAt(),now);
        directory.freezeAdvisoryTask("ADVISORY_SMS",eventId,recipient);
        return new Claim(eventId,event.sourceMode(),token,tasks.find(eventId).providerKey(),recipient);
    }
    private void finish(Claim claim,AdvisorySmsPort.Delivery delivery) {
        finish(claim,delivery,null);
    }
    private void finish(Claim claim,AdvisorySmsPort.Delivery delivery,Long deliveredAt) {
        EventRow event=events.lock(claim.eventId(),SYSTEM_SCOPE);if(event==null)return;
        Task current=tasks.find(claim.eventId());
        if(current==null||!claim.token().equals(current.token())||!"SENDING".equals(current.status()))return;
        long now=deliveredAt==null?clock.nowMillis():deliveredAt;
        if(delivery==null||!delivery.simulated()||!"SIMULATED_DELIVERED".equals(delivery.status())) {
            boolean failed=delivery!=null&&delivery.simulated()&&"FAILED".equals(delivery.status());
            tasks.finish(event.eventId(),claim.token(),failed?"FAILED":"UNKNOWN",failed?"模拟短信通道明确返回失败；当前条件仍满足时可申请补发":"短信发送结果未知，须先向通道对账，禁止盲目补发",null,now);
            audit.record(null,"AUTO_SMS","SYSTEM","alarm",failed?"auto_sms_failed":"auto_sms_unknown","uav_event",event.eventId(),"policy="+AutoSmsPolicy.CODE,"FAILED","","");return;
        }
        String recordId=UUID.randomUUID().toString();
        if(events.update(event.eventId(),event.version(),event.state(),Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1)throw new IllegalStateException("Event version changed under lock");
        records.appendAutomatic(recordId,event.eventId(),event.version()+1,now,content(event.eventId()),AutoSmsPolicy.CODE);
        directory.freezeAdvisoryRecord("ADVISORY_SMS",recordId,claim.recipient());
        tasks.finish(event.eventId(),claim.token(),"SIMULATED_DELIVERED","已收到模拟短信送达回执；不代表飞手阅读或目标已飞离",recordId,now);
        audit.record(null,"AUTO_SMS","SYSTEM","alarm","auto_sms_delivered","uav_event",event.eventId(),"policy="+AutoSmsPolicy.CODE+"; provider_key="+claim.providerKey()+"; simulated=true","SUCCESS","","");
    }
    /** Only the original pending attempt can consume an authenticated simulator receipt. */
    public boolean completeSimulatorReceipt(String eventId,String providerKey,String token,
            com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient,
            String outcome,Long deliveredAt,long requestedAt) {
        var event=events.lock(eventId,SYSTEM_SCOPE);if(event==null)return false;
        var task=tasks.find(eventId);long now=clock.nowMillis();
        if(task==null||token==null||!token.equals(task.token())||!java.util.Objects.equals(providerKey,task.providerKey())
                ||!"SENDING".equals(task.status())||task.leaseUntil()==null||task.leaseUntil()<now
                ||recipient==null||requestedAt!=task.updatedAt())return false;
        if("DELIVERED".equals(outcome)) {
            if(deliveredAt==null||deliveredAt<requestedAt||deliveredAt>now)return false;
            finish(new Claim(eventId,event.sourceMode(),token,providerKey,recipient),new AdvisorySmsPort.Delivery(true,"SIMULATED_DELIVERED"),deliveredAt);
        } else if(Set.of("FAILED","TIMEOUT").contains(outcome)) {
            finish(new Claim(eventId,event.sourceMode(),token,providerKey,recipient),new AdvisorySmsPort.Delivery(true,"FAILED".equals(outcome)?"FAILED":"UNKNOWN"));
        } else return false;
        return true;
    }
    /** 纯读取；不得在页面回读期间建任务或发送短信。 */
    public AutoSms overview(EventRow event,boolean mayRetry) {
        Task task=tasks.find(event.eventId());
        var target=recipient(event);
        // 误报不会再发短信：没有发送过的，直接写明不需要，不显示成“暂不满足条件”或“等待”。
        if("FALSE_POSITIVE".equals(event.state())&&(task==null||task.attempts()==0))
            return new AutoSms(policy.enabled(),"NOT_REQUIRED",FALSE_POSITIVE_REASON,null,task==null?null:task.updatedAt(),false,0,AutoSmsPolicy.CODE,null,null,null,target);
        if(!policy.enabled())return task!=null&&task.attempts()>0?new AutoSms(false,task.status(),task.reason(),task.triggeredAt(),task.updatedAt(),false,task.attempts(),AutoSmsPolicy.CODE,task.triggerSource(),task.evaluatedAt(),task.dataUpdatedAt(),target):new AutoSms(false,"DISABLED","后台自动短信尚未启用；"+policy.description(),null,null,false,0,AutoSmsPolicy.CODE,null,null,null,target);
        Eligibility e=eligible(event,task,false);
        // 只差时效这一条时，有权限的人核对最新情况后可以登记发送。
        boolean recheck=mayRetry&&stale(e)&&eligible(event,task,true).allowed();
        if(task==null)return new AutoSms(true,e.allowed()?"WAITING":e.status(),e.reason(),null,null,recheck,0,AutoSmsPolicy.CODE,e.source(),e.evaluation()==null?null:e.evaluation().evaluatedAt(),e.observedAt(),target);
        boolean retry=mayRetry&&Set.of("FAILED","UNAVAILABLE","BLOCKED").contains(task.status())&&(e.allowed()||recheck);
        String reason=task.reason(),status=task.status();
        if(!Set.of("SIMULATED_DELIVERED","SENDING","FAILED","UNKNOWN").contains(status)) {
            if(!e.allowed()){status=e.status();reason=e.reason();retry=recheck;}
            else if(Set.of("BLOCKED","UNAVAILABLE").contains(status)){status="WAITING";reason=e.reason();retry=false;}
        }
        return new AutoSms(true,status,reason,task.triggeredAt(),task.updatedAt(),retry,task.attempts(),AutoSmsPolicy.CODE,task.triggerSource(),task.evaluatedAt(),task.dataUpdatedAt(),target);
    }
    /** 调用者已经完成用户动作权限、范围、事件锁和版本检查，只排队不发短信。返回 true 表示这是超过时效后人工核对再发送。 */
    public boolean retry(EventRow event) {
        AutoSms view=overview(event,true);
        if(!view.canRetry())throw new ApiException(HttpStatus.CONFLICT,"AUTO_SMS_RETRY_BLOCKED",view.reason());
        long now=clock.nowMillis();
        Task task=tasks.find(event.eventId());
        if(stale(eligible(event,task,false))) {
            // 超过时效后的发送由人核对最新情况后登记，任务记下这次人工核对，后台据此发送这一条。
            tasks.initialize(event.eventId(),now,AutoSmsPolicy.CODE);
            tasks.queueRecheck(event.eventId(),RECHECK,now);
            return true;
        }
        tasks.queueRetry(event.eventId(),now);
        return false;
    }
    public boolean sending(String id){Task task=tasks.find(id);return task!=null&&"SENDING".equals(task.status());}
    public Long deliveredAt(String eventId){return tasks.deliveredAt(eventId);}
    /**
     * @param ignoreStale 只用来判断“除时效外是否都满足”，决定能否让人核对后登记发送；自动发送永远传 false。
     */
    private Eligibility eligible(EventRow event,Task task,boolean ignoreStale) {
        String source=task!=null&&RECHECK.equals(task.triggerSource())?RECHECK:TRIGGER;
        if(records.noCounterActive(event.eventId()))return new Eligibility(false,"BLOCKED",com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository.ACTIVE_REASON,source,null,null);
        if("FALSE_POSITIVE".equals(event.state()))return new Eligibility(false,"BLOCKED","已核实为误报，不发送飞手短信",source,null,null);
        if(!"CONFIRMED".equals(event.state()))return new Eligibility(false,"WAITING","事件尚未核实属实，核实后自动发送短信",source,null,null);
        // 告警接收后超过事件时效才走到发送（例如过了时效才核实，或通道很晚才接通）：情况可能已经变化，
        // 不再自动发短信和打电话，等人核对最新情况。页面总是先看到这条提示。
        if(!ignoreStale&&!RECHECK.equals(source)&&staleEvent(event.eventId())) {
            String stale=STALE_REASON+"：告警已超过"+(policy.eventMillis()/1000)+"秒，情况可能已经变化，系统不再自动发送短信和拨打电话";
            Eligibility rest=eligible(event,task,true);
            if(rest.allowed())return new Eligibility(false,"BLOCKED",stale,source,null,null);
            // 本来就发不了（通道未接通、没有可通知的飞手）：保留原来的状态和原因，通知阶段照旧进入待反制。
            return new Eligibility(false,rest.status(),stale+"；同时"+rest.reason(),rest.source(),rest.evaluation(),rest.observedAt());
        }
        if(!sms.automaticSimulationAvailable(event.sourceMode()))return new Eligibility(false,"UNAVAILABLE","正式短信渠道尚未接入，不能把模拟送达写成真实通知",source,null,null);
        var pilot=requiredPilot(event.eventId());
        if(pilot==null||!pilot.configured())return new Eligibility(false,"BLOCKED",pilotReason(pilot),source,null,null);
        return new Eligibility(true,"WAITING",RECHECK.equals(source)?"已核对最新情况并登记发送，等待后台发送":"告警已建立，等待后台自动模拟发送",source,null,null);
    }
    private boolean staleEvent(String eventId) {
        try {
            Long receivedAt=tasks.facts(eventId).receivedAt();
            return receivedAt!=null&&clock.nowMillis()-receivedAt>policy.eventMillis();
        } catch(org.springframework.dao.EmptyResultDataAccessException unavailable) { return false; }
    }
    private static boolean stale(Eligibility e){return e!=null&&!e.allowed()&&e.reason()!=null&&e.reason().startsWith(STALE_REASON);}
    /** 只接受已核验的执行飞手。没有飞手、联系方式未核验或名册不可用时不发送，也不改用单位联系人。 */
    private com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot requiredPilot(String eventId) {
        try {
            Evaluation evaluation=tasks.latestEvaluation(eventId);
            return directory.forPilotEvent("ADVISORY_SMS",eventId,evaluation==null?null:evaluation.id());
        } catch(ApiException unavailable) { return null; }
    }
    private static String pilotReason(com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot pilot) {
        return pilot!=null&&pilot.blockedReason()!=null&&!pilot.blockedReason().isBlank()?pilot.blockedReason():"没有可通知的执行飞手，不能发送短信";
    }
    private static String content(String id){return "【低空安全提醒·模拟】发现疑似违规飞行，请按现场管理要求停止违规飞行，安全飞离相关区域或降落，并配合核查。";}
    /** 只读：上级计划没有提供执行飞手或飞手电话（BLOCK-03）。不影响发送资格，阻断仍按 eligible 的原因。 */
    public boolean pilotContactMissing(EventRow event){return directory.pilotContactMissing(event.eventId());}
    public com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot currentRecipient(EventRow event){return directory.currentPilotEvent("ADVISORY_SMS",event.eventId());}
    public com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient(EventRow event){var recorded=directory.advisoryHistoryRecipient("ADVISORY_SMS",event.eventId());if(recorded!=null)return recorded;var task=tasks.find(event.eventId());if(task!=null&&task.attempts()>0)return null;var evaluation=tasks.latestEvaluation(event.eventId());return directory.forPilotEvent("ADVISORY_SMS",event.eventId(),evaluation==null?null:evaluation.id());}
    private record Claim(String eventId,String mode,String token,String providerKey,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient) { }
}
