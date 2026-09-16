package com.uav.lowaltitude.modules.alarm.application;

/** 短信渠道边界。正式渠道须另接发送、送达回执与手机号授权来源，不能把提交当送达。 */
public interface AdvisorySmsPort {
    boolean simulationAvailable(String sourceMode);
    Delivery simulate(String sourceMode, String recipientName, String content);
    record Delivery(boolean simulated, String status) { }
}
