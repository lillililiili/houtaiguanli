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
        return new Health("CONNECTED","后台规则判定已接入；判定结果见运行记录。当前仅判定，不自动派发设备或通知动作；反制仍由人工发起。事实最长有效期 "+maxAge/1000+" 秒。");
    }
}
