package com.uav.lowaltitude.platform.api;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** OBS-09：页面关闭断开实时推送是正常现象，不能每次都记一条 ERROR 和失败审计。 */
class ClientDisconnectLoggingTest {

    private AuditService auditService;
    private GlobalExceptionHandler handler;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        handler = new GlobalExceptionHandler(auditService);
        AuthContext.set(new AuthUser("user-1", "operator", "值班员", "ROLE-DUTY", 0, false, "ALL"));
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        AuthContext.clear();
    }

    @Test
    void closedRealtimeStreamIsNotLoggedAsErrorOrAudited() {
        var response = handler.handleUnknown(
                new AsyncRequestNotUsableException("ServletOutputStream failed to flush: java.io.IOException: Broken pipe"),
                request("GET", "/api/v1/realtime/events"));

        assertThat(response).isNull();
        assertThat(appender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        verifyNoInteractions(auditService);
    }

    @Test
    void tomcatClientAbortInsideAnotherExceptionIsAlsoTreatedAsDisconnect() {
        var response = handler.handleUnknown(
                new IllegalStateException("write failed", new ClientAbortException("Broken pipe")),
                request("GET", "/api/v1/alarms/export.csv"));

        assertThat(response).isNull();
        assertThat(appender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        verifyNoInteractions(auditService);
    }

    @Test
    void realFailuresStillLogErrorAuditAndReturn500() {
        var response = handler.handleUnknown(
                new IllegalStateException("connection reset by peer while calling upstream"),
                request("GET", "/api/v1/alarms"));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.ERROR);
        verify(auditService).recordStandalone(
                eq("user-1"), eq("operator"), eq("ROLE-DUTY"), eq("alarms"),
                eq("GET /api/v1/alarms"), eq("request"), eq("/api/v1/alarms"),
                anyString(), eq("FAILURE"), eq("127.0.0.1"), eq("disconnect-test"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "disconnect-test");
        return request;
    }
}
