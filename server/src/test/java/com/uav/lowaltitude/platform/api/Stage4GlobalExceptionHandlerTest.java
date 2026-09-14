package com.uav.lowaltitude.platform.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingRequestHeaderException;

import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class Stage4GlobalExceptionHandlerTest {

    private AuditService auditService;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        handler = new GlobalExceptionHandler(auditService);
        AuthContext.set(new AuthUser("user-1", "operator", "值班员", "ROLE-DUTY", 0, false, "ALL"));
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void riskFailureAuditUsesRiskModuleAndGenericIdempotencyMessage() {
        MockHttpServletRequest request = request("POST", "/api/v1/risks/risk-1/verifications");

        var response = handler.handleMissingHeader(
                new MissingRequestHeaderException(
                        "Idempotency-Key", mock(org.springframework.core.MethodParameter.class)),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getMessage()).isEqualTo("写操作必须提供 Idempotency-Key");
        verify(auditService).recordStandalone(
                eq("user-1"), eq("operator"), eq("ROLE-DUTY"), eq("risk"),
                eq("POST /api/v1/risks/risk-1/verifications"), eq("request"),
                eq("/api/v1/risks/risk-1/verifications"), anyString(), eq("FAILURE"),
                eq("127.0.0.1"), eq("stage4-test"));
    }

    @Test
    void uavEventFailureAuditRemainsInAlarmDomain() {
        MockHttpServletRequest request = request("POST", "/api/v1/uav-events/event-1/verifications");

        handler.handleApi(
                new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "版本冲突"), request);

        verify(auditService).recordStandalone(
                eq("user-1"), eq("operator"), eq("ROLE-DUTY"), eq("alarms"),
                eq("POST /api/v1/uav-events/event-1/verifications"), eq("request"),
                eq("/api/v1/uav-events/event-1/verifications"), anyString(), eq("FAILURE"),
                eq("127.0.0.1"), eq("stage4-test"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "stage4-test");
        return request;
    }
}
