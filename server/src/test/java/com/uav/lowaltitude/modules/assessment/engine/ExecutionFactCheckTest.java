package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.assessment.engine.checks.ExecutionFactCheck;
import com.uav.lowaltitude.modules.flight.domain.FlightExecutionFacts.*;

class ExecutionFactCheckTest {
    final OffsetDateTime at=OffsetDateTime.now();final long now=at.toInstant().toEpochMilli();
    TestRuleParams params(){var p=TestRuleParams.demoCatalog();for(String c:RuleCodes.EXECUTION_CHECKS)p.put(c,"required","true").put(c,"mismatch_status","ILLEGAL").put(c,"tolerance_m","100").put(c,"max_accuracy_m","20");return p;}
    EvaluationContext context(String mode,String pilot,String org,String phase,BigDecimal lon,BigDecimal accuracy,boolean verified){
        String id=UUID.randomUUID().toString();var plan=new PlanFact(id,"route", "SN-"+id,at.minusHours(1),at.plusHours(1),BigDecimal.TEN,null,null,null,"org","district");
        var filing=new Filing(id,3,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"pilot-A","org-A",true,true);
        var event=new PositionEvent(now-1000,lon,BigDecimal.ZERO,accuracy);
        var input=new Input("source",mode,"msg",1L,"target","track","execution",now-10000,now+10000,phase,event,"LANDED".equals(phase)?event:null,pilot,"binding",List.of("source/evidence"));
        var actual=new Actual("fact",mode,input,org,verified,true,now);
        var state=new TargetState("target","track",plan.uavSn(),BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,null,new BigDecimal("0.95"),at,at);
        return new EvaluationContext(new Subject(SubjectKind.TARGET,"target",null,null,mode),state,new TrackQuality(10,1L,false),new PlanMatch(PlanMatchCode.FULL,plan,Map.of(),List.of()),List.of(),at,Freshness.FRESH,RunMode.SHADOW,mode,new Comparison(filing,actual,null));
    }
    @ParameterizedTest @ValueSource(strings={"mock","replay"})
    void fourDimensionsUseIndependentFacts(String mode){
        var c=context(mode,"pilot-A","org-A","LANDED",BigDecimal.ZERO,BigDecimal.ONE,true);var p=params();
        String[] dimensions={"takeoff_point","landing_point","pilot","reporting_unit"};
        for(int n=0;n<4;n++)assertThat(new ExecutionFactCheck(RuleCodes.EXECUTION_CHECKS.get(n),dimensions[n],"核对").evaluate(c,p).resultCode()).isEqualTo(ResultCode.PASS);
        assertThat(new ExecutionFactCheck("C02-11","pilot","飞手").evaluate(context(mode,"pilot-B","org-A","AIRBORNE",BigDecimal.ZERO,BigDecimal.ONE,true),p).reasonCode()).isEqualTo("PILOT_IDENTITY_MISMATCH");
        assertThat(new ExecutionFactCheck("C02-12","reporting_unit","单位").evaluate(context(mode,"pilot-A","org-B","AIRBORNE",BigDecimal.ZERO,BigDecimal.ONE,true),p).resultCode()).isEqualTo(ResultCode.FAIL);
    }
    @Test void locationUncertaintyAndLandingPhaseAreIndependent(){
        var check=new ExecutionFactCheck("C02-9","takeoff_point","起飞");var p=params();
        assertThat(check.evaluate(context("mock","pilot-A","org-A","AIRBORNE",new BigDecimal("0.0009"),BigDecimal.TEN,true),p).reasonCode()).isEqualTo("EXECUTION_POSITION_BOUNDARY_UNKNOWN");
        assertThat(check.evaluate(context("mock","pilot-A","org-A","AIRBORNE",new BigDecimal("0.01"),BigDecimal.ONE,true),p).resultCode()).isEqualTo(ResultCode.FAIL);
        assertThat(check.evaluate(context("mock","pilot-A","org-A","AIRBORNE",null,BigDecimal.ONE,true),p).reasonCode()).isEqualTo("EXECUTION_POSITION_UNAVAILABLE");
        assertThat(check.evaluate(context("mock","pilot-A","org-A","AIRBORNE",BigDecimal.ZERO,null,true),p).reasonCode()).isEqualTo("EXECUTION_POSITION_ACCURACY_UNKNOWN");
        assertThat(new ExecutionFactCheck("C02-10","landing_point","降落").evaluate(context("mock","pilot-A","org-A","AIRBORNE",BigDecimal.ZERO,BigDecimal.ONE,true),p).resultCode()).isEqualTo(ResultCode.NOT_APPLICABLE);
    }
    @Test void missingUnconfirmedAndUnverifiedEvidenceNeverFailsAsViolation(){
        var check=new ExecutionFactCheck("C02-11","pilot","飞手");var p=params();
        assertThat(check.evaluate(context("live","pilot-B","org-A","AIRBORNE",BigDecimal.ZERO,BigDecimal.ONE,true),p).reasonCode()).isEqualTo("EXECUTION_RULE_PARAMETERS_UNCONFIRMED");
        assertThat(check.evaluate(context("mock","pilot-B","org-A","AIRBORNE",BigDecimal.ZERO,BigDecimal.ONE,false),p).resultCode()).isEqualTo(ResultCode.UNDETERMINED);
        assertThat(check.evaluate(context("mock","pilot-A","org-A","AIRBORNE",BigDecimal.ZERO,BigDecimal.ONE,true),TestRuleParams.empty()).reasonCode()).isEqualTo("EXECUTION_RULE_PARAMETERS_MISSING");
    }
    @Test void confirmedMismatchReachesFinalDecisionAndAssurance(){
        var p=org.mockito.Mockito.spy(params());
        org.mockito.Mockito.doReturn("CONFIRMED").when(p).paramStatus(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        var c=context("live","another-stable-pilot","org-A","LANDED",BigDecimal.ZERO,BigDecimal.ONE,true);
        var hits=allHits(c,p);
        var decision=new C03Decision().decide(c,hits,p);
        assertThat(decision.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(decision.violationReasons()).contains("PILOT_IDENTITY_MISMATCH").doesNotContain("NO_AUTHORIZATION");
        assertThat(new DecisionAssuranceAlgorithm().assess(c,hits,decision,p).status()).isEqualTo("SUFFICIENT");
    }
    @Test void newUnknownDoesNotEraseIndependentAirspaceViolation(){
        var p=params().without("C02-11","required");var c=context("mock","pilot-A","org-A","AIRBORNE",BigDecimal.ZERO,BigDecimal.ONE,true);
        var hits=new ArrayList<>(allHits(c,p));hits.removeIf(h->h.ruleCode().equals("C02-1"));
        hits.add(new HitDetail("C02-1",null,ResultCode.FAIL,"INSIDE_RESTRICTED_AIRSPACE",BigDecimal.ONE,Map.of(),List.of(),List.of(new EvidenceRef("airspace_version","independent-airspace")),"独立禁飞依据"));
        var decision=new C03Decision().decide(c,hits,p);
        assertThat(decision.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(new DecisionAssuranceAlgorithm().assess(c,hits,decision,p).status()).isEqualTo("SUFFICIENT");
        assertThat(new ExecutionFactCheck("C02-10","landing_point","降落").evaluate(c,TestRuleParams.empty()).resultCode()).isEqualTo(ResultCode.NOT_APPLICABLE);
    }
    private List<HitDetail> allHits(EvaluationContext c,RuleParams p){
        List<HitDetail> hits=new ArrayList<>();
        for(String code:List.of("C01","C02-1","C02-2","C02-3","C02-4","C02-5","C02-6","C02-7","C02-8"))hits.add(new HitDetail(code,null,ResultCode.PASS,null,null,Map.of(),List.of(),List.of(),"旧规则通过"));
        String[] dimensions={"takeoff_point","landing_point","pilot","reporting_unit"};
        for(int n=0;n<4;n++)hits.add(new ExecutionFactCheck(RuleCodes.EXECUTION_CHECKS.get(n),dimensions[n],"核对").evaluate(c,p));
        return hits;
    }
}
