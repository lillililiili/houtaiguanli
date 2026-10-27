package com.uav.lowaltitude.modules.alarm.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.disposal.application.DisposalAuthorizationService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

/** One independent transaction per event. Reuses single-event use cases and their locks/audit/idempotency. */
@Service
public class UavEventBatchItemService {
    private UavRiskCaseIdentity cases;
    private UavRiskCaseService riskCases;
    @org.springframework.beans.factory.annotation.Autowired public void riskCases(UavRiskCaseIdentity cases,UavRiskCaseService riskCases){this.cases=cases;this.riskCases=riskCases;}
    private final UavEventVerificationService verification;
    private final DisposalAuthorizationService disposal;
    private final AccessControlService access;
    private final UavEventRepository events;

    public UavEventBatchItemService(UavEventVerificationService verification, DisposalAuthorizationService disposal,
            AccessControlService access, UavEventRepository events) {
        this.verification = verification; this.disposal = disposal; this.access = access; this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object verify(JsonNode item) {
        return isolated(()->verifyScoped(item));
    }
    private Object verifyScoped(JsonNode item) {
        var selected = resolve(item,true);
        ObjectNode request = ((ObjectNode) item).deepCopy();
        request.remove("event_id"); request.remove("idempotency_key");
        if(selected!=null){
            request.put("risk_case_id",selected.riskCaseId());
            request.put("expected_case_version",selected.version());
            if(!item.has("risk_case_id") && "PREDICTED".equals(selected.stage()) && !request.hasNonNull("prediction_id")){
                Object prediction=cases.details(selected.riskCaseId()).get("latest_prediction_id");
                if(prediction!=null)request.put("prediction_id",prediction.toString());
            }
            riskCases.verify(selected.riskCaseId(),request.toString(),text(item,"idempotency_key"));
            // Commit the command before clients refresh the read model, as in the single-case endpoint.
            return null;
        }
        request.remove("risk_case_id");request.remove("expected_case_version");
        return verification.verify(text(item, "event_id"), request.toString(), text(item, "idempotency_key"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object counter(JsonNode item) {
        return isolated(()->counterSelected(item));
    }
    private Object counterSelected(JsonNode item) {
        var selected = resolve(item,true);
        return selected==null?counterScoped(item):cases.withCase(selected.riskCaseId(),()->counterScoped(item));
    }

    private Object counterScoped(JsonNode item) {
        String mode = text(item, "mode");
        if (!"REQUEST".equals(mode) && !"DIRECT".equals(mode)) throw invalid("mode 必须为 REQUEST 或 DIRECT");
        access.require("DIRECT".equals(mode) ? PermissionCode.DISPOSAL_DIRECT : PermissionCode.DISPOSAL_REQUEST);
        var scope = access.require(PermissionCode.ALARM_READ);
        String eventId = text(item, "event_id");
        var event = events.lock(eventId, scope);
        if (event == null) throw new ApiException(HttpStatus.NOT_FOUND, "UAV_EVENT_NOT_FOUND", "无人机事件不存在");
        JsonNode expected = item.get("expected_version");
        if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0)
            throw invalid("expected_version 无效");
        if (event.version() != expected.longValue())
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "事件已更新，请刷新后逐项核对");
        JsonNode request = item.get("request");
        if (request == null || !request.isObject()
                || !"UAV_EVENT".equals(text(request, "subject_kind"))
                || !eventId.equals(text(request, "subject_id"))
                || !"COUNTERMEASURE".equals(text(request, "action_type")))
            throw invalid("批量反制必须针对所选事件，动作须为 COUNTERMEASURE");
        String key = text(item, "idempotency_key");
        return "DIRECT".equals(mode) ? disposal.directExecute(request.toString(), key) : disposal.create(request.toString(), key);
    }


    /** Resolve each member independently; legacy event requests must never choose between areas. */
    UavRiskCaseIdentity.CaseRef resolve(JsonNode item,boolean lock) {
        if(cases==null)return null;
        String eventId=text(item,"event_id");
        String requested=item.has("risk_case_id")?text(item,"risk_case_id"):null;
        var associations=cases.allCases(eventId);
        if(requested==null && associations.size()>1)throw new ApiException(HttpStatus.CONFLICT,"RISK_CASE_AMBIGUOUS","该事件关联多个风险，请按风险重新选择");
        var selected=requested==null?(associations.isEmpty()?null:associations.get(0)):cases.find(requested);
        if(requested!=null && (selected==null || !selected.eventIds().contains(eventId)))throw new ApiException(HttpStatus.CONFLICT,"RISK_CASE_EVENT_MISMATCH","事件不属于所选风险");
        if(selected==null)throw new ApiException(HttpStatus.CONFLICT,"RISK_CASE_REQUIRED","事件缺少唯一风险关联，请重新读取风险后办理");
        if(lock){String caseId=selected.riskCaseId();cases.runWithCase(caseId,()->cases.lockCases(eventId));selected=cases.find(caseId);
            if(requested==null){var current=cases.allCases(eventId);if(current.size()!=1 || !current.get(0).riskCaseId().equals(caseId))throw new ApiException(HttpStatus.CONFLICT,"RISK_CASE_AMBIGUOUS","事件风险关联已变化，请重新选择");}
        }
        if(selected==null || !eventId.equals(selected.actionEventId()))throw new ApiException(HttpStatus.CONFLICT,"RISK_CASE_CHANGED","办理事件已变化，请重新读取风险");
        if(requested!=null){
            JsonNode expected=item.get("expected_case_version");
            if(expected==null || !expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue()!=selected.version())throw new ApiException(HttpStatus.CONFLICT,"RISK_CASE_CHANGED","风险已更新，请重新读取办理条件");
        }
        return selected;
    }
    @Transactional(readOnly=true)
    public com.uav.lowaltitude.modules.alarm.api.UavEventBatchDtos.PrecheckItem precheck(JsonNode item) {
        return isolated(()->precheckScoped(item));
    }
    private com.uav.lowaltitude.modules.alarm.api.UavEventBatchDtos.PrecheckItem precheckScoped(JsonNode item) {
        var selected=resolve(item,false);
        if(selected==null)return null;
        var detail=riskCases.detail(selected.riskCaseId());
        var event=(com.uav.lowaltitude.modules.alarm.api.AlarmDtos.UavEventDto)detail.get("event");
        var counter=(com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Overview)detail.get("advisory");
        var conclusions=new java.util.ArrayList<String>();
        if(event.allowedActions().contains("VERIFY")){conclusions.add("FALSE_POSITIVE");if(event.verificationBasis()!=null && event.verificationBasis().confirmable())conclusions.add("CONFIRMED");}
        var summary=(java.util.Map<?,?>)detail.get("risk_case");
        if(!(summary.get("version") instanceof Number version) || version.longValue()!=selected.version())throw new ApiException(HttpStatus.CONFLICT,"RISK_CASE_CHANGED","风险已更新，请重新读取办理条件");
        Object prediction=summary.get("latest_prediction_id");
        return new com.uav.lowaltitude.modules.alarm.api.UavEventBatchDtos.PrecheckItem(event.eventId(),selected.riskCaseId(),selected.version(),prediction==null?null:prediction.toString(),"READY",event,java.util.List.copyOf(conclusions),event.verificationBasis()==null?"当前风险无需核实":event.verificationBasis().message(),new com.uav.lowaltitude.modules.alarm.api.UavEventBatchDtos.CounterOptions(counter.canRequestCounter(),counter.canDirectCounter(),counter.counterBlockReason()),null);
    }

    private <T> T isolated(java.util.function.Supplier<T> operation) {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet))return operation.get();
        var request=servlet.getRequest();Object previous=request.getAttribute(UavRiskCaseIdentity.REQUEST_CASE);
        Object version=request.getAttribute(UavRiskCaseIdentity.REQUEST_VERSION);
        request.removeAttribute(UavRiskCaseIdentity.REQUEST_CASE);request.removeAttribute(UavRiskCaseIdentity.REQUEST_VERSION);
        try{return operation.get();}finally{
            if(previous==null)request.removeAttribute(UavRiskCaseIdentity.REQUEST_CASE);else request.setAttribute(UavRiskCaseIdentity.REQUEST_CASE,previous);
            if(version==null)request.removeAttribute(UavRiskCaseIdentity.REQUEST_VERSION);else request.setAttribute(UavRiskCaseIdentity.REQUEST_VERSION,version);
        }
    }
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid(field + " 必须为字符串");
        return value.textValue();
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
}
