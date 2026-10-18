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
        List<AuditResponse> items = mapper.query(filter.from(), filter.to(), account, menu(filter.module()),
                clean(filter.action()), clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()), filter.includeAutomatic(),
                page.size(), (page.page() - 1) * page.size()).stream().map(AuditQueryService::toResponse).toList();
        long total = mapper.count(filter.from(), filter.to(), account, menu(filter.module()), clean(filter.action()),
                clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()), filter.includeAutomatic());
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
        long total = mapper.count(filter.from(), filter.to(), account, menu(filter.module()), clean(filter.action()),
                clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()), filter.includeAutomatic());
        if (total > EXPORT_LIMIT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_LIMIT_EXCEEDED",
                    "筛选结果超过50000条，请缩小时间或筛选范围");
        }
        List<AuditResponse> rows = mapper.query(filter.from(), filter.to(), account, menu(filter.module()),
                clean(filter.action()), clean(filter.result()), clean(filter.objectType()), clean(filter.objectId()), filter.includeAutomatic(),
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
                log.getOccurredAt(), log.getIp(), log.getResult(), log.getUserAgent(),
                AuditLabels.module(log.getMenuCode()), AuditLabels.action(log.getAction()),
                roleName(log), log.getRoleSource(), log.getDisplayRoleCode(), log.getMenuCode(),
                AuditLabels.result(log.getResult()), AuditLabels.objectType(log.getObjectType()),
                AuditLabels.automatic(log.getAction()),
                AuditLabels.accountName(log.getUserId(), log.getAccount(), log.getRoleCode()));
    }

    private static String roleName(AuditLog log) {
        // 系统自动记录没有登录用户：角色写“系统”，不显示 SYSTEM 这类代码（问题清单 #20）。
        if (AuditLabels.systemPrincipal(log.getUserId(), log.getAccount(), log.getRoleCode())) return "系统";
        String name = log.getRoleName();
        if (name == null || name.isBlank()) name = AuditLabels.role(log.getDisplayRoleCode());
        if (name.isBlank()) return "未记录";
        return "CURRENT".equals(log.getRoleSource()) ? name + "（当前角色，历史未记录）" : name;
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String menu(String value) {
        String code = clean(value);
        return code == null || code.isEmpty() ? null : AuditMenus.resolve(code, null, null, null);
    }

    private static String like(String value) {
        String clean = clean(value);
        return clean == null || clean.isEmpty() ? null : "%" + clean.toLowerCase(Locale.ROOT) + "%";
    }

    /**
     * includeAutomatic=false（默认）时列表、计数与导出都不含 AuditLabels.AUTOMATIC_ACTIONS 这类系统自动高频记录；
     * 明确按动作筛选时照常返回。只影响查询，写入不变。
     */
    public record AuditFilter(Long from, Long to, String account, String module, String action,
            String result, String objectType, String objectId, boolean includeAutomatic) {
    }

    public record AuditResponse(
            String auditId, String userId, String account, String roleCode, String moduleCode,
            String action, String objectType, String objectId, String detail, long occurredAt,
            String ip, String result, String userAgent, String moduleName, String actionName,
            String roleName, String roleSource, String displayRoleCode, String menuCode,
            String resultName, String objectTypeName, boolean automatic, String accountName) {
    }
}
