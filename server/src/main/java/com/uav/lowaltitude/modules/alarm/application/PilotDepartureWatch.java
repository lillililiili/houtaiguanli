package com.uav.lowaltitude.modules.alarm.application;

/** 短信送达后的设备观察：只根据新位置和短信发出时所处空域判断是否仍在。 */
public interface PilotDepartureWatch {
    enum Presence { STILL_PRESENT, LEFT, UNKNOWN }
    Presence assess(String eventId, long smsAcceptedAt, long now);
}
