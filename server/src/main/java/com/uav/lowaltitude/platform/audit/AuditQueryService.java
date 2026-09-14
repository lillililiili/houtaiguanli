package com.uav.lowaltitude.platform.audit;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.api.SystemDtos.PageQuery;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.PageResponse;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@Service
public class AuditQueryService {

    public static final int EXPORT_LIMIT = 50_000;

    private final AuditMapper mapper;
    private final AuditService auditService;
    private final AccessService accessService;

    public AuditQueryService(AuditMapper mapper, AuditService auditService, AccessService accessService) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.accessService = accessService;
    }

    public PageResponse<AuditResponse> query(AuditFilter filter, PageQuery page) {
        accessService.require("audit.read");
        validateRange(filter);
        String account = like(filter.account());
        List<AuditResponse> items = mapper.query(filter.from(), filter.to(), account, clean(filter.module()),
                clean(filter.action()), clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()),
                page.size(), (page.page() - 1) * page.size()).stream().map(AuditQueryService::toResponse).toList();
        long total = mapper.count(filter.from(), filter.to(), account, clean(filter.module()), clean(filter.action()),
                clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()));
        return new PageResponse<>(items, page.page(), page.size(), total);
    }

    public AuditResponse detail(String auditId) {
        accessService.require("audit.read");
        AuditLog log = mapper.findById(auditId);
        if (log == null) throw new ApiException(HttpStatus.NOT_FOUND, "AUDIT_NOT_FOUND", "审计日志不存在");
        return toResponse(log);
    }

    @Transactional
    public List<AuditResponse> export(AuditFilter filter, String ip, String userAgent) {
        accessService.require("audit.op");
        validateRange(filter);
        String account = like(filter.account());
        long total = mapper.count(filter.from(), filter.to(), account, clean(filter.module()), clean(filter.action()),
                clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()));
        if (total > EXPORT_LIMIT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_LIMIT_EXCEEDED",
                    "筛选结果超过50000条，请缩小时间或筛选范围");
        }
        List<AuditResponse> rows = mapper.query(filter.from(), filter.to(), account, clean(filter.module()),
                clean(filter.action()), clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()),
                EXPORT_LIMIT, 0).stream().map(AuditQueryService::toResponse).toList();
        AuthUser actor = AuthContext.require();
        auditService.record(actor.userId(), actor.account(), actor.roleCode(), "audit", "audit_export_requested",
                "audit", "CSV", "rows=" + rows.size(), "SUCCESS", ip, userAgent);
        return rows;
    }

    private static void validateRange(AuditFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.from() > filter.to()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "开始时间不能晚于结束时间");
        }
    }

    private static AuditResponse toResponse(AuditLog log) {
        return new AuditResponse(log.getAuditId(), log.getUserId(), log.getAccount(), log.getRoleCode(),
                log.getModuleCode(), log.getAction(), log.getObjectType(), log.getObjectId(), log.getDetail(),
                log.getOccurredAt(), log.getIp(), log.getResult(), log.getUserAgent());
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String like(String value) {
        String clean = clean(value);
        return clean == null || clean.isEmpty() ? null : "%" + clean.toLowerCase(Locale.ROOT) + "%";
    }

    public record AuditFilter(Long from, Long to, String account, String module, String action,
            String result, String objectType, String objectId) {
    }

    public record AuditResponse(
            String auditId, String userId, String account, String roleCode, String moduleCode,
            String action, String objectType, String objectId, String detail, long occurredAt,
            String ip, String result, String userAgent) {
    }
}
