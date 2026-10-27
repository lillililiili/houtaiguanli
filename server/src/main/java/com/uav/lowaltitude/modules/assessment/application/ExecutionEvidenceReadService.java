package com.uav.lowaltitude.modules.assessment.application;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.*;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightExecutionFactRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

/** Historical execution assertions are selected by saved rule references, never by latest target data. */
@Service
public class ExecutionEvidenceReadService {
    private final LegalityEvaluationReadService evaluations;
    private final FlightExecutionFactRepository facts;
    private final FlightReadRepository plans;
    private final AccessControlService access;
    public ExecutionEvidenceReadService(LegalityEvaluationReadService evaluations,FlightExecutionFactRepository facts,
            FlightReadRepository plans,AccessControlService access) {
        this.evaluations=evaluations;this.facts=facts;this.plans=plans;this.access=access;
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(BigDecimal longitude,BigDecimal latitude,String coordinateSystem,Long occurredAt,BigDecimal accuracyM,
            String name,String nameBasis,Long planVersion,Long factVersion,Long validFrom,Long validTo,String sourceMode,String sourceName) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(String evaluationId,String ruleCode,String dimension,String comparison,Detail planned,Detail actual,
            String plannedAvailability,String actualAvailability,String reason) { }
    public record Result(String status,List<Item> items) { }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result read(String evaluationId) {
        access.require(PermissionCode.TARGET_READ);
        var planScope=access.require(PermissionCode.FLIGHT_READ);
        EvaluationDto evaluation=evaluations.detail(evaluationId);
        var output=new ArrayList<Item>();
        if(evaluation.hitDetails()==null)return new Result("UNKNOWN",List.of());
        for(var hit:evaluation.hitDetails()) {
            String dimension=switch(hit.ruleCode()){case "C02-9"->"takeoff_point";case "C02-10"->"landing_point";
                case "C02-11"->"pilot";case "C02-12"->"reporting_unit";default->null;};
            if(dimension==null)continue;
            String comparison=Objects.toString(hit.facts().get("comparison"),"UNDETERMINED");
            Long planVersion=number(hit.facts().get("plan_version"));
            var planIds=references(hit,"flight_plan");
            var factIds=references(hit,"flight_execution_fact");
            Detail planned=null,actual=null;
            var reasons=new ArrayList<String>();
            if(planVersion!=null && planIds.size()==1 && planIds.get(0).equals(evaluation.planId())) {
                var plan=plans.findPlan(planIds.get(0),planScope);
                if(plan!=null && plan.version()==planVersion && Objects.equals(plan.ownerOrgId(),evaluation.ownerOrgId())
                        && Objects.equals(plan.districtId(),evaluation.districtId())) {
                    var filing=plans.findFiling(plan.planId(),planScope);
                    if(filing!=null) {
                        boolean takeoff="takeoff_point".equals(dimension),position=dimension.endsWith("point");
                        String name="pilot".equals(dimension)?filing.pilotName():"reporting_unit".equals(dimension)
                                ?facts.planReportingLabel(plan.planId(),planVersion,evaluation.ownerOrgId(),evaluation.districtId(),plan.sourceMode()):null;
                        planned=new Detail(position?(takeoff?filing.takeoffLongitude():filing.landingLongitude()):null,
                                position?(takeoff?filing.takeoffLatitude():filing.landingLatitude()):null,
                                position?filing.coordinateSystem():null,null,null,name,name==null?null:"pilot".equals(dimension)?"PLAN_FILING":"CURRENT_DIRECTORY",planVersion,
                                null,null,null,plan.sourceMode(),plan.sourceName());
                    }
                } else reasons.add("原计划版本未留存或已变更，不使用当前计划替代当次报备依据");
            } else reasons.add("缺少本次规则保存的唯一计划及版本引用");
            if(factIds.size()==1 && evaluation.targetId()!=null && evaluation.trackId()!=null) {
                var stored=facts.historical(factIds.get(0),evaluation.targetId(),evaluation.trackId(),evaluation.ownerOrgId(),evaluation.districtId(),evaluation.sourceMode());
                if(stored!=null && Objects.equals(number(hit.facts().get("fact_version")),stored.input().version())) {
                    var input=stored.input();boolean position=dimension.endsWith("point");
                    var event="takeoff_point".equals(dimension)?input.takeoff():input.landing();
                    String name="pilot".equals(dimension)?facts.pilotLabel(input.pilotContactId(),evaluation.ownerOrgId()):"reporting_unit".equals(dimension)
                            ?facts.reportingLabel(input.reportingBindingId(),input.sourceId(),evaluation.ownerOrgId()):null;
                    actual=new Detail(position&&event!=null?event.longitude():null,position&&event!=null?event.latitude():null,null,
                            position&&event!=null?event.occurredAt():null,position&&event!=null?event.accuracyM():null,
                            name,name==null?null:"CURRENT_DIRECTORY",null,input.version(),input.validFrom(),input.validTo(),input.sourceMode(),stored.sourceName());
                    if(position)reasons.add("原始执行事实未记录坐标系，仅展示原始坐标，不推断或用于地图定位");
                } else reasons.add("本次执行事实引用不存在、版本或来源范围不匹配");
            } else reasons.add("缺少本次规则保存的唯一执行事实或可读轨迹引用");
            String plannedAvailability=available(planned,dimension)?"AVAILABLE":"UNKNOWN";
            String actualAvailability=available(actual,dimension)?"AVAILABLE":"UNKNOWN";
            if(planned!=null && dimension.endsWith("point") && !"WGS84".equals(planned.coordinateSystem()))
                reasons.add("原计划未记录明确的 WGS84 坐标系，仅显示原始坐标");
            if(!"AVAILABLE".equals(plannedAvailability))reasons.add("当次计划值未记录或不可读");
            if(!"AVAILABLE".equals(actualAvailability))reasons.add("当次实际值未记录或身份名称不可读");
            output.add(new Item(evaluationId,hit.ruleCode(),dimension,comparison,planned,actual,plannedAvailability,actualAvailability,
                    reasons.isEmpty()?null:String.join("；",reasons)));
        }
        boolean complete=!output.isEmpty()&&output.stream().allMatch(i->"AVAILABLE".equals(i.plannedAvailability())
                && "AVAILABLE".equals(i.actualAvailability()) && i.reason()==null);
        return new Result(output.isEmpty()?"UNKNOWN":complete?"AVAILABLE":"PARTIAL",List.copyOf(output));
    }
    private static boolean available(Detail detail,String dimension) {
        return detail!=null&&(dimension.endsWith("point")?detail.longitude()!=null&&detail.latitude()!=null:detail.name()!=null);
    }
    private static Long number(Object value){return value instanceof Number n?n.longValue():null;}
    private static List<String> references(HitDetailDto hit,String kind) {
        if(hit.evidence()==null)return List.of();
        return hit.evidence().stream().filter(r->kind.equals(r.kind())&&r.id()!=null).map(EvidenceRefDto::id).distinct().toList();
    }
}
