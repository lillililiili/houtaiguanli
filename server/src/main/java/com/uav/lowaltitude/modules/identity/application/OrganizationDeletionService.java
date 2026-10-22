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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes unused directory entries only; business records and historical ownership are never moved. */
@Service
public class OrganizationDeletionService {
    private final OrganizationDeletionRepository repository;
    private final AccessService access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final ObjectMapper json;

    public OrganizationDeletionService(OrganizationDeletionRepository repository, AccessService access,
            IdempotencyGuard idempotency, AuditService audit, ObjectMapper json) {
        this.repository = repository;
        this.access = access;
        this.idempotency = idempotency;
        this.audit = audit;
        this.json = json;
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
        if (((Number) before.get("version")).intValue() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "单位已发生变化，请刷新后重试");
        }
        List<String> references = repository.blockingReferences(orgId);
        if (!references.isEmpty()) throw inUse("该单位仍关联" + String.join("、", references)
                + "，不能删除。已有业务和历史记录的单位必须保留。");

        var detail = new LinkedHashMap<String, Object>();
        detail.put("organization", before);
        detail.put("profile", repository.profile(orgId));
        detail.put("deletion_type", "UNREFERENCED_DIRECTORY");
        try {
            detail.put("invalidated_preview_count", repository.deletePreviews(orgId));
            repository.deleteProfile(orgId);
            if (repository.deleteOrganization(orgId, expectedVersion) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "单位已发生变化，请刷新后重试");
            }
        } catch (DataIntegrityViolationException ex) {
            // A new foreign-key reference racing the precheck must never cascade or leak an SQL error.
            throw inUse("该单位已有人员或业务关联，不能删除，请刷新后核对。");
        }
        var actor = AuthContext.require();
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
