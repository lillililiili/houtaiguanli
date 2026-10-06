package com.uav.lowaltitude.modules.assessment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.LinkedMultiValueMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.assessment.api.RuleDtos.RuleSetVersionDto;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RuleSetRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.VersionDetailRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 管理端“启用”按钮与激活守卫同源：演示参数只在显式测试环境（local+qa / test）且打开 allow-demo-active 时可启用；
 * 正式环境或未打开开关时只给原因，不给按钮。已确认参数在任何环境都能启用。
 */
class RuleSetActivationAvailabilityTest {
    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 10, 6, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void demoParamsCanOnlyBeActivatedInExplicitTestEnvironmentWithDemoSwitch() {
        assertThat(versions(true, "local", "qa")).extracting(RuleSetVersionDto::activationAllowed).containsExactly(false, true, true, false);
        assertThat(versions(true, "local", "qa").get(1).activationBlockReason()).isNull();
        assertThat(versions(false, "local", "qa")).extracting(RuleSetVersionDto::activationAllowed).containsExactly(false, false, true, false);
        // 正式环境启动时就拒绝 allow-demo-active=true；即使开关被误配，守卫仍以环境为准。
        for (String[] profiles : new String[][]{{"production"}, {"local"}, {"local", "qa", "production"}}) {
            List<RuleSetVersionDto> items = versions(true, profiles);
            assertThat(items).extracting(RuleSetVersionDto::activationAllowed).containsExactly(false, false, true, false);
            assertThat(items.get(1).activationBlockReason()).isEqualTo("演示参数尚未经业务方确认，正式环境不能启用");
        }
    }

    @Test
    void unpublishedAndActiveVersionsExplainWhyTheyCannotBeActivated() {
        List<RuleSetVersionDto> items = versions(true, "test");
        assertThat(items.get(0).activationBlockReason()).isEqualTo("版本尚未发布");
        assertThat(items.get(3).activationBlockReason()).isEqualTo("该版本已是生效版本");
        assertThat(items.get(2).activationBlockReason()).isNull();
    }

    private static List<RuleSetVersionDto> versions(boolean allowDemoActive, String... profiles) {
        RuleEngineRepository repository = mock(RuleEngineRepository.class);
        when(repository.findRuleSetByCode("LEGALITY-TEST")).thenReturn(new RuleSetRow("rs", "LEGALITY-TEST", "合法性研判", "v1", null, null, 3, AT, AT));
        when(repository.listVersions("rs", 0, 20)).thenReturn(List.of(
                version("v4", 4, "DRAFT", "DEMO", false),
                version("v3", 3, "PUBLISHED", "DEMO", false),
                version("v2", 2, "PUBLISHED", "CONFIRMED", false),
                version("v1", 1, "PUBLISHED", "CONFIRMED", true)));
        when(repository.countVersions("rs")).thenReturn(4L);
        RuleEngineProperties properties = new RuleEngineProperties();
        properties.setAllowDemoActive(allowDemoActive);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        RuleSetManagementService service = new RuleSetManagementService(mock(AccessControlService.class), repository, properties,
                null, null, new AppClock(), new ObjectMapper(), new SimulationPolicy(environment));
        return service.listVersions("LEGALITY-TEST", new LinkedMultiValueMap<>()).items();
    }

    private static VersionDetailRow version(String id, int no, String status, String params, boolean active) {
        return new VersionDetailRow(id, "rs", "LEGALITY-TEST", no, status, params, AT, null, "v" + no, "mock", AT,
                "DRAFT".equals(status) ? null : AT, active, false);
    }
}
