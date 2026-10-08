package com.uav.lowaltitude.modules.alarm.application;

/** 短信送达后的设备观察：只根据新位置和短信发出时所处空域判断是否仍在。 */
public interface PilotDepartureWatch {
    enum Presence { STILL_PRESENT, LEFT, UNKNOWN }
    /** 短信后的观察：告警区域按短信发出时目标所在空域确定，只看短信之后上报的新位置。 */
    Presence assess(String eventId, long smsAcceptedAt, long now);
    /**
     * 电话后的观察：告警区域仍按短信发出时目标所在空域确定，只看 since（录音播完）之后上报的新位置。
     * 不能改用 since 时刻目标所在空域：电话期间已经飞出区域的目标，那时已不在任何告警空域里，区域会算成空的。
     */
    default Presence assess(String eventId, long smsAcceptedAt, long since, long now) {
        return since == smsAcceptedAt ? assess(eventId, smsAcceptedAt, now) : Presence.UNKNOWN;
    }
    /** 短信发出时目标是否在某个告警空域里。不在时是按最新违规研判判断撤离的，说法不能写成“离开了告警空域”。 */
    default boolean inAreaAtSms(String eventId, long smsAcceptedAt) { return true; }
}
