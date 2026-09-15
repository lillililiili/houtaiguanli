package com.uav.lowaltitude.modules.fusion.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 融合引擎调度配置（application.yml 的 app.fusion.*）。
 * enabled 默认 false：摄取 Worker 不注册，回放/种子只在 local/test 同步驱动管线。
 * live-promotion.enabled 默认 false：实测雷达 ops → 阶段 2 提升本阶段不实现（决策 8-1），只留开关与端口。
 */
@ConfigurationProperties(prefix = "app.fusion")
public class FusionProperties {
    private boolean enabled;
    private long pollMillis = 500;
    private int batchSize = 50;
    private long leaseMillis = 30_000;
    /** 同一 inbox 行最多被领取的次数，超过即置 FAILED（毒帧不得无限重领）。 */
    private int maxAttempts = 5;
    private final LivePromotion livePromotion = new LivePromotion();
    private final Replay replay = new Replay();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public long getPollMillis() { return pollMillis; }
    public void setPollMillis(long value) { pollMillis = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public long getLeaseMillis() { return leaseMillis; }
    public void setLeaseMillis(long value) { leaseMillis = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public LivePromotion getLivePromotion() { return livePromotion; }
    public Replay getReplay() { return replay; }

    public static class LivePromotion {
        private boolean enabled;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
    }

    public static class Replay {
        private boolean runOnStart;
        /**
         * 阶段 8/8.5/16 的回放夹具是否随 local/test 启动灌入（默认灌）。
         * 关掉它才能在一个库里只看直连 MQTT 摄取的结果：夹具与 MQTT 适配器给同一批报文建的是两个
         * integration_source，同时存在会让同一批物理目标以两套来源各进一遍融合。
         * 单独一个开关而不是复用 app.dev-seed.enabled——后者还管着 admin1 与设备登记，关了就没法登录了。
         */
        private boolean seedEnabled = true;

        public boolean isRunOnStart() { return runOnStart; }
        public void setRunOnStart(boolean value) { runOnStart = value; }
        public boolean isSeedEnabled() { return seedEnabled; }
        public void setSeedEnabled(boolean value) { seedEnabled = value; }
    }
}
