package com.uav.lowaltitude.modules.handoff.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 处罚交接前提（决策 13-6）：从"本期一律阻断"改为"该事件必须已有完成的处置授权"。
 * 纯单元：端口用假实现，不启 Spring。
 */
class HandoffPunishmentPrerequisiteTest {

    /** 记录被问过哪些主体，用来证明前提确实去查了，而不是凭 handoff_type 直接放行。 */
    private static final class FakePort implements DisposalCompletionPort {
        private final List<String> asked = new ArrayList<>();
        private final boolean answer;
        FakePort(boolean answer) { this.answer = answer; }
        @Override public boolean completedExists(String subjectKind, String subjectId) {
            asked.add(subjectKind + "/" + subjectId);
            return answer;
        }
    }

    @Test
    void riskNoticeNeverConsultsDisposals() {
        FakePort port = new FakePort(false);
        assertThatCode(() -> HandoffRules.requirePrerequisite("RISK_NOTICE", "RISK", "risk-1", port))
                .doesNotThrowAnyException();
        assertThat(port.asked).isEmpty();
    }

    @Test
    void punishmentWithoutCompletedAuthorizationIsBlocked() {
        FakePort port = new FakePort(false);
        assertThatThrownBy(() -> HandoffRules.requirePrerequisite("UAV_PUNISHMENT", "UAV_EVENT", "event-1", port))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "HANDOFF_PREREQUISITE_UNAVAILABLE");
        assertThat(port.asked).containsExactly("UAV_EVENT/event-1");
    }

    @Test
    void punishmentWithCompletedAuthorizationPasses() {
        FakePort port = new FakePort(true);
        assertThatCode(() -> HandoffRules.requirePrerequisite("UAV_PUNISHMENT", "UAV_EVENT", "event-1", port))
                .doesNotThrowAnyException();
        assertThat(port.asked).containsExactly("UAV_EVENT/event-1");
    }

    @Test
    void punishmentFromRiskStaysBlockedWithTheOriginalCode() {
        // 阶段 5 起 (RISK, UAV_PUNISHMENT) 的回答一直是 409 HANDOFF_PREREQUISITE_UNAVAILABLE；
        // 换机制不该换掉既有答复，否则调用方要为同一件事处理两个码。
        FakePort port = new FakePort(true);
        assertThatThrownBy(() -> HandoffRules.requirePrerequisite("UAV_PUNISHMENT", "RISK", "risk-1", port))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "HANDOFF_PREREQUISITE_UNAVAILABLE");
        assertThat(port.asked).isEmpty();
    }

    @Test
    void missingPortBlocksRatherThanPasses() {
        // 端口没接上时必须阻断。默认放行会让"处置域没装好"表现成"处罚随便交"——失败要往安全的方向倒。
        assertThatThrownBy(() -> HandoffRules.requirePrerequisite("UAV_PUNISHMENT", "UAV_EVENT", "event-1", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "HANDOFF_PREREQUISITE_UNAVAILABLE");
    }

    @Test
    void uavEventPunishmentCombinationIsNowAccepted() {
        // 只放开前提是不够的：requireKindSupportsType 会先把这个组合判成 400，前提再对也走不到。
        assertThatCode(() -> HandoffRules.requireKindSupportsType("UAV_EVENT", "UAV_PUNISHMENT"))
                .doesNotThrowAnyException();
        assertThatCode(() -> HandoffRules.requireKindSupportsType("RISK", "RISK_NOTICE"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> HandoffRules.requireKindSupportsType("UAV_EVENT", "RISK_NOTICE"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "INVALID_KIND");
    }
}
