package com.uav.lowaltitude.modules.handoff.infrastructure;

import java.time.OffsetDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffRules;

/**
 * local 缺省模拟上级：不发网络请求。
 * 风险通知只模拟“发出去了”（已送达、等待回执），不假装上级已经确认——工作台第四步是接收方确认，
 * 提交当时立刻已回执会把这一步跳过去。处罚移送仍模拟签收（没有“接收方确认”这一环）。
 * 配置了 mock 不等于已对接真实上级接口。
 */
@Component
@ConditionalOnProperty(prefix = "app.handoff", name = "channel", havingValue = "mock")
public class MockSuperiorHandoffChannel implements HandoffChannelPort {
    @Override public boolean simulated() { return true; }

    @Override
    public DeliveryOutcome deliver(HandoffDispatch dispatch) {
        OffsetDateTime submitted = dispatch.at();
        if (HandoffRules.TYPE_RISK_NOTICE.equals(dispatch.handoffType())) {
            return new DeliveryOutcome("DELIVERED", "PENDING", null, null,
                    submitted, submitted.plusSeconds(1), null);
        }
        return new DeliveryOutcome("DELIVERED", "ACKNOWLEDGED", null, null,
                submitted, submitted.plusSeconds(1), submitted.plusSeconds(2));
    }
}
