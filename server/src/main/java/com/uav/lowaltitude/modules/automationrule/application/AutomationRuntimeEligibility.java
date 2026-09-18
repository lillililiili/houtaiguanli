package com.uav.lowaltitude.modules.automationrule.application;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.automationrule.infrastructure.*;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeModel.*;

/** Read-only dispatch guard. Never takes a group lock while device code holds an event/authorization lock. */
@Service
public class AutomationRuntimeEligibility {
    private final AutomationRuleRepository config;
    private final AutomationRuntimeRepository runs;
    private final AutomationRuntimeFactsRepository facts;
    private final AutomationRuntimePolicy policy;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AutomationRuntimeEvaluator evaluator=new AutomationRuntimeEvaluator();
    public AutomationRuntimeEligibility(AutomationRuleRepository config,AutomationRuntimeRepository runs,AutomationRuntimeFactsRepository facts,AutomationRuntimePolicy policy,ObjectMapper json,AppClock clock){this.config=config;this.runs=runs;this.facts=facts;this.policy=policy;this.json=json;this.clock=clock;}
    public Decision check(String category,String eventId){
        if(!policy.enabled())return new Decision("PAUSED","自动规则引擎未启用",List.of(),Map.of(),null);
        var head=config.head(category,false);var state=runs.state(category,eventId);
        if(state==null||state.version()!=head.version())return new Decision("WAITING","等待按当前规则版本重新判定",List.of(),Map.of(),null);
        var value=evaluator.evaluate(head,config.rules(category),new HashSet<>(config.scopes(category).stream().map(s->s.id()).toList()),facts.read(eventId,clock.nowMillis()),clock.nowMillis(),policy.maxAge(),holds(state.holds()),state.unknownSince());
        var after=config.head(category,false);
        if(after.version()!=head.version())return new Decision("WAITING","判定期间规则版本已变化",List.of(),Map.of(),null);
        return value;
    }
    public boolean allows(String category,String eventId,String code){
        if(!"PASS".equals(check(category,eventId).status()))return false;
        if(!"dispose".equals(category))return true;
        try{return Arrays.asList(json.readValue(config.head(category,false).actions(),String[].class)).contains(code);}catch(Exception bad){return false;}
    }
    /** Existing jobs retain their previous deployment behavior until this engine is explicitly enabled. */
    public boolean legacyActionAllowed(String eventId,String code){return !policy.enabled()||allows("dispose",eventId,code);}
    public Map<String,Hold> holds(String raw){try{return json.readValue(raw,new TypeReference<Map<String,Hold>>(){});}catch(Exception invalid){return Map.of();}}
}
