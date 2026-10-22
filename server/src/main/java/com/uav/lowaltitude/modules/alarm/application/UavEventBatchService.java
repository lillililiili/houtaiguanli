package com.uav.lowaltitude.modules.alarm.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.UavEventBatchDtos.*;
import com.uav.lowaltitude.modules.disposal.api.DisposalDtos.DirectResultDto;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;

/** Transport aggregation only: no alternate verification, authorization or device state machine. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class UavEventBatchService {
    private static final Logger log = LoggerFactory.getLogger(UavEventBatchService.class);
    private static final Set<String> VERIFY_FIELDS = Set.of("event_id", "expected_version", "conclusion", "note", "idempotency_key");
    private static final Set<String> COUNTER_FIELDS = Set.of("event_id", "expected_version", "idempotency_key", "mode", "request");
    private final UavEventBatchItemService items;
    private final UavEventVerificationService verification;
    private final UavAdvisoryService advisory;
    private final AccessControlService access;
    private final ObjectMapper json;
    private final AuditService audit;

    public UavEventBatchService(UavEventBatchItemService items, UavEventVerificationService verification,
            UavAdvisoryService advisory, AccessControlService access, ObjectMapper json, AuditService audit) {
        this.items = items; this.verification = verification; this.advisory = advisory; this.access = access; this.json = json; this.audit = audit;
    }

    public PrecheckResult precheck(String raw) {
        access.require(PermissionCode.ALARM_READ);
        List<PrecheckItem> result = new ArrayList<>();
        for (JsonNode item : envelope(raw, Set.of("event_id"))) {
            String id = UavEventBatchItemService.text(item, "event_id");
            try {
                var event = verification.event(id);
                var counter = advisory.overview(id);
                if (event.version() != counter.eventVersion())
                    throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "事件已更新，请重新预检");
                List<String> conclusions = new ArrayList<>();
                String reason;
                if (event.allowedActions().contains("VERIFY")) {
                    conclusions.add("FALSE_POSITIVE");
                    if (event.verificationBasis() != null && event.verificationBasis().confirmable()) conclusions.add("CONFIRMED");
                    reason = event.verificationBasis() == null ? "无法确认核实依据" : event.verificationBasis().message();
                } else reason = "当前状态或权限不允许核实";
                result.add(new PrecheckItem(id, "READY", event, List.copyOf(conclusions), reason,
                        new CounterOptions(counter.canRequestCounter(), counter.canDirectCounter(), counter.counterBlockReason()), null));
            } catch (ApiException denied) {
                result.add(new PrecheckItem(id, "FAILED", null, List.of(), null, null, new ErrorDto(denied.getCode(), denied.getMessage())));
            } catch (RuntimeException unavailable) {
                log.warn("Batch precheck unavailable for event {}", id, unavailable);
                result.add(new PrecheckItem(id, "UNKNOWN", null, List.of(), null, null, unknownError()));
            }
        }
        return new PrecheckResult(List.copyOf(result));
    }

    public BatchResult verify(String raw) {
        access.require(PermissionCode.ALARM_READ); access.require(PermissionCode.ALARM_VERIFY);
        return submit(envelope(raw, VERIFY_FIELDS), items::verify, "batch_verify");
    }
    public BatchResult counter(String raw) {
        access.require(PermissionCode.ALARM_READ);
        return submit(envelope(raw, COUNTER_FIELDS), items::counter, "batch_counter");
    }
    private BatchResult submit(List<JsonNode> requests, Function<JsonNode, Object> operation, String action) {
        List<ItemResult> results = new ArrayList<>();
        for (JsonNode request : requests) {
            String id = UavEventBatchItemService.text(request, "event_id");
            ItemResult result;
            try {
                Object data = operation.apply(request); // The item proxy commits before returning.
                if (data instanceof DirectResultDto direct && ("APPROVED".equals(direct.status()) || direct.executionBlockReason() != null))
                    result = new ItemResult(id, "FAILED", data, new ErrorDto("COUNTER_EXECUTION_BLOCKED", "直接授权已建立，设备执行受阻，请查看授权详情"));
                else result = new ItemResult(id, "SUCCEEDED", data, null);
            } catch (ApiException denied) {
                result = new ItemResult(id, "FAILED", null, new ErrorDto(denied.getCode(), denied.getMessage()));
            } catch (RuntimeException uncertain) {
                log.warn("Batch operation outcome requires reconciliation for event {}", id, uncertain);
                result = new ItemResult(id, "UNKNOWN", null, unknownError());
            }
            if (result.error() != null) {
                try { failureAudit(action, id, result.error().code()); }
                catch (RuntimeException unavailable) {
                    log.error("Batch failure audit unavailable for event {}; reconciliation required", id, unavailable);
                    result = new ItemResult(id, "UNKNOWN", result.data(), new ErrorDto("AUDIT_UNAVAILABLE",
                            "该项结果的失败审计暂不可用，请刷新核对业务结果，勿重复提交"));
                }
            }
            results.add(result);
        }
        return new BatchResult(List.copyOf(results), count(results, "SUCCEEDED"), count(results, "FAILED"), count(results, "UNKNOWN"));
    }
    private void failureAudit(String action, String id, String code) {
        var actor = AuthContext.require();
        audit.recordStandalone(actor.userId(), actor.account(), actor.roleCode(), "alarms", action,
                "uav_event", id, "error_code=" + code, "FAILURE", "", "");
    }
    private List<JsonNode> envelope(String raw, Set<String> fields) {
        try (JsonParser parser = json.getFactory().createParser(raw == null ? "" : raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("items") || parser.nextToken() != null)
                throw invalid("请求仅允许 items 数组");
            JsonNode input = root.get("items");
            if (!input.isArray() || input.isEmpty() || input.size() > 20) throw invalid("每批须包含1至20个事件");
            Set<String> seen = new HashSet<>(); List<JsonNode> result = new ArrayList<>();
            for (JsonNode item : input) {
                if (!item.isObject()) throw invalid("批量成员必须为对象");
                var names = item.fieldNames();
                while (names.hasNext()) if (!fields.contains(names.next())) throw invalid("批量成员含未知字段");
                String id = UavEventBatchItemService.text(item, "event_id");
                if (id.isBlank() || id.length() > 36 || !id.equals(id.trim())) throw invalid("event_id 无效");
                if (!seen.add(id)) throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_EVENT", "同一事件不能在一批内重复提交");
                result.add(item);
            }
            return result;
        } catch (ApiException ex) { throw ex; }
        catch (Exception malformed) { throw invalid("请求参数格式不正确"); }
    }
    private static long count(List<ItemResult> items, String status) { return items.stream().filter(item -> status.equals(item.status())).count(); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    private static ErrorDto unknownError() { return new ErrorDto("OUTCOME_UNKNOWN", "结果暂无法确认，请保留该项并刷新核对，勿自动重复提交"); }
}
