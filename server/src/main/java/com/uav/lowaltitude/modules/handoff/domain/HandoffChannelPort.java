package com.uav.lowaltitude.modules.handoff.domain;

import java.time.OffsetDateTime;

/**
 * 交接投递渠道。提交服务只消费返回的投递/回执事实，不在这里发网络请求的语义之外再改交接状态。
 * 决策 15-52：缺省 none 保持“待投递 · 未接通”；local mock 不发真实上级请求。
 */
public interface HandoffChannelPort {

    DeliveryOutcome deliver(HandoffDispatch dispatch);

    default boolean simulated() { return false; }

    record HandoffDispatch(String handoffId, String sourceKind, String sourceId, String handoffType,
                           String recipientId, String recipientName, String snapshot, OffsetDateTime at) { }

    /**
     * `receiptResult` 是**处理结果**，与 `receiptStatus`（签收状态）是两件事（决策 18-14）：
     * 上级签收了不等于把无人机驱离了。风险通知靠它闭环，取值 DISPERSED / NOT_DISPERSED；
     * 其余交接类型为 null。两者混成一个状态，页面就永远回答不了"这条风险结束了吗"。
     */
    record DeliveryOutcome(String deliveryStatus, String receiptStatus, String receiptResult, String blockedReason,
                           OffsetDateTime submittedAt, OffsetDateTime deliveredAt, OffsetDateTime acknowledgedAt) {
        public static DeliveryOutcome notConnected() {
            return new DeliveryOutcome(HandoffRules.PENDING_DELIVERY, HandoffRules.NOT_EXPECTED, null,
                    HandoffRules.CHANNEL_NOT_CONNECTED, null, null, null);
        }
    }
}
