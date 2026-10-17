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
    public AutomationRuntimePolicy(@Value("${app.automation-rules.enabled:true}") boolean enabled,
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
        return new Health("CONNECTED","规则不控制人工按钮。启用的核实规则全部满足后自动核实；启用的反制规则全部满足且当前证据、范围、时效和设备等业务条件通过后自动发起反制，无规则或条件不满足、未知时可由有权限的操作员人工办理。设备处置完成并确认停机后，通知处罚规则全部满足则自动通知处罚部门。独立设备按最近 60 秒有效观测计数，其他事实默认最长有效期 "+maxAge/1000+" 秒。");
    }
}
