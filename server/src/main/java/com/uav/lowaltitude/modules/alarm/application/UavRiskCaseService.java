package com.uav.lowaltitude.modules.alarm.application;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.UavEventDto;
import com.uav.lowaltitude.modules.alarm.domain.UavVerificationBasis;
import com.uav.lowaltitude.modules.alarm.domain.UavEventState;
import com.uav.lowaltitude.modules.alarm.infrastructure.*;
import com.uav.lowaltitude.modules.identity.application.*;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.prediction.application.PredictionEventGate;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class UavRiskCaseService {
    private com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService responseOrder;
    @org.springframework.beans.factory.annotation.Autowired public void responseOrder(com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService value){responseOrder=value;}
    private final UavRiskCaseIdentity identities; private final UavEventVerificationService events;
    private final UavEventRepository eventRows; private final AlarmReadService alarms; private final UavAdvisoryService advisory;
    private final UavVerificationBasisRepository basis; private final PredictionEventGate predictions;
    private final AccessControlService access; private final IdempotencyGuard idempotency; private final JdbcTemplate jdbc;
    private final ObjectMapper json; private final AppClock clock; private final AuditService audit;
    public UavRiskCaseService(UavRiskCaseIdentity identities,UavEventVerificationService events,UavEventRepository eventRows,
            AlarmReadService alarms,UavAdvisoryService advisory,UavVerificationBasisRepository basis,PredictionEventGate predictions,
            AccessControlService access,IdempotencyGuard idempotency,JdbcTemplate jdbc,ObjectMapper json,AppClock clock,AuditService audit){
        this.identities=identities;this.events=events;this.eventRows=eventRows;this.alarms=alarms;this.advisory=advisory;this.basis=basis;
        this.predictions=predictions;this.access=access;this.idempotency=idempotency;this.jdbc=jdbc;this.json=json;this.clock=clock;this.audit=audit;
    }
    @Transactional(readOnly=true) public Map<String,Object> detail(String id){
        return identities.withCase(id,()->detailScoped(id));
    }
    private Map<String,Object> detailScoped(String id){
        var c=identities.find(id);if(c==null)throw missing();
        var scope=access.require(PermissionCode.ALARM_READ);
        var row=eventRows.find(c.actionEventId(),scope);if(row==null)throw missing();
        bind(id);var event=events.event(c.actionEventId());
        com.uav.lowaltitude.modules.identity.domain.AccessDecision disposal=null;
        try{disposal=access.require(PermissionCode.DISPOSAL_READ);}catch(ApiException denied){if(denied.getStatus()!=HttpStatus.FORBIDDEN)throw denied;}
        var summary=identities.summary(id,disposal);
        boolean mayVerify=false;
        try{access.require(PermissionCode.ALARM_VERIFY);mayVerify="PENDING_VERIFICATION".equals(summary.get("verification_status"));}catch(ApiException denied){if(denied.getStatus()!=HttpStatus.FORBIDDEN)throw denied;}
        var projected=new UavEventDto(event.eventId(),event.alarmId(),event.targetId(),(String)summary.get("verification_status"),event.version(),event.createdAt(),event.updatedAt(),
                mayVerify?List.of("VERIFY"):List.of(),mayVerify?verificationBasis(c):null);
        summary.put("allowed_actions",projected.allowedActions());
        summary.put("verifications",jdbc.queryForList("SELECT verification_id,case_version,event_id,event_version,conclusion,basis_kind,prediction_id,trigger_mode,automation_run_id,note,created_at,basis_json FROM uav_risk_case_verification WHERE risk_case_id=? ORDER BY case_version",id));
        return Map.of("risk_case",summary,"alarm",alarms.detail(row.alarmId()).withRiskCase(summary),"event",projected,"advisory",advisory.overview(c.actionEventId()));
    }
    @Transactional public Map<String,Object> verify(String id,String raw,String key){
        return identities.withCase(id,()->verifyScoped(id,raw,key));
    }
    private Map<String,Object> verifyScoped(String id,String raw,String key){
        var scope=access.require(PermissionCode.ALARM_READ);access.require(PermissionCode.ALARM_VERIFY);
        var c=identities.find(id);if(c==null||eventRows.find(c.actionEventId(),scope)==null)throw missing();
        bind(id);identities.lockCases(c.actionEventId());c=identities.find(id);
        var event=eventRows.lock(c.actionEventId(),scope);if(event==null)throw missing();
        com.fasterxml.jackson.databind.JsonNode input;
        try(var parser=json.getFactory().createParser(raw)){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);input=json.readTree(parser);if(input==null||!input.isObject()||parser.nextToken()!=null)throw new IllegalArgumentException();}
        catch(Exception ex){throw invalid("请求参数格式不正确");}
        for(var it=input.fieldNames();it.hasNext();)if(!Set.of("conclusion","note","expected_version","expected_case_version","prediction_id","risk_case_id").contains(it.next()))throw invalid("参数无效");
        if(input.has("risk_case_id")&&(!input.path("risk_case_id").isTextual()||!id.equals(input.path("risk_case_id").textValue())))throw invalid("风险编号与办理入口不一致");
        if(!input.path("expected_case_version").isIntegralNumber()||!input.path("expected_case_version").canConvertToLong()||input.path("expected_case_version").longValue()!=c.version())throw conflict("风险已更新，请刷新后核实");
        if(!input.path("expected_version").isIntegralNumber()||!input.path("expected_version").canConvertToLong()||input.path("expected_version").longValue()!=event.version())throw conflict("办理事件已更新，请刷新后核实");
        if(!input.path("conclusion").isTextual()||(input.has("note")&&!input.path("note").isTextual())||(input.hasNonNull("prediction_id")&&!input.path("prediction_id").isTextual()))throw invalid("核实参数类型不正确");
        String conclusion=input.path("conclusion").asText(),note=input.path("note").asText("").trim();
        if(!Set.of("CONFIRMED","FALSE_POSITIVE").contains(conclusion)||note.length()>1000)throw invalid("核实结论或说明无效");
        idempotency.claim(key,"risk-case-verification:"+id+":"+c.version()+":"+raw);
        if(!"PENDING_VERIFICATION".equals(identities.details(id).get("verification_status")))throw conflict("本次持续风险已核实，无需重复办理");
        String predictionId=input.path("prediction_id").asText(null);String kind="PREDICTED".equals(c.stage())?"PREDICTION":"OBSERVED";
        if("CONFIRMED".equals(conclusion)){
            if(Set.of("UNKNOWN","CLEARED","ENDED").contains(c.stage()))throw conflict("当前依据不足或风险已结束，不能核实通过");
            if("PREDICTION".equals(kind)){
                String block=predictions.confirmationBlock(event.eventId());if(block!=null&&!block.isEmpty())throw conflict(block);
                predictions.requireSelectedPrediction(event.eventId(),predictionId);
            }else{var result=UavVerificationBasis.evaluate(basis.facts(event.eventId(),clock.nowMillis()));if(!result.confirmable())throw conflict(result.message());
                if(!identities.currentMeasuredArea(c))throw conflict("缺少本禁飞区当前有效的实测入区依据");}
        }
        var actor=AuthContext.require();long now=clock.nowMillis();
        boolean eventVerdict="OBSERVED".equals(kind)&&UavEventState.verifiable(event.state())&&independentMeasuredCase(c);
        String snapshot=snapshot(c,kind,predictionId,now,!eventVerdict);
        jdbc.update("INSERT INTO uav_risk_case_verification(verification_id,risk_case_id,case_version,event_id,event_version,conclusion,basis_kind,prediction_id,actor_id,note,created_at,basis_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,c.version()+1,event.eventId(),event.version(),conclusion,kind,predictionId,actor.userId(),note,now,snapshot);
        jdbc.update("UPDATE uav_risk_case SET version=version+1 WHERE risk_case_id=?",id);
        // A unique, wholly measured risk is still the original human event verification. Keep
        // its real verdict/history for legacy punishment materials; a prediction or another area
        // must never inherit this event-level fact from a disposal-only confirmation.
        if(eventVerdict){
            var at=java.time.Instant.ofEpochMilli(now).atOffset(java.time.ZoneOffset.UTC);
            String next=UavEventState.next(event.state(),conclusion);
            if(eventRows.update(event.eventId(),event.version(),next,at)!=1)throw conflict("办理事件已更新，请刷新后核实");
            eventRows.appendHistory(UUID.randomUUID().toString(),event.eventId(),event.state(),next,conclusion,note,event.version()+1,actor.userId(),at);
            audit.record(actor.userId(),actor.account(),actor.roleCode(),"alarm","uav_event_verified","uav_event",event.eventId(),
                    "conclusion="+conclusion+"; version="+(event.version()+1)+"; risk_case_id="+id,"SUCCESS","","");
        }
        if("CONFIRMED".equals(conclusion)&&responseOrder!=null)responseOrder.ensureLocked(event);
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"alarm","uav_risk_case_verified","uav_risk_case",id,
                "conclusion="+conclusion+"; basis_kind="+kind+"; disposition_only="+!eventVerdict,"SUCCESS","","");
        return detail(id);
    }
    private boolean independentMeasuredCase(UavRiskCaseIdentity.CaseRef c){
        if(c.episodeId()!=null||c.manualCounterOnly())return false;
        var current=identities.allCases(c.actionEventId());
        if(current.size()!=1||!c.riskCaseId().equals(current.get(0).riskCaseId()))return false;
        // Inspect every member and superseded identity, not merely today's selected case label.
        return jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_member m WHERE m.risk_case_id=? AND ("
                +"EXISTS(SELECT 1 FROM prediction_event_link p WHERE p.event_id=m.event_id)"
                +" OR EXISTS(SELECT 1 FROM uav_risk_case_member old_member JOIN uav_risk_case old_case ON old_case.risk_case_id=old_member.risk_case_id WHERE old_member.event_id=m.event_id AND old_case.episode_id IS NOT NULL)"
                +" OR EXISTS(SELECT 1 FROM uav_event old_event JOIN alarm old_alarm ON old_alarm.alarm_id=old_event.alarm_id WHERE old_event.event_id=m.event_id AND old_alarm.alarm_type='PREDICTED_AIRSPACE_ENTRY'))",Long.class,c.riskCaseId())==0;
    }
    private String snapshot(UavRiskCaseIdentity.CaseRef c,String kind,String predictionId,long now,boolean dispositionOnly){
        var frozen=new LinkedHashMap<String,Object>();frozen.put("checked_at",now);frozen.put("stage",c.stage());frozen.put("basis_kind",kind);frozen.put("disposition_only",dispositionOnly);frozen.put("selected_prediction_id",predictionId);
        var current=identities.details(c.riskCaseId());frozen.put("checked_prediction_id",current.get("latest_prediction_id"));frozen.put("airspace_id",c.airspaceId());
        frozen.put("measured_entries",jdbc.queryForList("SELECT x.evaluation_id,x.observed_at,x.airspace_version_id FROM prediction_actual_entry_link x JOIN uav_risk_case_member m ON m.event_id=x.event_id WHERE m.risk_case_id=? AND x.airspace_id=? ORDER BY x.observed_at DESC FETCH FIRST 1 ROW ONLY",c.riskCaseId(),c.airspaceId()));
        try{return json.writeValueAsString(frozen);}catch(Exception invalid){throw new IllegalStateException("Cannot freeze verification evidence",invalid);}
    }
    private com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerificationBasisDto verificationBasis(UavRiskCaseIdentity.CaseRef c){
        long now=clock.nowMillis();String block=null;
        if(Set.of("UNKNOWN","CLEARED","ENDED").contains(c.stage()))block="当前依据不足或风险已结束，不能核实通过";
        else if("PREDICTED".equals(c.stage()))block=predictions.confirmationBlock(c.actionEventId());
        else if(!identities.currentMeasuredArea(c))block="缺少本禁飞区当前有效的实测入区依据";
        else{var result=UavVerificationBasis.evaluate(basis.facts(c.actionEventId(),now));if(!result.confirmable())block=result.message();}
        boolean valid=block==null||block.isEmpty();
        return new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerificationBasisDto(valid,valid?List.of():List.of("RISK_EVIDENCE_UNAVAILABLE"),valid?null:block,now);
    }
    private void bind(String id){if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes r)r.getRequest().setAttribute(UavRiskCaseIdentity.REQUEST_CASE,id);}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"UAV_RISK_CASE_NOT_FOUND","无人机风险不存在或无权限");}
    private static ApiException invalid(String m){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",m);}
    private static ApiException conflict(String m){return new ApiException(HttpStatus.CONFLICT,"RISK_CASE_CHANGED",m);}
}
