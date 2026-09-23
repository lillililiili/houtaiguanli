package com.uav.lowaltitude.modules.alarm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Presence;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow.Phase;

class NotifyFlowTest {
    @Test void verifiedAlarmWalksSmsWatchCallWatchThenCounter() {
        long sms = 1_000L;
        assertThat(NotifyFlow.phase("PENDING_VERIFICATION", "WAITING", "尚未核实", null, "WAITING", null, null, sms, null, null)).isNull();
        assertThat(NotifyFlow.phase("CONFIRMED", "SENDING", "发送中", null, "WAITING", null, null, sms, null, null)).isEqualTo(Phase.AUTO_SMS);
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "WAITING", null, null, sms + 9_000L, null, null)).isEqualTo(Phase.WATCHING);
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "CALLING", null, null, sms + 10_000L, Presence.STILL_PRESENT, null)).isEqualTo(Phase.AUTO_CALL);
        long played = sms + 12_000L;
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "SIMULATED_PLAYED", null, played, played + 9_000L, Presence.STILL_PRESENT, null)).isEqualTo(Phase.WATCHING);
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "SIMULATED_PLAYED", null, played, played + 10_000L, Presence.STILL_PRESENT, Presence.STILL_PRESENT)).isEqualTo(Phase.AWAIT_COUNTER);
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "SIMULATED_PLAYED", null, played, played + 10_000L, Presence.STILL_PRESENT, Presence.LEFT)).isNull();
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "BLOCKED", "最新位置已离开短信发出时所处的告警空域，不拨打电话", null, sms + 10_000L, Presence.LEFT, null)).isNull();
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "BLOCKED", "短信发出后没有新的位置，或无法判断是否仍在告警空域，不拨打电话，也不记为已撤离", null, sms + 10_000L, Presence.UNKNOWN, null)).isEqualTo(Phase.AWAIT_COUNTER);
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "UNAVAILABLE", null, null, sms + 10_000L, Presence.STILL_PRESENT, null)).isEqualTo(Phase.AWAIT_COUNTER);
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "DISABLED", null, null, sms + 1_000L, null, null)).isEqualTo(Phase.WATCHING);
        assertThat(NotifyFlow.phase("CONFIRMED", "SIMULATED_DELIVERED", "已送达", sms, "UNAVAILABLE", "未配置录音", null, sms + 10_000L, null, null)).isEqualTo(Phase.AWAIT_COUNTER);
        assertThat(NotifyFlow.phase("CONFIRMED", "UNAVAILABLE", "正式短信渠道尚未接入", null, "WAITING", null, null, 0L, null, null)).isEqualTo(Phase.AWAIT_COUNTER);
        assertThat(NotifyFlow.phase("CONFIRMED", "DISABLED", "自动短信未启用", null, "DISABLED", null, null, 0L, null, null)).isEqualTo(Phase.AWAIT_COUNTER);
    }
    @Test void missingPilotBecomesAwaitCounterOnlyAfterVerification() {
        assertThat(NotifyFlow.phase("PENDING_VERIFICATION", "BLOCKED", "没有可通知的执行飞手，不能发送短信", null, "WAITING", null, null, 0L, null, null)).isNull();
        assertThat(NotifyFlow.phase("CONFIRMED", "BLOCKED", "没有可通知的执行飞手，不能发送短信", null, "WAITING", null, null, 0L, null, null)).isEqualTo(Phase.AWAIT_COUNTER);
        assertThat(NotifyFlow.phase("FALSE_POSITIVE", "BLOCKED", "已核实为误报，不发送飞手短信", null, "DISABLED", null, null, 0L, null, null)).isNull();
    }
}
