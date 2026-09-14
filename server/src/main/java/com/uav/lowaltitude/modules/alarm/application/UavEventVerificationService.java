package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.PageDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.UavEventDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerificationDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerifyRequest;
import com.uav.lowaltitude.modules.alarm.domain.UavEventState;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.VerificationRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class UavEventVerificationService {
    private final AccessControlService access;
    private final UavEventRepository repository;
    private final AlarmReadRepository alarms;
    private final IdempotencyGuard idempotency;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public UavEventVerificationService(AccessControlService access, UavEventRepository repository, AlarmReadRepository alarms,
            IdempotencyGuard idempotency, AppClock clock, AuditService audit, ObjectMapper objectMapper) {
        this.access = access; this.repository = repository; this.alarms = alarms; this.idempotency = idempotency; this.clock = clock; this.audit = audit; this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public UavEventDto event(String eventId) {
        AccessDecision decision = access.require(PermissionCode.ALARM_READ);
        EventRow event = repository.find(id(eventId), decision);
        if (event == null) throw notFound();
        return dto(event);
    }

    @Transactional(readOnly = true)
    public PageDto<VerificationDto> history(String eventId, MultiValueMap<String, String> values) {
        AccessDecision decision = access.require(PermissionCode.ALARM_READ);
        values.keySet().stream().filter(key -> !key.equals("page") && !key.equals("size")).findFirst()
                .ifPresent(key -> { throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "参数无效"); });
        int page = page(values, "page", 1), size = page(values, "size", 20);
        if (page < 1 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页参数无效");
        String id = id(eventId);
        if (repository.find(id, decision) == null) throw notFound();
        long total = repository.countVerifications(id, decision);
        return new PageDto<>(repository.verifications(id, decision, offset(page, size), size).stream().map(this::history).toList(), page, size, total);
    }

    @Transactional
    public UavEventDto verify(String eventId, String rawBody, String idempotencyKey) {
        AccessDecision read = access.require(PermissionCode.ALARM_READ);
        access.require(PermissionCode.ALARM_VERIFY);
        String id = id(eventId);
        VerifyRequest request = request(rawBody);
        String conclusion = requiredConclusion(request);
        String note = requiredNote(request);
        long expectedVersion = requiredVersion(request);
        EventRow event = repository.lock(id, read);
        if (event == null) throw notFound();
        // 先完成读/写权限与范围锁定才声明幂等键，越权者不能靠重放键探测已提交请求。
        idempotency.claim(idempotencyKey, stableOperation(id, conclusion, note, expectedVersion));
        if (event.version() != expectedVersion) throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "事件已被其他操作更新");
        String next = UavEventState.next(event.state(), conclusion);
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        // 条件更新为 0 行代表竞争写入，绝不追加一条与实际状态不一致的核实历史。
        if (repository.update(id, expectedVersion, next, at) != 1) throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "事件已被其他操作更新");
        AuthUser actor = AuthContext.require();
        repository.appendHistory(UUID.randomUUID().toString(), id, event.state(), next, conclusion, note,
                expectedVersion + 1, actor.userId(), at);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "alarm", "uav_event_verified", "uav_event", id,
                "conclusion=" + conclusion + "; version=" + (expectedVersion + 1), "SUCCESS", "", "");
        return dto(new EventRow(event.eventId(), event.alarmId(), event.targetId(), next, event.ownerOrgId(), event.districtId(), event.createdAt(), at, expectedVersion + 1, event.sourceMode()));
    }

    private UavEventDto dto(EventRow event) {
        return new UavEventDto(event.eventId(), event.alarmId(), targetReferenceVisible(event.targetId(), event.ownerOrgId(), event.districtId()) ? event.targetId() : null, event.state(), event.version(),
                millis(event.createdAt()), millis(event.updatedAt()), UavEventState.verifiable(event.state()) && verifyAllowed() ? List.of("VERIFY") : List.of());
    }
    private boolean targetReferenceVisible(String targetId, String orgId, String districtId) { try { return alarms.targetVisible(targetId, orgId, districtId, access.require(PermissionCode.TARGET_READ)); } catch (ApiException ignored) { return false; } }
    // allowed_actions 不能只反映状态：读者无核实动作权限时不应得到可提交的误导入口。
    private boolean verifyAllowed() { try { access.require(PermissionCode.ALARM_VERIFY); return true; } catch (ApiException ignored) { return false; } }
    private VerificationDto history(VerificationRow row) { return new VerificationDto(row.historyId(), row.previousState(), row.resultingState(), row.conclusion(), row.note(), row.version(), row.actorId(), millis(row.createdAt()), row.actorName()); }
    private static String id(String value) { String id = value == null ? "" : value.trim(); if (id.isEmpty() || id.length() > 36) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效"); return id; }
    /** 长度前缀保留字段边界，避免备注中的分隔符把不同请求错误哈希成同一幂等操作。 */
    private static String stableOperation(String eventId, String conclusion, String note, long expectedVersion) {
        return part("uav-event-verification") + part(eventId) + part(conclusion) + part(note) + part(Long.toString(expectedVersion));
    }
    private static String part(String value) { return value.length() + ":" + value; }
    private VerifyRequest request(String rawBody) {
        try (JsonParser parser = objectMapper.getFactory().createParser(rawBody == null ? "" : rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException();
            Set<String> seen = new HashSet<>(); String conclusion = null, note = null; Long version = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IllegalArgumentException();
                String field = parser.currentName();
                if (!Set.of("conclusion", "note", "expected_version").contains(field) || !seen.add(field)) throw new IllegalArgumentException();
                JsonToken value = parser.nextToken();
                if ("conclusion".equals(field)) { if (value != JsonToken.VALUE_STRING) throw new IllegalArgumentException(); conclusion = parser.getText(); }
                else if ("note".equals(field)) { if (value != JsonToken.VALUE_STRING) throw new IllegalArgumentException(); note = parser.getText(); }
                else { if (!value.isNumeric() || !parser.isExpectedNumberIntToken()) throw new IllegalArgumentException(); version = parser.getLongValue(); }
            }
            if (parser.nextToken() != null || seen.size() != 3) throw new IllegalArgumentException();
            return new VerifyRequest(conclusion, note, version);
        } catch (Exception ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确"); }
    }
    private static long millis(OffsetDateTime value) { return value.toInstant().toEpochMilli(); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "UAV_EVENT_NOT_FOUND", "无人机事件不存在"); }
    private static String requiredConclusion(VerifyRequest request) { if (request == null || request.conclusion() == null || request.conclusion().trim().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONCLUSION", "核实结论无效"); return request.conclusion().trim(); }
    private static String requiredNote(VerifyRequest request) { String note = request == null || request.note() == null ? "" : request.note().trim(); if (note.isEmpty() || note.length() > 1000) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "核实说明长度必须为1至1000"); return note; }
    private static long requiredVersion(VerifyRequest request) { if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "expected_version 无效"); return request.expectedVersion(); }
    private static int page(MultiValueMap<String, String> values, String name, int fallback) { if (!values.containsKey(name)) return fallback; if (values.size() > 2 || values.get(name) == null || values.get(name).size() != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页参数无效"); try { return Integer.parseInt(values.getFirst(name)); } catch (RuntimeException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页参数无效"); } }
    private static int offset(int page, int size) { try { return Math.multiplyExact(page - 1, size); } catch (ArithmeticException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "分页参数无效"); } }
}
