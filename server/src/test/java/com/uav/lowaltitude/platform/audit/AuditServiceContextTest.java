package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

class AuditServiceContextTest {
    private final AuditMapper mapper = mock(AuditMapper.class);
    private final Instant now = Instant.parse("2030-03-02T01:02:03Z");
    private final AuditService service = new AuditService(mapper, new AppClock(Clock.fixed(now, ZoneOffset.UTC)));

    @AfterEach void clear() { AuthContext.clear(); RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest
    @CsvSource({"ROLE-ADMIN,192.0.2.17,commission_start,commission_task,commissioning",
            "ROLE-BACKEND,2001:db8::2,device_update,device,devices",
            "ROLE-TEST,127.0.0.1,device_maintenance_start,device_maintenance_task,maintenance"})
    void requestAuditCapturesTheSameActorsRoleAndAddress(String role, String ip, String action, String object, String menu) {
        AuthUser actor = context(role, ip);
        service.record(actor.userId(), actor.account(), action, object, UUID.randomUUID().toString(), null, null);
        AuditLog log = recorded();
        assertThat(log.getRoleCode()).isEqualTo(role);
        assertThat(log.getIp()).isEqualTo(ip);
        assertThat(log.getUserAgent()).isEqualTo("audit-browser");
        assertThat(log.getModuleCode()).isEqualTo(menu);
        assertThat(log.getOccurredAt()).isEqualTo(now.toEpochMilli());
    }

    @Test void backgroundAndDifferentActorsNeverBorrowRequestIdentity() {
        context("ROLE-ADMIN", "192.0.2.1");
        service.record(null, "SYSTEM", "device_maintenance_created", "device_maintenance_task", "task", null, null);
        AuditLog log = recorded();
        assertThat(log.getRoleCode()).isNull();
        assertThat(log.getIp()).isNull();
        assertThat(log.getUserAgent()).isEmpty();
        reset(mapper);
        service.record(UUID.randomUUID().toString(), "other", "device_update", "device", "device", null, null);
        assertThat(recorded().getRoleCode()).isNull();
        assertThat(recorded().getIp()).isNull();
    }

    @Test void explicitSnapshotsArePreservedAndClientAgentIsBounded() {
        AuthUser actor = context("ROLE-BACKEND", "192.0.2.2");
        service.record(actor.userId(), actor.account(), "ROLE-ORIGINAL", "authentication", "login_success",
                "user", actor.userId(), "", "SUCCESS", "2001:db8::99", "x".repeat(900));
        AuditLog log = recorded();
        assertThat(log.getRoleCode()).isEqualTo("ROLE-ORIGINAL");
        assertThat(log.getIp()).isEqualTo("2001:db8::99");
        assertThat(log.getUserAgent()).hasSize(512);
    }

    private AuthUser context(String role, String ip) {
        AuthUser actor = new AuthUser(UUID.randomUUID().toString(), "operator", "测试操作员", role, 0, false, "ALL");
        AuthContext.set(actor);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        request.addHeader("User-Agent", "audit-browser");
        request.addHeader("X-Forwarded-For", "203.0.113.66"); // never parsed directly by the audit service
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return actor;
    }
    private AuditLog recorded() {
        ArgumentCaptor<AuditLog> capture = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper, atLeastOnce()).insert(capture.capture());
        return capture.getValue();
    }
}
