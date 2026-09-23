package com.uav.lowaltitude.integration.mock;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.alarm.application.AdvisorySmsPort;
import com.uav.lowaltitude.platform.api.ApiException;

/** 明示模拟接收端，不调用短信网关、不采集手机号。人工发送仍只接受模拟/回放来源；自动发送在本地演示环境接受全部来源，但回执保持模拟。 */
@Component
public class LocalAdvisorySmsAdapter implements AdvisorySmsPort {
    private final Environment environment;
    public LocalAdvisorySmsAdapter(Environment environment) { this.environment = environment; }
    public boolean simulationAvailable(String mode) {
        return development() && mode != null && Set.of("mock", "replay").contains(mode);
    }
    public boolean automaticSimulationAvailable(String mode) { return development(); }
    public Delivery simulate(String mode, String recipientName, String content) {
        if (!simulationAvailable(mode)) throw new ApiException(HttpStatus.CONFLICT, "SMS_CHANNEL_UNAVAILABLE", "正式短信渠道尚未接入，不能模拟发送真实来源事件");
        return new Delivery(true, "SIMULATED_DELIVERED");
    }
    public Delivery simulateAutomatic(String mode, String recipientName, String content, String idempotencyKey) {
        if (!automaticSimulationAvailable(mode)) throw new ApiException(HttpStatus.CONFLICT, "SMS_CHANNEL_UNAVAILABLE", "正式短信渠道尚未接入，不能把模拟送达写成真实通知");
        return new Delivery(true, "SIMULATED_DELIVERED");
    }
    private boolean development() { return environment.acceptsProfiles(Profiles.of("!production & (local | test)")); }
}
