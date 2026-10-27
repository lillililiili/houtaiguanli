package com.uav.lowaltitude.modules.alarm.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.AutoVoice;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryEligibilityService.Eligibility;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Recording;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoVoiceRepository.Task;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/** 持久化电话任务；发送前在风险锁内重验，回执不会恢复已经结束的风险。 */
@Service
public class AutoVoiceService {
    private com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService responseOrder;
    private UavRiskCaseIdentity riskCases;
    @org.springframework.beans.factory.annotation.Autowired
    public void responseOrder(com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService responseOrder,UavRiskCaseIdentity riskCases){this.responseOrder=responseOrder;this.riskCases=riskCases;}
    private com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPredictionEventGate(com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions) { this.predictions=predictions; }
    private static final AccessDecision SYSTEM_SCOPE=new AccessDecision("system:auto-advisory-voice",ScopeMode.ALL);
    private static final Set<String> TERMINAL=Set.of("SIMULATED_PLAYED","FAILED","UNKNOWN");
    private final com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
    private final AutoVoiceRepository tasks;
    private final com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository noCounter;
    private final UavEventRepository events;
    private static final long WATCH_MILLIS=com.uav.lowaltitude.modules.alarm.domain.NotifyFlow.SMS_WATCH_MILLIS;
    private static final String TRIGGER="SMS_THEN_WATCH";
    static final String STALE_REASON="短信因超过自动通知时效没有自动发送，电话也不自动拨打；请先核对最新情况";
    private final AutoSmsRepository smsTasks;
    private final PilotDepartureWatch departure;
    private final AutoVoicePolicy policy;
    private final AdvisoryVoiceRecording recordings;
    private final AdvisoryVoicePort voice;
    private final AppClock clock;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public AutoVoiceService(AutoVoiceRepository tasks,UavEventRepository events,AutoSmsRepository smsTasks,PilotDepartureWatch departure,AutoVoicePolicy policy,
            AdvisoryVoiceRecording recordings,AdvisoryVoicePort voice,AppClock clock,AuditService audit,PlatformTransactionManager manager,com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory,com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository noCounter) {
        this.noCounter=noCounter;
        this.directory=directory;
        this.tasks=tasks;this.events=events;this.smsTasks=smsTasks;this.departure=departure;this.policy=policy;this.recordings=recordings;
        this.voice=voice;this.clock=clock;this.audit=audit;this.tx=new TransactionTemplate(manager);
    }
    public void poll() {
        var candidates=policy.enabled()?tasks.candidates():tasks.callingCandidates();
        if(policy.enabled()&&responseOrder!=null){var all=new java.util.LinkedHashSet<>(candidates);all.addAll(responseOrder.pendingVoiceCandidates());candidates=new java.util.ArrayList<>(all);}
        for(String id:candidates) {
            try {process(id);} catch(RuntimeException failed) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("automatic voice cycle failed for event {}; pending work requires reconciliation",id);}
        }
        for(String id:tasks.departureCandidates()) {
            try { eachCase(id,()->tx.executeWithoutResult(s->{String owner=taskOwner(id,"VOICE");if(riskCases!=null)riskCases.lockCases(owner);var event=events.lock(owner,SYSTEM_SCOPE);if(event!=null)captureDeparture(event,tasks.find(owner),clock.nowMillis());})); }
            catch(RuntimeException unavailable) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("departure observation unavailable for event {}",id); }
        }
    }
    private String taskOwner(String eventId,String channel){return riskCases==null?eventId:riskCases.notificationTaskOwner(eventId,channel);}
    public com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation departureSnapshot(String id) { return tasks.departure(taskOwner(id,"VOICE")); }
    /** A recorded decision ends this notification cycle; it is not a claim of a currently fresh position. */
    private boolean captureDeparture(EventRow event,Task task,long now) {
        if(tasks.departure(event.eventId())!=null)return true;
        boolean confirmed=riskCases!=null&&riskCases.hasCases(event.eventId())?riskCases.confirmedForDisposal(event.eventId()):"CONFIRMED".equals(event.state());
        if(!confirmed||(task!=null&&"CALLING".equals(task.status())))return false;
        Long smsAt=smsTasks.deliveredAt(taskOwner(event.eventId(),"SMS"));
        if(smsAt==null)return false;
        boolean played=task!=null&&"SIMULATED_PLAYED".equals(task.status())&&task.playbackCompletedAt()!=null;
        if(task!=null&&task.attempts()>0&&!played)return false;
        long since=played?task.playbackCompletedAt():smsAt;
        long deadline=since+(played?com.uav.lowaltitude.modules.alarm.domain.NotifyFlow.CALL_WATCH_MILLIS:WATCH_MILLIS);
        if(now<deadline)return false;
        if(task!=null)tasks.checkedDeparture(event.eventId(),now);
        var assessment=departure.assessment(event.eventId(),smsAt,since,now);
        if(assessment==null||assessment.presence()!=PilotDepartureWatch.Presence.LEFT||assessment.position()==null
                ||assessment.position().observedAt()<since||assessment.position().observedAt()>now)return false;
        tasks.initialize(event.eventId(),now,AutoVoicePolicy.CODE);
        var value=new com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.DepartureObservation(event.eventId(),played?"VOICE":"SMS","RECORDED","LEFT",since,deadline,now,assessment.position(),assessment.basis());
        if(tasks.recordDeparture(value))audit.record(null,"DEPARTURE_WATCH","SYSTEM","alarm","notification_departure_recorded","uav_event",event.eventId(),"channel="+value.channel()+"; observed_at="+value.position().observedAt()+"; basis="+value.basis(),"SUCCESS","","");
        return true;
    }
    public void process(String id) {
        eachCase(id,()->processScoped(id));
    }
    private void eachCase(String eventId,Runnable work){
        if(riskCases==null||riskCases.selectedCase()!=null){work.run();return;}
        var refs=riskCases.cases(eventId);
        if(refs.isEmpty())work.run();else for(var ref:refs)riskCases.runWithCase(ref.riskCaseId(),work);
    }
    private void processScoped(String requestedEventId) {
        String owner=riskCases==null?null:riskCases.notificationTaskOwner(requestedEventId,"VOICE");
        final String id=owner==null?requestedEventId:owner;
        Claim claim=tx.execute(s->claim(id));if(claim==null)return;
        var sentClaim=new java.util.concurrent.atomic.AtomicReference<>(claim);
        AdvisoryVoicePort.Delivery delivery=null;
        try {
            delivery=tx.execute(s->{
                if(riskCases!=null)riskCases.lockCases(id);
                if(predictions!=null)predictions.lockEpisode(id);
                var current=events.lock(id,SYSTEM_SCOPE);
                if(current==null)return null;
                Task pending=tasks.find(id);
                if(pending==null||!claim.token().equals(pending.token())||!"CALLING".equals(pending.status()))return null;
                Recording currentRecording=tasks.sendStarted(id)?claim.recording():recording(current);
                String block=predictions==null?null:predictions.notificationBlock(id);
                if(predictions!=null&&predictions.notificationClaimInterrupted(id,claim.predictionRevision()))
                    block="本次待拨电话已因预测风险变化取消，等待当前条件重新检查";
                long checkedAt=clock.nowMillis();
                var order=responseOrder==null?null:responseOrder.summary(current.eventId());
                var eligibility=eligible(current,checkedAt,pending,currentRecording,order);
                if(!eligibility.allowed())block=eligibility.reason();
                if(block!=null&&!block.isEmpty()) {
                    Task task=tasks.find(id);
                    if(task!=null&&claim.token().equals(task.token())&&"CALLING".equals(task.status()))
                        tasks.block(id,new Eligibility(false,"WAITING",block,TRIGGER,null,null),clock.nowMillis());
                    return null;
                }
                var recipient=tasks.sendStarted(id)?claim.recipient():requiredPilot(id);
                if(recipient==null||!recipient.configured()){
                    tasks.block(id,new Eligibility(false,"WAITING","飞手联系方式已变化，等待当前条件重新检查",TRIGGER,null,null),clock.nowMillis());
                    return null;
                }
                var orderBasis=order!=null&&order.matched()
                        ?new com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.ResponseOrderBasis(order.status(),order.reason(),order.maxWaitSeconds(),
                            order.priorityStartedAt(),order.priorityDeadlineAt(),order.policyVersion(),checkedAt):null;
                tasks.prepareSend(id,claim.token(),currentRecording,clock.nowMillis(),orderBasis);
                directory.freezeAdvisoryTask("ADVISORY_VOICE",id,recipient);
                sentClaim.set(new Claim(id,current.sourceMode(),claim.token(),claim.providerKey(),currentRecording,claim.startedAt(),recipient,claim.predictionRevision()));
                // Persist the exact attempted content even if the channel outcome is unknown.
                try{return voice.simulate(current.sourceMode(),recipient,currentRecording,claim.providerKey());}
                catch(RuntimeException uncertain){return null;}
            });
        }
        catch(RuntimeException uncertain) { /* 可能已经受理，不能把未知直接当失败并重拨。 */ }
        final var receipt=delivery;
        if(receipt!=null&&"SUBMITTED".equals(receipt.status()))return;
        tx.executeWithoutResult(s->finish(sentClaim.get(),receipt));
    }
    /** Preserve the frozen recipient and recording; a newer claim must never consume an older call. */
    public boolean completeSimulatorReceipt(String eventId,String providerKey,String token,
            com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient,Recording recording,
            String messageId,String outcome,Long answeredAt,Long completedAt,long requestedAt) {
        return riskCases==null?completeReceiptScoped(eventId,providerKey,token,recipient,recording,messageId,outcome,answeredAt,completedAt,requestedAt):
                riskCases.withReceipt(eventId,"VOICE",providerKey,()->completeReceiptScoped(eventId,providerKey,token,recipient,recording,messageId,outcome,answeredAt,completedAt,requestedAt));
    }
    private boolean completeReceiptScoped(String eventId,String providerKey,String token,
            com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient,Recording recording,
            String messageId,String outcome,Long answeredAt,Long completedAt,long requestedAt) {
        if(riskCases!=null)riskCases.lockCases(eventId);
        var event=events.lock(eventId,SYSTEM_SCOPE);if(event==null)return false;
        var task=tasks.find(eventId);long now=clock.nowMillis();
        if(task==null||token==null||!token.equals(task.token())||!java.util.Objects.equals(providerKey,task.providerKey())
                ||!Set.of("CALLING","UNKNOWN").contains(task.status())||task.leaseUntil()==null
                ||requestedAt!=task.leaseUntil()-60000||recipient==null||recording==null||!task.recordingMatches(recording))return false;
        if("ANSWERED".equals(outcome)) {
            if(answeredAt==null||answeredAt<requestedAt||answeredAt>now||task.answeredAt()!=null)return false;
            tasks.answer(eventId,token,messageId,answeredAt,now);return true;
        }
        if("PLAYED".equals(outcome)) {
            if(answeredAt==null||completedAt==null||answeredAt<requestedAt||completedAt<answeredAt||completedAt>now
                    ||task.answeredAt()==null||!task.answeredAt().equals(answeredAt))return false;
        } else if(!Set.of("FAILED","TIMEOUT").contains(outcome))return false;
        finish(new Claim(eventId,event.sourceMode(),token,providerKey,recording,requestedAt,recipient),
                new AdvisoryVoicePort.Delivery(true,"PLAYED".equals(outcome)?"SIMULATED_PLAYED":"FAILED".equals(outcome)?"FAILED":"UNKNOWN",messageId,answeredAt,completedAt));
        return true;
    }
    private Claim claim(String id) {
        if(riskCases!=null)riskCases.lockCases(id);
        if(predictions!=null)predictions.lockEpisode(id);
        EventRow event=events.lock(id,SYSTEM_SCOPE);if(event==null)return null;
        if(responseOrder!=null)responseOrder.ensureLocked(event);
        long now=clock.nowMillis();Task task=tasks.find(id);
        if(captureDeparture(event,task,now))return null;
        if(task!=null&&"CALLING".equals(task.status())) {
            if(task.leaseUntil()!=null&&task.leaseUntil()<now) {
                tasks.finish(id,task.token(),"UNKNOWN","上次外呼未取得明确结果，须先向通道对账，禁止盲目补呼",null,null,null,null,now);
                audit.record(null,"AUTO_VOICE","SYSTEM","alarm","auto_voice_unknown","uav_event",id,"lease_expired; policy="+AutoVoicePolicy.CODE,"FAILED","","");
            }
            return null;
        }
        if(!policy.enabled()||(task!=null&&(TERMINAL.contains(task.status())
                ||("BLOCKED".equals(task.status())&&task.attempts()>0))))return null;
        Recording recording=recording(event);Eligibility e=eligible(event,now,task,recording);
        tasks.initialize(id,now,AutoVoicePolicy.CODE);
        if(!e.allowed()){tasks.block(id,e,now);return null;}
        var recipient=requiredPilot(id);
        if(recipient==null||!recipient.configured()){tasks.block(id,blocked(e,"BLOCKED",recipient==null||recipient.blockedReason()==null||recipient.blockedReason().isBlank()?"没有可通知的执行飞手，不能拨打电话":recipient.blockedReason()),now);return null;}
        if(riskCases!=null&&!riskCases.claimChannel(id,"VOICE")){tasks.block(id,blocked(e,"BLOCKED","同次风险已有电话任务，保留原任务及真实回执，不重复拨打"),now);return null;}
        String token=UUID.randomUUID().toString();tasks.claim(id,token,e,recording,now);
        directory.freezeAdvisoryTask("ADVISORY_VOICE",id,recipient);
        return new Claim(id,event.sourceMode(),token,tasks.find(id).providerKey(),recording,now,recipient,
                predictions==null?null:predictions.notificationRevision(id));
    }
    private void finish(Claim claim,AdvisoryVoicePort.Delivery receipt) {
        if(riskCases!=null)riskCases.lockCases(claim.eventId());
        EventRow event=events.lock(claim.eventId(),SYSTEM_SCOPE);if(event==null)return;
        Task task=tasks.find(event.eventId());if(task==null||!claim.token().equals(task.token())||!Set.of("CALLING","UNKNOWN").contains(task.status()))return;
        long now=clock.nowMillis();
        boolean simulated=receipt!=null&&receipt.simulated();
        boolean played=simulated&&"SIMULATED_PLAYED".equals(receipt.status())&&receipt.providerCallId()!=null&&!receipt.providerCallId().isBlank()&&receipt.providerCallId().length()<=160
                &&receipt.answeredAt()!=null&&receipt.playbackCompletedAt()!=null&&receipt.answeredAt()>=claim.startedAt()
                &&receipt.playbackCompletedAt()>=receipt.answeredAt()&&receipt.playbackCompletedAt()<=now;
        if(!played) {
            boolean failed=simulated&&"FAILED".equals(receipt.status())&&receipt.answeredAt()==null&&receipt.playbackCompletedAt()==null;
            String status=failed?"FAILED":"UNKNOWN";
            String reason=failed?"模拟电话通道明确返回失败；当前条件仍满足时可申请补呼":"电话接通或录音播完结果不明确，须先向通道对账，禁止盲目补呼";
            boolean answered=simulated&&receipt.status()!=null&&Set.of("ANSWERED","UNKNOWN","SIMULATED_PLAYED","FAILED").contains(receipt.status())
                    &&receipt.providerCallId()!=null&&!receipt.providerCallId().isBlank()&&receipt.providerCallId().length()<=160
                    &&receipt.answeredAt()!=null&&receipt.answeredAt()>=claim.startedAt()&&receipt.answeredAt()<=now;
            if(answered)reason="已收到模拟接通回执，录音是否播完未知；须先向通道对账，禁止盲目补呼";
            tasks.finish(event.eventId(),claim.token(),status,reason,null,answered?receipt.providerCallId():null,answered?receipt.answeredAt():null,null,now);
            audit.record(null,"AUTO_VOICE","SYSTEM","alarm",failed?"auto_voice_failed":"auto_voice_unknown","uav_event",event.eventId(),"policy="+AutoVoicePolicy.CODE,"FAILED","","");return;
        }
        String recordId=UUID.randomUUID().toString();
        if(events.update(event.eventId(),event.version(),event.state(),Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1)throw new IllegalStateException("Event version changed under lock");
        tasks.append(recordId,event.eventId(),event.version()+1,now,claim.recording(),receipt.providerCallId(),receipt.answeredAt(),receipt.playbackCompletedAt(),AutoVoicePolicy.CODE);
        directory.freezeAdvisoryRecord("ADVISORY_VOICE",recordId,claim.recipient());
        tasks.finish(event.eventId(),claim.token(),"SIMULATED_PLAYED","已收到模拟电话接通及录音播放完成回执；不代表真实通话或目标飞离",recordId,receipt.providerCallId(),receipt.answeredAt(),receipt.playbackCompletedAt(),now);
        audit.record(null,"AUTO_VOICE","SYSTEM","alarm","auto_voice_played","uav_event",event.eventId(),"policy="+AutoVoicePolicy.CODE+"; provider_key="+claim.providerKey()+"; recording_sha256="+claim.recording().sha256()+"; simulated=true","SUCCESS","","");
    }
    /** 纯读取；即使查询多次或页面关闭，都不会创建或触发电话任务。 */
    public AutoVoice overview(EventRow event,boolean mayRetry) {
        event=channelEvent(event);
        Task task=tasks.find(event.eventId());boolean enabled=policy.enabled();
        if(tasks.departure(event.eventId())!=null&&(task==null||task.attempts()==0))
            return view(event,enabled,"BLOCKED","有效位置已离开告警区域，已停止后续电话；判断依据已留存",false,task,null,null);
        // 误报不会再打电话：没有拨打过的，直接写明不需要，不显示成“等待拨打”。
        if("FALSE_POSITIVE".equals(event.state())&&(task==null||task.attempts()==0))
            return view(event,enabled,"NOT_REQUIRED","已核实为误报，不需要拨打飞手电话",false,task,null,null);
        Recording recording=enabled?recording(event):null;
        // 未外呼的暂停原因可能随计划关联、联系人或位置事实更新；读取只重算摘要，不领取任务。
        if(task!=null&&(TERMINAL.contains(task.status())||"CALLING".equals(task.status())
                ||("BLOCKED".equals(task.status())&&task.attempts()>0))) {
            boolean retry=enabled&&mayRetry&&"FAILED".equals(task.status())&&eligible(event,clock.nowMillis(),task,recording).allowed();
            return view(event,enabled,task.status(),task.reason(),retry,task,recording,null);
        }
        if(!enabled)return view(event,false,"DISABLED","电话录音通知尚未启用；需开启后台电话通知，在管理端 运维管理 → 接口配置 → 电话通知录音 上传并选用录音，并接通授权通知渠道",false,task,null,null);
        Eligibility e=eligible(event,clock.nowMillis(),task,recording);
        // 短信因超过时效停发时，电话也不会自动拨打，不能一直显示“等短信送达”。只改显示，任务仍按等待短信处理。
        if(!e.allowed()&&"WAITING".equals(e.status())&&smsStale(event.eventId())&&(responseOrder==null||!responseOrder.summary(event.eventId()).matched()))
            return view(event,true,"BLOCKED",STALE_REASON,false,task,recording,e);
        return view(event,true,e.allowed()?"WAITING":e.status(),e.reason(),false,task,recording,e);
    }
    private boolean smsStale(String eventId) {
        var sms=smsTasks.find(taskOwner(eventId,"SMS"));
        return sms!=null&&Set.of("BLOCKED","UNAVAILABLE").contains(sms.status())&&sms.reason()!=null&&sms.reason().startsWith(AutoSmsService.STALE_REASON);
    }
    public String mode(EventRow event) {
        event=channelEvent(event);
        Task task=tasks.find(event.eventId());
        if(task!=null&&task.attempts()>0)return "SIMULATED";
        return policy.enabled()&&voice.simulationAvailable(event.sourceMode())&&recording(event)!=null?"SIMULATED":"UNAVAILABLE";
    }
    private Recording recording(EventRow event) {
        return predicted(event)?recordings.current(AdvisoryVoiceRecording.PREDICTED_AIRSPACE_ENTRY):recordings.current();
    }
    private boolean predicted(EventRow event) {
        if(riskCases!=null){var refs=riskCases.cases(event.eventId());if(!refs.isEmpty())return refs.stream().allMatch(c->"PREDICTED".equals(c.stage()));}
        return predictions!=null&&predictions.isPrediction(event.eventId());
    }
    private EventRow channelEvent(EventRow event){
        if(riskCases==null)return event;
        String ownerId=taskOwner(event.eventId(),"VOICE");
        if(ownerId==null||ownerId.equals(event.eventId()))return event;
        var owner=events.find(ownerId,SYSTEM_SCOPE);
        return owner!=null&&java.util.Objects.equals(owner.targetId(),event.targetId())&&java.util.Objects.equals(owner.ownerOrgId(),event.ownerOrgId())
                &&java.util.Objects.equals(owner.districtId(),event.districtId())&&java.util.Objects.equals(owner.sourceMode(),event.sourceMode())?owner:event;
    }
    public void retry(EventRow event) {
        event=channelEvent(event);
        if(!overview(event,true).canRetry())throw new ApiException(HttpStatus.CONFLICT,"AUTO_VOICE_RETRY_BLOCKED",overview(event,false).reason());
        tasks.queueRetry(event.eventId(),clock.nowMillis());
    }
    private Eligibility eligible(EventRow event,long now,Task task,Recording recording) {
        return eligible(event,now,task,recording,responseOrder==null?null:responseOrder.summary(event.eventId()));
    }
    private Eligibility eligible(EventRow event,long now,Task task,Recording recording,
            com.uav.lowaltitude.modules.automationrule.api.ResponseOrderDtos.Summary order) {
        if(riskCases!=null&&riskCases.legacyNotificationAmbiguous(event.eventId(),"VOICE"))
            return blocked(waiting("旧通知区域归属待核查"),"BLOCKED","已有电话发送记录无法确定所属区域，保留原回执并暂停新增拨打，避免重复通知");
        if(predictions!=null) {
            String block=predictions.notificationBlock(event.eventId());
            if(block!=null&&!block.isEmpty())return blocked(waiting("预测风险待确认"),"BLOCKED",block);
        }
        if(noCounter.active(event.eventId()))return blocked(waiting("已决定不反制"),"BLOCKED",com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository.ACTIVE_REASON);
        if("FALSE_POSITIVE".equals(event.state()))return blocked(waiting("误报"),"BLOCKED","已核实为误报，不拨打电话");
        boolean counterFirst=order!=null&&order.matched();
        if(counterFirst&&!order.voiceAllowed())return blocked(waiting(order.reason()),"PAUSED".equals(order.status())?"BLOCKED":"WAITING",order.reason());
        Long smsAt=smsTasks.deliveredAt(taskOwner(event.eventId(),"SMS"));
        if(!counterFirst&&smsAt==null)return waiting("飞手短信尚未送达，送达后即检查电话拨打条件");
        if(!counterFirst&&now<smsAt)return waiting("短信送达时间异常，暂不能拨打电话");
        if(!voice.simulationAvailable(event.sourceMode()))return blocked(waiting("通道不可用"),"UNAVAILABLE","正式电话录音通道尚未接入，不能把模拟接通写成真实通话");
        if(recording==null)return blocked(waiting("录音不可用"),"UNAVAILABLE",predicted(event)
                ?"还没有可用的预测进入禁飞区专用录音（在管理端 运维管理 → 接口配置 → 电话通知录音 上传并选用），预测预警电话不能执行，也不会改播普通告警录音"
                :"还没有选用电话通知录音（在管理端 运维管理 → 接口配置 → 电话通知录音 上传并选用），电话通知不能执行");
        PilotDepartureWatch.Presence presence;
        try { presence=counterFirst?PilotDepartureWatch.Presence.STILL_PRESENT:departure.assess(event.eventId(),smsAt,now); }
        catch(RuntimeException unavailable) { presence=PilotDepartureWatch.Presence.UNKNOWN; }
        if(presence==PilotDepartureWatch.Presence.LEFT)return blocked(waiting("已离开"),"BLOCKED",leftReason(event.eventId(),smsAt));
        if(presence!=PilotDepartureWatch.Presence.STILL_PRESENT)return blocked(waiting("无法确认"),"BLOCKED","短信发出后没有新的位置，或无法判断是否仍在告警空域，不拨打电话，也不记为已撤离");
        if(task!=null&&tasks.sendStarted(event.eventId())&&!task.recordingMatches(recording))return blocked(waiting("仍在"),"BLOCKED","录音配置与本任务原始内容不一致，不能沿用同一幂等编号更换录音重拨");
        var pilot=requiredPilot(event.eventId());
        if(pilot==null||!pilot.configured())return blocked(waiting("仍在"),"BLOCKED",pilot!=null&&pilot.blockedReason()!=null&&!pilot.blockedReason().isBlank()?pilot.blockedReason():"没有可通知的执行飞手，不能拨打电话");
        if(counterFirst)return new Eligibility(true,"WAITING",order.reason()+"；继续核验飞手、通道和本阶段录音，不等待短信送达","COUNTER_FIRST",null,null);
        return new Eligibility(true,"WAITING",predicted(event)
                ?"短信已送达，预计进入禁飞区的风险仍持续，可立即使用预测专用录音拨打模拟电话；模拟不会实际拨号或播放"
                :"短信已送达，目标仍在告警空域，可立即拨打模拟电话；模拟不会实际拨号或播放",TRIGGER,null,null);
    }
    /**
     * 短信发出时不在任何告警空域的，撤离是按最新研判恢复合法判断的（新-31），不能写成离开了告警空域。
     * 原因里不能出现 NotifyFlow.cannotNotify 认的词（飞手、任务、计划、联系等），否则会被当成通知不了而直接待定反制。
     */
    private String leftReason(String eventId,long smsAt) {
        boolean area;
        try { area=departure.inAreaAtSms(eventId,smsAt); } catch(RuntimeException unavailable) { area=true; }
        return area?"最新位置已离开短信发出时所处的告警空域，不拨打电话":"短信发出后的最新研判已恢复合法（例如回到航线），视为已离开告警区域，不拨打电话";
    }
    private Eligibility waiting(String reason){return new Eligibility(false,"WAITING",reason,TRIGGER,null,null);}
    private Eligibility blocked(Eligibility e,String status,String reason){return new Eligibility(false,status,reason,e.source(),e.evaluation(),e.observedAt());}
    private com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot requiredPilot(String eventId) {
        try {
            var evaluation=smsTasks.latestEvaluation(eventId);
            return directory.forPilotEvent("ADVISORY_VOICE",eventId,evaluation==null?null:evaluation.id());
        } catch(ApiException unavailable) { return null; }
    }
    private AutoVoice view(EventRow event,boolean enabled,String status,String reason,boolean retry,Task task,Recording recording,Eligibility e) {
        return new AutoVoice(enabled,status,reason,task==null?null:task.triggeredAt(),task==null?null:task.updatedAt(),retry,task==null?0:task.attempts(),AutoVoicePolicy.CODE,
                e!=null?e.source():task==null?null:task.triggerSource(),e!=null&&e.evaluation()!=null?e.evaluation().evaluatedAt():task==null?null:task.evaluatedAt(),e!=null?e.observedAt():task==null?null:task.dataUpdatedAt(),
                task!=null&&task.recordingId()!=null?task.recordingId():recording==null?null:recording.id(),task!=null&&task.recordingName()!=null?task.recordingName():recording==null?null:recording.name(),
                task==null?null:task.answeredAt(),task==null?null:task.playbackCompletedAt(),recipient(event),task==null?null:task.responseOrderBasis());
    }
    private com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient(EventRow event){var recorded=directory.advisoryHistoryRecipient("ADVISORY_VOICE",event.eventId());if(recorded!=null)return recorded;var task=tasks.find(event.eventId());return task!=null&&task.attempts()>0?null:directory.currentPilotEvent("ADVISORY_VOICE",event.eventId());}
    private record Claim(String eventId,String mode,String token,String providerKey,Recording recording,long startedAt,
            com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient,Long predictionRevision) {
        Claim(String eventId,String mode,String token,String providerKey,Recording recording,long startedAt,
                com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient) {
            this(eventId,mode,token,providerKey,recording,startedAt,recipient,null);
        }
    }
}
