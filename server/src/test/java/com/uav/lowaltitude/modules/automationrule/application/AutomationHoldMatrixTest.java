package com.uav.lowaltitude.modules.automationrule.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.Rule;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository.Head;

/** The four catalog conditions supporting sustained observations; no scheduler time is substituted for evidence. */
class AutomationHoldMatrixTest {
    static Stream<Arguments> cases() {
        return Stream.of(new String[]{"verify","confidence","90","95","89"},
                new String[]{"verify","consistency","同一目标的身份信息一致","true","false"},
                new String[]{"verify","accuracy","20","10","21"},
                new String[]{"counter","riskLevel","高风险","HIGH","LOW"})
                .flatMap(row -> Stream.of("PASS","FAIL","UNKNOWN","SHORT").map(state -> Arguments.of(row,state)));
    }

    @ParameterizedTest @MethodSource("cases")
    void allFourSustainedConditionsRequireCurrentContinuousEvidence(String[] row, String outcome) {
        long start = 1_800_000_000_000L;
        var engine = new AutomationRuntimeEvaluator();
        var group = new Head(row[0],1,"ALL","ALL_DAY","00:00","23:59",0,"[]");
        var rule = new Rule("condition",row[1],row[1],row[2],5,true,start,"qa");
        var first = engine.evaluate(group,List.of(rule),Set.of(),facts(row[1],row[3],start),start,30000,Map.of(),null);
        assertThat(first.status()).isEqualTo("WAITING");
        long observed = start + ("SHORT".equals(outcome) ? 4999 : 5000);
        String value = "UNKNOWN".equals(outcome) ? null : "FAIL".equals(outcome) ? row[4] : row[3];
        var result = engine.evaluate(group,List.of(rule),Set.of(),facts(row[1],value,observed),observed,30000,first.holds(),null);
        assertThat(result.status()).isEqualTo(switch(outcome) {
            case "PASS" -> "PASS"; case "FAIL" -> "NOT_MATCHED"; case "UNKNOWN" -> "REVIEW"; default -> "WAITING";
        });
        assertThat(result.conditions().get(0).result()).isEqualTo("SHORT".equals(outcome) ? "WAITING" : outcome);
        if (Set.of("FAIL","UNKNOWN").contains(outcome)) assertThat(result.holds()).isEmpty();
    }

    private AutomationRuntimeFacts facts(String code,String value,long at) {
        return new AutomationRuntimeFacts("event","target","alarm","org","district","replay","CONFIRMED","UAV",at,
                Set.of(),true,Map.of(code,new AutomationRuntimeFacts.Fact(value,at,"isolated-observation",null)));
    }
}
