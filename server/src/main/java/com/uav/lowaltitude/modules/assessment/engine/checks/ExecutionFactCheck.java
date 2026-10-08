package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
import java.util.*;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.flight.domain.FlightExecutionFacts.*;

/** Pure comparison: evidence is collected before rule execution, never read from a page or copied from the plan. */
public class ExecutionFactCheck implements RuleCheck {
    private final String code,dimension,label;
    public ExecutionFactCheck(String code,String dimension,String label){this.code=code;this.dimension=dimension;this.label=label;}
    @Override public String ruleCode(){return code;}
    @Override public int defaultPriority(){return 280+Integer.parseInt(code.substring(4));}
    @Override public HitDetail evaluate(EvaluationContext context,RuleParams params){
        Map<String,Object> facts=new LinkedHashMap<>();facts.put("dimension",dimension);
        List<ParamRef> refs=new ArrayList<>();List<EvidenceRef> evidence=new ArrayList<>();
        var comparison=context.execution();
        if(context.planMatch()==null||context.planMatch().plan()==null||context.planMatch().code()!=PlanMatchCode.FULL)
            return result(ResultCode.UNDETERMINED,"EXECUTION_PLAN_ASSOCIATION_UNKNOWN",facts,refs,evidence,"尚未可靠关联同一架无人机的计划");
        if(comparison!=null&&comparison.filing()!=null){facts.put("plan_version",comparison.filing().planVersion());evidence.add(new EvidenceRef("flight_plan",comparison.filing().planId()));}
        if(comparison!=null&&comparison.actual()!=null){
            var actual=comparison.actual();facts.put("source_mode",actual.sourceMode());facts.put("fact_version",actual.input().version());
            facts.put("received_at",actual.receivedAt());evidence.add(new EvidenceRef("flight_execution_fact",actual.factId()));
        }
        long evaluationAt=context.asOf().toInstant().toEpochMilli();
        if("landing_point".equals(dimension)&&comparison!=null&&comparison.unknownReason()==null&&comparison.actual()!=null){
            var input=comparison.actual().input();
            if("AIRBORNE".equals(input.phase())&&input.validFrom()<=evaluationAt&&evaluationAt<input.validTo())
                return result(ResultCode.NOT_APPLICABLE,null,facts,refs,evidence,"当前仍在飞行，尚未进入降落核对阶段");
        }
        String[] keys=dimension.endsWith("point")?new String[]{"required","mismatch_status","tolerance_m","max_accuracy_m"}:new String[]{"required","mismatch_status"};
        for(String key:keys){
            if(!params.has(code,key))return result(ResultCode.UNDETERMINED,"EXECUTION_RULE_PARAMETERS_MISSING",facts,refs,evidence,"比对规则参数尚未配置");
            refs.add(new ParamRef(key,params.string(code,key),params.paramStatus(code,key)));
        }
        if("live".equals(context.sourceMode())&&refs.stream().anyMatch(r->!"CONFIRMED".equals(r.status())))
            return result(ResultCode.UNDETERMINED,"EXECUTION_RULE_PARAMETERS_UNCONFIRMED",facts,refs,evidence,"比对规则参数尚未正式确认");
        boolean required;String mismatch;
        try{required=params.bool(code,"required");mismatch=params.string(code,"mismatch_status");}
        catch(RuntimeException invalid){return result(ResultCode.UNDETERMINED,"EXECUTION_RULE_PARAMETERS_INVALID",facts,refs,evidence,"比对规则参数无效");}
        if(!Set.of("ILLEGAL","UNDETERMINED").contains(mismatch))return result(ResultCode.UNDETERMINED,"EXECUTION_RULE_PARAMETERS_INVALID",facts,refs,evidence,"不符时的判定策略无效");
        facts.put("required",required);
        if(comparison==null||comparison.filing()==null||comparison.actual()==null||comparison.unknownReason()!=null)
            return unavailable(required,comparison==null?"EXECUTION_FACTS_UNAVAILABLE":comparison.unknownReason(),facts,refs,evidence,"缺少唯一、有效的独立执行事实");
        Filing filing=comparison.filing();Actual actual=comparison.actual();Input input=actual.input();
        long at=context.asOf().toInstant().toEpochMilli();
        if(input.validFrom()>at||input.validTo()<=at)return unavailable(required,"EXECUTION_FACTS_EXPIRED",facts,refs,evidence,"执行事实不覆盖本次研判时刻");
        if("landing_point".equals(dimension) && "AIRBORNE".equals(input.phase()))
            return result(ResultCode.NOT_APPLICABLE,null,facts,refs,evidence,"当前仍在飞行，尚未进入降落核对阶段");
        Boolean matches;
        if(dimension.endsWith("point")){
            PositionEvent event="takeoff_point".equals(dimension)?input.takeoff():input.landing();
            BigDecimal lon="takeoff_point".equals(dimension)?filing.takeoffLongitude():filing.landingLongitude();
            BigDecimal lat="takeoff_point".equals(dimension)?filing.takeoffLatitude():filing.landingLatitude();
            if(event==null||event.occurredAt()>at||lon==null||lat==null||event.longitude()==null||event.latitude()==null)return unavailable(required,"EXECUTION_POSITION_UNAVAILABLE",facts,refs,evidence,"缺少有效起降事件或地点坐标");
            BigDecimal tolerance,accuracy;
            try{tolerance=params.number(code,"tolerance_m");accuracy=params.number(code,"max_accuracy_m");}
            catch(RuntimeException invalid){return result(ResultCode.UNDETERMINED,"EXECUTION_RULE_PARAMETERS_INVALID",facts,refs,evidence,"距离或精度参数无效");}
            if(tolerance.signum()<0||accuracy.signum()<0)return result(ResultCode.UNDETERMINED,"EXECUTION_RULE_PARAMETERS_INVALID",facts,refs,evidence,"距离或精度参数无效");
            if(event.accuracyM()==null||event.accuracyM().compareTo(accuracy)>0)return unavailable(required,"EXECUTION_POSITION_ACCURACY_UNKNOWN",facts,refs,evidence,"起降位置精度不足");
            double meters=distance(lon,lat,event.longitude(),event.latitude());
            if(!Double.isFinite(meters))return unavailable(required,"EXECUTION_POSITION_DISTANCE_UNKNOWN",facts,refs,evidence,"位置距离无法可靠计算");
            BigDecimal distance=BigDecimal.valueOf(meters);
            facts.put("distance_m",distance);facts.put("accuracy_m",event.accuracyM());facts.put("occurred_at",event.occurredAt());
            if(distance.add(event.accuracyM()).compareTo(tolerance)<=0)matches=true;
            else if(distance.subtract(event.accuracyM()).max(BigDecimal.ZERO).compareTo(tolerance)>0)matches=false;
            else return unavailable(required,"EXECUTION_POSITION_BOUNDARY_UNKNOWN",facts,refs,evidence,"位置误差范围跨越允许距离边界");
        }else if("pilot".equals(dimension)){
            if(!filing.pilotVerified()||!actual.pilotVerified()||filing.pilotId()==null||input.pilotContactId()==null)
                return unavailable(required,"EXECUTION_PILOT_IDENTITY_UNKNOWN",facts,refs,evidence,"缺少有效核验的飞手身份依据");
            matches=filing.pilotId().equals(input.pilotContactId());
        }else{
            if(!filing.reportingVerified()||!actual.reportingVerified()||filing.reportingOrgId()==null||actual.reportingOrgId()==null)
                return unavailable(required,"EXECUTION_REPORTING_UNIT_UNKNOWN",facts,refs,evidence,"报送单位的来源绑定缺失或无效");
            matches=filing.reportingOrgId().equals(actual.reportingOrgId());
        }
        facts.put("comparison",matches?"MATCH":"MISMATCH");
        if(matches)return result(ResultCode.PASS,null,facts,refs,evidence,"与计划一致");
        String reason=switch(dimension){case "takeoff_point"->"TAKEOFF_POINT_MISMATCH";case "landing_point"->"LANDING_POINT_MISMATCH";case "pilot"->"PILOT_IDENTITY_MISMATCH";default->"REPORTING_UNIT_MISMATCH";};
        return result("ILLEGAL".equals(mismatch)?ResultCode.FAIL:ResultCode.UNDETERMINED,reason,facts,refs,evidence,"与计划不符"+("ILLEGAL".equals(mismatch)?"":"，按配置保留不可判定"));
    }
    private HitDetail unavailable(boolean required,String reason,Map<String,Object> facts,List<ParamRef> refs,List<EvidenceRef> evidence,String text){
        facts.put("comparison","UNDETERMINED");
        return result(required?ResultCode.UNDETERMINED:ResultCode.NOT_APPLICABLE,reason==null?"EXECUTION_FACTS_UNAVAILABLE":reason,facts,refs,evidence,text);
    }
    private HitDetail result(ResultCode result,String reason,Map<String,Object> facts,List<ParamRef> refs,List<EvidenceRef> evidence,String text){
        if(!facts.containsKey("comparison"))facts.put("comparison",result==ResultCode.NOT_APPLICABLE?"NOT_APPLICABLE":"UNDETERMINED");
        return new HitDetail(code,null,result,reason,null,Collections.unmodifiableMap(facts),List.copyOf(refs),List.copyOf(evidence),label+"："+text);
    }
    static double distance(BigDecimal lon1,BigDecimal lat1,BigDecimal lon2,BigDecimal lat2){
        // Vincenty's inverse on the WGS-84 ellipsoid. Nonconvergence is unknown, never a spherical fallback.
        final double major=6378137.0,flattening=1.0/298.257223563,minor=major*(1-flattening);
        double u1=Math.atan((1-flattening)*Math.tan(Math.toRadians(lat1.doubleValue())));
        double u2=Math.atan((1-flattening)*Math.tan(Math.toRadians(lat2.doubleValue())));
        double sin1=Math.sin(u1),cos1=Math.cos(u1),sin2=Math.sin(u2),cos2=Math.cos(u2);
        double longitude=Math.IEEEremainder(Math.toRadians(lon2.doubleValue()-lon1.doubleValue()),2*Math.PI),lambda=longitude;
        double sinSigma=0,cosSigma=0,sigma=0,cosSquaredAlpha=0,cosTwoSigma=0;
        boolean converged=false;
        for(int iteration=0;iteration<200;iteration++){
            double sinLambda=Math.sin(lambda),cosLambda=Math.cos(lambda);
            sinSigma=Math.hypot(cos2*sinLambda,cos1*sin2-sin1*cos2*cosLambda);
            cosSigma=sin1*sin2+cos1*cos2*cosLambda;
            if(sinSigma<1e-15)return cosSigma>0?0:Double.NaN;
            sigma=Math.atan2(sinSigma,cosSigma);
            double sinAlpha=cos1*cos2*sinLambda/sinSigma;
            cosSquaredAlpha=Math.max(0,1-sinAlpha*sinAlpha);
            cosTwoSigma=cosSquaredAlpha<1e-15?0:cosSigma-2*sin1*sin2/cosSquaredAlpha;
            double c=flattening/16*cosSquaredAlpha*(4+flattening*(4-3*cosSquaredAlpha));
            double next=longitude+(1-c)*flattening*sinAlpha*(sigma+c*sinSigma*(cosTwoSigma+c*cosSigma*(-1+2*cosTwoSigma*cosTwoSigma)));
            if(Math.abs(next-lambda)<1e-12){converged=true;break;}
            lambda=next;
        }
        if(!converged)return Double.NaN;
        double squared=cosSquaredAlpha*(major*major-minor*minor)/(minor*minor);
        double a=1+squared/16384*(4096+squared*(-768+squared*(320-175*squared)));
        double b=squared/1024*(256+squared*(-128+squared*(74-47*squared)));
        double delta=b*sinSigma*(cosTwoSigma+b/4*(cosSigma*(-1+2*cosTwoSigma*cosTwoSigma)
            -b/6*cosTwoSigma*(-3+4*sinSigma*sinSigma)*(-3+4*cosTwoSigma*cosTwoSigma)));
        return minor*a*(sigma-delta);
    }
}
