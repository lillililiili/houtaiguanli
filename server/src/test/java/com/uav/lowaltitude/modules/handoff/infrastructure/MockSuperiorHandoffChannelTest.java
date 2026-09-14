package com.uav.lowaltitude.modules.handoff.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;

/** 模拟上级：风险通知已送达、等待回执；处罚交接仍模拟签收；未接通渠道保持待投递。 */
class MockSuperiorHandoffChannelTest {
    private final OffsetDateTime at = OffsetDateTime.of(2026, 9, 8, 12, 0, 0, 0, ZoneOffset.UTC);
    private final HandoffDispatch dispatch = new HandoffDispatch("h-1", "RISK", "r-1", "RISK_NOTICE", "rcpt-1", "民航监管部门", "{}", at);

    @Test
    void mockRiskNoticeDeliversAndWaitsForReceipt() {
        DeliveryOutcome outcome = new MockSuperiorHandoffChannel().deliver(dispatch);
        assertThat(outcome.deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(outcome.receiptStatus()).isEqualTo("PENDING");
        assertThat(outcome.blockedReason()).isNull();
        assertThat(outcome.receiptResult()).isNull();
        assertThat(outcome.submittedAt()).isEqualTo(at);
        assertThat(outcome.deliveredAt()).isEqualTo(at.plusSeconds(1));
        assertThat(outcome.acknowledgedAt()).isNull();
        assertThat(HandoffRules.DELIVERY_STATUSES).contains(outcome.deliveryStatus());
        assertThat(HandoffRules.RECEIPT_STATUSES).contains(outcome.receiptStatus());
    }

    /** 处罚交接交的是案卷，上级签收就是签收，没有"驱离与否"这回事——别拿风险通知的结果套上去。 */
    @Test
    void punishmentHandoffAcknowledgesWithoutDispersalResult() {
        HandoffDispatch punishment = new HandoffDispatch("h-2", "UAV_EVENT", "e-1", HandoffRules.TYPE_UAV_PUNISHMENT,
                "rcpt-2", "公安机关", "{}", at);
        DeliveryOutcome outcome = new MockSuperiorHandoffChannel().deliver(punishment);
        assertThat(outcome.deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(outcome.receiptStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(outcome.receiptResult()).isNull();
    }

    @Test
    void noChannelStaysPendingWithBlockedReason() {
        DeliveryOutcome outcome = new NoHandoffChannel().deliver(dispatch);
        assertThat(outcome.deliveryStatus()).isEqualTo(HandoffRules.PENDING_DELIVERY);
        assertThat(outcome.receiptStatus()).isEqualTo(HandoffRules.NOT_EXPECTED);
        assertThat(outcome.blockedReason()).isEqualTo(HandoffRules.CHANNEL_NOT_CONNECTED);
        assertThat(outcome.deliveredAt()).isNull();
        assertThat(outcome.receiptResult()).isNull();
    }
}
