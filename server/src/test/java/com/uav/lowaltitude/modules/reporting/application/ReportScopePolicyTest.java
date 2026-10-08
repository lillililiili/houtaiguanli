package com.uav.lowaltitude.modules.reporting.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.report.BusinessReportSource.SourceScope;

class ReportScopePolicyTest {
    @ParameterizedTest @CsvSource({"local|qa,true,true", "local|qa,false,false", "local,true,false",
        "qa,true,false", "default,true,false", "production,true,false", "prod,true,false",
        "local|qa|production,true,false", "local|qa|prod,true,false", "test,true,true",
        "test,false,false", "test|production,true,false", "test|prod,true,false"})
    void requiresExplicitAcceptanceEnvironmentAndNeverOverridesProduction(String profiles,boolean enabled,boolean allowed) {
        var environment=new MockEnvironment();environment.setActiveProfiles(profiles.split("\\|"));
        var policy=new ReportScopePolicy(environment,enabled);
        assertThat(policy.resolve(null)).isEqualTo(SourceScope.LIVE);
        assertThat(policy.resolve("live")).isEqualTo(SourceScope.LIVE);
        assertThat(policy.available().contains("simulated")).isEqualTo(allowed);
        if(allowed) assertThat(policy.resolve("simulated")).isEqualTo(SourceScope.SIMULATED);
        else assertThatThrownBy(()->policy.resolve("simulated")).isInstanceOf(ApiException.class)
                .hasMessageContaining("未启用模拟验收报表");
    }
}
