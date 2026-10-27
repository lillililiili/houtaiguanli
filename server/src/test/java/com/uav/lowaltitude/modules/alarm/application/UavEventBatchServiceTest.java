package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.DirectResultDto;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/** Device boundary is mocked: tests never dispatch or retry a physical operation. */
class UavEventBatchServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final UavEventBatchItemService item = mock(UavEventBatchItemService.class);
    final AuditService audit = mock(AuditService.class);
    final UavEventBatchService service = new UavEventBatchService(item, mock(UavEventVerificationService.class),
            mock(UavAdvisoryService.class), mock(AccessControlService.class), json, audit);

    @BeforeEach void actor() { AuthContext.set(new AuthUser("actor", "operator", "操作员", "role", 0, false, "ALL")); }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void uncertainOutcomeIsRetainedWithoutRetryAndNeighborsStillRun() throws Exception {
        when(item.verify(any())).thenThrow(new IllegalStateException("uncertain commit"))
                .thenReturn(Map.of("state", "FALSE_POSITIVE"));
        var result = service.verify("{\"items\":[{\"event_id\":\"first\"},{\"event_id\":\"neighbor\"}]}");
        assertThat(result.unknown()).isEqualTo(1); assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("UNKNOWN");
        assertThat(result.items().get(0).error().code()).isEqualTo("OUTCOME_UNKNOWN");
        verify(item, times(2)).verify(any(JsonNode.class));
    }

    @Test void blockedDirectAuthorizationRetainsCommittedIdAndDoesNotClaimExecutionSuccess() {
        when(item.counter(any())).thenReturn(new DirectResultDto("authorization", "DA-1", "APPROVED", 1, "DIRECT", "DEVICE_OFFLINE"));
        var result = service.counter("{\"items\":[{\"event_id\":\"event\"}]}");
        assertThat(result.failed()).isEqualTo(1); assertThat(result.succeeded()).isZero();
        assertThat(result.items().get(0).error().code()).isEqualTo("COUNTER_EXECUTION_BLOCKED");
        assertThat(((DirectResultDto) result.items().get(0).data()).authorizationId()).isEqualTo("authorization");
        verify(item).counter(any());
    }

    @Test void directAuthorizationStillApprovedIsBlockedEvenWhenLegacyReasonIsUnavailable() {
        when(item.counter(any())).thenReturn(new DirectResultDto("authorization", "DA-1", "APPROVED", 1, "DIRECT", null));
        var result = service.counter("{\"items\":[{\"event_id\":\"event\"}]}");
        assertThat(result.items().get(0).status()).isEqualTo("FAILED");
    }

    @Test void aDeniedItemCannotPreventProcessingItsIndependentNeighbor() {
        when(item.counter(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "权限不足"))
                .thenReturn(Map.of("status", "REQUESTED"));
        var result = service.counter("{\"items\":[{\"event_id\":\"first\"},{\"event_id\":\"neighbor\"}]}");
        assertThat(result.failed()).isEqualTo(1); assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.items().get(1).eventId()).isEqualTo("neighbor");
        verify(audit).recordStandalone(eq("actor"), eq("operator"), eq("role"), eq("alarms"), eq("batch_counter"), eq("uav_event"), eq("first"), eq("error_code=FORBIDDEN"), eq("FAILURE"), eq(""), eq(""));
    }

    @Test void unavailableFailureAuditStillRetainsExactlyOneOutcomePerSelectedEvent() {
        when(item.counter(any())).thenReturn(new DirectResultDto("authorization", "DA-1", "APPROVED", 1, "DIRECT", "DEVICE_OFFLINE"))
                .thenReturn(Map.of("status", "REQUESTED"));
        doThrow(new IllegalStateException("audit temporarily unavailable")).when(audit).recordStandalone(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        var result = service.counter("{\"items\":[{\"event_id\":\"first\"},{\"event_id\":\"neighbor\"}]}");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).status()).isEqualTo("UNKNOWN");
        assertThat(result.items().get(0).data()).isInstanceOf(DirectResultDto.class);
        assertThat(result.items().get(1).status()).isEqualTo("SUCCEEDED");
    }

    @Test void transportKeepsSameEventDifferentCasesIndependentThroughPartialFailure() {
        when(item.counter(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","来源或权限不允许"))
            .thenReturn(Map.of("status","REQUESTED"));
        var result=service.counter("{\"items\":[{\"event_id\":\"shared\",\"risk_case_id\":\"region-a\",\"expected_case_version\":3},{\"event_id\":\"shared\",\"risk_case_id\":\"region-b\",\"expected_case_version\":8}]}");
        assertThat(result.items()).hasSize(2);assertThat(result.failed()).isEqualTo(1);assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.items().get(0).riskCaseId()).isEqualTo("region-a");assertThat(result.items().get(1).riskCaseId()).isEqualTo("region-b");
        verify(item,times(2)).counter(any());
    }
}
