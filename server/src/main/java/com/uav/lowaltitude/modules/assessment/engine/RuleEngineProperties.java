package com.uav.lowaltitude.modules.assessment.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规则引擎调度配置（application.yml 的 app.rule-engine.*）。
 * enabled 默认 false：生产默认没有 ACTIVE 版本，引擎空转；即使有版本，也必须显式打开调度。
 * allow-demo-active 默认 false：DEMO 参数只能做影子验证，不能成为生效规则。
 */
@ConfigurationProperties(prefix = "app.rule-engine")
public class RuleEngineProperties {
    private boolean enabled;
    private long pollMillis = 5000;
    private int batchSize = 50;
    private int leaseSeconds = 30;
    private String instanceId = "";
    private boolean allowDemoActive;
    private final Replay replay = new Replay();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public long getPollMillis() { return pollMillis; }
    public void setPollMillis(long value) { pollMillis = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = value; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String value) { instanceId = value == null ? "" : value; }
    public boolean isAllowDemoActive() { return allowDemoActive; }
    public void setAllowDemoActive(boolean value) { allowDemoActive = value; }
    public Replay getReplay() { return replay; }

    /** 回放回归（RuleReplayRunner，执行者 2）；这里只承载键，默认关闭。 */
    public static class Replay {
        private boolean runOnStart;

        public boolean isRunOnStart() { return runOnStart; }
        public void setRunOnStart(boolean value) { runOnStart = value; }
    }
}
