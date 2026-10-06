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
    /** 调度间隔（ZT-06：由 5 秒改为 1 秒，首次告警不再平均多等 2.5 秒；研判量由下面两项节流）。 */
    private long pollMillis = 1000;
    private int batchSize = 50;
    /** 新出现的目标在这段时间内每个 tick 都评：C03 要攒够轨迹点，第一次研判常是"不可判定"，下一次不能再等 5 秒。 */
    private int fastWindowSeconds = 30;
    /** 其余目标同一版本两次研判之间至少间隔这么久——与原来 5 秒一评的量相当，研判表不因调度变快而膨胀。 */
    private long reevaluateMillis = 5000;
    /** 一批满了说明还有积压，同一次调度里接着评下一批的时间上限；0 表示每次调度只评一批（旧行为）。 */
    private long drainBudgetMillis = 3000;
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
    public int getFastWindowSeconds() { return fastWindowSeconds; }
    public void setFastWindowSeconds(int value) {
        if (value < 0) throw new IllegalArgumentException("fast-window-seconds must not be negative");
        fastWindowSeconds = value;
    }
    public long getReevaluateMillis() { return reevaluateMillis; }
    public void setReevaluateMillis(long value) {
        if (value < 0) throw new IllegalArgumentException("reevaluate-millis must not be negative");
        reevaluateMillis = value;
    }
    public long getDrainBudgetMillis() { return drainBudgetMillis; }
    public void setDrainBudgetMillis(long value) {
        if (value < 0 || value > 60_000) throw new IllegalArgumentException("drain-budget-millis must be between 0 and 60000");
        drainBudgetMillis = value;
    }
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
