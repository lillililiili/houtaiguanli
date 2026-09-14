package com.uav.lowaltitude.modules.handoff.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;

/** 生产缺省：没有接通任何上级渠道，首条投递只能是待投递并标明未接通。 */
@Component
@ConditionalOnProperty(prefix = "app.handoff", name = "channel", havingValue = "none", matchIfMissing = true)
public class NoHandoffChannel implements HandoffChannelPort {

    @Override
    public DeliveryOutcome deliver(HandoffDispatch dispatch) {
        return DeliveryOutcome.notConnected();
    }
}
