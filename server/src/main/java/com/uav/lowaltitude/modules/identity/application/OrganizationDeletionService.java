package com.uav.lowaltitude.modules.identity.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.infrastructure.OrganizationDeletionRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retires inactive units; historical directory and business ownership are never removed or moved. */
@Service
public class OrganizationDeletionService {
    private final OrganizationDeletionRepository repository;
    private final AccessService access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final ObjectMapper json;
    private final AppClock clock;

    public OrganizationDeletionService(OrganizationDeletionRepository repository, AccessService access,
            IdempotencyGuard idempotency, AuditService audit, ObjectMapper json, AppClock clock) {
        this.repository = repository;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public void delete(String orgId, int expectedVersion, String key, SystemManagementService.RequestMeta meta) {
        access.requireBackend();
        access.require("users.auth");
        if (expectedVersion < 0) throw new ApiException(HttpStatus.BAD_REQUEST,
                "INVALID_VERSION", "单位版本无效，请刷新后重试");
        idempotency.claim(key, "organization-delete:" + orgId + ":" + expectedVersion);
        Map<String, Object> before = repository.lockOrganization(orgId);
        if (before == null) throw new ApiException(HttpStatus.NOT_FOUND, "ORG_NOT_FOUND", "单位不存在");
        if (before.get("deleted_at") != null) throw new ApiException(HttpStatus.CONFLICT,
                "ORG_DELETED", "单位已删除，请刷新单位目录");
        if (((Number) before.get("version")).intValue() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "单位已发生变化，请刷新后重试");
        }
        List<String> references = repository.blockingReferences(orgId, clock.nowMillis());
        if (!references.isEmpty()) throw inUse("该单位仍关联" + String.join("、", references)
                + "，暂不能删除。请先处理在用人员、下级单位和未结束业务；已完成的历史记录不会阻止删除。");

        var detail = new LinkedHashMap<String, Object>();
        detail.put("organization", before);
        detail.put("profile", repository.profile(orgId));
        var actor = AuthContext.require();
        long at = clock.nowMillis();
        detail.put("deletion_type", "LOGICAL_RETIREMENT");
        detail.put("invalidated_preview_count", repository.deletePreviews(orgId));
        detail.put("authorization_changes", repository.retireAuthorization(orgId, at));
        if (repository.retireOrganization(orgId, expectedVersion, actor.userId(), at) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "单位已发生变化，请刷新后重试");
        detail.put("deleted_at", at);
        try {
            audit.record(actor.userId(), actor.account(), actor.roleCode(), "users", "organization_deleted",
                    "organization", orgId, json.writeValueAsString(detail), "SUCCESS", meta.ip(), meta.userAgent());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize organization deletion audit", ex);
        }
    }

    private static ApiException inUse(String message) {
        return new ApiException(HttpStatus.CONFLICT, "ORG_IN_USE", message);
    }
}
