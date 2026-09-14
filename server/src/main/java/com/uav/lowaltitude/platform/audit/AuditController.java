package com.uav.lowaltitude.platform.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.query(filter(from, to, account, module, action, result, objectType, objectId),
                UserAdminController.page(page, size)));
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditResponse> detail(@PathVariable String auditId) {
        return ApiResponse.ok(service.detail(auditId));
    }

    @GetMapping("/export.csv")
    public void export(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        List<AuditResponse> rows = service.export(
                filter(from, to, account, module, action, result, objectType, objectId),
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"audit-logs.csv\"");
        response.getWriter().write('\ufeff');
        response.getWriter().println("时间,账号,角色,模块,动作,结果,IP,终端,详情");
        for (AuditResponse row : rows) {
            response.getWriter().println(String.join(",",
                    csv(String.valueOf(row.occurredAt())), csv(row.account()), csv(AuditLabels.role(row.roleCode())),
                    csv(AuditLabels.module(row.moduleCode())), csv(AuditLabels.action(row.action())),
                    csv(AuditLabels.result(row.result())), csv(row.ip()), csv(row.userAgent()), csv(row.detail())));
        }
    }

    private static AuditFilter filter(Long from, Long to, String account, String module, String action,
            String result, String objectType, String objectId) {
        return new AuditFilter(from, to, account, module, action, result, objectType, objectId);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }
}
