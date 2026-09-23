package com.uav.lowaltitude.modules.alarm.application;

/** 短信渠道边界。正式渠道须另接发送、送达回执与手机号授权来源，不能把提交当送达。 */
public interface AdvisorySmsPort {
    boolean simulationAvailable(String sourceMode);
    Delivery simulate(String sourceMode, String recipientName, String content);
    /** 后续正式适配器必须按此稳定键去重并核对回执；自动重试不产生新的逻辑通知。 */
    default Delivery simulate(String sourceMode,String recipientName,String content,String idempotencyKey) {
        return simulate(sourceMode,recipientName,content);
    }
    /** 自动短信在本地演示环境不看来源种类；回执仍必须标成模拟，不能写成真实送达。 */
    default boolean automaticSimulationAvailable(String sourceMode) { return simulationAvailable(sourceMode); }
    default Delivery simulateAutomatic(String sourceMode,String recipientName,String content,String idempotencyKey) {
        return simulate(sourceMode,recipientName,content,idempotencyKey);
    }
    record Delivery(boolean simulated, String status) { }
}
