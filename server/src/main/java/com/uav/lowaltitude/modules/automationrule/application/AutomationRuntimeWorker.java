package com.uav.lowaltitude.modules.automationrule.application;

import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.alarm.application.AlarmRuleCounter;
import com.uav.lowaltitude.modules.alarm.application.AlarmRuleVerification;
import com.uav.lowaltitude.modules.automationrule.infrastructure.*;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.platform.time.AppClock;

/** 判定告警流程规则。核实通过后确认事件，反制通过后尝试自动反制，通知处罚通过后尝试自动通知。 */
@Component
public class AutomationRuntimeWorker {
    private final AutomationRuntimePolicy policy;
    private final AutomationRuntimeRepository runs;
    private final AutomationRuntimeFactsRepository facts;
    private final AutomationRuntimeService service;
    private final AlarmRuleVerification verification;
    private final AlarmRuleCounter counter;
    private final HandoffSubmissionService handoffs;
    private final AppClock clock;
    public AutomationRuntimeWorker(AutomationRuntimePolicy policy,AutomationRuntimeRepository runs,AutomationRuntimeFactsRepository facts,
            AutomationRuntimeService service,AlarmRuleVerification verification,AlarmRuleCounter counter,
            HandoffSubmissionService handoffs,AppClock clock){
        this.policy=policy;this.runs=runs;this.facts=facts;this.service=service;this.verification=verification;
        this.counter=counter;this.handoffs=handoffs;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${app.automation-rules.poll-ms:2000}")
    public void poll(){
        if(!policy.enabled())return;
        String cursor=runs.cursor(),error=null;
        try{
            // Drain several pages in one tick, keeping the durable cursor fair across restarts.
            for(int page=0;page<20;page++){
                var candidates=facts.candidates(clock.nowMillis(),100,cursor);
                if(candidates.isEmpty()){cursor="";break;}
                for(String event:candidates){
                    for(String category:List.of("verify","counter","dispose")){
                        try {
                            service.evaluate(category,event);
                            var state=runs.state(category,event);
                            if(state==null||!"PASS".equals(state.status()))continue;
                            if("verify".equals(category))verification.confirmIfPassed(event,state.runId());
                            else if("counter".equals(category))counter.launchIfPassed(event,state.runId());
                            else if("dispose".equals(category))handoffs.automaticAfterJamming(event);
                        }
                        catch(RuntimeException failed){error="部分事件判定异常，需检查后台日志";org.slf4j.LoggerFactory.getLogger(getClass()).warn("Rule evaluation failed for {} / {}: {}",category,event,failed.getClass().getSimpleName());}
                    }
                    cursor=event;
                }
                if(candidates.size()<100){cursor="";break;}
            }
        }catch(RuntimeException failed){error="自动规则判定本轮运行异常，等待恢复";org.slf4j.LoggerFactory.getLogger(getClass()).warn("Rule evaluation cycle failed: {}",failed.getClass().getSimpleName());}
        runs.heartbeat(clock.nowMillis(),cursor,error);
    }
}
