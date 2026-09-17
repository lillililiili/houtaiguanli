package com.uav.lowaltitude.modules.alarm.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 明示本地演示策略；正式部署未启用业务规则和渠道，不将演示阈值当行业规范。 */
@Component
public class AutoSmsPolicy {
    public static final String CODE="LOCAL_AUTO_SMS_DEMO_V1";
    public static final String ACTOR="自动短信服务";
    private final Environment env;
    private final boolean configured;
    private final long freshMillis,eventMillis;
    public AutoSmsPolicy(Environment env,@Value("${app.advisory.auto-sms.enabled:false}") boolean enabled,
            @Value("${app.advisory.auto-sms.fresh-seconds:120}") long fresh,
            @Value("${app.advisory.auto-sms.event-seconds:300}") long event) {
        if(fresh<1||fresh>3600||event<1||event>86400)throw new IllegalArgumentException("Invalid automatic SMS DEMO freshness window");
        this.env=env;this.configured=enabled;this.freshMillis=fresh*1000;this.eventMillis=event*1000;
    }
    public boolean enabled(){return configured&&env.acceptsProfiles(Profiles.of("!production & (local | test)"));}
    public long freshMillis(){return freshMillis;}
    public long eventMillis(){return eventMillis;}
    public String description(){return "本地演示策略：目标和研判有效期"+(freshMillis/1000)+"秒，事件及人工确认有效期"+(eventMillis/1000)+"秒";}
    public boolean fresh(Long at,long now,long window){return at!=null&&at<=now&&now-at<=window;}
}
