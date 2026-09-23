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
        @Override public java.util.List<String> jammingCompletedWithoutPunishment() { return java.util.List.of(); }
        @Override public String completedJammingRequester(String eventId) { return null; }
    }

    @Test
    void riskNoticeNeverConsultsDisposals() {
        FakePort port = new FakePort(false);
        assertThatCode(() -> HandoffRules.requirePrerequisite("RISK_NOTICE", "RISK", "risk-1", port))
                .doesNotThrowAnyException();
        assertThat(port.asked).isEmpty();
    }

    @Test
    void punishmentCanBeSubmittedWithoutDisposal() {
        FakePort port = new FakePort(false);
        assertThatCode(() -> HandoffRules.requirePrerequisite("UAV_PUNISHMENT", "UAV_EVENT", "event-1", port))
                .doesNotThrowAnyException();
        assertThat(port.asked).isEmpty();
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
    void disposalPortIsNoLongerAPrerequisite() {
        assertThatCode(() -> HandoffRules.requirePrerequisite("UAV_PUNISHMENT", "UAV_EVENT", "event-1", null))
                .doesNotThrowAnyException();
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
