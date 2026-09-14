package com.uav.lowaltitude.modules.risk.application.spacerisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.RuleVersionRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 决策 9-16：定时评估前的规则集守卫。
 * 没有生效版本，或生效版本是 DEMO 参数而部署未打开 allow-demo-active 时一律不评估——
 * 演示阈值算出的风险在生产没有依据，宁可不产出，也不能让它进入风险队列。
 */
class SpaceRiskEvaluationGuardTest {

    @Test
    void withoutActiveRuleSetTheJobDoesNotEvaluate() {
        SpaceRiskRepository repository = mock(SpaceRiskRepository.class);
        when(repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE)).thenReturn(null);
        assertThat(job(repository, false).unavailableReason()).isEqualTo("NO_ACTIVE_RULE_SET");
    }

    @Test
    void demoParametersAreRefusedUnlessTheDeploymentAllowsThem() {
        SpaceRiskRepository repository = mock(SpaceRiskRepository.class);
        when(repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE))
                .thenReturn(new RuleVersionRow("SPACE-RISK-DEMO", "space-risk-demo-v1", 1, "DEMO"));
        assertThat(job(repository, false).unavailableReason()).isEqualTo("DEMO_PARAMS_NOT_ALLOWED");
        // 明确打开演示开关（local/演示环境）后才允许评估。
        assertThat(job(repository, true).unavailableReason()).isNull();
    }

    @Test
    void confirmedParametersAreAlwaysAllowed() {
        SpaceRiskRepository repository = mock(SpaceRiskRepository.class);
        when(repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE))
                .thenReturn(new RuleVersionRow("SPACE-RISK-DEMO", "space-risk-demo-v2", 2, "CONFIRMED"));
        assertThat(job(repository, false).unavailableReason()).isNull();
    }

    private static SpaceRiskEvaluationJob job(SpaceRiskRepository repository, boolean allowDemoActive) {
        RuleEngineProperties properties = new RuleEngineProperties();
        properties.setAllowDemoActive(allowDemoActive);
        return new SpaceRiskEvaluationJob(mock(SpaceRiskEvaluationService.class), repository, properties, new AppClock(), 30);
    }
}
