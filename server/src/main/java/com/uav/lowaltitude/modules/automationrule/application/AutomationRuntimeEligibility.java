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
    /** No enabled rule leaves the existing alarm flow unchanged. A passing rule is the only automatic go-ahead. */
    public boolean passed(String category,String eventId){return enabled(category)&&block(category,eventId)==null;}
    public String punishNotifyBlock(String eventId){return block("dispose",eventId);}
    public String counterBlock(String eventId){return block("counter",eventId);}
    public String block(String category,String eventId){
        if(!enabled(category))return null;
        String noun=switch(category){case "verify"->"核实";case "counter"->"反制";default->"通知处罚";};
        if(eventId==null||eventId.isBlank())return noun+"规则缺少来源事件，不能放行";
        try{
            var rules=config.rules(category);
            var head=config.head(category,false);
            var scope=new HashSet<>(config.scopes(category).stream().map(s->s.id()).toList());
            long now=clock.nowMillis();
            var state=runs.state(category,eventId);
            var decision=evaluator.evaluate(head,rules,scope,facts.read(eventId,now),now,policy.maxAge(),state==null?Map.of():holds(state.holds()),state==null?null:state.unknownSince());
            if("PASS".equals(decision.status()))return null;
            String action=switch(category){case "verify"->"，告警继续等待人工核实";case "counter"->"，暂不能发起反制";default->"，暂不能通知处罚部门";};
            return switch(decision.status()){
                case "OUT_OF_SCHEDULE"->"当前不在"+noun+"规则的生效时段内"+action;
                case "OUT_OF_SCOPE"->"当前位置不在"+noun+"规则指定的空域内"+action;
                case "NOT_MATCHED"->noun+"规则尚未全部满足"+action;
                case "WAITING"->noun+"规则仍在等待条件持续满足"+action;
                case "REVIEW"->noun+"依据不足"+action;
                default->noun+"规则未通过"+action;
            };
        }catch(RuntimeException error){return noun+"规则暂时无法核对，请稍后重试";}
    }
    private boolean enabled(String category){return config.rules(category).stream().anyMatch(rule->rule.enabled());}
    public Map<String,Hold> holds(String raw){try{return json.readValue(raw,new TypeReference<Map<String,Hold>>(){});}catch(Exception invalid){return Map.of();}}
}
