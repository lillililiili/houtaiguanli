package com.uav.lowaltitude.modules.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 鸟群、异物停报后的“待确认”说明（问题清单 #31）：通俗、有标点，并按实际可结束方式写明谁来结束。 */
class RiskPresenceTextTest {
    @Test void reasonsArePlainSentencesWithPunctuation() {
        for (String text : new String[] { RiskPresenceService.NO_NEW_POSITION, RiskPresenceService.UNRELIABLE_POSITION }) {
            assertThat(text).endsWith("。").contains("，").contains("暂时不能确认已离开")
                    .doesNotContain("实测依据，预测、矛盾或失联");
        }
    }

    @Test void whoEndsTheRiskFollowsTheStateAndTheSource() {
        assertThat(RiskPresenceService.howToEnd("PENDING_VERIFICATION", false))
                .contains("系统会自动解除").contains("“人工核验”，选择排除");
        assertThat(RiskPresenceService.howToEnd("PENDING_NOTIFICATION", false)).contains("“改判为排除”");
        assertThat(RiskPresenceService.howToEnd("NOTIFIED", false)).contains("不能再改判排除").doesNotContain("人工核验");
        assertThat(RiskPresenceService.howToEnd("ACKNOWLEDGED", true))
                .contains("模拟数据不会自动解除").doesNotContain("系统会自动解除");
        assertThat(RiskPresenceService.howToEnd("PENDING_VERIFICATION", true)).startsWith("模拟数据不会自动解除。");
    }
}
