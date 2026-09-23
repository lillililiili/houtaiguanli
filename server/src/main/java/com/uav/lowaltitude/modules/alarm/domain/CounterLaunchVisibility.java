package com.uav.lowaltitude.modules.alarm.domain;

/** 发起反制只在通知阶段到达「待反制」时出现。 */
public final class CounterLaunchVisibility {
    private CounterLaunchVisibility() { }
    public static boolean visible(NotifyFlow.Phase phase) { return phase == NotifyFlow.Phase.AWAIT_COUNTER; }
}
