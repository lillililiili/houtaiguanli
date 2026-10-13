package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.device.infrastructure.LocalQaDeviceRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.security.AuthUser;

class ManualRadarSimulationTest {
    @Test void requiresExplicitEnvironmentFlagPermissionsAndGlobalScope() {
        var env=new MockEnvironment(); var user=mock(AuthUser.class);var access=mock(AccessService.class);
        var repository=mock(LocalQaDeviceRepository.class);var audit=mock(AuditService.class);
        when(user.scopeMode()).thenReturn("ALL");when(user.roleCode()).thenReturn("ROLE-BACKEND");
        when(access.permissionCodes("ROLE-BACKEND")).thenReturn(List.of("interfaces.op","devices.auth","devices.op"));
        var simulation=new ManualRadarSimulation(new SimulationPolicy(env),true,access,repository,audit);
        for (String[] profiles : List.of(new String[]{"local"},new String[]{"qa"},new String[]{"local","qa","prod"},new String[]{"test","production"})) {
            env.setActiveProfiles(profiles);assertThat(simulation.allowed(user)).isFalse();
            assertThatThrownBy(()->simulation.validate(user,null)).isInstanceOf(com.uav.lowaltitude.platform.api.ApiException.class);
        }
        env.setActiveProfiles("local","qa");assertThat(simulation.allowed(user)).isTrue();
        assertThat(new ManualRadarSimulation(new SimulationPolicy(env),false,access,repository,audit).allowed(user)).isFalse();
        when(user.scopeMode()).thenReturn("ASSIGNED");assertThat(simulation.allowed(user)).isFalse();
        when(user.scopeMode()).thenReturn("ALL");when(access.permissionCodes("ROLE-BACKEND")).thenReturn(List.of("devices.op"));
        assertThat(simulation.allowed(user)).isFalse();verifyNoInteractions(repository,audit);
    }
}
