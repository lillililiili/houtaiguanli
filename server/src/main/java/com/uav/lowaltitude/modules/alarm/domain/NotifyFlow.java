package com.uav.lowaltitude.modules.alarm.domain;

import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch;

/** 核实属实后的通知阶段。短信送达后观察 3 秒，电话播放后再观察 10 秒。 */
public final class NotifyFlow {
    public static final long SMS_WATCH_MILLIS = 3_000L;
    public static final long CALL_WATCH_MILLIS = 10_000L;
    public enum Phase { AUTO_SMS, WATCHING, AUTO_CALL, AWAIT_COUNTER }
    private NotifyFlow() { }
    public static Phase phase(String state, String smsStatus, String smsReason, Long smsAt, String voiceStatus, String voiceReason, Long playedAt, long now,
            PilotDepartureWatch.Presence afterSms, PilotDepartureWatch.Presence afterCall) {
        if (!"CONFIRMED".equals(state)) return null;
        if (cannotNotify(smsStatus, smsReason)) return Phase.AWAIT_COUNTER;
        if (!"SIMULATED_DELIVERED".equals(smsStatus) || smsAt == null) {
            if ("WAITING".equals(smsStatus) || "SENDING".equals(smsStatus) || "FAILED".equals(smsStatus)) return Phase.AUTO_SMS;
            return null;
        }
        if (now < smsAt + SMS_WATCH_MILLIS) return Phase.WATCHING;
        if (cannotNotify(voiceStatus, voiceReason)) return Phase.AWAIT_COUNTER;
        // BLOCKED 已停止自动拨号；新位置恢复也不能把持久化终止任务显示为自动外呼。
        if ("BLOCKED".equals(voiceStatus)) return afterSms == PilotDepartureWatch.Presence.LEFT ? null : Phase.AWAIT_COUNTER;
        // 未知结果等待对账，不代表仍在外呼，也不能开始播完后的观察。
        if ("UNKNOWN".equals(voiceStatus)) return null;
        if (!"SIMULATED_PLAYED".equals(voiceStatus)) {
            if (afterSms == PilotDepartureWatch.Presence.STILL_PRESENT || "CALLING".equals(voiceStatus) || "WAITING".equals(voiceStatus))
                return Phase.AUTO_CALL;
            if (afterSms == PilotDepartureWatch.Presence.UNKNOWN || unconfirmed(voiceReason)) return Phase.AWAIT_COUNTER;
            return null;
        }
        if (playedAt == null || now < playedAt + CALL_WATCH_MILLIS) return Phase.WATCHING;
        if (afterCall == PilotDepartureWatch.Presence.LEFT) return null;
        return Phase.AWAIT_COUNTER;
    }
    /** 没有可通知的执行飞手，或短信通道本身不可用。尚未核实的等待不在这里。 */
    public static boolean cannotNotify(String status, String reason) {
        if ("UNAVAILABLE".equals(status) || "DISABLED".equals(status)) return true;
        if (!"BLOCKED".equals(status)) return false;
        return reason != null && (reason.contains("飞手") || reason.contains("任务") || reason.contains("计划") || reason.contains("联系") || reason.contains("名册") || reason.contains("通知配置"));
    }
    private static boolean unconfirmed(String reason) {
        return reason != null && (reason.contains("没有新的位置") || reason.contains("无法判断") || reason.contains("无法确认"));
    }
}
