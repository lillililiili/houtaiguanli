package com.uav.lowaltitude.modules.alarm.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.disposal.application.DisposalAuthorizationService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

/** One independent transaction per event. Reuses single-event use cases and their locks/audit/idempotency. */
@Service
public class UavEventBatchItemService {
    private final UavEventVerificationService verification;
    private final DisposalAuthorizationService disposal;
    private final AccessControlService access;
    private final UavEventRepository events;

    public UavEventBatchItemService(UavEventVerificationService verification, DisposalAuthorizationService disposal,
            AccessControlService access, UavEventRepository events) {
        this.verification = verification; this.disposal = disposal; this.access = access; this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object verify(JsonNode item) {
        ObjectNode request = ((ObjectNode) item).deepCopy();
        request.remove("event_id"); request.remove("idempotency_key");
        return verification.verify(text(item, "event_id"), request.toString(), text(item, "idempotency_key"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object counter(JsonNode item) {
        String mode = text(item, "mode");
        if (!"REQUEST".equals(mode) && !"DIRECT".equals(mode)) throw invalid("mode 必须为 REQUEST 或 DIRECT");
        access.require("DIRECT".equals(mode) ? PermissionCode.DISPOSAL_DIRECT : PermissionCode.DISPOSAL_REQUEST);
        var scope = access.require(PermissionCode.ALARM_READ);
        String eventId = text(item, "event_id");
        var event = events.lock(eventId, scope);
        if (event == null) throw new ApiException(HttpStatus.NOT_FOUND, "UAV_EVENT_NOT_FOUND", "无人机事件不存在");
        JsonNode expected = item.get("expected_version");
        if (expected == null || !expected.isIntegralNumber() || !expected.canConvertToLong() || expected.longValue() < 0)
            throw invalid("expected_version 无效");
        if (event.version() != expected.longValue())
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "事件已更新，请刷新后逐项核对");
        JsonNode request = item.get("request");
        if (request == null || !request.isObject()
                || !"UAV_EVENT".equals(text(request, "subject_kind"))
                || !eventId.equals(text(request, "subject_id"))
                || !"COUNTERMEASURE".equals(text(request, "action_type")))
            throw invalid("批量反制必须针对所选事件，动作须为 COUNTERMEASURE");
        String key = text(item, "idempotency_key");
        return "DIRECT".equals(mode) ? disposal.directExecute(request.toString(), key) : disposal.create(request.toString(), key);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid(field + " 必须为字符串");
        return value.textValue();
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
}
