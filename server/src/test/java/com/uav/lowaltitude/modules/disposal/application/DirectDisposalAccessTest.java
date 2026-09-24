package com.uav.lowaltitude.modules.disposal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@ExtendWith(MockitoExtension.class)
class DirectDisposalAccessTest {
    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");

    @Mock IdentityAdminMapper identity;
    @Mock DisposalRepository authorizations;
    @Mock DeviceAccessPolicy devices;

    @Test
    void automationPrincipalStaysEligibleInsideTheDirectWindowWithoutAnApprover() {
        AuthUser actor = new AuthUser(AutomationPrincipal.USER_ID, AutomationPrincipal.ACCOUNT, "自动规则",
                AutomationPrincipal.ROLE, 0, false, "ALL");
        when(authorizations.actor(AutomationPrincipal.USER_ID)).thenReturn(actor);
        DirectDisposalAccess access = access();

        assertThat(access.eligibleRequester(row(NOW.minusSeconds(30), NOW.plusSeconds(60)), true)).isSameAs(actor);
        verify(identity, never()).findAdminUser(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void automationPrincipalExpiresWithTheOriginalWindow() {
        DirectDisposalAccess access = access();

        assertThat(access.eligibleRequester(row(NOW.minusSeconds(120), NOW.minusSeconds(1)), true)).isNull();
        verify(authorizations, never()).actor(AutomationPrincipal.USER_ID);
    }

    private DirectDisposalAccess access() {
        return new DirectDisposalAccess(identity, authorizations, devices, new AppClock(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static AuthorizationRow row(Instant from, Instant until) {
        OffsetDateTime start = from.atOffset(ZoneOffset.UTC);
        return new AuthorizationRow("auth", "NO", "COUNTERMEASURE", "UAV_EVENT", "event", null, "device",
                "LINGYUN_B", "反制规则已满足，系统自动发起。", AutomationPrincipal.USER_ID, start, null, null, null,
                start, until.atOffset(ZoneOffset.UTC), "APPROVED", null, null, null, "demo", "org", "district",
                "mock", 1L, null, null, "DIRECT");
    }
}
