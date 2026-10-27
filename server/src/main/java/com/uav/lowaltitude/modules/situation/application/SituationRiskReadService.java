package com.uav.lowaltitude.modules.situation.application;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.application.RiskPresenceService;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.situation.api.SituationRiskDtos.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read composition only: no verification, scheduling, authorization or history writes. */
@Service
public class SituationRiskReadService {
    private final AccessControlService access;
    private final AlarmReadRepository alarms;
    private final RiskRepository risks;
    private final RiskPresenceService presence;
    private final AppClock clock;
    private final ObjectMapper json;
    public SituationRiskReadService(AccessControlService access, AlarmReadRepository alarms,
            RiskRepository risks, RiskPresenceService presence, AppClock clock, ObjectMapper json) {
        this.access=access;this.alarms=alarms;this.risks=risks;this.presence=presence;this.clock=clock;this.json=json;
    }
    private record Candidate(Item item, boolean active, boolean processing) { }
    private static final Set<String> PARAMETERS=Set.of("kind","view","page","size");
    private static final Comparator<Item> ORDER=Comparator.comparingInt((Item i)->severity(i.severity())).reversed()
            .thenComparing(Item::occurredAt,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Item::key);

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(MultiValueMap<String,String> values) {
        AccessDecision alarmScope=optional(PermissionCode.ALARM_READ);
        AccessDecision riskScope=optional(PermissionCode.RISK_READ);
        if(riskScope!=null && optional(PermissionCode.FLIGHT_READ)==null) riskScope=null;
        if(alarmScope==null && riskScope==null) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权读取态势风险");
        AccessDecision targetScope=optional(PermissionCode.TARGET_READ);
        boolean routeReadable=optional(PermissionCode.ROUTE_READ)!=null;
        if(values.keySet().stream().anyMatch(key->!PARAMETERS.contains(key))) throw invalid();
        Kind kind=enumeration(values,"kind",Kind.ALL,Kind.class);
        View view=enumeration(values,"view",View.ACTIVE,View.class);
        int page=integer(values,"page",1,Integer.MAX_VALUE),size=integer(values,"size",20,100);
        long offset=(long)(page-1)*size,now=clock.nowMillis();
        var candidates=new LinkedHashMap<String,Candidate>();
        var riskRows=new HashMap<String,RiskRepository.RiskRow>();
        var alarmRows=new HashMap<String,AlarmReadRepository.AlarmRow>();
        if(alarmScope!=null) for(var row:alarms.situationRows(alarmScope)) {
            var a=row.alarm();
            boolean active=activeUav(row);
            boolean processing=row.workRemains() || (row.currentUnit() && !Set.of("CLEARED","ENDED").contains(row.stage())
                    && Set.of("PENDING_VERIFICATION","CONFIRMED").contains(Objects.toString(a.state(),""))
                    && !"HISTORY".equals(a.attentionGroup()));
            String key=a.riskCaseId()!=null?"risk:"+a.riskCaseId():a.eventId()!=null?"event:"+a.eventId():"alarm:"+a.alarmId();
            List<String> reasons=reasons(a.escalationCount()>0?a.escalatedReasonsJson():a.detailJson());
            Item item=new Item(key,Kind.UAV,a.riskCaseId(),row.caseVersion(),a.eventId(),row.eventVersion(),a.alarmId(),null,
                    a.targetId(),a.targetNo(),null,null,null,row.areaId(),row.areaName(),a.severity(),reasons.isEmpty()?null:String.join(",",reasons),
                    reasons.isEmpty()?null:com.uav.lowaltitude.platform.export.CsvLabels.violationReasons(reasons),
                    row.occurredAt(),millis(row.updatedAt()),row.stage(),a.state(),a.sourceMode(),
                    row.simulated(),null,active?"CURRENT":row.stage(),active?null:"当前风险已结束或依据不足，暂停新处置");
            merge(candidates,new Candidate(item,active,processing));
            alarmRows.put(key,a);
        }
        if(riskScope!=null) {
            var query=new RiskRepository.RiskQuery(null,null,null,null,null,null,null,null,null,null,null,
                    List.of("SPACE_OBJECT","FOREIGN_OBJECT","WEATHER"),true);
            for(var candidate:risks.situationCandidates(query,riskScope,now)) {
                var row=candidate.risk();
                // Queue-only history needs no current spatial/detail expansion on every poll.
                var current=candidate.possibleCurrent()?presence.read(row,now):new RiskPresenceService.Presence(
                        "EXCLUDED".equals(row.state())?"EXCLUDED":"NOT_CURRENT","风险已不在当前有效集合，未完成的通知和回执继续办理",null,false);
                boolean active=activePresence(current);
                boolean processing=candidate.processing();
                Kind category="WEATHER".equals(row.riskType())?Kind.WEATHER:Kind.SPACE_OBJECT;
                Item item=new Item((category==Kind.WEATHER?"weather:":"space:")+row.riskId(),category,null,null,null,null,null,row.riskId(),row.targetId(),row.targetNo(),
                        row.planId(),row.planNo(),row.routeVersionId(),row.districtId(),row.districtName(),row.severity(),row.reasonCode(),row.reasonText(),
                        millis(row.occurredAt()),millis(row.updatedAt()),current.status(),row.state(),row.sourceMode(),null,null,current.status(),
                        "UNKNOWN".equals(current.status())?"当前影响待确认："+current.reason():current.reason());
                riskRows.put(item.key(),row);
                merge(candidates,new Candidate(item,active,processing));
            }
        }
        var active=candidates.values().stream().filter(Candidate::active).map(Candidate::item).toList();
        Long uav=alarmScope==null?null:count(active,Kind.UAV),space=riskScope==null?null:count(active,Kind.SPACE_OBJECT),weather=riskScope==null?null:count(active,Kind.WEATHER);
        var counts=new Counts(uav==null||space==null||weather==null?null:uav+space+weather,uav,space,weather);
        var selected=candidates.values().stream().filter(c->view==View.ACTIVE?c.active():c.processing())
                .map(Candidate::item).filter(i->kind==Kind.ALL||kind==i.kind()).sorted(ORDER).toList();
        int start=(int)Math.min(offset,selected.size()),end=(int)Math.min((long)start+size,selected.size());
        var items=new ArrayList<Item>();
        // Simulation provenance only for the visible page, never load risk details for all candidates.
        for(var item:selected.subList(start,end)) {
            var row=riskRows.get(item.key());
            var alarm=alarmRows.get(item.key());
            String org=row==null?alarm.ownerOrgId():row.ownerOrgId(),district=row==null?alarm.districtId():row.districtId();
            boolean targetVisible=targetScope!=null && alarms.targetVisible(item.targetId(),org,district,targetScope);
            String route=row!=null && routeReadable && risks.routeReferenceVisible(row)?item.routeVersionId():null;
            items.add(new Item(item.key(),item.kind(),item.riskCaseId(),item.riskCaseVersion(),item.eventId(),item.eventVersion(),
                    item.alarmId(),item.riskId(),targetVisible?item.targetId():null,targetVisible?item.targetNo():null,item.planId(),item.planNo(),route,
                    item.areaId(),item.areaName(),item.severity(),item.reasonCode(),item.reasonText(),item.occurredAt(),item.updatedAt(),
                    item.stage(),item.state(),item.sourceMode(),row==null?item.simulated():risks.simulatedObservation(row),item.position(),item.currentStatus(),item.presenceReason()));
        }
        return new Page(List.copyOf(items),page,size,selected.size(),counts,new Availability(
                alarmScope==null?"UNAVAILABLE":"AVAILABLE",riskScope==null?"UNAVAILABLE":"AVAILABLE",riskScope==null?"UNAVAILABLE":"AVAILABLE"),now);
    }
    /** Exact scoped lookup survives pagination and an item's exit from the active/processing queues. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Item detail(String key) {
        if(key==null || key.length()>80 || !key.matches("(risk|event|alarm|space|weather):[A-Za-z0-9_-]{1,36}"))throw invalid();
        int split=key.indexOf(':');String type=key.substring(0,split),id=key.substring(split+1);
        AccessDecision targetScope=optional(PermissionCode.TARGET_READ);
        boolean routeReadable=optional(PermissionCode.ROUTE_READ)!=null;
        if(Set.of("risk","event","alarm").contains(type)) {
            var row=alarms.situationItem(access.require(PermissionCode.ALARM_READ),type,id);
            if(row==null)throw missing();
            var a=row.alarm();boolean active=activeUav(row);
            boolean targetVisible=targetScope!=null&&alarms.targetVisible(a.targetId(),a.ownerOrgId(),a.districtId(),targetScope);
            var reasons=reasons(a.escalationCount()>0?a.escalatedReasonsJson():a.detailJson());
            String status=active?"CURRENT":row.currentUnit()&&"UNKNOWN".equals(row.stage())?"UNKNOWN":"NOT_CURRENT";
            return new Item(key,Kind.UAV,a.riskCaseId(),row.caseVersion(),a.eventId(),row.eventVersion(),a.alarmId(),null,
                    targetVisible?a.targetId():null,targetVisible?a.targetNo():null,null,null,null,row.areaId(),row.areaName(),a.severity(),
                    reasons.isEmpty()?null:String.join(",",reasons),reasons.isEmpty()?null:com.uav.lowaltitude.platform.export.CsvLabels.violationReasons(reasons),
                    row.occurredAt(),millis(row.updatedAt()),row.stage(),a.state(),a.sourceMode(),row.simulated(),null,status,
                    active?null:!row.currentUnit()?"归属单位已不在当前有效目录，保留历史详情与停止回执":"当前风险已结束或依据不足，暂停新处置");
        }
        var scope=access.require(PermissionCode.RISK_READ);access.require(PermissionCode.FLIGHT_READ);
        var row=risks.find(id,scope);
        Kind kind="weather".equals(type)?Kind.WEATHER:Kind.SPACE_OBJECT;
        if(row==null || (kind==Kind.WEATHER?!"WEATHER".equals(row.riskType()):!Set.of("SPACE_OBJECT","FOREIGN_OBJECT").contains(row.riskType())))throw missing();
        boolean currentUnit=risks.currentUnit(row);
        var current=presence.read(row,clock.nowMillis());
        boolean active=currentUnit&&activePresence(current);
        String status=active?"CURRENT":currentUnit&&"UNKNOWN".equals(current.status())?"UNKNOWN":"NOT_CURRENT";
        boolean targetVisible=targetScope!=null&&alarms.targetVisible(row.targetId(),row.ownerOrgId(),row.districtId(),targetScope);
        boolean planVisible=risks.planReferenceVisible(row);
        return new Item(key,kind,null,null,null,null,null,row.riskId(),targetVisible?row.targetId():null,targetVisible?row.targetNo():null,
                planVisible?row.planId():null,planVisible?row.planNo():null,routeReadable&&risks.routeReferenceVisible(row)?row.routeVersionId():null,
                row.districtId(),row.districtName(),row.severity(),row.reasonCode(),row.reasonText(),millis(row.occurredAt()),millis(row.updatedAt()),
                current.status(),row.state(),row.sourceMode(),risks.simulatedObservation(row),null,status,
                !currentUnit?"归属单位已不在当前有效目录，保留历史详情与通知回执":"UNKNOWN".equals(current.status())?"当前影响待确认："+current.reason():current.reason());
    }
    private static boolean activeUav(AlarmReadRepository.SituationRow row) {
        return row.currentUnit()&&(Set.of("PREDICTED","ENTERED").contains(row.stage())
                ||("OBSERVED".equals(row.stage())&&"CURRENT".equals(row.alarm().observationStatus())&&!"HISTORY".equals(row.alarm().attentionGroup())));
    }
    private static boolean activePresence(RiskPresenceService.Presence current) {
        return current.eligibleForCurrentList()&&"CURRENT".equals(current.status());
    }
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"SITUATION_RISK_NOT_FOUND","风险不存在或无权限");}
    private static void merge(Map<String,Candidate> candidates,Candidate next) {
        candidates.merge(next.item().key(),next,(a,b)->ORDER.compare(a.item(),b.item())<=0?a:b);
    }
    private AccessDecision optional(PermissionCode permission) {
        try{return access.require(permission);}catch(ApiException e){if(e.getStatus()==HttpStatus.FORBIDDEN)return null;throw e;}
    }
    private static long count(List<Item> items,Kind kind){return items.stream().filter(i->i.kind()==kind).count();}
    private static Long millis(OffsetDateTime at){return at==null?null:at.toInstant().toEpochMilli();}
    private static int severity(String value){return switch(Objects.toString(value,"")){case "CRITICAL"->4;case "HIGH"->3;case "MEDIUM"->2;case "LOW"->1;default->0;};}
    private static String single(MultiValueMap<String,String> values,String key) {
        var list=values.get(key);if(list==null)return null;
        if(list.size()!=1||list.get(0)==null||list.get(0).isBlank())throw invalid();return list.get(0);
    }
    private static int integer(MultiValueMap<String,String> values,String key,int fallback,int max) {
        String text=single(values,key);if(text==null)return fallback;
        try{int value=Integer.parseInt(text);if(value<1||value>max)throw invalid();return value;}catch(NumberFormatException e){throw invalid();}
    }
    private static <T extends Enum<T>> T enumeration(MultiValueMap<String,String> values,String key,T fallback,Class<T> type) {
        String text=single(values,key);if(text==null)return fallback;
        try{return Enum.valueOf(type,text);}catch(IllegalArgumentException e){throw invalid();}
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","态势风险分类、视图或分页参数无效");}
    private List<String> reasons(String raw) {
        if(raw==null||raw.isBlank())return List.of();
        try {
            var node=json.readTree(raw);if(node!=null&&node.isTextual())node=json.readTree(node.textValue());
            if(node!=null&&node.isObject())node=node.get("violation_reasons");
            var result=new ArrayList<String>();
            if(node!=null&&node.isArray())for(var value:node)if(value.isTextual()&&!result.contains(value.textValue()))result.add(value.textValue());
            return result;
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){return List.of();}
    }
}
