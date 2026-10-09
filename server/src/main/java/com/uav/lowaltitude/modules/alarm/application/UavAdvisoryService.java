package com.uav.lowaltitude.modules.alarm.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.*;
import com.uav.lowaltitude.modules.alarm.domain.CounterLaunchVisibility;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
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
    private final DisposalRepository disposals;
    private final UavEventRepository events;
    private final UavAdvisoryRepository repository;
    private final AccessControlService access;
    private final AdvisorySmsPort sms;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AuditService audit;
    private final NoCounterService noCounter;
    public UavAdvisoryService(UavEventRepository events,UavAdvisoryRepository repository,AccessControlService access,
            AdvisorySmsPort sms,ObjectMapper json,AppClock clock,AuditService audit,AutoSmsService automatic,AutoVoiceService voice,PilotDepartureWatch departure,HandoffRepository handoffs,DisposalRepository disposals,NoCounterService noCounter) {
        this.noCounter=noCounter;
        this.voice=voice;
        this.automatic=automatic;
        this.departure=departure;
        this.handoffs=handoffs;
        this.disposals=disposals;
        this.events=events;this.repository=repository;this.access=access;this.sms=sms;this.json=json;this.clock=clock;this.audit=audit;
    }
    @Transactional(readOnly=true)
    public Overview overview(String id) { return view(event(id,false)); }
    /** Exposes the existing device-position assessment; reading never sends or confirms. */
    @Transactional(readOnly=true)
    public DepartureObservation observation(String id) {
        var current = event(id, false); // Same alarm permission and data scope as overview.
        long now = clock.nowMillis();
        if (!"CONFIRMED".equals(current.state())) return new DepartureObservation(id, null, "NOT_STARTED", "UNKNOWN", null, null, now);
        var sms = automatic.overview(current, false);
        var call = voice.overview(current, false);
        Long smsAt = automatic.deliveredAt(id);
        Long since = smsAt;
        String channel = "SMS";
        if (call != null && "SIMULATED_PLAYED".equals(call.status())) {
            channel = "VOICE";
            since = call.playbackCompletedAt() != null ? call.playbackCompletedAt() : call.updatedAt();
        } else if (sms == null || !"SIMULATED_DELIVERED".equals(sms.status())) {
            since = null;
        }
        if (since == null) return new DepartureObservation(id, channel, "NOT_STARTED", "UNKNOWN", null, null, now);
        long deadline = since + ("VOICE".equals(channel) ? NotifyFlow.CALL_WATCH_MILLIS : NotifyFlow.SMS_WATCH_MILLIS);
        if (now < deadline) return new DepartureObservation(id, channel, "WATCHING", "UNKNOWN", since, deadline, now);
        var presence = "VOICE".equals(channel) ? presenceAfterCall(id, smsAt, since, now) : presence(id, since, now);
        return new DepartureObservation(id, channel, "ASSESSED", presence.name(), since, deadline, now);
    }
    @Transactional
    public Overview retryAutomatic(String id,String raw,String key) {
        var scope=access.require(PermissionCode.ALARM_READ);
        access.require(PermissionCode.ALARM_VERIFY);access.require(PermissionCode.HANDOFF_CREATE);
        long version;String note;
        try(JsonParser parser=json.getFactory().createParser(raw==null?"":raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode n=json.readTree(parser);
            if(n==null||!n.isObject()||parser.nextToken()!=null||n.size()<1||n.size()>2||(n.size()==2&&!n.has("note"))||!n.has("expected_version")||!n.get("expected_version").isIntegralNumber()||!n.get("expected_version").canConvertToLong())throw new IllegalArgumentException();
            version=n.get("expected_version").longValue();if(version<0)throw new IllegalArgumentException();note=optionalRetryNote(n);
        } catch(Exception bad){throw bad("VALIDATION_ERROR","补发需要有效版本；说明选填，最多1000字");}
        if(key==null||key.trim().length()<8||key.trim().length()>128)throw bad("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key 必须为8至128个字符");
        EventRow event=events.lock(id,scope);if(event==null)throw notFound();var actor=AuthContext.require();
        String hash=hash("auto-sms-retry:"+id+":"+version+":"+note);var previous=repository.replay(actor.userId(),key.trim());
        if(previous!=null) {
            if(!id.equals(previous.eventId())||!hash.equals(previous.hash()))throw conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于其他操作");
            try{return json.readValue(previous.response(),Overview.class);}catch(Exception invalid){throw new IllegalStateException(invalid);}
        }
        if(event.version()!=version)throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        boolean recheck=automatic.retry(event);
        long now=clock.nowMillis();
        if(events.update(id,version,event.state(),java.time.Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1)throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"alarm",recheck?"auto_sms_recheck_requested":"auto_sms_retry_requested","uav_event",id,note,"SUCCESS","","");
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
            if(n==null||!n.isObject()||parser.nextToken()!=null||n.size()<1||n.size()>2||(n.size()==2&&!n.has("note"))||!n.has("expected_version")||!n.get("expected_version").isIntegralNumber()||!n.get("expected_version").canConvertToLong())throw new IllegalArgumentException();
            version=n.get("expected_version").longValue();if(version<0)throw new IllegalArgumentException();note=optionalRetryNote(n);
        } catch(Exception bad){throw bad("VALIDATION_ERROR","补呼需要有效版本；说明选填，最多1000字");}
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
    /** 规则引擎读取通知阶段。不校验当前登录人，也不创建授权。 */
    public NotifyFlow.Phase phaseForAutomation(String eventId) {
        EventRow event = events.find(eventId, new AccessDecision("system:auto-counter", ScopeMode.ALL));
        if (event == null) return null;
        return notifyPhase(event, automatic.overview(event, false), voice.overview(event, false));
    }

    /**
     * 告警导出的“处置进度”（2026-10-08 新-2 第 5 点）。先后和写法与告警页“状态”列一样（AlarmsPage.vue displayState）：
     * 不反制的决定、已移送处罚、反制或干扰到了哪一步、通知到了哪一步。页面上只显示核实结论的
     * （待核实、误报、已确认但还没有进展）返回 null，导出留空。调用方已校验告警读取权限；
     * 反制进度和页面一样，只在有处置查看权限、授权在其范围内时才写，没有就接着看通知阶段。只读，不建任务、不发送。
     */
    public String progressLabel(String eventId, AccessDecision alarmScope, AccessDecision disposalScope) {
        EventRow event=events.find(eventId,alarmScope);
        if(event==null)return null;
        boolean confirmed="CONFIRMED".equals(event.state());
        if(!confirmed&&!"PENDING_VERIFICATION".equals(event.state()))return null;
        if(confirmed) {
            Boolean review=noCounter.reviewRequired(event);
            if(Boolean.FALSE.equals(review))return "不反制 · 处置已结束";
            if(Boolean.TRUE.equals(review))return "风险变化待决策";
        }
        String handoffId=handoffs.existingPunishment(eventId);
        if(handoffId!=null&&!"FAILED".equals(handoffs.latestDeliveryStatus(handoffId)))return "已移送处罚";
        if(!confirmed)return null;
        if(disposalScope!=null) {
            String counter=counterProgress(disposals.list(disposalScope,new DisposalRepository.Query("UAV_EVENT",eventId,null,null,null),0,50));
            if(counter!=null)return counter;
        }
        var phase=notifyPhase(event,automatic.overview(event,false),voice.overview(event,false));
        if(phase==null)return null;
        return switch(phase){case AUTO_SMS->"自动短信";case WATCHING->"观察中";case AUTO_CALL->"自动电话";case AWAIT_COUNTER->"待定是否反制";};
    }
    /** 同页面 deriveAlarmProgress：只看信号干扰和联动反制，失败、驳回、撤销、过期的不算，取最近申请的一条（列表按申请时间倒序）。 */
    private static String counterProgress(List<DisposalRepository.AuthorizationRow> rows) {
        DisposalRepository.AuthorizationRow latest=null;
        for(var row:rows) {
            if(!List.of("JAMMING","COUNTERMEASURE").contains(row.actionType())||List.of("FAILED","REJECTED","CANCELLED","EXPIRED").contains(row.status()))continue;
            if(latest==null||requestedMillis(row)>=requestedMillis(latest))latest=row;
        }
        if(latest==null)return null;
        boolean jamming="JAMMING".equals(latest.actionType());
        return switch(latest.status()) {
            case "STOPPED"->"反制已中止";
            case "APPROVED","EXECUTING"->jamming?"干扰中":"反制中";
            case "COMPLETED"->jamming?"已干扰":"已反制";
            case "REQUESTED"->"待审批";
            default->null;
        };
    }
    private static long requestedMillis(DisposalRepository.AuthorizationRow row) {
        return row.requestedAt()==null?0L:row.requestedAt().toInstant().toEpochMilli();
    }

    /** 调用方持有受限事件/授权范围；锁定事件并读取当前系统依据。 */
    @Transactional
    public void requireCounter(String eventId, com.uav.lowaltitude.modules.identity.domain.AccessDecision scope) {
        EventRow event=events.lock(eventId,scope);
        if(event==null) throw notFound();
        String reason=repository.counterBlockReason(eventId);
        if(!reason.isEmpty()) throw conflict("ADVISORY_COUNTER_BLOCKED",reason);
    }
    private EventRow event(String id,boolean lock) {
        var decision=access.require(PermissionCode.ALARM_READ);
        EventRow row=lock?events.lock(id,decision):events.find(id,decision);
        if(row==null) throw notFound(); return row;
    }
    private Overview view(EventRow event) {
        var records=repository.records(event.eventId());
        String reason=repository.counterBlockReason(event.eventId());
        boolean mode=sms.simulationAvailable(event.sourceMode());
        boolean request=allowed(PermissionCode.DISPOSAL_REQUEST);
        boolean direct=allowed(PermissionCode.DISPOSAL_DIRECT);
        var currentRecipient=automatic.currentRecipient(event);
        var autoSms=automatic.overview(event,allowed(PermissionCode.ALARM_VERIFY)&&allowed(PermissionCode.HANDOFF_CREATE));
        var autoVoice=voice.overview(event,allowed(PermissionCode.ALARM_VERIFY)&&allowed(PermissionCode.HANDOFF_CREATE));
        var phase=notifyPhase(event,autoSms,autoVoice);
        var decision=noCounter.status(event);
        return new Overview(event.eventId(),event.version(),mode?"SIMULATED":"UNAVAILABLE",
                !decision.decisionActive() && "CONFIRMED".equals(event.state()) && allowed(PermissionCode.ALARM_VERIFY) && allowed(PermissionCode.HANDOFF_CREATE),
                reason.isEmpty() && request,reason.isEmpty() && direct,"CONFIRMED".equals(event.state()) && allowed(PermissionCode.HANDOFF_CREATE),reason.isEmpty()&&!request&&!direct?"当前账号没有反制申请或直接反制权限":reason,records,
                currentRecipient.recipientName()==null?null:new Recipient(currentRecipient.recipientName(),currentRecipient.contactHint(),"当前明确关联的任务执行飞手"),
                autoSms,voice.mode(event),autoVoice,!decision.decisionActive()&&CounterLaunchVisibility.visible(phase),phaseName(phase),autoHandoff(event),decision,
                automatic.pilotContactMissing(event));
    }
    private boolean allowed(PermissionCode permission) {try {access.require(permission);return true;} catch(ApiException ignored){return false;}}
    private NotifyFlow.Phase notifyPhase(EventRow event, AutoSms sms, AutoVoice voice) {
        long now = clock.nowMillis();
        Long smsAt = automatic.deliveredAt(event.eventId());
        Long playedAt = voice == null ? null : voice.playbackCompletedAt() != null ? voice.playbackCompletedAt() : voice.updatedAt();
        PilotDepartureWatch.Presence afterSms = null;
        PilotDepartureWatch.Presence afterCall = null;
        if (sms != null && "SIMULATED_DELIVERED".equals(sms.status()) && smsAt != null && now >= smsAt + NotifyFlow.SMS_WATCH_MILLIS && (voice == null || !"SIMULATED_PLAYED".equals(voice.status())))
            afterSms = presence(event.eventId(), smsAt, now);
        if (voice != null && "SIMULATED_PLAYED".equals(voice.status()) && playedAt != null && now >= playedAt + NotifyFlow.CALL_WATCH_MILLIS)
            afterCall = presenceAfterCall(event.eventId(), smsAt, playedAt, now);
        return NotifyFlow.phase(event.state(), sms == null ? null : sms.status(), sms == null ? null : sms.reason(), smsAt,
                voice == null ? null : voice.status(), voice == null ? null : voice.reason(), playedAt, now, afterSms, afterCall);
    }
    private String phaseName(NotifyFlow.Phase phase) { return phase == null ? null : phase.name(); }
    private AutoHandoff autoHandoff(EventRow event) {
        String eventId = event.eventId();
        String handoffId = handoffs.existingPunishment(eventId);
        if (handoffId == null) {
            // 误报不进入处罚移送，不能一直显示“等待移送”。
            if ("FALSE_POSITIVE".equals(event.state()))
                return new AutoHandoff(false, "NOT_REQUIRED", "已核实为误报，不需要移送处罚", null, null, null, null, null);
            if(repository.noCounterActive(eventId))return new AutoHandoff(false,"NOT_REQUIRED","已决定不反制；是否移送按事件事实另行判断",null,null,null,null,null);
            boolean inspect = canInspectHandoffRecipients(eventId);
            // Match the automatic submission gate, without selecting a recipient or starting any work.
            int recipients = inspect ? handoffs.enabledRecipients("UAV_PUNISHMENT").size() : -1;
            if (disposals.completedJammingRequester(eventId) == null)
                return new AutoHandoff(true, "WAITING", recipients > 1 ? "干扰完成后，需要有权限的人员选择处罚接收单位再移送"
                        : recipients == 0 ? "干扰完成后移送到处罚。目前还没有启用的处罚接收单位，请联系管理员配置" : "干扰完成后自动移送到处罚",
                        null, null, null, null, null);
            if (!inspect)
                return new AutoHandoff(true, "WAITING", "干扰已完成，处罚移送进度需由有权限人员核查", null, null, null, null, null);
            if (recipients == 0)
                return new AutoHandoff(true, "BLOCKED", "还没有启用的处罚接收单位，暂时不能移送。请联系管理员配置处罚接收单位", null, null, null, null, null);
            if (recipients > 1) {
                // 处罚移送不给默认接收单位（决策 18-14）：后台不替人选，提示有权限的人员选定后提交。
                var party = handoffs.eventParty(eventId);
                var assessment = com.uav.lowaltitude.modules.handoff.domain.HandoffParty.assess(party.planId(), party.pilotName(),
                        party.operatorName(), party.planSerial(), party.targetSerial(), null);
                return new AutoHandoff(true, "MANUAL_REQUIRED", "启用了 " + recipients + " 个处罚接收单位，系统不会替你选择。请选择接收单位后移送到处罚",
                        null, null, null, assessment.status(), assessment.reasons().isEmpty() ? null : assessment.reasons());
            }
            return new AutoHandoff(true, "WAITING", "干扰已完成，等待后台自动移送", null, null, null, null, null);
        }
        var latest = handoffs.latestDelivery(handoffId);
        String trigger = handoffs.triggerSource(handoffId);
        Long updatedAt = latest == null || latest.createdAt() == null ? null : latest.createdAt().toInstant().toEpochMilli();
        String delivery = latest == null ? null : latest.deliveryStatus();
        if ("FAILED".equals(delivery))
            return new AutoHandoff(true, "FAILED", "处罚交接投递失败", handoffId, trigger, updatedAt, null, null);
        // 交接已建立但还没发出（例如通知处罚规则未全部满足、接收单位没配通知方式）：如实写明，不说成“已移送”。
        if ("PENDING_DELIVERY".equals(delivery))
            return new AutoHandoff(true, "PENDING", pendingReason(latest.blockedReason()), handoffId, trigger, updatedAt, null, null);
        return new AutoHandoff(true, "SUBMITTED", null, handoffId, trigger, updatedAt, null, null);
    }
    private static String pendingReason(String blocked) {
        if (blocked == null || blocked.isBlank()) return "处罚交接已建立，还没有发给处罚部门";
        if ("通知处罚规则尚未全部满足".equals(blocked)) return "通知处罚的规则还没有全部满足，系统没有自动发出。有权限的人员可以打开处罚交接手动发送";
        return blocked;
    }
    private boolean canInspectHandoffRecipients(String eventId) {
        for (PermissionCode permission : List.of(PermissionCode.HANDOFF_READ, PermissionCode.HANDOFF_CREATE)) {
            try {
                if (events.find(eventId, access.require(permission)) != null) return true;
            } catch (ApiException denied) { /* Keep directory configuration private without matching permission and scope. */ }
        }
        return false;
    }
    private PilotDepartureWatch.Presence presence(String eventId, long since, long now) {
        try { return known(departure.assess(eventId, since, now)); }
        catch (RuntimeException unavailable) { return PilotDepartureWatch.Presence.UNKNOWN; }
    }
    /** 电话后的观察：区域仍按短信发出时目标所在空域，只看录音播完后的新位置。没有短信送达时刻就无法确定区域。 */
    private PilotDepartureWatch.Presence presenceAfterCall(String eventId, Long smsAt, long playedAt, long now) {
        if (smsAt == null || smsAt > playedAt) return PilotDepartureWatch.Presence.UNKNOWN;
        try { return known(departure.assess(eventId, smsAt, playedAt, now)); }
        catch (RuntimeException unavailable) { return PilotDepartureWatch.Presence.UNKNOWN; }
    }
    private static PilotDepartureWatch.Presence known(PilotDepartureWatch.Presence presence) {
        return presence == null ? PilotDepartureWatch.Presence.UNKNOWN : presence;
    }
    private static String optionalRetryNote(JsonNode node) {
        if (!node.hasNonNull("note")) return "";
        if (!node.get("note").isTextual()) throw new IllegalArgumentException();
        if (node.get("note").textValue().trim().isEmpty()) return "";
        return text(node, "note", 1000, false);
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
