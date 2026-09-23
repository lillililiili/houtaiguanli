package com.uav.lowaltitude.modules.alarm.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.*;
import com.uav.lowaltitude.modules.alarm.domain.CounterLaunchVisibility;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class UavAdvisoryService {
    private final AutoSmsService automatic;
    private final AutoVoiceService voice;
    private final PilotDepartureWatch departure;
    private final HandoffRepository handoffs;
    private final UavEventRepository events;
    private final UavAdvisoryRepository repository;
    private final AccessControlService access;
    private final AdvisorySmsPort sms;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AuditService audit;
    private final AutomationRuntimeEligibility rules;
    public UavAdvisoryService(UavEventRepository events,UavAdvisoryRepository repository,AccessControlService access,
            AdvisorySmsPort sms,ObjectMapper json,AppClock clock,AuditService audit,AutoSmsService automatic,AutoVoiceService voice,PilotDepartureWatch departure,HandoffRepository handoffs,AutomationRuntimeEligibility rules) {
        this.voice=voice;
        this.automatic=automatic;
        this.departure=departure;
        this.handoffs=handoffs;
        this.events=events;this.repository=repository;this.access=access;this.sms=sms;this.json=json;this.clock=clock;this.audit=audit;this.rules=rules;
    }
    @Transactional(readOnly=true)
    public Overview overview(String id) { return view(event(id,false)); }
    @Transactional
    public Overview act(String id,String raw,String key) {
        var read=access.require(PermissionCode.ALARM_READ);
        access.require(PermissionCode.ALARM_VERIFY);access.require(PermissionCode.HANDOFF_CREATE);
        Action a=parse(raw);
        if(key==null || key.trim().length()<8 || key.trim().length()>128) throw bad("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key 必须为8至128个字符");
        EventRow event=events.lock(id,read); if(event==null) throw notFound();
        var actor=AuthContext.require();
        String hash=hash(id+":"+write(a));
        var replay=repository.replay(actor.userId(),key.trim());
        if(replay!=null) {
            if(!hash.equals(replay.hash()) || !id.equals(replay.eventId())) throw conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于其他操作");
            try { return json.readValue(replay.response(),Overview.class); } catch(Exception ex) {throw new IllegalStateException("Invalid advisory replay",ex);}
        }
        if(event.version()!=a.expectedVersion()) throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        if(!"CONFIRMED".equals(event.state())) throw conflict("INVALID_TRANSITION","请先人工核实事件属实");
        if("SMS_SIMULATED".equals(a.kind())&&automatic.sending(id))throw conflict("AUTO_SMS_SENDING","后台正在发送短信，请等待结果后再补发");
        AdvisorySmsPort.Delivery delivery="SMS_SIMULATED".equals(a.kind())?sms.simulate(event.sourceMode(),a.recipientName(),a.content()):null;
        long now=clock.nowMillis();
        if(events.update(id,event.version(),event.state(),java.time.Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1) throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        repository.append(UUID.randomUUID().toString(),id,event.version()+1,actor.userId(),now,a,delivery!=null && delivery.simulated(),delivery==null?null:delivery.status());
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"alarm","uav_advisory_recorded","uav_event",id,"kind="+a.kind()+"; simulated="+(delivery!=null),"SUCCESS","","");
        Overview response=view(events.find(id,read));
        try { repository.saveReplay(actor.userId(),key.trim(),hash,id,write(response)); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { throw conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于其他操作"); }
        return response;
    }
    @Transactional
    public Overview retryAutomatic(String id,String raw,String key) {
        var scope=access.require(PermissionCode.ALARM_READ);
        access.require(PermissionCode.ALARM_VERIFY);access.require(PermissionCode.HANDOFF_CREATE);
        long version;String note;
        try(JsonParser parser=json.getFactory().createParser(raw==null?"":raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode n=json.readTree(parser);
            if(n==null||!n.isObject()||parser.nextToken()!=null||n.size()!=2||!n.has("expected_version")||!n.get("expected_version").isIntegralNumber()||!n.get("expected_version").canConvertToLong())throw new IllegalArgumentException();
            version=n.get("expected_version").longValue();if(version<0)throw new IllegalArgumentException();note=text(n,"note",1000,true);
        } catch(Exception bad){throw bad("VALIDATION_ERROR","补发需要有效版本与原因说明");}
        if(key==null||key.trim().length()<8||key.trim().length()>128)throw bad("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key 必须为8至128个字符");
        EventRow event=events.lock(id,scope);if(event==null)throw notFound();var actor=AuthContext.require();
        String hash=hash("auto-sms-retry:"+id+":"+version+":"+note);var previous=repository.replay(actor.userId(),key.trim());
        if(previous!=null) {
            if(!id.equals(previous.eventId())||!hash.equals(previous.hash()))throw conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于其他操作");
            try{return json.readValue(previous.response(),Overview.class);}catch(Exception invalid){throw new IllegalStateException(invalid);}
        }
        if(event.version()!=version)throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        automatic.retry(event);
        long now=clock.nowMillis();
        if(events.update(id,version,event.state(),java.time.Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1)throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"alarm","auto_sms_retry_requested","uav_event",id,note,"SUCCESS","","");
        Overview result=view(events.find(id,scope));
        try { repository.saveReplay(actor.userId(),key.trim(),hash,id,write(result)); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { throw conflict("IDEMPOTENCY_KEY_REUSED","\u8be5\u8bf7\u6c42\u7f16\u53f7\u5df2\u7528\u4e8e\u5176\u4ed6\u64cd\u4f5c"); }
        return result;
    }
    @Transactional
    public Overview retryAutomaticVoice(String id,String raw,String key) {
        var scope=access.require(PermissionCode.ALARM_READ);
        access.require(PermissionCode.ALARM_VERIFY);access.require(PermissionCode.HANDOFF_CREATE);
        long version;String note;
        try(JsonParser parser=json.getFactory().createParser(raw==null?"":raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode n=json.readTree(parser);
            if(n==null||!n.isObject()||parser.nextToken()!=null||n.size()!=2||!n.has("expected_version")||!n.get("expected_version").isIntegralNumber()||!n.get("expected_version").canConvertToLong())throw new IllegalArgumentException();
            version=n.get("expected_version").longValue();if(version<0)throw new IllegalArgumentException();note=text(n,"note",1000,true);
        } catch(Exception bad){throw bad("VALIDATION_ERROR","补呼需要有效版本与原因说明");}
        if(key==null||key.trim().length()<8||key.trim().length()>128)throw bad("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key 必须为8至128个字符");
        EventRow event=events.lock(id,scope);if(event==null)throw notFound();var actor=AuthContext.require();
        String hash=hash("auto-voice-retry:"+id+":"+version+":"+note);var previous=repository.replay(actor.userId(),key.trim());
        if(previous!=null) {
            if(!id.equals(previous.eventId())||!hash.equals(previous.hash()))throw conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于其他操作");
            try{return json.readValue(previous.response(),Overview.class);}catch(Exception invalid){throw new IllegalStateException(invalid);}
        }
        if(event.version()!=version)throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        voice.retry(event);
        long now=clock.nowMillis();
        if(events.update(id,version,event.state(),java.time.Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1)throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"alarm","auto_voice_retry_requested","uav_event",id,note,"SUCCESS","","");
        Overview result=view(events.find(id,scope));
        try { repository.saveReplay(actor.userId(),key.trim(),hash,id,write(result)); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { throw conflict("IDEMPOTENCY_KEY_REUSED","\u8be5\u8bf7\u6c42\u7f16\u53f7\u5df2\u7528\u4e8e\u5176\u4ed6\u64cd\u4f5c"); }
        return result;
    }
    /** 调用方持有受限事件/授权范围；锁定事件并读取当前系统依据。 */
    @Transactional
    public void requireCounter(String eventId, com.uav.lowaltitude.modules.identity.domain.AccessDecision scope) {
        EventRow event=events.lock(eventId,scope);
        if(event==null) throw notFound();
        String reason=counterReason(eventId);
        if(!reason.isEmpty()) throw conflict("ADVISORY_COUNTER_BLOCKED",reason);
    }
    private EventRow event(String id,boolean lock) {
        var decision=access.require(PermissionCode.ALARM_READ);
        EventRow row=lock?events.lock(id,decision):events.find(id,decision);
        if(row==null) throw notFound(); return row;
    }
    private Overview view(EventRow event) {
        var records=repository.records(event.eventId());
        String reason=counterReason(event.eventId());
        boolean mode=sms.simulationAvailable(event.sourceMode());
        boolean request=allowed(PermissionCode.DISPOSAL_REQUEST);
        boolean direct=allowed(PermissionCode.DISPOSAL_DIRECT);
        var currentRecipient=automatic.currentRecipient(event);
        var autoSms=automatic.overview(event,allowed(PermissionCode.ALARM_VERIFY)&&allowed(PermissionCode.HANDOFF_CREATE));
        var autoVoice=voice.overview(event,allowed(PermissionCode.ALARM_VERIFY)&&allowed(PermissionCode.HANDOFF_CREATE));
        var phase=notifyPhase(event,autoSms,autoVoice);
        return new Overview(event.eventId(),event.version(),mode?"SIMULATED":"UNAVAILABLE",
                "CONFIRMED".equals(event.state()) && allowed(PermissionCode.ALARM_VERIFY) && allowed(PermissionCode.HANDOFF_CREATE),
                reason.isEmpty() && request,reason.isEmpty() && direct,"CONFIRMED".equals(event.state()) && allowed(PermissionCode.HANDOFF_CREATE),reason.isEmpty()&&!request&&!direct?"当前账号没有反制申请或直接反制权限":reason,records,
                currentRecipient.recipientName()==null?null:new Recipient(currentRecipient.recipientName(),currentRecipient.contactHint(),"当前明确关联的计划执行飞手"),
                autoSms,voice.mode(event),autoVoice,CounterLaunchVisibility.visible(phase),phaseName(phase),autoHandoff(event.eventId()));
    }
    private String counterReason(String eventId) {
        String reason = repository.counterBlockReason(eventId);
        if (!reason.isEmpty()) return reason;
        String rule = rules.counterBlock(eventId);
        return rule == null ? "" : rule;
    }
    private boolean allowed(PermissionCode permission) {try {access.require(permission);return true;} catch(ApiException ignored){return false;}}
    private NotifyFlow.Phase notifyPhase(EventRow event, AutoSms sms, AutoVoice voice) {
        long now = clock.nowMillis();
        Long smsAt = automatic.deliveredAt(event.eventId());
        Long playedAt = voice == null ? null : voice.playbackCompletedAt() != null ? voice.playbackCompletedAt() : voice.updatedAt();
        PilotDepartureWatch.Presence afterSms = null;
        PilotDepartureWatch.Presence afterCall = null;
        if (sms != null && "SIMULATED_DELIVERED".equals(sms.status()) && smsAt != null && now >= smsAt + NotifyFlow.WATCH_MILLIS && (voice == null || !"SIMULATED_PLAYED".equals(voice.status())))
            afterSms = presence(event.eventId(), smsAt, now);
        if (voice != null && "SIMULATED_PLAYED".equals(voice.status()) && playedAt != null && now >= playedAt + NotifyFlow.WATCH_MILLIS)
            afterCall = presence(event.eventId(), playedAt, now);
        return NotifyFlow.phase(event.state(), sms == null ? null : sms.status(), sms == null ? null : sms.reason(), smsAt,
                voice == null ? null : voice.status(), voice == null ? null : voice.reason(), playedAt, now, afterSms, afterCall);
    }
    private String phaseName(NotifyFlow.Phase phase) { return phase == null ? null : phase.name(); }
    private AutoHandoff autoHandoff(String eventId) {
        String handoffId = handoffs.existingPunishment(eventId);
        if (handoffId == null) return new AutoHandoff(true, "WAITING", "干扰完成后自动移送到处罚", null, null, null);
        String delivery = handoffs.latestDeliveryStatus(handoffId);
        String status = "FAILED".equals(delivery) ? "FAILED" : "SUBMITTED";
        return new AutoHandoff(true, status, "FAILED".equals(status) ? "处罚交接投递失败" : null, handoffId, "JAMMING_COMPLETED", null);
    }
    private PilotDepartureWatch.Presence presence(String eventId, long since, long now) {
        try { return departure.assess(eventId, since, now); }
        catch (RuntimeException unavailable) { return PilotDepartureWatch.Presence.UNKNOWN; }
    }
    private Action parse(String raw) {
        try(JsonParser parser=json.getFactory().createParser(raw==null?"":raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode n=json.readTree(parser);
            if(n==null || !n.isObject() || parser.nextToken()!=null) throw new IllegalArgumentException();
            Set<String> fields=Set.of("expected_version","kind","recipient_name","contact_basis","content","outcome","danger","note","urgent");
            n.fieldNames().forEachRemaining(f->{if(!fields.contains(f)) throw new IllegalArgumentException();});
            if(!n.has("expected_version") || !n.get("expected_version").isIntegralNumber() || !n.get("expected_version").canConvertToLong() || n.get("expected_version").longValue()<0) throw new IllegalArgumentException();
            String kind=text(n,"kind",32,true),recipient=text(n,"recipient_name",120,false),basis=text(n,"contact_basis",500,false),content=text(n,"content",1000,false),outcome=text(n,"outcome",24,false),danger=text(n,"danger",16,false),note=text(n,"note",1000,false);
            if("OBSERVATION".equals(kind)) throw bad("MANUAL_OBSERVATION_RETIRED","人工现场记录已停用，请使用系统观测与研判依据");
            if(!Set.of("SMS_SIMULATED","CONTACT_RECORDED").contains(kind)) throw new IllegalArgumentException();
            if(n.has("urgent")&&!n.get("urgent").isBoolean()) throw new IllegalArgumentException();
            boolean urgent=n.path("urgent").asBoolean(false);
            if(recipient==null||basis==null||content==null||outcome!=null||danger!=null||urgent) throw new IllegalArgumentException();
            return new Action(n.get("expected_version").longValue(),kind,recipient,basis,content,outcome,danger,note,urgent);
        } catch(ApiException ex) {throw ex;} catch(Exception ex) {throw bad("VALIDATION_ERROR","请填写接收对象、联系依据与劝离内容");}
    }
    private static String text(JsonNode n,String key,int max,boolean required) {
        if(!n.has(key)||n.get(key).isNull()) {if(required) throw new IllegalArgumentException();return null;}
        if(!n.get(key).isTextual()) throw new IllegalArgumentException();
        String s=n.get(key).textValue().trim();if(s.isEmpty()||s.length()>max) throw new IllegalArgumentException();
        // 本接口不接收真实手机号，记录与交接材料均不保存号码。
        return s.replaceAll("(?<![0-9])(?:[+]?[8][6][- ]?)?1[3-9](?:[- ]?[0-9]){9}(?![0-9])","[手机号已隐藏]");
    }
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static ApiException bad(String code,String message){return new ApiException(HttpStatus.BAD_REQUEST,code,message);}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
    private static ApiException notFound(){return new ApiException(HttpStatus.NOT_FOUND,"UAV_EVENT_NOT_FOUND","无人机事件不存在");}
}
