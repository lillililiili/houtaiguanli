package com.uav.lowaltitude.modules.automationrule.application;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.Rule;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository.Head;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts.Fact;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeModel.*;

class AutomationRuntimeEvaluatorTest {
    final AutomationRuntimeEvaluator engine=new AutomationRuntimeEvaluator();
    final long now=Instant.parse("2026-09-17T16:05:00Z").toEpochMilli();
    Head group=new Head("verify",1,"ALL","ALL_DAY","00:00","23:59",5,"[]");
    Rule confidence=new Rule("r1","识别置信度","confidence","90",0,true,0,"admin");
    AutomationRuntimeFacts facts(long observed,Map<String,Fact> values){return new AutomationRuntimeFacts("e","t","a","o","d","mock","PENDING_VERIFICATION","UAV",observed,Set.of("air"),true,values);}
    Fact fact(String value,long at){return new Fact(value,at,"observation",null);}
    @Test void phoneWaitUsesElapsedPlaybackTimeAndStillRequiresAllOtherConditions() {
        group=new Head("counter",1,"ALL","ALL_DAY","00:00","23:59",5,"[]");
        var wait=new Rule("phone","电话完成后等待","counterVoiceWait","30",0,true,0,"admin");
        var risk=new Rule("risk","高风险","riskLevel","高风险",0,true,0,"admin");
        var values=new HashMap<String,Fact>();
        values.put("counterVoiceWait",fact("29.999",now)); values.put("riskLevel",fact("HIGH",now));
        var pending=evaluate(List.of(wait,risk),facts(now,values),now,Map.of(),null);
        assertThat(pending.status()).isEqualTo("WAITING");
        assertThat(pending.conditions().get(0).reason()).contains("电话播放完成", "30 秒");
        values.put("counterVoiceWait",fact("30.000",now));
        assertThat(evaluate(List.of(wait,risk),facts(now,values),now,Map.of(),null).status()).isEqualTo("PASS");
        values.put("riskLevel",fact("LOW",now));
        assertThat(evaluate(List.of(wait,risk),facts(now,values),now,Map.of(),null).status()).isEqualTo("NOT_MATCHED");
        assertThat(evaluate(List.of(wait),facts(now-30_001,values),now,Map.of(),null).status()).isEqualTo("REVIEW");
    }
    @Test void explicitlyUnavailablePhoneSkipsOnlyPhoneWaitAndStillRequiresFreshOtherConditions() {
        group=new Head("counter",1,"ALL","ALL_DAY","00:00","23:59",0,"[]");
        var wait=new Rule("phone","电话完成后等待","counterVoiceWait","600",0,true,0,"admin");
        var risk=new Rule("risk","高风险","riskLevel","高风险",0,true,0,"admin");
        var values=new HashMap<String,Fact>();
        values.put("counterVoiceWait",fact("NOT_REQUIRED",now)); values.put("riskLevel",fact("HIGH",now));
        var passed=evaluate(List.of(wait,risk),facts(now,values),now,Map.of(),null);
        assertThat(passed.status()).isEqualTo("PASS");
        assertThat(passed.conditions().get(0).reason()).contains("无法通知", "跳过电话等待");
        values.put("riskLevel",fact("LOW",now));
        assertThat(evaluate(List.of(wait,risk),facts(now,values),now,Map.of(),null).status()).isEqualTo("NOT_MATCHED");
        values.remove("riskLevel");
        assertThat(evaluate(List.of(wait,risk),facts(now,values),now,Map.of(),null).status()).isEqualTo("REVIEW");
        values.put("counterVoiceWait",fact("NOT_REQUIRED",now-30_001));
        assertThat(evaluate(List.of(wait),facts(now,values),now,Map.of(),null).status()).isEqualTo("REVIEW");
        assertThat(evaluate(List.of(risk),facts(now,Map.of("riskLevel",fact("NOT_REQUIRED",now))),now,Map.of(),null).status()).isEqualTo("NOT_MATCHED");
    }
    @Test void zeroPhoneWaitRequiresPlaybackEvidenceAndDisabledWaitDoesNotBlockExistingRules() {
        group=new Head("counter",1,"ALL","ALL_DAY","00:00","23:59",0,"[]");
        var wait=new Rule("phone","电话完成后等待","counterVoiceWait","0",0,true,0,"admin");
        assertThat(evaluate(List.of(wait),facts(now,Map.of()),now,Map.of(),null).status()).isEqualTo("REVIEW");
        assertThat(evaluate(List.of(wait),facts(now,Map.of("counterVoiceWait",fact("0",now))),now,Map.of(),null).status()).isEqualTo("PASS");
        var off=new Rule("phone","电话完成后等待","counterVoiceWait","60",0,false,0,"admin");
        var risk=new Rule("risk","高风险","riskLevel","高风险",0,true,0,"admin");
        assertThat(evaluate(List.of(off,risk),facts(now,Map.of("riskLevel",fact("HIGH",now))),now,Map.of(),null).status()).isEqualTo("PASS");
    }
    Decision evaluate(List<Rule> rules,AutomationRuntimeFacts facts,long time,Map<String,Hold> holds,Long unknown){return engine.evaluate(group,rules,Set.of("air"),facts,time,30000,holds,unknown);}
    @Test void emptyAndAllDisabledPause(){
        assertThat(evaluate(List.of(),null,now,Map.of(),null).status()).isEqualTo("PAUSED");
        var disabled=new Rule("r","off","confidence","90",0,false,0,"admin");
        assertThat(evaluate(List.of(disabled),null,now,Map.of(),null).status()).isEqualTo("PAUSED");
    }
    @Test void enabledConditionsUseAndAndBoundaryIsInclusive(){
        var count=new Rule("r2","源数量","sourceCount","2",0,true,0,"admin");
        assertThat(evaluate(List.of(confidence,count),facts(now,Map.of("confidence",fact("90",now),"sourceCount",fact("1",now))),now,Map.of(),null).status()).isEqualTo("NOT_MATCHED");
        assertThat(evaluate(List.of(confidence,count),facts(now,Map.of("confidence",fact("90",now),"sourceCount",fact("2",now))),now,Map.of(),null).status()).isEqualTo("PASS");
    }
    @Test void missingWaitsThenRequiresReviewNeverPasses(){
        var first=evaluate(List.of(confidence),facts(now,Map.of()),now,Map.of(),null);
        assertThat(first.status()).isEqualTo("WAITING");
        assertThat(evaluate(List.of(confidence),facts(now+5000,Map.of()),now+5000,Map.of(),first.unknownSince()).status()).isEqualTo("REVIEW");
    }
    @Test void deviceCountUsesItsExplicitSixtySecondWindowWithoutRelaxingOtherFacts(){
        var count=new Rule("devices","独立设备","sourceCount","2",0,true,0,"admin");
        for(long age:List.of(50_000L,60_000L)) {
            var current=facts(now-1000,Map.of("sourceCount",fact("2",now-age)));
            assertThat(evaluate(List.of(count),current,now,Map.of(),null).status()).isEqualTo("PASS");
        }
        for(long age:List.of(60_001L,-1L)) {
            var current=facts(now,Map.of("sourceCount",fact("2",now-age)));
            assertThat(evaluate(List.of(count),current,now,Map.of(),null).status()).isEqualTo("WAITING");
        }
        var staleConfidence=facts(now,Map.of("sourceCount",fact("2",now-50_000),"confidence",fact("99",now-50_000)));
        assertThat(evaluate(List.of(count,confidence),staleConfidence,now,Map.of(),null).status()).isEqualTo("WAITING");
        assertThat(evaluate(List.of(count),facts(now-30_001,Map.of("sourceCount",fact("2",now-30_001))),now,Map.of(),null).status()).isEqualTo("REVIEW");
    }
    @Test void deviceCountHoldUsesRealEvidenceTimesAndResetsBeyondSixtySeconds(){
        var count=new Rule("devices","独立设备持续","sourceCount","2",5,true,0,"admin");
        var previous=Map.of("devices",new Hold(now-60_000,now-50_000));
        assertThat(evaluate(List.of(count),facts(now,Map.of("sourceCount",fact("2",now))),now,previous,null).status()).isEqualTo("PASS");
        previous=Map.of("devices",new Hold(now-90_000,now-60_001));
        assertThat(engine.evaluate(group,List.of(count),Set.of(),facts(now,Map.of("sourceCount",fact("2",now))),now,120_000,previous,null).status()).isEqualTo("WAITING");
    }
    @Test void staleFutureAndUnknownObjectNeverPass(){
        for(long at:List.of(now-30001,now+1))assertThat(evaluate(List.of(confidence),facts(at,Map.of("confidence",fact("99",at))),now,Map.of(),null).status()).isEqualTo("REVIEW");
        var f=new AutomationRuntimeFacts("e","t","a","o","d","mock","PENDING_VERIFICATION","UNKNOWN",now,Set.of(),false,Map.of("confidence",fact("99",now)));
        assertThat(evaluate(List.of(confidence),f,now,Map.of(),null).status()).isEqualTo("REVIEW");
    }
    @Test void pollingSameObservationDoesNotAccumulateHold(){
        var held=new Rule("r1","置信度持续","confidence","90",5,true,0,"admin");
        var first=evaluate(List.of(held),facts(now,Map.of("confidence",fact("99",now))),now,Map.of(),null);
        assertThat(first.status()).isEqualTo("WAITING");
        assertThat(evaluate(List.of(held),facts(now,Map.of("confidence",fact("99",now))),now+10000,first.holds(),null).status()).isEqualTo("WAITING");
        assertThat(evaluate(List.of(held),facts(now+5000,Map.of("confidence",fact("99",now+5000))),now+5000,first.holds(),null).status()).isEqualTo("PASS");
    }
    @Test void staleGapAndFailureResetHold(){
        var held=new Rule("r1","置信度持续","confidence","90",5,true,0,"admin");
        var old=Map.of("r1",new Hold(now-10000,now));
        assertThat(evaluate(List.of(held),facts(now+31000,Map.of("confidence",fact("99",now+31000))),now+31000,old,null).status()).isEqualTo("WAITING");
        assertThat(evaluate(List.of(held),facts(now,Map.of("confidence",fact("80",now))),now,old,null).holds()).isEmpty();
    }
    @Test void beijingCrossMidnightScheduleAndExclusiveEnd(){
        group=new Head("verify",1,"ALL","DAILY","23:00","01:00",5,"[]");
        assertThat(evaluate(List.of(confidence),facts(now,Map.of("confidence",fact("99",now))),now,Map.of(),null).status()).isEqualTo("PASS");
        long end=Instant.parse("2026-09-17T17:00:00Z").toEpochMilli();
        assertThat(evaluate(List.of(confidence),facts(end,Map.of("confidence",fact("99",end))),end,Map.of(),null).status()).isEqualTo("OUT_OF_SCHEDULE");
    }
    @Test void unknownAirspaceIsNotAnOutOfScopeConclusion(){
        group=new Head("verify",1,"AIRSPACES","ALL_DAY","00:00","23:59",0,"[]");
        var unknown=new AutomationRuntimeFacts("e","t","a","o","d","mock","PENDING_VERIFICATION","UAV",now,Set.of(),false,Map.of("confidence",fact("99",now)));
        assertThat(evaluate(List.of(confidence),unknown,now,Map.of(),null).status()).isEqualTo("REVIEW");
        assertThat(engine.evaluate(group,List.of(confidence),Set.of("elsewhere"),facts(now,Map.of("confidence",fact("99",now))),now,30000,Map.of(),null).status()).isEqualTo("OUT_OF_SCOPE");
    }
}
