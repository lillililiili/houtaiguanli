package com.uav.lowaltitude.modules.alarm.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.NoCounterDtos.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoCounterService {
    private final NoCounterRepository repository;
    private final UavEventRepository events;
    private final AccessControlService access;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AuditService audit;
    public NoCounterService(NoCounterRepository repository,UavEventRepository events,AccessControlService access,ObjectMapper json,AppClock clock,AuditService audit) {
        this.repository=repository;this.events=events;this.access=access;this.json=json;this.clock=clock;this.audit=audit;
    }
    @Transactional(readOnly=true)
    public Status get(String id) {
        var event=events.find(id,access.require(PermissionCode.ALARM_READ));
        if(event==null)throw missing();
        return status(event);
    }
    /** Caller has already authorized ALARM_READ and the supplied event scope. */
    public Status status(EventRow event) {
        var snapshot=repository.snapshot(event);
        String reason=snapshot.blockReason();
        boolean verify=false;
        try { verify=events.find(event.eventId(),access.require(PermissionCode.ALARM_VERIFY))!=null; }
        catch(ApiException denied) { /* A reader may see the decision but cannot make one. */ }
        if(!verify && reason.isEmpty())reason="当前账号没有本事件的告警核实权限";
        var d=snapshot.decision();
        return new Status(event.eventId(),event.version(),verify&&reason.isEmpty(),reason,snapshot.basis(),d==null?null:new Decision(d.id(),d.at(),d.actorName(),d.reason(),d.frozen().basis()),snapshot.active(),snapshot.reviewRequired());
    }
    @Transactional
    public Status decide(String id,String raw,String key) {
        var read=access.require(PermissionCode.ALARM_READ);
        var verify=access.require(PermissionCode.ALARM_VERIFY);
        if(events.find(id,verify)==null)throw missing();
        Request request=parse(raw);
        if(key==null||key.trim().length()<8||key.trim().length()>128)throw error(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key 必须为8至128个字符");
        var event=events.lock(id,read);if(event==null||events.find(id,verify)==null)throw missing();
        var actor=AuthContext.require();String hash=hash(id+":"+request.version()+":"+request.evaluationId());
        var replay=repository.replay(actor.userId(),key.trim());
        if(replay!=null) {
            if(!id.equals(replay.eventId())||!hash.equals(replay.hash()))throw conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于其他操作");
            try {
                Status original=json.readValue(replay.response(),Status.class);
                Status current=status(event);
                return new Status(id,current.eventVersion(),current.canDecide(),current.blockReason(),current.basis(),current.decision(),current.decisionActive(),current.reviewRequired(),original.decision().decisionId());
            }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException(invalid);}
        }
        if(event.version()!=request.version())throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        var snapshot=repository.snapshot(event);
        if(!snapshot.blockReason().isEmpty())throw conflict("NO_COUNTER_BLOCKED",snapshot.blockReason());
        if(snapshot.basis()==null||!request.evaluationId().equals(snapshot.basis().evaluationId()))throw conflict("EVALUATION_CONFLICT","当前研判已更新，请刷新后重新确认");
        long now=clock.nowMillis();
        if(events.update(id,event.version(),event.state(),Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))!=1)throw conflict("VERSION_CONFLICT","事件已更新，请刷新后重试");
        String decisionId=UUID.randomUUID().toString();
        repository.append(decisionId,event,actor.userId(),repository.actorName(actor.userId()),now,snapshot.frozen());
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"alarm","uav_no_counter_decided","uav_event",id,"decision_id="+decisionId+"; evaluation_id="+snapshot.basis().evaluationId()+"; 人工确认当前无风险，决定不反制","SUCCESS","","");
        Status result=status(events.find(id,read));
        try {repository.saveReplay(actor.userId(),key.trim(),hash,id,json.writeValueAsString(result));}
        catch(org.springframework.dao.DuplicateKeyException duplicate){throw conflict("IDEMPOTENCY_KEY_REUSED","该请求编号已用于其他操作");}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException(invalid);}
        return result;
    }
    private Request parse(String raw) {
        try(JsonParser parser=json.getFactory().createParser(raw==null?"":raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);com.fasterxml.jackson.databind.JsonNode n=json.readTree(parser);
            if(n==null||!n.isObject()||n.size()!=2||parser.nextToken()!=null||!n.has("expected_version")||!n.get("expected_version").isIntegralNumber()
                    ||!n.get("expected_version").canConvertToLong()||n.get("expected_version").longValue()<0||!n.has("expected_evaluation_id")||!n.get("expected_evaluation_id").isTextual()
                    ||n.get("expected_evaluation_id").textValue().isBlank()||n.get("expected_evaluation_id").textValue().length()>36)throw new IllegalArgumentException();
            return new Request(n.get("expected_version").longValue(),n.get("expected_evaluation_id").textValue());
        }catch(Exception invalid){throw error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","需要当前事件版本和研判编号");}
    }
    private record Request(long version,String evaluationId) { }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception invalid){throw new IllegalStateException(invalid);}}
    private static ApiException error(HttpStatus status,String code,String text){return new ApiException(status,code,text);}
    private static ApiException missing(){return error(HttpStatus.NOT_FOUND,"UAV_EVENT_NOT_FOUND","无人机事件不存在");}
    private static ApiException conflict(String code,String text){return error(HttpStatus.CONFLICT,code,text);}
}
