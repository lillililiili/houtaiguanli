package com.uav.lowaltitude.modules.alarm.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 人工核实为“属实”前的依据检查，只看系统里已有的事实：告警有没有关联目标、有没有合法性研判、
 * 目标数据是否还在有效时长内；目标已停报时，已入库的光电截图、录像或现场照片可以作为补充依据。
 * 不限制核实为误报，也不参与自动核实规则（自动核实另走 AlarmRuleVerification）。
 */
public final class UavVerificationBasis {
    public static final String NO_TARGET = "NO_TARGET";
    public static final String NO_EVALUATION = "NO_EVALUATION";
    public static final String NO_CURRENT_DATA = "NO_CURRENT_DATA";
    public static final String NO_FRESHNESS_RULE = "NO_FRESHNESS_RULE";

    /**
     * @param target         告警是否关联到与事件同一组织区域的目标
     * @param eventCreatedAt 事件建立时刻
     * @param observedAt     目标（含融合后的当前目标）最近一次观测时刻
     * @param evaluatedAt    目标最近一次正式（ACTIVE）合法性研判时刻
     * @param freshSeconds   生效规则集的 C03.fresh_seconds，取不到为 null
     * @param mediaEvidence  关联本事件或本次告警期间关联目标、可用的光电截图/录像/现场照片数量
     */
    public record Facts(boolean target, Long eventCreatedAt, Long observedAt, Long evaluatedAt,
                        Integer freshSeconds, long mediaEvidence, long now) { }

    public record Result(boolean confirmable, List<String> missing, String message) { }

    private UavVerificationBasis() { }

    public static Result evaluate(Facts facts) {
        List<String> missing = new ArrayList<>(), reasons = new ArrayList<>();
        Integer seconds = facts.freshSeconds() == null || facts.freshSeconds() <= 0 ? null : facts.freshSeconds();
        long window = seconds == null ? 0 : seconds * 1000L;
        if (!facts.target()) {
            missing.add(NO_TARGET);
            reasons.add("告警没有关联目标，系统里没有可核对的目标数据");
        } else {
            // 早于本次告警的旧研判不算本次依据；由研判生成的告警，研判时刻与事件建立时刻相差不会超过有效时长。
            if (facts.evaluatedAt() == null
                    || (facts.eventCreatedAt() != null && facts.evaluatedAt() < facts.eventCreatedAt() - window)) {
                missing.add(NO_EVALUATION);
                reasons.add("还没有这次告警的合法性研判结果");
            }
            Long observed = facts.observedAt();
            boolean current = seconds != null && observed != null && Math.abs(facts.now() - observed) <= window;
            if (!current && facts.mediaEvidence() == 0) {
                if (seconds == null) {
                    missing.add(NO_FRESHNESS_RULE);
                    reasons.add("没有配置目标数据有效时长（规则 C03），判断不了目标数据是否还新，也没有光电截图或录像");
                } else {
                    missing.add(NO_CURRENT_DATA);
                    reasons.add(observed == null ? "目标没有位置数据，也没有光电截图或录像"
                            : observed > facts.now() ? "目标数据的时间晚于系统当前时间，确认不了数据是否还新，也没有光电截图或录像"
                            : "目标已经 " + duration(facts.now() - observed) + "没有新数据（有效时长 " + duration(window)
                              + "），也没有光电截图或录像");
                }
            }
        }
        if (missing.isEmpty()) return new Result(true, List.of(), null);
        return new Result(false, List.copyOf(missing),
                "缺少依据，不能核实为属实：" + String.join("；", reasons) + "。确认是误报的，可以核实为误报。");
    }

    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟";
        long hours = minutes / 60;
        return hours < 48 ? hours + " 小时" : hours / 24 + " 天";
    }
}
