package com.uav.lowaltitude.modules.automationrule.application;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.automationrule.api.ResponseOrderDtos.*;
import com.uav.lowaltitude.modules.automationrule.infrastructure.ResponseOrderRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.ResponseOrderRepository.Window;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.platform.time.AppClock;

/** Shared ordering only. Evidence, human verification, permissions and authorization remain independent. */
@Service
public class ResponseOrderService {
    private static final AccessDecision SYSTEM=new AccessDecision("system:response-order",ScopeMode.ALL);
    private final ResponseOrderRepository repo;
    private final UavRiskCaseIdentity cases;
    private final UavEventRepository events;
    private final UavAdvisoryRepository evidence;
    private final AppClock clock;
    private final TransactionTemplate tx;
    private String cursor="";
    private String voiceCursor="";
    public ResponseOrderService(ResponseOrderRepository repo,UavRiskCaseIdentity cases,UavEventRepository events,
            UavAdvisoryRepository evidence,AppClock clock,PlatformTransactionManager manager) {
        this.repo=repo;this.cases=cases;this.events=events;this.evidence=evidence;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    /** Keyset scan includes still-active older/cross-day events; no browser GET starts the timer. */
    @Scheduled(fixedDelayString="${app.response-order.interval-millis:1000}")
    public void tick(){
        if(!repo.policy(false).enabled())return;
        var ids=repo.candidates(cursor,200);if(ids.isEmpty()){cursor="";return;}
        for(String id:ids){cursor=id;try{var refs=cases.cases(id);if(refs.isEmpty())initialize(id);else for(var ref:refs)cases.runWithCase(ref.riskCaseId(),()->initialize(id));}
            catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("response ordering unavailable for event {}",id);}}
    }
    private void initialize(String eventId){tx.executeWithoutResult(s->{cases.lockCases(eventId);var event=events.lock(eventId,SYSTEM);if(event!=null)ensureLocked(event);});}
    /** Called only from a mutation after case/event locks have been taken. */
    public Summary ensureLocked(EventRow event){
        var policy=repo.policy(false);var contexts=contexts(event);
        if(matchesAll(policy,event,contexts,clock.nowMillis())&&valid(event,contexts))
            for(var c:contexts)repo.start(c.key(),event.eventId(),policy,clock.nowMillis());
        return summary(event,policy,contexts);
    }
    /** Pure read projection, suitable for repeatable-read overview transactions. */
    public Summary summary(String eventId){
        var event=events.find(eventId,SYSTEM);var policy=repo.policy(false);
        return event==null?normal(policy,"当前风险不存在"):summary(event,policy,contexts(event));
    }
    public boolean counterPriority(String eventId){return summary(eventId).counterPriority();}
    public synchronized List<String> pendingVoiceCandidates(){
        if(!repo.policy(false).enabled())return List.of();
        var ids=repo.candidates(voiceCursor,200);
        voiceCursor=ids.isEmpty()?"":ids.get(ids.size()-1);
        return ids;
    }
    private Summary summary(EventRow event,Policy policy,List<Context> contexts){
        long now=clock.nowMillis();
        if(!matchesAll(policy,event,contexts,now))return normal(policy,"通知优先；按现有短信、电话及反制条件办理");
        var windows=contexts.stream().map(c->repo.window(c.key())).filter(Objects::nonNull).toList();
        Long began=windows.stream().map(Window::startedAt).min(Long::compare).orElse(null);
        Long deadline=windows.stream().map(Window::deadlineAt).max(Long::compare).orElse(null);
        int wait=windows.stream().mapToInt(Window::maxWaitSeconds).max().orElse(policy.maxWaitSeconds());
        if(!valid(event,contexts))return summary(policy,"PAUSED","风险尚未核实或当前依据无效，暂停通知及新动作",began,deadline,wait,false,false);
        if(windows.size()!=contexts.size())return summary(policy,"WAITING_START","反制优先，等待后台登记本风险优先期限",began,deadline,wait,true,false);
        String status="START_CONFIRMED";
        for(var context:contexts){var window=repo.window(context.key());
            var start=repo.startEvidence(context.caseId(),context.events(),contexts.size()==1,window.startedAt(),now);
            String one=start.started()?"START_CONFIRMED":start.failed()?"START_FAILED":
                    repo.noCounterDevice(event.ownerOrgId(),event.districtId(),event.sourceMode())?"START_UNAVAILABLE":
                    now>=window.deadlineAt()?"WAIT_TIMEOUT":"WAITING_START";
            if("WAITING_START".equals(one)){status=one;break;}
            if(!"START_CONFIRMED".equals(one))status=one;
        }
        String reason=switch(status){
            case "START_CONFIRMED"->"已收到设备真实启动回执，风险仍持续，转电话通知资格检查";
            case "START_FAILED"->"设备明确启动失败，转电话通知资格检查";
            case "START_UNAVAILABLE"->"当前范围没有已启用的反制设备，转电话通知资格检查";
            case "WAIT_TIMEOUT"->"等待超过"+wait+"秒，转电话通知；符合条件仍可继续反制";
            default->"反制优先，电话等待启动确认；短信并行发送";
        };
        return summary(policy,status,reason,began,deadline,wait,true,!"WAITING_START".equals(status));
    }
    private boolean valid(EventRow event,List<Context> contexts){
        if(cases.hasCases(event.eventId())?!cases.confirmedForDisposal(event.eventId()):!"CONFIRMED".equals(event.state()))return false;
        for(var context:contexts){
            if(Set.of("UNKNOWN","CLEARED","ENDED").contains(context.stage())||!context.confirmed())return false;
            String block=evidence.counterBlockReason(context.actionEvent());
            if(block==null||!block.isEmpty())return false;
        }
        return true;
    }
    private boolean matchesAll(Policy p,EventRow event,List<Context> contexts,long now){
        if(!p.enabled()||contexts.isEmpty()||!riskMatches(p.minimumRisk(),repo.severity(event.eventId()))||!timeMatches(p,now))return false;
        for(var c:contexts){
            boolean predicted="PREDICTED".equals(c.stage());
            if(predicted?!p.predictionEnabled():!p.observedEnabled())return false;
            if("AIRSPACES".equals(p.scopeMode())&&(c.airspace()==null||!p.airspaceIds().contains(c.airspace())))return false;
        }
        return true;
    }
    static boolean riskMatches(String minimum,String risk){return "HIGH".equals(risk)||"MEDIUM".equals(minimum)&&"MEDIUM".equals(risk);}
    static boolean timeMatches(Policy p,long now){
        if("ALL_DAY".equals(p.scheduleMode()))return true;
        LocalTime time=Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime(),start=LocalTime.parse(p.startTime()),end=LocalTime.parse(p.endTime());
        return start.isBefore(end)?!time.isBefore(start)&&time.isBefore(end):!time.isBefore(start)||time.isBefore(end);
    }
    private List<Context> contexts(EventRow event){
        var refs=cases.cases(event.eventId());
        if(refs.isEmpty())return List.of(new Context("event:"+event.eventId(),null,null,"OBSERVED",event.eventId(),List.of(event.eventId()),"CONFIRMED".equals(event.state())));
        return refs.stream().map(c->new Context("case:"+c.riskCaseId(),c.riskCaseId(),c.airspaceId(),c.stage(),c.actionEventId(),c.eventIds(),c.confirmed())).toList();
    }
    private static Summary summary(Policy p,String status,String reason,Long began,Long deadline,int wait,boolean counter,boolean voice){
        return new Summary("COUNTER_FIRST",true,status,reason,p.version(),began,deadline,wait,voice,voice?reason:null,counter,status,deadline,voice);
    }
    private static Summary normal(Policy p,String reason){return new Summary("NOTIFY_FIRST",false,"NOTIFY_FIRST",reason,p.version(),null,null,p.maxWaitSeconds(),true,null,false,"NOTIFY_FIRST",null,true);}
    private record Context(String key,String caseId,String airspace,String stage,String actionEvent,List<String> events,boolean confirmed) { }
}
