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

/** 持久化电话录音任务；外部调用不占数据库事务，不依赖页面或用户会话。 */
@Service
public class AutoVoiceService {
    private static final AccessDecision SYSTEM_SCOPE=new AccessDecision("system:auto-advisory-voice",ScopeMode.ALL);
    private static final Set<String> TERMINAL=Set.of("SIMULATED_PLAYED","FAILED","UNKNOWN");
    private final com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory;
    private final AutoVoiceRepository tasks;
    private final UavEventRepository events;
    private static final long WATCH_MILLIS=com.uav.lowaltitude.modules.alarm.domain.NotifyFlow.WATCH_MILLIS;
    private static final String TRIGGER="SMS_THEN_WATCH";
    private final AutoSmsRepository smsTasks;
    private final PilotDepartureWatch departure;
    private final AutoVoicePolicy policy;
    private final AdvisoryVoiceRecording recordings;
    private final AdvisoryVoicePort voice;
    private final AppClock clock;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public AutoVoiceService(AutoVoiceRepository tasks,UavEventRepository events,AutoSmsRepository smsTasks,PilotDepartureWatch departure,AutoVoicePolicy policy,
            AdvisoryVoiceRecording recordings,AdvisoryVoicePort voice,AppClock clock,AuditService audit,PlatformTransactionManager manager,com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService directory) {
        this.directory=directory;
        this.tasks=tasks;this.events=events;this.smsTasks=smsTasks;this.departure=departure;this.policy=policy;this.recordings=recordings;
        this.voice=voice;this.clock=clock;this.audit=audit;this.tx=new TransactionTemplate(manager);
    }
    public void poll() {
        var candidates=policy.enabled()?tasks.candidates():tasks.callingCandidates();
        for(String id:candidates) {
            try {process(id);} catch(RuntimeException failed) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("automatic voice cycle failed for event {}; pending work requires reconciliation",id);}
        }
    }
    public void process(String id) {
        Claim claim=tx.execute(s->claim(id));if(claim==null)return;
        AdvisoryVoicePort.Delivery delivery=null;
        try {delivery=voice.simulate(claim.mode(),claim.recipient(),claim.recording(),claim.providerKey());}
        catch(RuntimeException uncertain) { /* 可能已经受理，不能把未知直接当失败并重拨。 */ }
        final var receipt=delivery;
        tx.executeWithoutResult(s->finish(claim,receipt));
    }
    private Claim claim(String id) {
        EventRow event=events.lock(id,SYSTEM_SCOPE);if(event==null)return null;
        long now=clock.nowMillis();Task task=tasks.find(id);
        if(task!=null&&"CALLING".equals(task.status())) {
            if(task.leaseUntil()!=null&&task.leaseUntil()<now) {
                tasks.finish(id,task.token(),"UNKNOWN","上次外呼未取得明确结果，须先向通道对账，禁止盲目补呼",null,null,null,null,now);
                audit.record(null,"AUTO_VOICE","SYSTEM","alarm","auto_voice_unknown","uav_event",id,"lease_expired; policy="+AutoVoicePolicy.CODE,"FAILED","","");
            }
            return null;
        }
        if(!policy.enabled()||(task!=null&&TERMINAL.contains(task.status())))return null;
        Recording recording=recordings.current();Eligibility e=eligible(event,now,task,recording);
        tasks.initialize(id,now,AutoVoicePolicy.CODE);
        if(!e.allowed()){tasks.block(id,e,now);return null;}
        var recipient=requiredPilot(id);
        if(recipient==null||!recipient.configured()){tasks.block(id,blocked(e,"BLOCKED",recipient==null||recipient.blockedReason()==null||recipient.blockedReason().isBlank()?"没有可通知的执行飞手，不能拨打电话":recipient.blockedReason()),now);return null;}
        String token=UUID.randomUUID().toString();tasks.claim(id,token,e,recording,now);
        directory.freezeAdvisoryTask("ADVISORY_VOICE",id,recipient);
        return new Claim(id,event.sourceMode(),token,tasks.find(id).providerKey(),recording,now,recipient);
    }
    private void finish(Claim claim,AdvisoryVoicePort.Delivery receipt) {
        EventRow event=events.lock(claim.eventId(),SYSTEM_SCOPE);if(event==null)return;
        Task task=tasks.find(event.eventId());if(task==null||!claim.token().equals(task.token())||!"CALLING".equals(task.status()))return;
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
        tasks.finish(event.eventId(),claim.token(),"SIMULATED_PLAYED","模拟接通并播放录音已完成；未实际拨号或播放，不代表飞手听取或目标飞离",recordId,receipt.providerCallId(),receipt.answeredAt(),receipt.playbackCompletedAt(),now);
        audit.record(null,"AUTO_VOICE","SYSTEM","alarm","auto_voice_played","uav_event",event.eventId(),"policy="+AutoVoicePolicy.CODE+"; provider_key="+claim.providerKey()+"; recording_sha256="+claim.recording().sha256()+"; simulated=true","SUCCESS","","");
    }
    /** 纯读取；即使查询多次或页面关闭，都不会创建或触发电话任务。 */
    public AutoVoice overview(EventRow event,boolean mayRetry) {
        Task task=tasks.find(event.eventId());boolean enabled=policy.enabled();
        Recording recording=enabled?recordings.current():null;
        if(task!=null&&(TERMINAL.contains(task.status())||"CALLING".equals(task.status())||"BLOCKED".equals(task.status()))) {
            boolean retry=enabled&&mayRetry&&"FAILED".equals(task.status())&&eligible(event,clock.nowMillis(),task,recording).allowed();
            return view(event,enabled,task.status(),task.reason(),retry,task,recording,null);
        }
        if(!enabled)return view(event,false,"DISABLED","电话录音通知尚未启用；需配置已有录音文件、模板名称及文稿，并接通授权通知渠道",false,task,null,null);
        Eligibility e=eligible(event,clock.nowMillis(),task,recording);
        return view(event,true,e.allowed()?"WAITING":e.status(),e.reason(),false,task,recording,e);
    }
    public String mode(EventRow event) {
        Task task=tasks.find(event.eventId());
        if(task!=null&&task.attempts()>0)return "SIMULATED";
        return policy.enabled()&&voice.simulationAvailable(event.sourceMode())&&recordings.current()!=null?"SIMULATED":"UNAVAILABLE";
    }
    public void retry(EventRow event) {
        if(!overview(event,true).canRetry())throw new ApiException(HttpStatus.CONFLICT,"AUTO_VOICE_RETRY_BLOCKED",overview(event,false).reason());
        tasks.queueRetry(event.eventId(),clock.nowMillis());
    }
    private Eligibility eligible(EventRow event,long now,Task task,Recording recording) {
        Long smsAt=smsTasks.deliveredAt(event.eventId());
        if(smsAt==null)return waiting("飞手短信尚未送达，电话要等短信送达并观察 10 秒");
        // 电话通道或录音不可用，也不能跳过短信送达后的 10 秒观察。
        if(now<smsAt+WATCH_MILLIS)return waiting("短信已送达，正在用设备位置观察目标是否撤离。满 10 秒后，仍在告警空域才会拨打电话");
        if(!voice.simulationAvailable(event.sourceMode()))return blocked(waiting("通道不可用"),"UNAVAILABLE","正式电话录音通道尚未接入，不能把模拟接通写成真实通话");
        if(recording==null)return blocked(waiting("录音不可用"),"UNAVAILABLE","未配置有效的已有 WAV 录音文件、模板名称和文稿，电话通知不能执行");
        PilotDepartureWatch.Presence presence;
        try { presence=departure.assess(event.eventId(),smsAt,now); }
        catch(RuntimeException unavailable) { presence=PilotDepartureWatch.Presence.UNKNOWN; }
        if(presence==PilotDepartureWatch.Presence.LEFT)return blocked(waiting("已离开"),"BLOCKED","最新位置已离开短信发出时所处的告警空域，不拨打电话");
        if(presence!=PilotDepartureWatch.Presence.STILL_PRESENT)return blocked(waiting("无法确认"),"BLOCKED","短信发出后没有新的位置，或无法判断是否仍在告警空域，不拨打电话，也不记为已撤离");
        if(task!=null&&!task.recordingMatches(recording))return blocked(waiting("仍在"),"BLOCKED","录音配置与本任务原始内容不一致，不能沿用同一幂等编号更换录音重拨");
        var pilot=requiredPilot(event.eventId());
        if(pilot==null||!pilot.configured())return blocked(waiting("仍在"),"BLOCKED",pilot!=null&&pilot.blockedReason()!=null&&!pilot.blockedReason().isBlank()?pilot.blockedReason():"没有可通知的执行飞手，不能拨打电话");
        return new Eligibility(true,"WAITING","短信送达已满 10 秒，目标仍在告警空域，等待后台拨打模拟电话；模拟不会实际拨号或播放",TRIGGER,null,null);
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
                task==null?null:task.answeredAt(),task==null?null:task.playbackCompletedAt(),recipient(event));
    }
    private com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient(EventRow event){var recorded=directory.advisoryHistoryRecipient("ADVISORY_VOICE",event.eventId());if(recorded!=null)return recorded;var task=tasks.find(event.eventId());return task!=null&&task.attempts()>0?null:directory.currentPilotEvent("ADVISORY_VOICE",event.eventId());}
    private record Claim(String eventId,String mode,String token,String providerKey,Recording recording,long startedAt,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient) { }
}
