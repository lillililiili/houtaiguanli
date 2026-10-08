package com.uav.lowaltitude.modules.reporting.application;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.report.BusinessReportSource.SourceScope;

/** Source selection changes only report reads, never business facts or action eligibility. */
@Component
public class ReportScopePolicy {
    private final boolean simulationEnabled;
    public ReportScopePolicy(Environment environment, @Value("${app.qa.reporting.enabled:false}") boolean enabled) {
        simulationEnabled = enabled && !environment.acceptsProfiles(Profiles.of("production | prod"))
                && environment.acceptsProfiles(Profiles.of("(local & qa) | test"));
    }
    public SourceScope resolve(String value) {
        if (value == null || value.equals("live")) return SourceScope.LIVE;
        if (!value.equals("simulated")) throw BusinessReportingService.bad("报表来源口径只能为 live 或 simulated");
        if (!simulationEnabled) throw new ApiException(HttpStatus.FORBIDDEN,"SIMULATED_REPORT_DISABLED",
                "当前环境未启用模拟验收报表");
        return SourceScope.SIMULATED;
    }
    public List<String> available() { return simulationEnabled ? List.of("live","simulated") : List.of("live"); }
}
