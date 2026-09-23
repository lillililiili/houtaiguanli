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
    private final com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
    private final AdvisoryEligibilityService eligibility;
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
            AdvisorySmsPort sms,AppClock clock,ObjectMapper json,AuditService audit,PlatformTransactionManager manager,AdvisoryEligibilityService eligibility,com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory) {
        this.directory=directory;
        this.eligibility=eligibility;
        this.tasks=tasks;this.events=events;this.records=records;this.policy=policy;this.sms=sms;this.clock=clock;this.json=json;this.audit=audit;this.tx=new TransactionTemplate(manager);
    }
    public void poll() {
        var candidates=policy.enabled()?tasks.candidates(clock.nowMillis()-policy.eventMillis()):tasks.sendingCandidates();
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
        try { delivery=sms.simulate(claim.mode(),claim.recipient().recipientName(),content(eventId),claim.providerKey()); }
        catch(RuntimeException failed) { /* 通道可能已受理，未知结果独立持久化，不能盲目重发。 */ }
        final AdvisorySmsPort.Delivery receipt=delivery;
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
        Eligibility eligible=eligible(event,now);
        tasks.initialize(eventId,now,AutoSmsPolicy.CODE);
        if(!eligible.allowed()) {tasks.block(eventId,eligible.status(),eligible.reason(),eligible.source(),eligible.evaluation(),eligible.observedAt(),now);return null;}
        var recipient=directory.forPilotEvent("ADVISORY_SMS",eventId,eligible.evaluation()==null?null:eligible.evaluation().id());
        if(!recipient.configured()){tasks.block(eventId,"BLOCKED",recipient.blockedReason(),eligible.source(),eligible.evaluation(),eligible.observedAt(),now);return null;}
        String token=UUID.randomUUID().toString();
        tasks.claim(eventId,token,eligible.source(),eligible.evaluation(),eligible.observedAt(),now);
        directory.freezeAdvisoryTask("ADVISORY_SMS",eventId,recipient);
        return new Claim(eventId,event.sourceMode(),token,tasks.find(eventId).providerKey(),recipient);
    }
    private void finish(Claim claim,AdvisorySmsPort.Delivery delivery) {
        EventRow event=events.lock(claim.eventId(),SYSTEM_SCOPE);if(event==null)return;
        Task current=tasks.find(claim.eventId());
        if(current==null||!claim.token().equals(current.token())||!"SENDING".equals(current.status()))return;
        long now=clock.nowMillis();
        if(delivery==null||!delivery.simulated()||!"SIMULATED_DELIVERED".equals(delivery.status())) {
            boolean failed=delivery!=null&&delivery.simulated()&&"FAILED".equals(delivery.status());
            tasks.finish(event.eventId(),claim.token(),failed?"FAILED":"UNKNOWN",failed?"模拟短信通道明确返回失败；当前条件仍满足时可申请补发":"短信发送结果未知，须先向通道对账，禁止盲目补发",null,now);
            audit.record(null,"AUTO_SMS","SYSTEM","alarm",failed?"auto_sms_failed":"auto_sms_unknown","uav_event",event.eventId(),"policy="+AutoSmsPolicy.CODE,"FAILED","","");return;
        }
        String recordId=UUID.randomUUID().toString();
        if(events.update(event.eventId(),event.version(),event.state(),Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1)throw new IllegalStateException("Event version changed under lock");
        records.appendAutomatic(recordId,event.eventId(),event.version()+1,now,content(event.eventId()),AutoSmsPolicy.CODE);
        directory.freezeAdvisoryRecord("ADVISORY_SMS",recordId,claim.recipient());
        tasks.finish(event.eventId(),claim.token(),"SIMULATED_DELIVERED","后台已自动模拟发送短信；模拟送达不代表飞手阅读或目标已飞离",recordId,now);
        audit.record(null,"AUTO_SMS","SYSTEM","alarm","auto_sms_delivered","uav_event",event.eventId(),"policy="+AutoSmsPolicy.CODE+"; provider_key="+claim.providerKey()+"; simulated=true","SUCCESS","","");
    }
    /** 纯读取；不得在页面回读期间建任务或发送短信。 */
    public AutoSms overview(EventRow event,boolean mayRetry) {
        Task task=tasks.find(event.eventId());
        var target=recipient(event);
        if(!policy.enabled())return task!=null&&task.attempts()>0?new AutoSms(false,task.status(),task.reason(),task.triggeredAt(),task.updatedAt(),false,task.attempts(),AutoSmsPolicy.CODE,task.triggerSource(),task.evaluatedAt(),task.dataUpdatedAt(),target):new AutoSms(false,"DISABLED","后台自动短信尚未启用；"+policy.description(),null,null,false,0,AutoSmsPolicy.CODE,null,null,null,target);
        Eligibility e=eligible(event,clock.nowMillis());
        if(task==null)return new AutoSms(true,e.allowed()?"WAITING":e.status(),e.reason(),null,null,false,0,AutoSmsPolicy.CODE,e.source(),e.evaluation()==null?null:e.evaluation().evaluatedAt(),e.observedAt(),target);
        boolean retry=mayRetry&&Set.of("FAILED","UNAVAILABLE","BLOCKED").contains(task.status())&&e.allowed();
        String reason=task.reason(),status=task.status();
        if(!Set.of("SIMULATED_DELIVERED","SENDING","FAILED","UNKNOWN").contains(status)) {
            if(!e.allowed()){status=e.status();reason=e.reason();}
            else if(Set.of("BLOCKED","UNAVAILABLE").contains(status)){status="WAITING";reason=e.reason();retry=false;}
        }
        return new AutoSms(true,status,reason,task.triggeredAt(),task.updatedAt(),retry,task.attempts(),AutoSmsPolicy.CODE,task.triggerSource(),task.evaluatedAt(),task.dataUpdatedAt(),target);
    }
    /** 调用者已经完成用户动作权限、范围、事件锁和版本检查，只排队不发短信。 */
    public void retry(EventRow event) {
        AutoSms view=overview(event,true);
        if(!view.canRetry())throw new ApiException(HttpStatus.CONFLICT,"AUTO_SMS_RETRY_BLOCKED",view.reason());
        tasks.queueRetry(event.eventId(),clock.nowMillis());
    }
    public boolean sending(String id){Task task=tasks.find(id);return task!=null&&"SENDING".equals(task.status());}
    private Eligibility eligible(EventRow event,long now) {
        Eligibility result=eligibility.evaluate(event,now,"SMS_SIMULATED");
        if(!result.allowed())return result;
        if(!sms.simulationAvailable(event.sourceMode()))return new Eligibility(false,"UNAVAILABLE","正式短信渠道尚未接入，真实来源不能冒充模拟送达",result.source(),result.evaluation(),result.observedAt());
        return new Eligibility(true,"WAITING","触发条件满足，等待后台自动模拟发送；"+policy.description(),result.source(),result.evaluation(),result.observedAt());
    }
    private static String content(String id){return "【低空安全提醒·模拟】发现疑似违规飞行，请按现场管理要求停止违规飞行，安全飞离相关区域或降落，并配合核查。";}
    public com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot currentRecipient(EventRow event){return directory.currentPilotEvent("ADVISORY_SMS",event.eventId());}
    public com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient(EventRow event){var recorded=directory.advisoryHistoryRecipient("ADVISORY_SMS",event.eventId());if(recorded!=null)return recorded;var task=tasks.find(event.eventId());if(task!=null&&task.attempts()>0)return null;var evaluation=tasks.latestEvaluation(event.eventId());return directory.forPilotEvent("ADVISORY_SMS",event.eventId(),evaluation==null?null:evaluation.id());}
    private record Claim(String eventId,String mode,String token,String providerKey,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient) { }
}
