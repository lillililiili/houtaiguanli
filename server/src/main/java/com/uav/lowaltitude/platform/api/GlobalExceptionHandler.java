package com.uav.lowaltitude.platform.api;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditService auditService;

    public GlobalExceptionHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex, HttpServletRequest request) {
        auditFailure(request, ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> fieldMessage(error.getField(), error.getDefaultMessage()))
                .orElse("参数无效");
        auditFailure(request, "VALIDATION_ERROR", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex,
            HttpServletRequest request) {
        String message = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? "写操作必须提供 Idempotency-Key"
                : "缺少请求头 " + ex.getHeaderName();
        auditFailure(request, "MISSING_REQUEST_HEADER", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("MISSING_REQUEST_HEADER", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception ex, HttpServletRequest request) {
        auditFailure(request, "INVALID_REQUEST", "请求参数格式不正确");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("INVALID_REQUEST", "请求参数格式不正确"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex,
            HttpServletRequest request) {
        auditFailure(request, "FILE_TOO_LARGE", "文件超过服务器上传上限");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("FILE_TOO_LARGE", "文件超过服务器上传上限"));
    }

    /**
     * 决策 10-3：未映射路径是 404 `NOT_FOUND`，不是 500。
     * 后端不托管前端静态资源（Vite 代理），所以任何没有 Controller 的路径最终都落在 Spring 6.1+ 的
     * {@link NoResourceFoundException}（默认资源处理器）或开启 throw-exception-if-no-handler-found 后的
     * {@link NoHandlerFoundException}；两者都不是服务故障，落进兜底分支只会把"打错地址"报成"服务坏了"，
     * 既误导前端重试，也污染错误告警。响应不回显路径：探测者拿不到"哪段路径存在"的额外线索；
     * 路径只进服务端审计（有登录者时），便于事后追查。鉴权仍在过滤器里先做，未登录到不了这里。
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleUnmapped(Exception ex, HttpServletRequest request) {
        auditFailure(request, "NOT_FOUND", "资源不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("NOT_FOUND", "资源不存在"));
    }

    /**
     * 决策 12-6：已映射路径用错 HTTP 方法是 405 `METHOD_NOT_ALLOWED`，与未映射路径的 404 同因——
     * 都是"客户端打错了"，落进兜底分支就变成 500，前端会照服务故障重试，错误告警也被污染。
     *
     * `Allow` 头是 HTTP 规范要求的：客户端得知道该用哪个方法。但响应体仍不回显路径与被拒的方法，
     * 口径与 404 一致，不给探测者额外线索。Spring 允许 supportedMethods 为空，那就不发这个头，
     * 不伪造一个空 Allow，也不猜一组方法。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        auditFailure(request, "METHOD_NOT_ALLOWED", "请求方法不支持");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(new HttpMethod[0]));
        }
        return response.body(ApiResponse.fail("METHOD_NOT_ALLOWED", "请求方法不支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("Unhandled {} on {}", ex.getClass().getSimpleName(),
                request == null ? "" : request.getRequestURI(), ex);
        auditFailure(request, "INTERNAL_ERROR", "服务内部错误");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("INTERNAL_ERROR", "服务内部错误"));
    }

    private void auditFailure(HttpServletRequest request, String code, String message) {
        AuthUser actor = AuthContext.get();
        if (actor == null || request == null || !request.getRequestURI().startsWith("/api/")) return;
        try {
            String path = request.getRequestURI();
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null && userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
            auditService.recordStandalone(actor.userId(), actor.account(), actor.roleCode(), module(path),
                    request.getMethod() + " " + path, "request", path,
                    "error_code=" + code + "; message=" + message, "FAILURE",
                    request.getRemoteAddr(), userAgent);
        } catch (RuntimeException ignored) {
            // 审计写入异常不能改变原业务错误的 HTTP 语义。
        }
    }

    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("temporaryPassword", "临时密码"),
            Map.entry("temporary_password", "临时密码"),
            Map.entry("account", "登录账号"),
            Map.entry("name", "名称"),
            Map.entry("phone", "联系电话"),
            Map.entry("orgId", "所属组织"),
            Map.entry("org_id", "所属组织"),
            Map.entry("roleCode", "角色"),
            Map.entry("role_code", "角色"),
            Map.entry("reason", "操作原因"),
            Map.entry("orgCode", "组织编码"),
            Map.entry("org_code", "组织编码"),
            Map.entry("parentId", "上级组织"),
            Map.entry("parent_id", "上级组织"),
            Map.entry("districtCode", "区域编码"),
            Map.entry("district_code", "区域编码"),
            Map.entry("status", "状态"),
            Map.entry("description", "说明"),
            Map.entry("currentPassword", "当前密码"),
            Map.entry("current_password", "当前密码"),
            Map.entry("newPassword", "新密码"),
            Map.entry("new_password", "新密码"));

    private static String fieldMessage(String field, String defaultMessage) {
        String label = FIELD_LABELS.getOrDefault(field, field);
        String message = defaultMessage == null ? "" : defaultMessage.trim();
        if (message.isEmpty()) return label + "无效";
        if (message.contains(label)) return message;
        return label + " " + message.replace("个数必须", "长度必须");
    }

    private static String module(String path) {
        if (path.contains("/map-packages")) return "maps";
        if (path.contains("/mqtt-brokers")) return "interfaces";
        if (path.contains("/audit-logs")) return "audit";
        if (path.contains("/roles") || path.contains("/permissions") || path.contains("/access-change")) return "roles";
        if (path.contains("/users") || path.contains("/organizations") || path.contains("/districts")) return "users";
        if (path.contains("/device") || path.contains("/commission") || path.contains("/eo-tracking-tasks")) return "devices";
        // 无人机事件与来源告警属于同一核实域；风险保持独立，失败审计不能都落到笼统的 system。
        if (path.contains("/alarms") || path.contains("/uav-events")) return "alarms";
        if (path.contains("/risks")) return "risk";
        // 交接与工作台各自是独立模块：失败审计按模块归档，不能都落到笼统的 system。
        if (path.contains("/handoff")) return "handoff";
        if (path.contains("/workbench")) return "workbench";
        // 阶段 7：合法性研判与规则引擎各自归档；计划/航线/空域只读接口归飞行监管，不再落到 system。
        if (path.contains("/legality-") || path.contains("/rule-effects")) return "assessment";
        if (path.contains("/rule-sets") || path.contains("/rule-set-versions") || path.contains("/rule-runs")) return "rules";
        if (path.contains("/flight-plans") || path.contains("/routes") || path.contains("/route-versions")) return "flights";
        // 阶段 9：机场基础数据独立归档；空间风险汇总、异物细类字典与 C04/C05 评估触发归风险模块。
        // 阶段 13：处置授权（申请/审批/执行/停止）独立归档，不能落到 devices 或 system。
        if (path.contains("/alarms/export.csv")) return "alarms";
        if (path.contains("/risks/export.csv")) return "risks";
        if (path.contains("/disposal-")) return "disposal";
        if (path.contains("/punishment-cases") || path.contains("/penalty-rules") || path.contains("/decision-documents")) return "punishment";
        if (path.contains("/airports")) return "airport";
        if (path.contains("/space-risks") || path.contains("/space-object-subtypes") || path.contains("/rule-evaluations")) return "risk";
        if (path.contains("/airspace")) return "airspace";
        if (path.contains("/stats")) return "statistics";
        // 阶段 8：融合引擎配置/状态/指标与目标修订、合并、分裂都归融合模块；阶段 2 的目标只读接口本身仍归 system（无写审计）。
        if (path.contains("/fusion") || path.contains("/classification-revisions") || path.contains("/targets/merge") || path.matches(".*/targets/[^/]+/split$")) return "fusion";
        if (path.contains("/evidence-files") || path.contains("/evidence-chains")) return "evidence";
        return "system";
    }
}
