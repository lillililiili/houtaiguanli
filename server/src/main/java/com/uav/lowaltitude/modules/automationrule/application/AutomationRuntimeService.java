package com.uav.lowaltitude.modules.automationrule.application;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.uav.lowaltitude.modules.automationrule.infrastructure.*;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.RunRow;
import com.uav.lowaltitude.platform.time.AppClock;

/** Persists decisions only. This service never dispatches device or notification actions. */
@Service
public class AutomationRuntimeService {
    private final AutomationRuleRepository config;
    private final AutomationRuntimeRepository runs;
    private final AutomationRuntimeFactsRepository facts;
    private final AutomationRuntimePolicy policy;
    private final AutomationRuntimeEligibility eligibility;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AutomationRuntimeEvaluator evaluator=new AutomationRuntimeEvaluator();
    public AutomationRuntimeService(AutomationRuleRepository config,AutomationRuntimeRepository runs,AutomationRuntimeFactsRepository facts,
            AutomationRuntimePolicy policy,AutomationRuntimeEligibility eligibility,ObjectMapper json,AppClock clock){
        this.config=config;this.runs=runs;this.facts=facts;this.policy=policy;this.eligibility=eligibility;this.json=json;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void evaluate(String category,String eventId){
        if(!policy.enabled())return;
        var head=config.head(category,true);long now=clock.nowMillis();
        var old=runs.state(category,eventId);boolean same=old!=null&&old.version()==head.version();
        var current=facts.read(eventId,now);
        var decision=evaluator.evaluate(head,config.rules(category),new HashSet<>(config.scopes(category).stream().map(s->s.id()).toList()),
            current,now,policy.maxAge(),same?eligibility.holds(old.holds()):Map.of(),same?old.unknownSince():null);
        String signature=encode(Arrays.asList(head.version(),decision.status(),decision.reason(),stableFacts(current),decision.conditions()));
        String run=same&&signature.equals(old.signature())?old.runId():UUID.randomUUID().toString();
        if(!same||!signature.equals(old.signature()))runs.insertRun(new RunRow(run,category,eventId,current==null?null:current.targetId(),head.version(),
            decision.status(),decision.reason(),now,current==null?null:current.observedAt(),current==null?null:current.sourceMode(),encode(decision.conditions())),encode(current));
        runs.state(category,eventId,head.version(),run,decision.status(),decision.unknownSince(),encode(decision.holds()),signature);
    }
    private Object stableFacts(AutomationRuntimeFacts current){
        if(current==null)return null;
        var tree=json.valueToTree(current);
        // Age changes with every scheduler tick; observation identity/time remain in the signature.
        for(String age:List.of("freshness","counterFreshness","disposeFreshness")){
            var node=tree.path("facts").path(age);
            if(node.isObject())((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("value");
        }
        return tree;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException(invalid);}}
}
