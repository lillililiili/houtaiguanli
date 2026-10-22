package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.disposal.application.DisposalAuthorizationService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

class UavEventBatchItemServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final UavEventVerificationService verification = mock(UavEventVerificationService.class);
    final DisposalAuthorizationService disposal = mock(DisposalAuthorizationService.class);
    final AccessControlService access = mock(AccessControlService.class);
    final UavEventRepository events = mock(UavEventRepository.class);
    final UavEventBatchItemService item = new UavEventBatchItemService(verification, disposal, access, events);

    @Test void aSubstitutedCounterSubjectCannotReachTheExistingDisposalService() throws Exception {
        when(events.lock(eq("selected"), any())).thenReturn(new EventRow("selected", "alarm", "target", "CONFIRMED", "org", "district", null, null, 7, "live"));
        var request = json.valueToTree(Map.of("event_id", "selected", "expected_version", 7, "mode", "DIRECT", "idempotency_key", "per-item-key",
                "request", Map.of("subject_kind", "UAV_EVENT", "subject_id", "another", "action_type", "COUNTERMEASURE")));
        assertThatThrownBy(() -> item.counter(request)).isInstanceOf(ApiException.class).satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_REQUEST"));
        verifyNoInteractions(disposal);
    }

    @Test void requestAndDirectUseTheirOwnExistingPermissionsAndUnmodifiedRequestPayloads() throws Exception {
        when(events.lock(eq("selected"), any())).thenReturn(new EventRow("selected", "alarm", "target", "CONFIRMED", "org", "district", null, null, 7, "replay"));
        var request = json.valueToTree(Map.of("subject_kind", "UAV_EVENT", "subject_id", "selected", "action_type", "COUNTERMEASURE", "device_id", "device", "channel", "LINGYUN_B", "countermeasure_mode", "DRIVE_AWAY"));
        for (String mode : new String[]{"REQUEST", "DIRECT"}) {
            var input = json.createObjectNode().put("event_id", "selected").put("expected_version", 7).put("mode", mode).put("idempotency_key", "item-key-" + mode);
            input.set("request", request); item.counter(input);
        }
        verify(access).require(PermissionCode.DISPOSAL_REQUEST); verify(access).require(PermissionCode.DISPOSAL_DIRECT);
        verify(disposal).create(request.toString(), "item-key-REQUEST");
        verify(disposal).directExecute(request.toString(), "item-key-DIRECT");
    }

    @Test void verificationDelegatesOnlyTheExistingSingleEventBodyAndPerItemKey() throws Exception {
        item.verify(json.readTree("{\"event_id\":\"selected\",\"idempotency_key\":\"verify-item-key\",\"conclusion\":\"CONFIRMED\",\"expected_version\":2,\"note\":\"已核对\"}"));
        verify(verification).verify(eq("selected"), argThat(body -> !body.contains("idempotency_key") && !body.contains("event_id") && body.contains("expected_version")), eq("verify-item-key"));
        verifyNoInteractions(disposal);
    }
}
