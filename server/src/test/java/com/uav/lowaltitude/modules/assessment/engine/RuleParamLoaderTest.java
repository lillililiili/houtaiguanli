package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.platform.api.ApiException;

class RuleParamLoaderTest {
    @Test void missingSeverityIsExplicitConfigurationFailureWithoutInventedScore() {
        var params = new RuleParamLoader.LoadedRuleParams("qa-version", Map.of());
        Throwable error = catchThrowable(() -> params.number("C03", "severity.BVLOS_EXCEEDED"));
        assertThat(error).isInstanceOf(ApiException.class);
        var api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(api.getCode()).isEqualTo("RULE_CONFIGURATION_INVALID");
        assertThat(api.getMessage()).contains("C03.severity.BVLOS_EXCEEDED", "参数缺失");
    }

    @Test void malformedValueIsExplicitFailureWithoutExposingItsContent() {
        var params = new RuleParamLoader.LoadedRuleParams("qa-version", Map.of(
                "C03.conf_min", new RuleParamLoader.Entry("invalid-private-value", "NUMBER", "DEMO")));
        Throwable error = catchThrowable(() -> params.number("C03", "conf_min"));
        assertThat(error).isInstanceOf(ApiException.class);
        assertThat(((ApiException) error).getCode()).isEqualTo("RULE_CONFIGURATION_INVALID");
        assertThat(error.getMessage()).contains("C03.conf_min").doesNotContain("invalid-private-value");
    }

    @Test void configuredNumbersStillUseExactStoredValueAndStatus() {
        var params = new RuleParamLoader.LoadedRuleParams("qa-version", Map.of(
                "C03.conf_min", new RuleParamLoader.Entry("0.80", "NUMBER", "DEMO")));
        assertThat(params.number("C03", "conf_min")).isEqualByComparingTo("0.80");
        assertThat(params.paramStatus("C03", "conf_min")).isEqualTo("DEMO");
    }
}
