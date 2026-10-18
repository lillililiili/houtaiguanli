package com.uav.lowaltitude.platform.audit;

import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import com.uav.lowaltitude.platform.export.CsvExport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.identity.api.SystemDtos.PageResponse;
import com.uav.lowaltitude.modules.identity.api.UserAdminController;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.audit.AuditQueryService.AuditFilter;
import com.uav.lowaltitude.platform.audit.AuditQueryService.AuditResponse;

@RestController
@com.uav.lowaltitude.platform.security.BackendOnly
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<AuditResponse>> query(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(name = "include_automatic", required = false) Boolean includeAutomatic,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.query(filter(from, to, account, module, action, result, objectType, objectId, includeAutomatic),
                UserAdminController.page(page, size)));
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditResponse> detail(@PathVariable String auditId) {
        return ApiResponse.ok(service.detail(auditId));
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(name = "include_automatic", required = false) Boolean includeAutomatic,
            HttpServletRequest request) {
        List<AuditResponse> rows = service.export(
                filter(from, to, account, module, action, result, objectType, objectId, includeAutomatic),
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        return CsvExport.response("audit-logs.csv", List.of("时间", "账号", "角色", "菜单", "动作", "结果", "IP", "终端", "详情"),
                rows.stream().map(row -> Arrays.asList(String.valueOf(row.occurredAt()), row.accountName(), row.roleName(),
                        row.moduleName(), row.actionName(), row.resultName(),
                        row.ip() == null || row.ip().isBlank() ? "未记录" : row.ip(), row.userAgent(), row.detail())).toList());
    }

    /** 默认不含系统自动高频记录（问题清单 #20）；include_automatic=true 时与原来一样全部返回。 */
    private static AuditFilter filter(Long from, Long to, String account, String module, String action,
            String result, String objectType, String objectId, Boolean includeAutomatic) {
        return new AuditFilter(from, to, account, module, action, result, objectType, objectId, Boolean.TRUE.equals(includeAutomatic));
    }

}
