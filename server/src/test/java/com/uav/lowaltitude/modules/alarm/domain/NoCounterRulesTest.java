package com.uav.lowaltitude.modules.alarm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoCounterRulesTest {
    @Test void repeatedEvaluationAndReducedRiskDoNotReopen() {
        assertThat(NoCounterRules.newRisk("ILLEGAL","HIGH",List.of("AIRSPACE"),"ILLEGAL","HIGH",List.of("AIRSPACE"))).isFalse();
        assertThat(NoCounterRules.newRisk("ILLEGAL","HIGH",List.of("AIRSPACE"),"LEGAL",null,List.of())).isFalse();
    }
    @Test void NewReasonEscalationAndRecurrenceReopen() {
        assertThat(NoCounterRules.newRisk("ILLEGAL","LOW",List.of("AIRSPACE"),"ILLEGAL","HIGH",List.of("AIRSPACE"))).isTrue();
        assertThat(NoCounterRules.newRisk("ILLEGAL","HIGH",List.of("AIRSPACE"),"ILLEGAL","HIGH",List.of("AIRSPACE","HEIGHT"))).isTrue();
        assertThat(NoCounterRules.newRisk("LEGAL",null,List.of(),"ILLEGAL","LOW",List.of())).isTrue();
    }
    @Test void unknownIsNeverNewReliableRisk() {
        assertThat(NoCounterRules.newRisk("LEGAL",null,List.of(),"UNKNOWN",null,List.of("MISSING"))).isFalse();
    }
}
