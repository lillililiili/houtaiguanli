package com.uav.lowaltitude.modules.alarm.domain;

import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch;

/** 核实属实后的通知阶段。两段观察都是 10 秒，只根据这段时间里的新位置判断是否仍在告警空域。 */
public final class NotifyFlow {
    public static final long WATCH_MILLIS = 10_000L;
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
        if (now < smsAt + WATCH_MILLIS) return Phase.WATCHING;
        if ("UNAVAILABLE".equals(voiceStatus) || "DISABLED".equals(voiceStatus)) return Phase.AWAIT_COUNTER;
        if (!"SIMULATED_PLAYED".equals(voiceStatus)) {
            if (afterSms == PilotDepartureWatch.Presence.STILL_PRESENT || "CALLING".equals(voiceStatus) || "WAITING".equals(voiceStatus))
                return Phase.AUTO_CALL;
            if (afterSms == PilotDepartureWatch.Presence.UNKNOWN || unconfirmed(voiceReason)) return Phase.AWAIT_COUNTER;
            return null;
        }
        if (playedAt == null || now < playedAt + WATCH_MILLIS) return Phase.WATCHING;
        if (afterCall == PilotDepartureWatch.Presence.LEFT) return null;
        return Phase.AWAIT_COUNTER;
    }
    /** 没有可通知的执行飞手，或短信通道本身不可用。尚未核实的等待不在这里。 */
    static boolean cannotNotify(String status, String reason) {
        if ("UNAVAILABLE".equals(status) || "DISABLED".equals(status)) return true;
        if (!"BLOCKED".equals(status)) return false;
        return reason != null && (reason.contains("飞手") || reason.contains("计划") || reason.contains("联系") || reason.contains("名册") || reason.contains("通知配置"));
    }
    private static boolean unconfirmed(String reason) {
        return reason != null && (reason.contains("没有新的位置") || reason.contains("无法判断") || reason.contains("无法确认"));
    }
}
