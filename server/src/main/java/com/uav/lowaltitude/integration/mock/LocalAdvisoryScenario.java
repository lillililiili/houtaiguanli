package com.uav.lowaltitude.integration.mock;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** 单事件模拟通道故障注入；默认关闭，不改写任务或业务结果。 */
record LocalAdvisoryScenario(String status, long delayMillis) {
    private static final String PREFIX="app.qa.advisory-scenario.";
    static LocalAdvisoryScenario read(Environment env,String mode,String key,boolean voice) {
        if(!env.acceptsProfiles(Profiles.of("((local & qa) | test) & !prod & !production"))
                || !Boolean.parseBoolean(env.getProperty(PREFIX+"enabled","false"))
                || mode==null || !Set.of("mock","replay").contains(mode)) return null;
        String event=env.getProperty(PREFIX+"event-id","").trim();
        if(event.isEmpty() || event.length()>64) throw new IllegalArgumentException("模拟通知场景必须绑定一个测试事件");
        if(!((voice?"auto-advisory-voice:":"auto-advisory:")+event).equals(key)) return null;
        String status=env.getProperty(PREFIX+(voice?"voice-status":"sms-status"),voice?"SIMULATED_PLAYED":"SIMULATED_DELIVERED");
        Set<String> allowed=voice?Set.of("SIMULATED_PLAYED","NO_ANSWER","ANSWERED","FAILED","UNKNOWN")
                :Set.of("SIMULATED_DELIVERED","SENT","FAILED","UNKNOWN");
        if(!allowed.contains(status)) throw new IllegalArgumentException("模拟通知场景状态无效");
        long delay;
        try { delay=Long.parseLong(env.getProperty(PREFIX+"delay-ms","0")); }
        catch(NumberFormatException invalid) { throw new IllegalArgumentException("模拟通知延迟必须为0至60000毫秒"); }
        if(delay<0 || delay>60000) throw new IllegalArgumentException("模拟通知延迟必须为0至60000毫秒");
        return new LocalAdvisoryScenario(status,delay);
    }
    void awaitResponse() {
        try { if(delayMillis>0) Thread.sleep(delayMillis); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException("模拟通知响应被中断",interrupted); }
    }
}
