package com.uav.lowaltitude.modules.automationrule.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeModel.Health;

@Component
public class AutomationRuntimePolicy {
    private final boolean enabled;
    private final long maxAge,poll;
    private final AutomationRuntimeRepository runs;
    private final AppClock clock;
    public AutomationRuntimePolicy(@Value("${app.automation-rules.enabled:false}") boolean enabled,
            @Value("${app.automation-rules.fact-max-age-ms:30000}") long maxAge,
            @Value("${app.automation-rules.poll-ms:2000}") long poll,AutomationRuntimeRepository runs,AppClock clock){
        if(maxAge<1000||maxAge>300000||poll<500||poll>60000)throw new IllegalArgumentException("自动规则调度和事实时效配置无效");
        this.enabled=enabled;this.maxAge=maxAge;this.poll=poll;this.runs=runs;this.clock=clock;
    }
    public boolean enabled(){return enabled;}
    public long maxAge(){return maxAge;}
    public Health health(){
        if(!enabled)return new Health("DISABLED","自动规则执行引擎未启用，当前配置不会触发自动动作。");
        long heartbeat=runs.heartbeat();
        if(heartbeat==0)return new Health("STARTING","自动规则执行引擎正在启动，尚未完成首次检查。");
        if(clock.nowMillis()-heartbeat>Math.max(15000,poll*5)||runs.lastError()!=null)return new Health("UNAVAILABLE","自动规则引擎检查异常，自动动作暂停，请检查运行服务。");
        return new Health("CONNECTED","规则只控制前台告警到处罚。核实通过后，待核实告警由系统核实属实并进入飞手通知。反制仍由人工发起，且须反制规则通过。通知处罚部门须通知处罚规则通过后才能点击。事实最长有效期 "+maxAge/1000+" 秒。");
    }
}
