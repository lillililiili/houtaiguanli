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
    /** 超出接收时刻的最大时钟偏差；超过的原始信封保留为失败记录，不进入目标与轨迹。 */
    private long maxFutureSkewMillis = 30_000;
    /** 本地回放可优先仍在上报的来源；不改变同一来源内部的摄取顺序。 */
    private boolean prioritizeFreshSources;
    /**
     * 一次调度最多连续处理多久（ZT-06）。有积压时不再"处理一批就睡 poll-millis"，而是接着领下一批，
     * 直到领空或用完这段预算再把调度线程让出来；0 表示每次调度只处理一批（旧行为）。
     */
    private long drainBudgetMillis = 10_000;
    /**
     * 报文时刻比平台接收时刻晚多少毫秒就算"设备时间不可信/数据迟到"（ZT-20）。超过的观测在 quality 里记
     * time_untrusted 与 arrival_lag_ms，目标最新状态带 observed_at 的 TIME_UNTRUSTED 提示，页面据此写明
     * "数据过期/设备时间不准"，而不是当实时数据显示。0 表示不检查。
     */
    private long timeUntrustedLagMillis = 30_000;
    private final LossSweep lossSweep = new LossSweep();
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
    public long getMaxFutureSkewMillis() { return maxFutureSkewMillis; }
    public void setMaxFutureSkewMillis(long value) {
        if (value < 0 || value > 300_000) throw new IllegalArgumentException("max-future-skew-millis must be between 0 and 300000");
        maxFutureSkewMillis = value;
    }
    public long getDrainBudgetMillis() { return drainBudgetMillis; }
    public void setDrainBudgetMillis(long value) {
        if (value < 0 || value > 600_000) throw new IllegalArgumentException("drain-budget-millis must be between 0 and 600000");
        drainBudgetMillis = value;
    }
    public long getTimeUntrustedLagMillis() { return timeUntrustedLagMillis; }
    public void setTimeUntrustedLagMillis(long value) {
        if (value < 0) throw new IllegalArgumentException("time-untrusted-lag-millis must not be negative");
        timeUntrustedLagMillis = value;
    }
    public boolean isPrioritizeFreshSources() { return prioritizeFreshSources; }
    public void setPrioritizeFreshSources(boolean value) { prioritizeFreshSources = value; }
    public LossSweep getLossSweep() { return lossSweep; }
    public LivePromotion getLivePromotion() { return livePromotion; }
    public Replay getReplay() { return replay; }

    /**
     * 按平台时钟推进长时间没有数据的目标（app.fusion.loss-sweep.*，ZT-20 复测 2，见 {@link FusionLossSweeper}）。
     * 管线只在处理一帧时顺带推进同一分区里没被命中的目标；分区里唯一的设备停报后再没有帧来推进，要靠这里定时补上。
     */
    public static class LossSweep {
        private boolean enabled = true;
        /** 两轮检查的间隔（毫秒）；调度用 ${app.fusion.loss-sweep.interval-millis}，这里只做校验与说明。 */
        private long intervalMillis = 500;
        /** 判定短失/终止时在阈值之外多等的余量：盖住"平台已收到、还没写进 inbox"的那一小段。 */
        private long graceMillis = 500;
        /** 只推进这段时间以内还收到过数据的目标：回放数据集的接收时刻在回放时钟上（几周前的固定时刻），不去动它们。 */
        private long horizonMillis = 86_400_000;
        /** 一轮最多推进多少个目标，余下的下一轮接着推进。 */
        private int batchSize = 200;
        /** PostgreSQL 上每个目标的小事务等锁最多等多久；要小于 deadlock_timeout（默认 1 秒），与融合互等时总是这里先放弃。 */
        private long lockTimeoutMillis = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public long getIntervalMillis() { return intervalMillis; }
        public void setIntervalMillis(long value) {
            if (value <= 0) throw new IllegalArgumentException("loss-sweep.interval-millis must be positive");
            intervalMillis = value;
        }
        public long getGraceMillis() { return graceMillis; }
        public void setGraceMillis(long value) {
            if (value < 0 || value > 60_000) throw new IllegalArgumentException("loss-sweep.grace-millis must be between 0 and 60000");
            graceMillis = value;
        }
        public long getHorizonMillis() { return horizonMillis; }
        public void setHorizonMillis(long value) {
            if (value <= 0) throw new IllegalArgumentException("loss-sweep.horizon-millis must be positive");
            horizonMillis = value;
        }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) {
            if (value <= 0) throw new IllegalArgumentException("loss-sweep.batch-size must be positive");
            batchSize = value;
        }
        public long getLockTimeoutMillis() { return lockTimeoutMillis; }
        public void setLockTimeoutMillis(long value) {
            if (value <= 0 || value >= 1_000) throw new IllegalArgumentException("loss-sweep.lock-timeout-millis must be between 1 and 999");
            lockTimeoutMillis = value;
        }
    }

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
